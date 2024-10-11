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

import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.internal.OtlpConfigUtil;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * You can control when these customizations are applied using both the properties -
 * otel.aws.jmx.enabled and otel.aws.jmx.exporter.metrics.endpoint or the environment variable
 * AWS_JMX_ENABLED_CONFIG and AWS_JMX_ENDPOINT_CONFIG. These flags are disabled by default.
 */
public class AwsJMXMetricsCustomizerProvider implements AutoConfigurationCustomizerProvider {
  private static final Duration DEFAULT_METRIC_EXPORT_INTERVAL = Duration.ofMinutes(1);
  private static final Logger logger =
      Logger.getLogger(AwsJMXMetricsCustomizerProvider.class.getName());

  private static final String AWS_JMX_ENABLED_CONFIG = "otel.aws.jmx.enabled";
  private static final String AWS_JMX_ENDPOINT_CONFIG = "otel.aws.jmx.exporter.metrics.endpoint";

  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    autoConfiguration.addMeterProviderCustomizer(this::customizeMeterProvider);
  }

  private boolean isOtelJMXEnabled(ConfigProperties configProps) {
    return configProps.getBoolean(AWS_JMX_ENABLED_CONFIG, false)
        && configProps.getString(AWS_JMX_ENDPOINT_CONFIG, "") != "";
  }

  private SdkMeterProviderBuilder customizeMeterProvider(
      SdkMeterProviderBuilder sdkMeterProviderBuilder, ConfigProperties configProps) {

    if (isOtelJMXEnabled(configProps)) {
      Set<String> registeredScopeNames = new HashSet<>(1);
      String jmxRuntimeScopeName = "io.opentelemetry.jmx";
      registeredScopeNames.add(jmxRuntimeScopeName);

      SDKMeterProviderBuilder.configureMetricFilter(
          configProps, sdkMeterProviderBuilder, registeredScopeNames, logger);

      MetricExporter metricsExporter = JMXExporterProvider.INSTANCE.createExporter(configProps);
      MetricReader metricReader =
          ScopeBasedPeriodicMetricReader.create(metricsExporter, registeredScopeNames)
              .setInterval(
                  SDKMeterProviderBuilder.getMetricExportInterval(
                      configProps, DEFAULT_METRIC_EXPORT_INTERVAL, logger))
              .build();
      sdkMeterProviderBuilder.registerMetricReader(metricReader);

      logger.info("Otel JMX metric collection enabled");
    }
    return sdkMeterProviderBuilder;
  }

  private enum JMXExporterProvider {
    INSTANCE;

    public MetricExporter createExporter(ConfigProperties configProps) {
      String protocol =
          OtlpConfigUtil.getOtlpProtocol(OtlpConfigUtil.DATA_TYPE_METRICS, configProps);
      logger.log(Level.FINE, String.format("Otel JMX metrics export protocol: %s", protocol));

      String otelJMXEndpoint;
      if (protocol.equals(OtlpConfigUtil.PROTOCOL_HTTP_PROTOBUF)) {
        otelJMXEndpoint = configProps.getString(AWS_JMX_ENDPOINT_CONFIG, "http://localhost:4314");
        logger.log(
            Level.FINE, String.format("AWS Otel JMX metrics export endpoint: %s", otelJMXEndpoint));
        return OtlpHttpMetricExporter.builder()
            .setEndpoint(otelJMXEndpoint)
            .setDefaultAggregationSelector(this::getAggregation)
            .build();
      }
      throw new ConfigurationException(
          "Unsupported AWS Otel JMX metrics export protocol: " + protocol);
    }

    private Aggregation getAggregation(InstrumentType instrumentType) {
      if (instrumentType == InstrumentType.HISTOGRAM) {
        return Aggregation.base2ExponentialBucketHistogram();
      }
      return Aggregation.defaultAggregation();
    }
  }
}
