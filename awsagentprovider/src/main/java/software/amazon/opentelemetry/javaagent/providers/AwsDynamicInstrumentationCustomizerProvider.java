/*
 * Copyright Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.opentelemetry.javaagent.providers;

import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.contrib.awsxray.ResourceHolder;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.DynamicInstrumentationManager;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.config.DynamicInstrumentationConfig;

public class AwsDynamicInstrumentationCustomizerProvider
    implements AutoConfigurationCustomizerProvider {
  private static final Logger logger =
      Logger.getLogger(AwsDynamicInstrumentationCustomizerProvider.class.getName());

  private static final String AWS_DI_ENABLED_CONFIG = "otel.aws.dynamic.instrumentation.enabled";
  private static final String AWS_DI_API_URL_CONFIG = "otel.aws.dynamic.instrumentation.api.url";
  private static final String AWS_DI_PROBE_POLL_INTERVAL_CONFIG =
      "otel.aws.dynamic.instrumentation.probe.poll.interval";
  private static final String AWS_DI_BREAKPOINT_POLL_INTERVAL_CONFIG =
      "otel.aws.dynamic.instrumentation.breakpoint.poll.interval";
  private static final String AWS_OTLP_LOGS_ENDPOINT_CONFIG = "otel.aws.otlp.logs.endpoint";

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    autoConfiguration.addPropertiesCustomizer(this::customizeProperties);
    autoConfiguration.addTracerProviderCustomizer(this::customizeTracerProvider);
  }

  /** Set default configuration properties if not already specified. */
  private Map<String, String> customizeProperties(ConfigProperties configProps) {
    Map<String, String> overrides = new HashMap<>();

    boolean enabled = configProps.getBoolean(AWS_DI_ENABLED_CONFIG, false);
    if (!enabled) {
      return overrides; // Feature is opt-in; do nothing unless explicitly enabled
    }

    // Skip DI in Lambda environments where CloudWatch Agent is not available
    if (isLambdaEnvironment()) {
      logger.info("AWS DI: Lambda environment detected, skipping Dynamic Instrumentation");
      return overrides;
    }

    // Set defaults for dynamic instrumentation configuration
    if (configProps.getString(AWS_DI_API_URL_CONFIG) == null) {
      overrides.put(AWS_DI_API_URL_CONFIG, "http://localhost:2000");
    }

    if (configProps.getString(AWS_DI_PROBE_POLL_INTERVAL_CONFIG) == null) {
      overrides.put(AWS_DI_PROBE_POLL_INTERVAL_CONFIG, "600"); // 10 minutes
    }

    if (configProps.getString(AWS_DI_BREAKPOINT_POLL_INTERVAL_CONFIG) == null) {
      overrides.put(AWS_DI_BREAKPOINT_POLL_INTERVAL_CONFIG, "60"); // 1 minute
    }

    return overrides;
  }

  /** Customize TracerProvider to initialize Dynamic Instrumentation after SDK is fully built. */
  private SdkTracerProviderBuilder customizeTracerProvider(
      SdkTracerProviderBuilder builder, ConfigProperties configProps) {

    boolean enabled = configProps.getBoolean(AWS_DI_ENABLED_CONFIG, false);
    if (!enabled) {
      logger.fine("AWS Dynamic Instrumentation is disabled");
      return builder;
    }

    // Skip DI in Lambda environments where CloudWatch Agent is not available
    if (isLambdaEnvironment()) {
      logger.info("AWS DI: Lambda environment detected, skipping Dynamic Instrumentation");
      return builder;
    }

    // Everything below is best-effort: a Dynamic Instrumentation setup failure (e.g. a malformed
    // poll-interval that makes ConfigProperties.getInt throw) must never escape this customizer and
    // abort SDK autoconfiguration, which would disable ALL telemetry for the application.
    try {
      logger.fine("AWS Dynamic Instrumentation feature enabled - initializing");

      // Get Resource (already available via ResourceHolder)
      Resource resource = ResourceHolder.getResource();

      // Get Instrumentation via reflection (from otelagent bootstrap module)
      Instrumentation instrumentation = getInstrumentationViaReflection();
      if (instrumentation == null) {
        logger.warning("AWS DI: Instrumentation not available, cannot initialize");
        return builder;
      }

      // Build configuration
      String logsEndpoint = configProps.getString(AWS_OTLP_LOGS_ENDPOINT_CONFIG);

      DynamicInstrumentationConfig config =
          DynamicInstrumentationConfig.builder()
              .enabled(true)
              .apiUrl(configProps.getString(AWS_DI_API_URL_CONFIG))
              .probePollIntervalSeconds(configProps.getInt(AWS_DI_PROBE_POLL_INTERVAL_CONFIG))
              .breakpointPollIntervalSeconds(
                  configProps.getInt(AWS_DI_BREAKPOINT_POLL_INTERVAL_CONFIG))
              .logsEndpoint(logsEndpoint)
              .resource(resource)
              .instrumentation(instrumentation)
              .build();

      // Schedule manager initialization with 100ms delay
      // This ensures TracerProvider is fully built before we access it
      ScheduledExecutorService scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "aws-di-initializer");
                t.setDaemon(true);
                return t;
              });
      scheduler.schedule(
          () -> {
            try {
              // The DI snapshot path exports OTLP logs (not spans), and the bytecode advice
              // resolves its tracer via GlobalOpenTelemetry at the point of use — so the manager
              // does not need the global TracerProvider here. We deliberately do NOT call
              // GlobalOpenTelemetry.getTracerProvider(): if the global is not yet registered (this
              // task can fire before the agent finishes building the SDK), that call permanently
              // sets the global to no-op, which would silently disable ALL telemetry for the
              // application. Passing noop() is sufficient since the reference is otherwise unused.
              DynamicInstrumentationManager.getInstance().initialize(TracerProvider.noop(), config);

            } catch (Exception e) {
              logger.log(Level.SEVERE, "AWS DI: Failed to initialize", e);
            } finally {
              scheduler.shutdown();
            }
          },
          100,
          TimeUnit.MILLISECONDS);

      // Register shutdown hook
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    logger.fine("AWS DI: Shutdown hook triggered");
                    DynamicInstrumentationManager.getInstance().shutdown();
                  },
                  "aws-di-shutdown"));

      logger.fine("AWS DI: Initialization scheduled");
    } catch (Exception e) {
      logger.log(
          Level.SEVERE,
          "AWS DI: Failed to set up Dynamic Instrumentation; continuing without it",
          e);
    }

    return builder;
  }

  /**
   * Returns true if running in an AWS Lambda environment, where Dynamic Instrumentation is not
   * supported (the CloudWatch Agent is not available to proxy configuration and snapshots).
   */
  private static boolean isLambdaEnvironment() {
    String lambdaFunctionName = System.getenv("AWS_LAMBDA_FUNCTION_NAME");
    return lambdaFunctionName != null && !lambdaFunctionName.trim().isEmpty();
  }

  /**
   * Get Instrumentation via reflection from InstrumentationHolder. Uses reflection to avoid
   * compile-time dependency on otelagent module.
   */
  private static Instrumentation getInstrumentationViaReflection() {
    try {
      logger.fine(
          "AWS DI: Attempting to get Instrumentation via reflection from AwsInstrumentationHolder");
      Class<?> holderClass =
          Class.forName(
              "software.amazon.opentelemetry.javaagent.bootstrap.AwsInstrumentationHolder",
              true, // initialize class
              null); // null = bootstrap classloader
      logger.fine("AWS DI: Found AwsInstrumentationHolder class");

      java.lang.reflect.Method getMethod = holderClass.getMethod("getInstrumentation");
      logger.fine("AWS DI: Found getInstrumentation method");

      Instrumentation inst = (Instrumentation) getMethod.invoke(null);
      if (inst == null) {
        logger.warning(
            "AWS DI: AwsInstrumentationHolder.getInstrumentation() returned null - not yet set in AwsAgentBootstrap?");
      } else {
        logger.fine("AWS DI: Successfully retrieved Instrumentation via reflection");
      }
      return inst;
    } catch (Exception e) {
      logger.log(Level.SEVERE, "AWS DI: Failed to get Instrumentation via reflection", e);
      e.printStackTrace();
      return null;
    }
  }
}
