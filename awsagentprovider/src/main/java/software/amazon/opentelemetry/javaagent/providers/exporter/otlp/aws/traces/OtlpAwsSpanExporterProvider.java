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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.traces;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.internal.OtlpConfigUtil;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.AutoConfigureListener;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.concurrent.atomic.AtomicReference;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.OtlpAwsExporterConfig;

/** Java autoconfiguration SPI registration for the {@code otlp/sigv4} traces exporter. */
public final class OtlpAwsSpanExporterProvider
    implements ConfigurableSpanExporterProvider, AutoConfigureListener {
  private final AtomicReference<MeterProvider> meterProviderRef =
      new AtomicReference<>(MeterProvider.noop());

  @Override
  public String getName() {
    return OtlpAwsExporterConfig.EXPORTER_NAME;
  }

  @Override
  public SpanExporter createExporter(ConfigProperties config) {
    OtlpAwsExporterConfig.validate("traces", config);
    String compression = OtlpAwsExporterConfig.compression("traces", config);
    OtlpHttpSpanExporterBuilder builder = OtlpHttpSpanExporter.builder();
    AtomicReference<String> endpoint = new AtomicReference<>("http://localhost:4318/v1/traces");
    OtlpConfigUtil.configureOtlpExporterBuilder(
        "traces",
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
    builder.setMeterProvider(meterProviderRef::get);
    return OtlpAwsSpanExporterBuilder.create(builder, endpoint.get())
        .setCompression(compression)
        .setAwsRegion(OtlpAwsExporterConfig.region(config, endpoint.get()))
        .setAwsService(OtlpAwsExporterConfig.service("traces", config, "xray"))
        .build();
  }

  @Override
  public void afterAutoConfigure(OpenTelemetrySdk sdk) {
    meterProviderRef.set(sdk.getMeterProvider());
  }
}
