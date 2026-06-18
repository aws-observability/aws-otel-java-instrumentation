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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.bootstrap.di.DIDataStore;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.client.DynamicInstrumentationClient;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.client.StatusReporter;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.config.DynamicInstrumentationConfig;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.ByteBuddyInstrumentationEngine;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.InstrumentationRegistry;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.ErrorCause;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.output.DISerializerImpl;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.output.DISnapshotCollector;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.output.DISnapshotOtlpEmitter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.logs.OtlpAwsLogRecordExporterBuilder;

/**
 * Central manager for AWS Dynamic Instrumentation feature.
 *
 * <p>Coordinates the lifecycle and interaction between:
 *
 * <ul>
 *   <li>HTTP Client: Fetches configurations from backend API
 *   <li>Configuration Poller: Manages PROBE and BREAKPOINT polling threads
 *   <li>Configuration Application: Applies instrumentation configs (future phase)
 *   <li>Tracer: Emits spans when instrumentation points are hit (future phase)
 * </ul>
 *
 * <p>Initialization occurs after the ADOT SDK (TracerProvider) is fully built, ensuring access to
 * the complete ADOT configuration including X-Ray ID generator, samplers, span processors, and
 * exporters.
 */
public final class DynamicInstrumentationManager {
  private static final Logger logger =
      Logger.getLogger(DynamicInstrumentationManager.class.getName());

  private static final String AWS_OTLP_LOGS_ENDPOINT_PATTERN =
      "^https://logs\\.([a-z0-9-]+)\\.amazonaws\\.com/v1/logs$";

  private static volatile DynamicInstrumentationManager INSTANCE;
  private static final AtomicBoolean initialized = new AtomicBoolean(false);

  // volatile: written in initialize() (one thread) and read/cleared from poller and shutdown
  // threads. volatile guarantees the writes from initialize() are visible to those readers and that
  // the nulling done in cleanup() is observed.
  private volatile DynamicInstrumentationConfig config;
  private volatile TracerProvider tracerProvider;
  private volatile Tracer tracer;
  private volatile DynamicInstrumentationClient client;
  private volatile ByteBuddyInstrumentationEngine engine;
  private volatile StatusReporter statusReporter;
  private volatile DISnapshotCollector collector;
  private volatile DISnapshotOtlpEmitter otlpEmitter;

  private DynamicInstrumentationManager() {}

  /** Get the singleton instance. */
  public static DynamicInstrumentationManager getInstance() {
    if (INSTANCE == null) {
      synchronized (DynamicInstrumentationManager.class) {
        if (INSTANCE == null) {
          INSTANCE = new DynamicInstrumentationManager();
        }
      }
    }
    return INSTANCE;
  }

  /**
   * Initialize the manager with TracerProvider and configuration. This should be called after the
   * ADOT SDK is fully initialized.
   *
   * @param tracerProvider The fully-configured ADOT TracerProvider
   * @param config Configuration for dynamic instrumentation
   */
  public void initialize(TracerProvider tracerProvider, DynamicInstrumentationConfig config) {
    if (!initialized.compareAndSet(false, true)) {
      logger.warning("AWS DI: Manager already initialized");
      return;
    }

    this.tracerProvider = tracerProvider;
    this.config = config;

    logger.info("AWS DI: Initializing manager");
    logger.log(
        Level.FINE,
        "AWS DI: Service: {0}, Environment: {1}",
        new Object[] {config.getServiceName(), config.getDeploymentEnvironment()});

    try {
      // Create tracer from the fully-configured TracerProvider
      // Note: This tracer is stored for testing/verification purposes only.
      // The actual instrumentation (Advice classes) uses GlobalOpenTelemetry.getTracer()
      // because Advice code is inlined into application code and can only reference
      // classes visible to the application classloader.
      this.tracer = tracerProvider.get("aws.dynamicinstrumentation", "1.0.0");
      logger.fine("AWS DI: Tracer created from ADOT TracerProvider");

      // Create instrumentation engine
      Instrumentation instrumentation = config.getInstrumentation();
      if (instrumentation == null) {
        logger.warning(
            "AWS DI: Instrumentation not available, cannot apply bytecode transformations");
      } else {
        this.engine = new ByteBuddyInstrumentationEngine(instrumentation);
        logger.fine("AWS DI: Bytecode instrumentation engine created");
      }

      // Create and start client
      this.client = new DynamicInstrumentationClient(config);
      this.client.startPolling();

      // Create and start status reporter
      this.statusReporter = new StatusReporter(client, 60);
      this.statusReporter.start();

      // Create OTLP emitter for DI snapshots (dedicated, isolated LoggerProvider)
      this.otlpEmitter = createOtlpEmitter(config);
      logger.log(
          Level.FINE, "AWS DI: OTLP emitter created (endpoint: {0})", config.getLogsEndpoint());

      // Create and wire DISerializerImpl
      DISerializerImpl diSerializer = new DISerializerImpl();
      software.amazon.opentelemetry.javaagent.bootstrap.di.DIDataStore.setSerializer(diSerializer);
      logger.fine("AWS DI: DISerializerImpl created and wired to DIDataStore");

      // Create and start DISnapshotCollector
      this.collector = new DISnapshotCollector(otlpEmitter, config);
      this.collector.start();
      logger.fine("AWS DI: DISnapshotCollector created and started");

      logger.info("AWS DI: Manager initialized successfully");
      logger.log(
          Level.FINE,
          "AWS DI: Configuration polling started - PROBE: {0}s, BREAKPOINT: {1}s",
          new Object[] {
            config.getProbePollIntervalSeconds(), config.getBreakpointPollIntervalSeconds()
          });

    } catch (Throwable e) {
      // Catch Throwable: initialization must not crash the application even on
      // OutOfMemoryError or NoClassDefFoundError from missing dependencies.
      // Intentional trade-off for agent code: crashing the host application is always
      // considered worse than running DI in a degraded (disabled) state.
      logger.log(Level.SEVERE, "AWS DI: Failed to initialize manager", e);
      initialized.set(false);
      cleanup();
    }
  }

  /**
   * Apply instrumentation configurations. Called by ConfigurationPoller after merging PROBE and
   * BREAKPOINT configs.
   *
   * <p>Uses batch application with single transformer pattern: All configurations are registered,
   * then one transformer is rebuilt that applies all instrumentations. This minimizes JVM
   * retransformation overhead by grouping changes per class.
   *
   * <p>Performance: N configurations affecting M classes triggers M retransformations (not N).
   *
   * @param configurations List of instrumentation configurations to apply
   */
  public void applyConfigurations(List<InstrumentationConfiguration> configurations) {
    // Bail if not initialized (or already shut down). Reading the AtomicBoolean is also a memory
    // fence that makes the fields written in initialize() visible, and prevents operating on
    // fields that cleanup() may be nulling out concurrently.
    if (!initialized.get()) {
      logger.fine("AWS DI: Manager not initialized, ignoring applyConfigurations");
      return;
    }

    logger.log(Level.FINE, "AWS DI: Received {0} configurations to apply", configurations.size());

    if (engine == null) {
      logger.warning("AWS DI: Bytecode engine not available, cannot apply configurations");
      return;
    }

    int registered = 0;
    int skipped = 0;

    // Configs accepted into the registry — diagnosed for binding errors after the transformer is
    // rebuilt, so a non-bindable target reports ERROR instead of a misleading READY.
    List<InstrumentationConfiguration> registeredConfigs = new ArrayList<>();

    // Register all configurations in registry
    for (InstrumentationConfiguration config : configurations) {
      // Reject constructors and static initializers — ByteBuddy Advice cannot instrument them
      String methodName = config.getMethodName();
      if ("<init>".equals(methodName) || "<clinit>".equals(methodName)) {
        logger.log(
            Level.WARNING,
            "AWS DI: Skipping unsupported target {0}.{1} — constructor/initializer "
                + "instrumentation is not supported",
            new Object[] {config.getClassName(), methodName});
        if (statusReporter != null) {
          statusReporter.reportError(
              config.getInstrumentationType().name(),
              config.getLocationHash(),
              ErrorCause.UNSUPPORTED_TARGET);
        }
        skipped++;
        continue;
      }

      // Determine correct registry key based on instrumentation level
      // Line-level: use instrumentationKey (includes line number)
      // Method-level: use methodKey (class.method only)
      String registryKey =
          config.isLineLevel() ? config.getInstrumentationKey() : config.getMethodKey();

      InstrumentationRegistry.register(registryKey, config);

      // Write runtime config to bootstrap-visible DIDataStore for Advice reads
      CaptureConfiguration cc = config.getCaptureConfig();
      int[] configLimits;
      String[] captureArgs;
      String[] capLocals;
      boolean capReturn;
      if (cc != null) {
        configLimits =
            new int[] {
              cc.getMaxObjectDepth(),
              cc.getMaxFieldsPerObject(),
              cc.getMaxCollectionWidth(),
              cc.getMaxStringLength(),
              cc.getMaxCollectionDepth()
            };
        captureArgs =
            cc.getCaptureArguments() != null
                ? cc.getCaptureArguments().toArray(new String[0])
                : null;
        capLocals =
            cc.getCaptureLocals() != null ? cc.getCaptureLocals().toArray(new String[0]) : null;
        capReturn = cc.isCaptureReturn();
      } else {
        configLimits = new int[] {3, 20, 20, 255, 3};
        captureArgs = null;
        capLocals = null;
        capReturn = false;
      }
      DIDataStore.registerConfig(
          registryKey,
          configLimits,
          captureArgs,
          capLocals,
          capReturn,
          config.getMaxHits(),
          config.getExpiresAt() != null ? config.getExpiresAt().toEpochMilli() : 0L);

      registered++;
      registeredConfigs.add(config);

      logger.log(
          Level.FINE,
          "Registered {0} instrumentation: {1}",
          new Object[] {config.isLineLevel() ? "line-level" : "method-level", registryKey});
    }

    logger.log(
        Level.FINE,
        "AWS DI: Registered {0} configurations, skipped {1}",
        new Object[] {registered, skipped});

    // Rebuild transformer with all registered configs and apply (batched operation)
    try {
      engine.rebuildAndApplyTransformer();

      // Diagnose non-bindable targets BEFORE reporting READY, so a config whose method does not
      // exist on the (loaded) target class, or whose class is not modifiable, is reported as ERROR
      // rather than a misleading READY. Inheritance-aware and conservative: a not-yet-loaded class
      // is left untouched (it may bind when it loads). Must precede reportNow() because the status
      // reporter suppresses READY for any locationHash already reported as ERROR.
      if (statusReporter != null) {
        for (InstrumentationConfiguration config : registeredConfigs) {
          ErrorCause cause = engine.diagnoseBindingError(config);
          if (cause != null) {
            logger.log(
                Level.WARNING,
                "AWS DI: Target {0}.{1} cannot bind ({2}); reporting ERROR",
                new Object[] {config.getFullyQualifiedClassName(), config.getMethodName(), cause});
            statusReporter.reportError(
                config.getInstrumentationType().name(), config.getLocationHash(), cause);
          }
        }

        // Report READY status immediately after applying configurations
        statusReporter.reportNow();
      }
    } catch (Exception e) {
      logger.log(Level.SEVERE, "AWS DI: Failed to rebuild transformer", e);
    }
  }

  /**
   * Remove instrumentation configurations.
   *
   * <p>Removes configurations from registry and rebuilds the single transformer. Classes that no
   * longer have any instrumentations will be retransformed to their original state (no
   * instrumentation).
   *
   * @param methodKeys Set of method keys to remove
   */
  public void removeInstrumentations(Set<String> methodKeys) {
    if (methodKeys == null || methodKeys.isEmpty()) {
      return;
    }

    // Bail if not initialized (or already shut down) — see applyConfigurations for rationale.
    if (!initialized.get()) {
      logger.fine("AWS DI: Manager not initialized, ignoring removeInstrumentations");
      return;
    }

    logger.log(Level.INFO, "AWS DI: Removing {0} instrumentations", methodKeys.size());

    if (engine == null) {
      logger.warning("AWS DI: Bytecode engine not available");
      return;
    }

    // Remove from registry
    int removed = 0;
    for (String methodKey : methodKeys) {
      InstrumentationConfiguration removedConfig = InstrumentationRegistry.remove(methodKey);
      if (removedConfig != null) {
        DIDataStore.removeConfig(methodKey);
        // Clear the status reporter's one-time-report bookkeeping for this location hash, so a
        // later re-applied config with the same hash (e.g. a now-bindable target) is not kept
        // permanently suppressed by a prior ERROR.
        if (statusReporter != null) {
          statusReporter.forget(removedConfig.getLocationHash());
        }
        removed++;
      }
    }

    logger.log(Level.FINE, "AWS DI: Removed {0} configurations from registry", removed);

    // Rebuild transformer without removed configs
    try {
      engine.rebuildAndApplyTransformer();
      logger.log(Level.FINE, "AWS DI: Rebuilt transformer after removal");
    } catch (Exception e) {
      logger.log(Level.SEVERE, "AWS DI: Failed to rebuild transformer after removal", e);
    }
  }

  /** Shutdown the manager and cleanup resources. */
  public void shutdown() {
    if (!initialized.get()) {
      return;
    }

    logger.info("AWS DI: Shutting down manager");
    // Flip the flag BEFORE cleanup() so that applyConfigurations/removeInstrumentations (which
    // check initialized) stop operating before cleanup() begins nulling the fields they read.
    initialized.set(false);
    cleanup();
    logger.fine("AWS DI: Manager shutdown complete");
  }

  /** Internal cleanup method. */
  private void cleanup() {
    if (collector != null) {
      try {
        collector.stop();
        logger.fine("AWS DI: DISnapshotCollector stopped");
      } catch (Exception e) {
        logger.log(Level.WARNING, "AWS DI: Error stopping DISnapshotCollector", e);
      }
      collector = null;
    }

    if (otlpEmitter != null) {
      SdkLoggerProvider logProvider = otlpEmitter.getLoggerProvider();
      if (logProvider != null) {
        try {
          logProvider.forceFlush().join(10, TimeUnit.SECONDS);
          logProvider.shutdown().join(10, TimeUnit.SECONDS);
          logger.fine("AWS DI: OTLP LoggerProvider shut down");
        } catch (Exception e) {
          logger.log(Level.WARNING, "AWS DI: Error shutting down OTLP LoggerProvider", e);
        }
      }
      otlpEmitter = null;
    }

    if (statusReporter != null) {
      try {
        statusReporter.stop();
        logger.fine("AWS DI: Status reporter stopped");
      } catch (Exception e) {
        logger.log(Level.WARNING, "AWS DI: Error stopping status reporter", e);
      }
      statusReporter = null;
    }

    if (client != null) {
      try {
        client.stopPolling();
        logger.fine("AWS DI: Client polling stopped");
      } catch (Exception e) {
        logger.log(Level.WARNING, "AWS DI: Error stopping client", e);
      }
      client = null;
    }

    // Drop remaining references so a retry after a failed initialize() starts from a clean
    // singleton instead of inheriting partially-built state.
    engine = null;
    tracer = null;
    tracerProvider = null;
    config = null;
  }

  /** Check if manager is initialized. */
  public boolean isInitialized() {
    return initialized.get();
  }

  /** Get the tracer (for future span emission). */
  public Tracer getTracer() {
    return tracer;
  }

  /** Get the TracerProvider (for accessing full ADOT configuration). */
  public TracerProvider getTracerProvider() {
    return tracerProvider;
  }

  /** Get the configuration. */
  public DynamicInstrumentationConfig getConfig() {
    return config;
  }

  /** Get the HTTP client (for monitoring/debugging). */
  public DynamicInstrumentationClient getClient() {
    return client;
  }

  /**
   * Create the dedicated OTLP emitter for DI snapshots.
   *
   * <p>Creates a dedicated SdkLoggerProvider, fully isolated from OTel application logs and
   * Application Signals. Applies SigV4 wrapping when the endpoint matches the CloudWatch OTLP URL
   * pattern.
   */
  private DISnapshotOtlpEmitter createOtlpEmitter(DynamicInstrumentationConfig diConfig) {
    String logsEndpoint = diConfig.getLogsEndpoint();

    // Resource supplier: defers reading ResourceHolder until first emit
    Resource fallbackResource =
        Resource.create(
            Attributes.of(
                AttributeKey.stringKey("service.name"), diConfig.getServiceName(),
                AttributeKey.stringKey("deployment.environment"),
                    diConfig.getDeploymentEnvironment()));

    java.util.function.Supplier<Resource> resourceSupplier =
        () -> {
          try {
            Class<?> holderClass = Class.forName("io.opentelemetry.contrib.awsxray.ResourceHolder");
            java.lang.reflect.Method getResource = holderClass.getMethod("getResource");
            Resource holderResource = (Resource) getResource.invoke(null);
            if (holderResource != null && !holderResource.equals(Resource.getDefault())) {
              return holderResource;
            }
          } catch (Throwable ignored) {
            // ResourceHolder not available or not yet populated — use fallback
          }
          return fallbackResource;
        };

    // LoggerProvider factory: creates the dedicated provider on first use
    java.util.function.Supplier<SdkLoggerProvider> loggerProviderFactory =
        () -> {
          OtlpHttpLogRecordExporter plainLogExporter =
              OtlpHttpLogRecordExporter.builder().setEndpoint(logsEndpoint).build();

          io.opentelemetry.sdk.logs.export.LogRecordExporter logExporter;
          if (logsEndpoint.matches(AWS_OTLP_LOGS_ENDPOINT_PATTERN)) {
            logExporter =
                OtlpAwsLogRecordExporterBuilder.create(plainLogExporter, logsEndpoint).build();
            logger.fine(
                "AWS DI: OTLP logs using SigV4 direct-to-CloudWatch (" + logsEndpoint + ")");
          } else {
            logExporter = plainLogExporter;
            logger.fine("AWS DI: OTLP logs using collector-proxied (" + logsEndpoint + ")");
          }

          return SdkLoggerProvider.builder()
              .setResource(resourceSupplier.get())
              .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build())
              .build();
        };

    return new DISnapshotOtlpEmitter(loggerProviderFactory);
  }
}
