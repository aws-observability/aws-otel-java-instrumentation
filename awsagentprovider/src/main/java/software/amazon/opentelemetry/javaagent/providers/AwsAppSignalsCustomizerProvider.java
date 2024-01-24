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

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.contrib.awsxray.AlwaysRecordSampler;
import io.opentelemetry.contrib.awsxray.ResourceHolder;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.HashMap;
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
 * <p>You can control when these customizations are applied using the property otel.smp.enabled or
 * the environment variable OTEL_SMP_ENABLED. This flag is enabled by default.
 */
public class AwsAppSignalsCustomizerProvider implements AutoConfigurationCustomizerProvider {
  private static final Duration DEFAULT_METRIC_EXPORT_INTERVAL = Duration.ofMinutes(1);
  private static final Logger logger =
      Logger.getLogger(AwsAppSignalsCustomizerProvider.class.getName());

  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    grpcProtocolByDefault(autoConfiguration);
    autoConfiguration.addSamplerCustomizer(this::customizeSampler);
    autoConfiguration.addTracerProviderCustomizer(this::customizeTracerProviderBuilder);
    autoConfiguration.addSpanExporterCustomizer(this::customizeSpanExporter);
  }

  private boolean isSmpEnabled(ConfigProperties configProps) {
    return configProps.getBoolean("otel.smp.enabled", false);
  }

  private Sampler customizeSampler(Sampler sampler, ConfigProperties configProps) {
    if (isSmpEnabled(configProps)) {
      return AlwaysRecordSampler.create(sampler);
    }
    return sampler;
  }

  private SdkTracerProviderBuilder customizeTracerProviderBuilder(
      SdkTracerProviderBuilder tracerProviderBuilder, ConfigProperties configProps) {
    if (isSmpEnabled(configProps)) {
      logger.info("Span Metrics Processor enabled");
      String smpEndpoint =
          configProps.getString(
              "otel.aws.smp.exporter.endpoint", "http://cloudwatch-agent.amazon-cloudwatch:4317");
      Duration exportInterval =
          configProps.getDuration("otel.metric.export.interval", DEFAULT_METRIC_EXPORT_INTERVAL);
      logger.log(Level.FINE, String.format("Span Metrics endpoint: %s", smpEndpoint));
      logger.log(Level.FINE, String.format("Span Metrics export interval: %s", exportInterval));
      // Cap export interval to 60 seconds. This is currently required for metrics-trace correlation
      // to work correctly.
      if (exportInterval.compareTo(DEFAULT_METRIC_EXPORT_INTERVAL) > 0) {
        exportInterval = DEFAULT_METRIC_EXPORT_INTERVAL;
        logger.log(
            Level.INFO,
            String.format("AWS AppSignals metrics export interval capped to %s", exportInterval));
      }
      // Construct and set local and remote attributes span processor
      tracerProviderBuilder.addSpanProcessor(
          AttributePropagatingSpanProcessorBuilder.create().build());
      // Construct meterProvider
      MetricExporter metricsExporter =
          OtlpGrpcMetricExporter.builder()
              .setEndpoint(smpEndpoint)
              .setDefaultAggregationSelector(
                  instrumentType -> {
                    if (instrumentType == InstrumentType.HISTOGRAM) {
                      return Aggregation.base2ExponentialBucketHistogram();
                    }
                    return Aggregation.defaultAggregation();
                  })
              .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
              .build();
      MetricReader metricReader =
          PeriodicMetricReader.builder(metricsExporter).setInterval(exportInterval).build();

      MeterProvider meterProvider =
          SdkMeterProvider.builder()
              .setResource(ResourceHolder.getResource())
              .registerMetricReader(metricReader)
              .build();
      // Construct and set span metrics processor
      SpanProcessor spanMetricsProcessor =
          AwsSpanMetricsProcessorBuilder.create(meterProvider, ResourceHolder.getResource())
              .build();
      tracerProviderBuilder.addSpanProcessor(spanMetricsProcessor);
    }
    return tracerProviderBuilder;
  }

  private SpanExporter customizeSpanExporter(
      SpanExporter spanExporter, ConfigProperties configProps) {
    if (isSmpEnabled(configProps)) {
      return AwsMetricAttributesSpanExporterBuilder.create(
              spanExporter, ResourceHolder.getResource())
          .build();
    }

    return spanExporter;
  }

  private void grpcProtocolByDefault(AutoConfigurationCustomizer autoConfiguration) {
    if (System.getProperty("otel.exporter.otlp.protocol") == null
        && System.getenv("OTEL_EXPORTER_OTLP_PROTOCOL") == null) {
      autoConfiguration.addPropertiesSupplier(
          () ->
              new HashMap<String, String>() {
                {
                  put("otel.exporter.otlp.protocol", "grpc");
                }
              });
    }
  }
}
