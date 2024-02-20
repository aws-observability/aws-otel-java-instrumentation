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
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.internal.OtlpConfigUtil;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
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
 * otel.aws.app.signals.enabled or the environment variable OTEL_AWS_APP_SIGNALS_ENABLED. This flag
 * is disabled by default.
 */
public class AwsAppSignalsCustomizerProvider implements AutoConfigurationCustomizerProvider {
  private static final Duration DEFAULT_METRIC_EXPORT_INTERVAL = Duration.ofMinutes(1);
  private static final Logger logger =
      Logger.getLogger(AwsAppSignalsCustomizerProvider.class.getName());

  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    autoConfiguration.addSamplerCustomizer(this::customizeSampler);
    autoConfiguration.addTracerProviderCustomizer(this::customizeTracerProviderBuilder);
    autoConfiguration.addSpanExporterCustomizer(this::customizeSpanExporter);
  }

  private boolean isAppSignalsEnabled(ConfigProperties configProps) {
    return configProps.getBoolean(
        "otel.aws.app.signals.enabled", configProps.getBoolean("otel.smp.enabled", false));
  }

  private Sampler customizeSampler(Sampler sampler, ConfigProperties configProps) {
    if (isAppSignalsEnabled(configProps)) {
      return AlwaysRecordSampler.create(sampler);
    }
    return sampler;
  }

  private SdkTracerProviderBuilder customizeTracerProviderBuilder(
      SdkTracerProviderBuilder tracerProviderBuilder, ConfigProperties configProps) {
    if (isAppSignalsEnabled(configProps)) {
      logger.info("AWS AppSignals enabled");
      Duration exportInterval =
          configProps.getDuration("otel.metric.export.interval", DEFAULT_METRIC_EXPORT_INTERVAL);
      logger.log(
          Level.FINE, String.format("AppSignals Metrics export interval: %s", exportInterval));
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
          AppSignalsExporterProvider.INSTANCE.createExporter(configProps);

      MetricReader metricReader =
          PeriodicMetricReader.builder(metricsExporter).setInterval(exportInterval).build();

      MeterProvider meterProvider =
          SdkMeterProvider.builder()
              .setResource(ResourceHolder.getResource())
              .registerMetricReader(metricReader)
              .build();
      // Construct and set AppSignals metrics processor
      SpanProcessor spanMetricsProcessor =
          AwsSpanMetricsProcessorBuilder.create(meterProvider, ResourceHolder.getResource())
              .build();
      tracerProviderBuilder.addSpanProcessor(spanMetricsProcessor);
    }
    return tracerProviderBuilder;
  }

  private SpanExporter customizeSpanExporter(
      SpanExporter spanExporter, ConfigProperties configProps) {
    if (isAppSignalsEnabled(configProps)) {
      return AwsMetricAttributesSpanExporterBuilder.create(
              spanExporter, ResourceHolder.getResource())
          .build();
    }

    return spanExporter;
  }

  private enum AppSignalsExporterProvider {
    INSTANCE;

    public MetricExporter createExporter(ConfigProperties configProps) {
      String protocol =
          OtlpConfigUtil.getOtlpProtocol(OtlpConfigUtil.DATA_TYPE_METRICS, configProps);
      logger.log(Level.FINE, String.format("AppSignals export protocol: %s", protocol));

      String appSignalsEndpoint =
          configProps.getString(
              "otel.aws.app.signals.exporter.endpoint",
              configProps.getString("otel.aws.smp.exporter.endpoint", "http://localhost:4315"));
      logger.log(Level.FINE, String.format("AppSignals export endpoint: %s", appSignalsEndpoint));

      if (protocol.equals(OtlpConfigUtil.PROTOCOL_HTTP_PROTOBUF)) {
        return OtlpHttpMetricExporter.builder()
            .setEndpoint(appSignalsEndpoint)
            .setDefaultAggregationSelector(this::getAggregation)
            .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
            .build();
      } else if (protocol.equals(OtlpConfigUtil.PROTOCOL_GRPC)) {
        return OtlpGrpcMetricExporter.builder()
            .setEndpoint(appSignalsEndpoint)
            .setDefaultAggregationSelector(this::getAggregation)
            .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
            .build();
      }
      throw new ConfigurationException("Unsupported AppSignals export protocol: " + protocol);
    }

    private Aggregation getAggregation(InstrumentType instrumentType) {
      if (instrumentType == InstrumentType.HISTOGRAM) {
        return Aggregation.base2ExponentialBucketHistogram();
      }
      return Aggregation.defaultAggregation();
    }
  }
}
