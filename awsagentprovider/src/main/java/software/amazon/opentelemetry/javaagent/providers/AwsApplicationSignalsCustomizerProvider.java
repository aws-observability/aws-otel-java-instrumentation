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

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.contrib.awsxray.AlwaysRecordSampler;
import io.opentelemetry.contrib.awsxray.ResourceHolder;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.internal.OtlpConfigUtil;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This customizer performs the following customizations:
 *
 * <ul>
 *   <li>Use AlwaysRecordSampler to record all spans.
 *   <li>Add SpanMetricsProcessor to create RED metrics.
 *   <li>Add AttributePropagatingSpanProcessor to propagate span attributes from parent to child
 *       spans.
 *   <li>Add AwsMetricAttributesSpanExporter to add more attributes to all spans.
 * </ul>
 *
 * <p>You can control when these customizations are applied using the property
 * otel.aws.application.signals.enabled or the environment variable
 * OTEL_AWS_APPLICATION_SIGNALS_ENABLED. This flag is disabled by default.
 */
public class AwsApplicationSignalsCustomizerProvider
    implements AutoConfigurationCustomizerProvider {
  static final String AWS_LAMBDA_FUNCTION_NAME_CONFIG = "AWS_LAMBDA_FUNCTION_NAME";

  private static final Duration DEFAULT_METRIC_EXPORT_INTERVAL = Duration.ofMinutes(1);
  private static final Logger logger =
      Logger.getLogger(AwsApplicationSignalsCustomizerProvider.class.getName());

  private static final String DEPRECATED_SMP_ENABLED_CONFIG = "otel.smp.enabled";
  private static final String DEPRECATED_APP_SIGNALS_ENABLED_CONFIG =
      "otel.aws.app.signals.enabled";
  private static final String APPLICATION_SIGNALS_ENABLED_CONFIG =
      "otel.aws.application.signals.enabled";

  private static final String OTEL_RESOURCE_PROVIDERS_AWS_ENABLED =
      "otel.resource.providers.aws.enabled";
  private static final String APPLICATION_SIGNALS_RUNTIME_ENABLED_CONFIG =
      "otel.aws.application.signals.runtime.enabled";
  private static final String DEPRECATED_SMP_EXPORTER_ENDPOINT_CONFIG =
      "otel.aws.smp.exporter.endpoint";
  private static final String DEPRECATED_APP_SIGNALS_EXPORTER_ENDPOINT_CONFIG =
      "otel.aws.app.signals.exporter.endpoint";
  private static final String APPLICATION_SIGNALS_EXPORTER_ENDPOINT_CONFIG =
      "otel.aws.application.signals.exporter.endpoint";

  private static final String OTEL_JMX_TARGET_SYSTEM_CONFIG = "otel.jmx.target.system";
  private static final String OTEL_EXPORTER_OTLP_TRACES_ENDPOINT_CONFIG =
      "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT";
  private static final String AWS_XRAY_DAEMON_ADDRESS_CONFIG = "AWS_XRAY_DAEMON_ADDRESS";
  private static final String DEFAULT_UDP_ENDPOINT = "127.0.0.1:2000";
  private static final String OTEL_DISABLED_RESOURCE_PROVIDERS_CONFIG =
      "otel.java.disabled.resource.providers";
  private static final String OTEL_BSP_MAX_EXPORT_BATCH_SIZE_CONFIG =
      "otel.bsp.max.export.batch.size";

  private static final String OTEL_METRICS_EXPORTER = "otel.metrics.exporter";
  private static final String OTEL_LOGS_EXPORTER = "otel.logs.exporter";
  private static final String OTEL_AWS_APPLICATION_SIGNALS_EXPORTER_ENDPOINT =
      "otel.aws.application.signals.exporter.endpoint";
  private static final String OTEL_EXPORTER_OTLP_PROTOCOL = "otel.exporter.otlp.protocol";
  private static final String OTEL_EXPORTER_OTLP_TRACES_ENDPOINT =
      "otel.exporter.otlp.traces.endpoint";
  private static final String OTEL_TRACES_SAMPLER = "otel.traces.sampler";
  private static final String OTEL_TRACES_SAMPLER_ARG = "otel.traces.sampler.arg";

  // UDP packet can be upto 64KB. To limit the packet size, we limit the exported batch size.
  // This is a bit of a magic number, as there is no simple way to tell how many spans can make a
  // 64KB batch since spans can vary in size.
  private static final int LAMBDA_SPAN_EXPORT_BATCH_SIZE = 10;

  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    autoConfiguration.addPropertiesCustomizer(this::customizeProperties);
    autoConfiguration.addPropertiesCustomizer(this::customizeLambdaEnvProperties);
    autoConfiguration.addResourceCustomizer(this::customizeResource);
    autoConfiguration.addSamplerCustomizer(this::customizeSampler);
    autoConfiguration.addTracerProviderCustomizer(this::customizeTracerProviderBuilder);
    autoConfiguration.addMeterProviderCustomizer(this::customizeMeterProvider);
    autoConfiguration.addSpanExporterCustomizer(this::customizeSpanExporter);
  }

  static boolean isLambdaEnvironment() {
    return System.getenv(AWS_LAMBDA_FUNCTION_NAME_CONFIG) != null;
  }

  private boolean isApplicationSignalsEnabled(ConfigProperties configProps) {
    return configProps.getBoolean(
        APPLICATION_SIGNALS_ENABLED_CONFIG,
        configProps.getBoolean(
            DEPRECATED_APP_SIGNALS_ENABLED_CONFIG,
            configProps.getBoolean(DEPRECATED_SMP_ENABLED_CONFIG, false)));
  }

  private boolean isApplicationSignalsRuntimeEnabled(ConfigProperties configProps) {
    return isApplicationSignalsEnabled(configProps)
        && configProps.getBoolean(APPLICATION_SIGNALS_RUNTIME_ENABLED_CONFIG, true);
  }

  private Map<String, String> customizeProperties(ConfigProperties configProps) {
    if (isApplicationSignalsEnabled(configProps)) {
      Map<String, String> propsOverride = new HashMap<>();
      // Enable AWS Resource Providers
      propsOverride.put(OTEL_RESOURCE_PROVIDERS_AWS_ENABLED, "true");

      if (!isLambdaEnvironment()) {
        // Check if properties exist in `configProps`, and only set if missing
        if (configProps.getString(OTEL_METRICS_EXPORTER) == null) {
          propsOverride.put(OTEL_METRICS_EXPORTER, "none");
        }
        if (configProps.getString(OTEL_LOGS_EXPORTER) == null) {
          propsOverride.put(OTEL_LOGS_EXPORTER, "none");
        }
        if (configProps.getString(OTEL_AWS_APPLICATION_SIGNALS_EXPORTER_ENDPOINT) == null) {
          propsOverride.put(
              OTEL_AWS_APPLICATION_SIGNALS_EXPORTER_ENDPOINT, "http://localhost:4316/v1/metrics");
        }
        if (configProps.getString(OTEL_EXPORTER_OTLP_PROTOCOL) == null) {
          propsOverride.put(OTEL_EXPORTER_OTLP_PROTOCOL, "http/protobuf");
        }
        if (configProps.getString(OTEL_EXPORTER_OTLP_TRACES_ENDPOINT) == null) {
          propsOverride.put(OTEL_EXPORTER_OTLP_TRACES_ENDPOINT, "http://localhost:4316/v1/traces");
        }
        if (configProps.getString(OTEL_TRACES_SAMPLER) == null) {
          propsOverride.put(OTEL_TRACES_SAMPLER, "xray");
        }
        if (configProps.getString(OTEL_TRACES_SAMPLER_ARG) == null) {
          propsOverride.put(OTEL_TRACES_SAMPLER_ARG, "endpoint=http://localhost:2000");
        }
      }

      if (isApplicationSignalsRuntimeEnabled(configProps)) {
        List<String> list = configProps.getList(OTEL_JMX_TARGET_SYSTEM_CONFIG);
        if (list.contains("jvm")) {
          logger.log(Level.INFO, "Found jmx in {0}", OTEL_JMX_TARGET_SYSTEM_CONFIG);
        } else {
          logger.log(Level.INFO, "Configure jmx in {0}", OTEL_JMX_TARGET_SYSTEM_CONFIG);
          List<String> jmxTargets = new ArrayList<>(list);
          jmxTargets.add("jvm");
          propsOverride.put(OTEL_JMX_TARGET_SYSTEM_CONFIG, String.join(",", jmxTargets));
        }
      }
      return propsOverride;
    }
    return Collections.emptyMap();
  }

  private Map<String, String> customizeLambdaEnvProperties(ConfigProperties configProperties) {
    if (isLambdaEnvironment()) {
      Map<String, String> propsOverride = new HashMap<>(2);

      // Disable other AWS Resource Providers
      List<String> list = configProperties.getList(OTEL_DISABLED_RESOURCE_PROVIDERS_CONFIG);
      List<String> disabledResourceProviders = new ArrayList<>(list);
      disabledResourceProviders.add(
          "io.opentelemetry.contrib.aws.resource.BeanstalkResourceProvider");
      disabledResourceProviders.add("io.opentelemetry.contrib.aws.resource.Ec2ResourceProvider");
      disabledResourceProviders.add("io.opentelemetry.contrib.aws.resource.EcsResourceProvider");
      disabledResourceProviders.add("io.opentelemetry.contrib.aws.resource.EksResourceProvider");
      propsOverride.put(
          OTEL_DISABLED_RESOURCE_PROVIDERS_CONFIG, String.join(",", disabledResourceProviders));

      // Set the max export batch size for BatchSpanProcessors
      propsOverride.put(
          OTEL_BSP_MAX_EXPORT_BATCH_SIZE_CONFIG, String.valueOf(LAMBDA_SPAN_EXPORT_BATCH_SIZE));

      return propsOverride;
    }
    return Collections.emptyMap();
  }

  private Resource customizeResource(Resource resource, ConfigProperties configProps) {
    if (isApplicationSignalsEnabled(configProps)) {
      AttributesBuilder builder = Attributes.builder();
      AwsResourceAttributeConfigurator.setServiceAttribute(
          resource,
          builder,
          () -> logger.log(Level.WARNING, "Service name is undefined, use UnknownService instead"));
      Resource additionalResource = Resource.create((builder.build()));
      return resource.merge(additionalResource);
    }
    return resource;
  }

  private Sampler customizeSampler(Sampler sampler, ConfigProperties configProps) {
    if (isApplicationSignalsEnabled(configProps)) {
      return AlwaysRecordSampler.create(sampler);
    }
    return sampler;
  }

  private SdkTracerProviderBuilder customizeTracerProviderBuilder(
      SdkTracerProviderBuilder tracerProviderBuilder, ConfigProperties configProps) {
    if (isApplicationSignalsEnabled(configProps)) {
      logger.info("AWS Application Signals enabled");
      Duration exportInterval =
          SDKMeterProviderBuilder.getMetricExportInterval(
              configProps, DEFAULT_METRIC_EXPORT_INTERVAL, logger);
      // Construct and set local and remote attributes span processor
      tracerProviderBuilder.addSpanProcessor(
          AttributePropagatingSpanProcessorBuilder.create().build());

      // If running on Lambda, we just need to export 100% spans and skip generating any Application
      // Signals metrics.
      if (isLambdaEnvironment()) {
        tracerProviderBuilder.addSpanProcessor(
            AwsUnsampledOnlySpanProcessorBuilder.create()
                .setMaxExportBatchSize(LAMBDA_SPAN_EXPORT_BATCH_SIZE)
                .build());
        return tracerProviderBuilder;
      }

      // Construct meterProvider
      MetricExporter metricsExporter =
          ApplicationSignalsExporterProvider.INSTANCE.createExporter(configProps);

      MetricReader metricReader =
          PeriodicMetricReader.builder(metricsExporter).setInterval(exportInterval).build();

      SdkMeterProvider meterProvider =
          SdkMeterProvider.builder()
              .setResource(ResourceHolder.getResource())
              .registerMetricReader(metricReader)
              .build();

      // Construct and set application signals metrics processor
      SpanProcessor spanMetricsProcessor =
          AwsSpanMetricsProcessorBuilder.create(
                  meterProvider, ResourceHolder.getResource(), meterProvider::forceFlush)
              .build();
      tracerProviderBuilder.addSpanProcessor(spanMetricsProcessor);
    }
    return tracerProviderBuilder;
  }

  private SdkMeterProviderBuilder customizeMeterProvider(
      SdkMeterProviderBuilder sdkMeterProviderBuilder, ConfigProperties configProps) {

    if (isApplicationSignalsRuntimeEnabled(configProps)) {
      Set<String> registeredScopeNames = new HashSet<>(1);
      String jmxRuntimeScopeName = "io.opentelemetry.jmx";
      registeredScopeNames.add(jmxRuntimeScopeName);

      SDKMeterProviderBuilder.configureMetricFilter(
          configProps, sdkMeterProviderBuilder, registeredScopeNames, logger);

      MetricExporter metricsExporter =
          ApplicationSignalsExporterProvider.INSTANCE.createExporter(configProps);
      MetricReader metricReader =
          ScopeBasedPeriodicMetricReader.create(metricsExporter, registeredScopeNames)
              .setInterval(
                  SDKMeterProviderBuilder.getMetricExportInterval(
                      configProps, DEFAULT_METRIC_EXPORT_INTERVAL, logger))
              .build();
      sdkMeterProviderBuilder.registerMetricReader(metricReader);

      logger.info("AWS Application Signals runtime metric collection enabled");
    }
    return sdkMeterProviderBuilder;
  }

  private SpanExporter customizeSpanExporter(
      SpanExporter spanExporter, ConfigProperties configProps) {
    // When running in Lambda, override the default OTLP exporter with UDP exporter
    if (isLambdaEnvironment()) {
      if (isOtlpSpanExporter(spanExporter)
          && System.getenv(OTEL_EXPORTER_OTLP_TRACES_ENDPOINT_CONFIG) == null) {
        String tracesEndpoint =
            Optional.ofNullable(System.getenv(AWS_XRAY_DAEMON_ADDRESS_CONFIG))
                .orElse(DEFAULT_UDP_ENDPOINT);
        spanExporter =
            new OtlpUdpSpanExporterBuilder()
                .setPayloadSampleDecision(TracePayloadSampleDecision.SAMPLED)
                .setEndpoint(tracesEndpoint)
                .build();
      }
    }

    if (isApplicationSignalsEnabled(configProps)) {
      return AwsMetricAttributesSpanExporterBuilder.create(
              spanExporter, ResourceHolder.getResource())
          .build();
    }

    return spanExporter;
  }

  private boolean isOtlpSpanExporter(SpanExporter spanExporter) {
    return spanExporter instanceof OtlpGrpcSpanExporter
        || spanExporter instanceof OtlpHttpSpanExporter;
  }

  private enum ApplicationSignalsExporterProvider {
    INSTANCE;

    public MetricExporter createExporter(ConfigProperties configProps) {
      String protocol =
          OtlpConfigUtil.getOtlpProtocol(OtlpConfigUtil.DATA_TYPE_METRICS, configProps);
      logger.log(
          Level.FINE, String.format("AWS Application Signals export protocol: %s", protocol));

      String applicationSignalsEndpoint;
      if (protocol.equals(OtlpConfigUtil.PROTOCOL_HTTP_PROTOBUF)) {
        applicationSignalsEndpoint =
            configProps.getString(
                APPLICATION_SIGNALS_EXPORTER_ENDPOINT_CONFIG,
                configProps.getString(
                    DEPRECATED_APP_SIGNALS_EXPORTER_ENDPOINT_CONFIG,
                    configProps.getString(
                        DEPRECATED_SMP_EXPORTER_ENDPOINT_CONFIG,
                        "http://localhost:4316/v1/metrics")));
        logger.log(
            Level.FINE,
            String.format(
                "AWS Application Signals export endpoint: %s", applicationSignalsEndpoint));
        return OtlpHttpMetricExporter.builder()
            .setEndpoint(applicationSignalsEndpoint)
            .setDefaultAggregationSelector(this::getAggregation)
            .setAggregationTemporalitySelector(CloudWatchTemporalitySelector.alwaysDelta())
            .build();
      } else if (protocol.equals(OtlpConfigUtil.PROTOCOL_GRPC)) {
        applicationSignalsEndpoint =
            configProps.getString(
                APPLICATION_SIGNALS_EXPORTER_ENDPOINT_CONFIG,
                configProps.getString(
                    DEPRECATED_APP_SIGNALS_EXPORTER_ENDPOINT_CONFIG,
                    configProps.getString(
                        DEPRECATED_SMP_EXPORTER_ENDPOINT_CONFIG, "http://localhost:4315")));
        logger.log(
            Level.FINE,
            String.format(
                "AWS Application Signals export endpoint: %s", applicationSignalsEndpoint));
        return OtlpGrpcMetricExporter.builder()
            .setEndpoint(applicationSignalsEndpoint)
            .setDefaultAggregationSelector(this::getAggregation)
            .setAggregationTemporalitySelector(CloudWatchTemporalitySelector.alwaysDelta())
            .build();
      }
      throw new ConfigurationException(
          "Unsupported AWS Application Signals export protocol: " + protocol);
    }

    private Aggregation getAggregation(InstrumentType instrumentType) {
      if (instrumentType == InstrumentType.HISTOGRAM) {
        return Aggregation.base2ExponentialBucketHistogram();
      }
      return Aggregation.defaultAggregation();
    }
  }
}
