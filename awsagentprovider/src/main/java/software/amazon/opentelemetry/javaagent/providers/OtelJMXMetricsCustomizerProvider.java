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
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * You can control when these customizations are applied using the property otel.jmx.enabled or the
 * environment variable OTEL_JMX_ENABLED_CONFIG. This flag is disabled by default.
 */
public class OtelJMXMetricsCustomizerProvider implements AutoConfigurationCustomizerProvider {
    private static final Duration DEFAULT_METRIC_EXPORT_INTERVAL = Duration.ofMinutes(1);
    private static final Logger logger =
            Logger.getLogger(OtelJMXMetricsCustomizerProvider.class.getName());

    private static final String OTEL_JMX_ENABLED_CONFIG = "otel.jmx.enabled";
    private static final String OTEL_JMX_ENDPOINT_CONFIG = "otel.jmx.exporter.metrics.endpoint";

    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addMeterProviderCustomizer(this::customizeMeterProvider);
    }

    private boolean isOtelJMXEnabled(ConfigProperties configProps) {
        return configProps.getBoolean(OTEL_JMX_ENABLED_CONFIG);
    }

    private SdkMeterProviderBuilder customizeMeterProvider(
            SdkMeterProviderBuilder sdkMeterProviderBuilder, ConfigProperties configProps) {

        if (isOtelJMXEnabled(configProps)) {
            Set<String> registeredScopeNames = new HashSet<>(1);
            String jmxRuntimeScopeName = "io.opentelemetry.jmx";
            registeredScopeNames.add(jmxRuntimeScopeName);

            configureMetricFilter(configProps, sdkMeterProviderBuilder, registeredScopeNames);

            MetricExporter metricsExporter = JMXExporterProvider.INSTANCE.createExporter(configProps);
            MetricReader metricReader =
                    ScopeBasedPeriodicMetricReader.create(metricsExporter, registeredScopeNames)
                            .setInterval(getMetricExportInterval(configProps))
                            .build();
            sdkMeterProviderBuilder.registerMetricReader(metricReader);

            logger.info("Otel JMX metric collection enabled");
        }
        return sdkMeterProviderBuilder;
    }

    private static void configureMetricFilter(
            ConfigProperties configProps,
            SdkMeterProviderBuilder sdkMeterProviderBuilder,
            Set<String> registeredScopeNames) {
        Set<String> exporterNames =
                DefaultConfigProperties.getSet(configProps, "otel.metrics.exporter");
        if (exporterNames.contains("none")) {
            for (String scope : registeredScopeNames) {
                sdkMeterProviderBuilder.registerView(
                        InstrumentSelector.builder().setMeterName(scope).build(),
                        View.builder().setAggregation(Aggregation.defaultAggregation()).build());

                logger.log(Level.FINE, "Registered scope {0}", scope);
            }
            sdkMeterProviderBuilder.registerView(
                    InstrumentSelector.builder().setName("*").build(),
                    View.builder().setAggregation(Aggregation.drop()).build());
        }
    }

    private static Duration getMetricExportInterval(ConfigProperties configProps) {
        Duration exportInterval =
                configProps.getDuration("otel.metric.export.interval", DEFAULT_METRIC_EXPORT_INTERVAL);
        logger.log(Level.FINE, String.format("Otel JMX Metrics export interval: %s", exportInterval));
        // Cap export interval to 60 seconds. This is currently required for metrics-trace correlation
        // to work correctly.
        if (exportInterval.compareTo(DEFAULT_METRIC_EXPORT_INTERVAL) > 0) {
            exportInterval = DEFAULT_METRIC_EXPORT_INTERVAL;
            logger.log(
                    Level.INFO,
                    String.format("Otel JMX Metrics export interval capped to %s", exportInterval));
        }
        return exportInterval;
    }

    private enum JMXExporterProvider {
        INSTANCE;

        public MetricExporter createExporter(ConfigProperties configProps) {
            String protocol =
                    OtlpConfigUtil.getOtlpProtocol(OtlpConfigUtil.DATA_TYPE_METRICS, configProps);
            logger.log(Level.FINE, String.format("Otel JMX metrics export protocol: %s", protocol));

            String otelJMXEndpoint;
            if (protocol.equals(OtlpConfigUtil.PROTOCOL_HTTP_PROTOBUF)) {
                otelJMXEndpoint = configProps.getString(OTEL_JMX_ENDPOINT_CONFIG, "http://localhost:4314");
                logger.log(
                        Level.FINE, String.format("Otel JMX metrics export endpoint: %s", otelJMXEndpoint));
                return OtlpHttpMetricExporter.builder()
                        .setEndpoint(otelJMXEndpoint)
                        .setDefaultAggregationSelector(this::getAggregation)
                        .setAggregationTemporalitySelector(CloudWatchTemporalitySelector.alwaysDelta())
                        .build();
            }
            throw new ConfigurationException("Unsupported Otel JMX metrics export protocol: " + protocol);
        }

        private Aggregation getAggregation(InstrumentType instrumentType) {
            if (instrumentType == InstrumentType.HISTOGRAM) {
                return Aggregation.base2ExponentialBucketHistogram();
            }
            return Aggregation.defaultAggregation();
        }
    }
}