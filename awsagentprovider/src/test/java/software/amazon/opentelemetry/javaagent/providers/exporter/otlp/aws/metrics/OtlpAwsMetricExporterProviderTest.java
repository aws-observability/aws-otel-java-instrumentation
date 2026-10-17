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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.internal.AutoConfigureListener;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Spy;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.AbstractOtlpAwsExporterProviderTest;

class OtlpAwsMetricExporterProviderTest
    extends AbstractOtlpAwsExporterProviderTest<OtlpAwsMetricExporter> {
  @Spy private OtlpAwsMetricExporterProvider provider;
  @Spy private OtlpHttpMetricExporterBuilder httpBuilder;

  OtlpAwsMetricExporterProviderTest() {
    super("metrics", "monitoring", OtlpAwsMetricExporter.class, OtlpHttpMetricExporter.class);
  }

  @BeforeEach
  void setup() {
    doReturn(httpBuilder).when(provider).httpBuilder();
  }

  @Test
  void testCreateExporterHttpWithMetricConfiguration() {
    Map<String, String> properties = properties();
    properties.put("otel.exporter.otlp.metrics.temporality.preference", "DELTA");
    properties.put(
        "otel.exporter.otlp.metrics.default.histogram.aggregation",
        "base2_exponential_bucket_histogram");
    OtlpAwsMetricExporter exporter = createExporter(properties);
    try {
      assertThat(exporter.getAggregationTemporality(InstrumentType.COUNTER))
          .isEqualTo(AggregationTemporality.DELTA);
      assertThat(exporter.getDefaultAggregation(InstrumentType.HISTOGRAM).toString())
          .contains("Base2ExponentialHistogram");
    } finally {
      exporter.shutdown();
    }
  }

  @Override
  protected String providerName() {
    return provider.getName();
  }

  @Override
  protected AutoConfigureListener provider() {
    return provider;
  }

  @Override
  protected OtlpAwsMetricExporter createExporter(Map<String, String> properties) {
    return (OtlpAwsMetricExporter)
        provider.createExporter(DefaultConfigProperties.createFromMap(properties));
  }

  @Override
  protected void shutdown(OtlpAwsMetricExporter exporter) {
    exporter.shutdown();
  }

  @Override
  protected void verifyDefaults(String endpoint) {
    verify(httpBuilder).build();
    verify(httpBuilder).setComponentLoader(any());
    verify(httpBuilder).setEndpoint(endpoint);
    verify(httpBuilder, never()).addHeader(any(), any());
    verify(httpBuilder).setCompression("none");
    verify(httpBuilder, never()).setTimeout(any());
    verify(httpBuilder, never()).setTrustedCertificates(any());
    verify(httpBuilder, never()).setClientTls(any(), any());
    verify(httpBuilder).setMemoryMode(MemoryMode.IMMUTABLE_DATA);
    assertThat(httpBuilder).extracting("delegate").extracting("retryPolicy").isNotNull();
  }

  @Override
  protected void verifyConfiguration(
      String endpoint, Map<String, String> headers, boolean retryDisabled) {
    verify(httpBuilder).build();
    verify(httpBuilder).setComponentLoader(any());
    verify(httpBuilder, atLeastOnce()).setEndpoint(endpoint);
    headers.forEach((key, value) -> verify(httpBuilder).addHeader(key, value));
    verify(httpBuilder, times(headers.size())).addHeader(any(), any());
    verify(httpBuilder, atLeastOnce()).setCompression("gzip");
    verify(httpBuilder).setTimeout(Duration.ofSeconds(15));
    verify(httpBuilder).setTrustedCertificates(trustedCertificates);
    verify(httpBuilder).setClientTls(clientKey, clientCertificates);
    verify(httpBuilder, atLeastOnce()).setMemoryMode(MemoryMode.IMMUTABLE_DATA);
    if (retryDisabled) {
      assertThat(httpBuilder).extracting("delegate").extracting("retryPolicy").isNull();
    } else {
      assertThat(httpBuilder).extracting("delegate").extracting("retryPolicy").isNotNull();
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Supplier<MeterProvider> meterProviderSupplier() {
    ArgumentCaptor<Supplier<MeterProvider>> supplier = ArgumentCaptor.forClass(Supplier.class);
    verify(httpBuilder).setMeterProvider(supplier.capture());
    return supplier.getValue();
  }

  @Override
  protected void addExporterCustomizer(
      AutoConfiguredOpenTelemetrySdkBuilder builder, Consumer<Object> customizer) {
    builder.addMetricExporterCustomizer(
        (exporter, unused) -> {
          customizer.accept(exporter);
          return exporter;
        });
  }
}
