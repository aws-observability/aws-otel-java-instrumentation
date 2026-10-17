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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.internal.AutoConfigureListener;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.common.export.MemoryMode;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Spy;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.AbstractOtlpAwsExporterProviderTest;

class OtlpAwsSpanExporterProviderTest
    extends AbstractOtlpAwsExporterProviderTest<OtlpAwsSpanExporter> {
  @Spy private OtlpAwsSpanExporterProvider provider;
  @Spy private OtlpHttpSpanExporterBuilder httpBuilder;

  OtlpAwsSpanExporterProviderTest() {
    super("traces", "xray", OtlpAwsSpanExporter.class, OtlpHttpSpanExporter.class);
  }

  @BeforeEach
  void setup() {
    doReturn(httpBuilder).when(provider).httpBuilder();
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
  protected OtlpAwsSpanExporter createExporter(Map<String, String> properties) {
    return (OtlpAwsSpanExporter)
        provider.createExporter(DefaultConfigProperties.createFromMap(properties));
  }

  @Override
  protected void shutdown(OtlpAwsSpanExporter exporter) {
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
    builder.addSpanExporterCustomizer(
        (exporter, unused) -> {
          customizer.accept(exporter);
          return exporter;
        });
  }
}
