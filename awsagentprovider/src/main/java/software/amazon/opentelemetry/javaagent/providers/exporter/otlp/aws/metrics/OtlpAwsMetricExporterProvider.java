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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.metrics;

import static software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.OtlpAwsConfigUtil.DATA_TYPE_METRICS;
import static software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.OtlpAwsConfigUtil.DEFAULT_METRICS_ENDPOINT;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.internal.ExporterBuilderUtil;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.internal.OtlpConfigUtil;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.AutoConfigureListener;
import io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.util.concurrent.atomic.AtomicReference;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.OtlpAwsConfigUtil;

/** Java autoconfiguration SPI registration for the {@code otlp/sigv4} metrics exporter. */
public final class OtlpAwsMetricExporterProvider
    implements ConfigurableMetricExporterProvider, AutoConfigureListener {
  private final AtomicReference<MeterProvider> meterProviderRef =
      new AtomicReference<>(MeterProvider.noop());

  @Override
  public String getName() {
    return "otlp/sigv4";
  }

  @Override
  public MetricExporter createExporter(ConfigProperties config) {
    String compression = OtlpAwsConfigUtil.getCompression(DATA_TYPE_METRICS, config);
    String defaultAwsService = "monitoring";
    OtlpHttpMetricExporterBuilder builder = OtlpHttpMetricExporter.builder();
    AtomicReference<String> endpoint = new AtomicReference<>(DEFAULT_METRICS_ENDPOINT);
    OtlpConfigUtil.configureOtlpExporterBuilder(
        DATA_TYPE_METRICS,
        config,
        builder::setComponentLoader,
        value -> {
          endpoint.set(value);
          builder.setEndpoint(value);
        },
        builder::addHeader,
        builder::setCompression,
        builder::setTimeout,
        builder::setTrustedCertificates,
        builder::setClientTls,
        builder::setRetryPolicy,
        builder::setMemoryMode,
        builder::setInternalTelemetryVersion);
    ExporterBuilderUtil.configureOtlpAggregationTemporality(
        config, builder::setAggregationTemporalitySelector);
    ExporterBuilderUtil.configureOtlpHistogramDefaultAggregation(
        config, builder::setDefaultAggregationSelector);
    builder.setMeterProvider(meterProviderRef::get);
    return OtlpAwsMetricExporterBuilder.create(builder, endpoint.get())
        .setCompression(compression)
        .setAwsRegion(OtlpAwsConfigUtil.getAwsRegion(config, endpoint.get()))
        .setAwsService(
            OtlpAwsConfigUtil.getAwsService(DATA_TYPE_METRICS, config, defaultAwsService))
        .build();
  }

  @Override
  public void afterAutoConfigure(OpenTelemetrySdk sdk) {
    meterProviderRef.set(sdk.getMeterProvider());
  }
}
