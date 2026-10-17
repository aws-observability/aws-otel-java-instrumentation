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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.internal.AutoConfigureListener;
import io.opentelemetry.sdk.common.export.MemoryMode;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public abstract class AbstractOtlpAwsExporterProviderTest<T extends BaseOtlpAwsExporter> {
  private final String dataType;
  private final String defaultAwsService;
  private final Class<T> exporterType;
  private final Class<?> upstreamExporterType;
  protected byte[] trustedCertificates;
  protected byte[] clientKey;
  protected byte[] clientCertificates;
  private String certificatePath;
  private String clientKeyPath;
  private String clientCertificatePath;

  protected AbstractOtlpAwsExporterProviderTest(
      String dataType,
      String defaultAwsService,
      Class<T> exporterType,
      Class<?> upstreamExporterType) {
    this.dataType = dataType;
    this.defaultAwsService = defaultAwsService;
    this.exporterType = exporterType;
    this.upstreamExporterType = upstreamExporterType;
  }

  @BeforeEach
  void setup(@TempDir Path tempDir) throws IOException {
    trustedCertificates = "test-trusted-certificates".getBytes(StandardCharsets.UTF_8);
    clientKey = "test-client-key".getBytes(StandardCharsets.UTF_8);
    clientCertificates = "test-client-certificates".getBytes(StandardCharsets.UTF_8);
    certificatePath =
        Files.write(tempDir.resolve("certificate.cert"), trustedCertificates).toString();
    clientKeyPath = Files.write(tempDir.resolve("clientKey.key"), clientKey).toString();
    clientCertificatePath =
        Files.write(tempDir.resolve("clientCertificate.cert"), clientCertificates).toString();
  }

  @Test
  protected void testGetName() {
    assertThat(providerName()).isEqualTo("otlp/sigv4");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"foo", "grpc"})
  protected void testCreateExporterUnsupportedProtocol(String protocol) {
    Map<String, String> properties = properties();
    if (protocol == null) {
      properties.remove("otel.exporter.otlp.protocol");
    } else {
      properties.put("otel.exporter.otlp.protocol", protocol);
    }
    assertThatThrownBy(() -> createExporter(properties))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("Unsupported OTLP " + dataType + " protocol")
        .hasMessageContaining("http/protobuf");
    if (protocol != null) {
      properties.put("otel.exporter.otlp.protocol", "http/protobuf");
      properties.put("otel.exporter.otlp." + dataType + ".protocol", protocol);
      assertThatThrownBy(() -> createExporter(properties))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("Unsupported OTLP " + dataType + " protocol")
          .hasMessageContaining("http/protobuf");
    }
  }

  @Test
  protected void testCreateExporterHttpDefaults() {
    Map<String, String> properties = properties();
    properties.remove("otel.exporter.otlp.protocol");
    properties.put("otel.exporter.otlp." + dataType + ".protocol", "http/protobuf");
    T exporter = createExporter(properties);
    try {
      assertThat(exporter).isExactlyInstanceOf(exporterType);
      assertThat(exporter.endpoint).isEqualTo("http://localhost:4318/v1/" + dataType);
      assertThat(exporter.awsRegion).isEqualTo("us-east-1");
      assertThat(exporter.serviceName()).isEqualTo(defaultAwsService);
      assertThat(exporter.getCompression()).isEqualTo(CompressionMethod.NONE);
      assertImmutableMemoryMode(exporter);
      verifyDefaults(exporter.endpoint);
    } finally {
      shutdown(exporter);
    }
  }

  @Test
  protected void testCreateExporterHttpWithGeneralConfiguration() {
    Map<String, String> properties = properties();
    properties.put("otel.exporter.otlp.endpoint", "https://localhost:443/");
    properties.put("otel.exporter.otlp.certificate", certificatePath);
    properties.put("otel.exporter.otlp.client.key", clientKeyPath);
    properties.put("otel.exporter.otlp.client.certificate", clientCertificatePath);
    properties.put(
        "otel.exporter.otlp.headers", "header-key1=header%20value1,header-key2=header value2");
    properties.put("otel.exporter.otlp.compression", "gzip");
    properties.put("otel.exporter.otlp.timeout", "15s");
    properties.put("otel.java.exporter.otlp.retry.disabled", "true");
    properties.put("otel.java.exporter.memory_mode", "reusable_data");
    properties.put("aws.region", "us-west-2");
    properties.put("aws.exporter.service", "custom-service");
    T exporter = createExporter(properties);
    try {
      assertThat(exporter).isExactlyInstanceOf(exporterType);
      assertThat(exporter.endpoint).isEqualTo("https://localhost:443/v1/" + dataType);
      assertThat(exporter.awsRegion).isEqualTo("us-west-2");
      assertThat(exporter.serviceName()).isEqualTo("custom-service");
      assertThat(exporter.getCompression()).isEqualTo(CompressionMethod.GZIP);
      assertImmutableMemoryMode(exporter);
      verifyConfiguration(
          exporter.endpoint,
          Map.of("header-key1", "header value1", "header-key2", "header value2"),
          true);
    } finally {
      shutdown(exporter);
    }
  }

  @Test
  protected void testCreateExporterHttpWithSignalConfiguration() {
    Map<String, String> properties = properties();
    properties.put("otel.exporter.otlp.protocol", "grpc");
    properties.put("otel.exporter.otlp." + dataType + ".protocol", "http/protobuf");
    properties.put("otel.exporter.otlp.endpoint", "https://dummy:443/");
    properties.put(
        "otel.exporter.otlp." + dataType + ".endpoint", "https://localhost:443/v1/" + dataType);
    properties.put("otel.exporter.otlp.certificate", "dummy.cert");
    properties.put("otel.exporter.otlp." + dataType + ".certificate", certificatePath);
    properties.put("otel.exporter.otlp.client.key", "dummy.key");
    properties.put("otel.exporter.otlp." + dataType + ".client.key", clientKeyPath);
    properties.put("otel.exporter.otlp.client.certificate", "dummy-client.cert");
    properties.put("otel.exporter.otlp." + dataType + ".client.certificate", clientCertificatePath);
    properties.put("otel.exporter.otlp.headers", "dummy=value");
    properties.put("otel.exporter.otlp." + dataType + ".headers", "header-key=header-value");
    properties.put("otel.exporter.otlp.compression", "none");
    properties.put("otel.exporter.otlp." + dataType + ".compression", "gzip");
    properties.put("otel.exporter.otlp.timeout", "1s");
    properties.put("otel.exporter.otlp." + dataType + ".timeout", "15s");
    properties.put("otel.java.exporter.memory_mode", "immutable_data");
    properties.put("aws." + dataType + ".exporter.service", "custom-service");
    T exporter = createExporter(properties);
    try {
      assertThat(exporter).isExactlyInstanceOf(exporterType);
      assertThat(exporter.endpoint).isEqualTo("https://localhost:443/v1/" + dataType);
      assertThat(exporter.awsRegion).isEqualTo("us-east-1");
      assertThat(exporter.serviceName()).isEqualTo("custom-service");
      assertThat(exporter.getCompression()).isEqualTo(CompressionMethod.GZIP);
      assertImmutableMemoryMode(exporter);
      verifyConfiguration(exporter.endpoint, Map.of("header-key", "header-value"), false);
    } finally {
      shutdown(exporter);
    }
  }

  @Test
  protected void testCreateExporterDecodingError() {
    Map<String, String> properties = properties();
    properties.put("otel.exporter.otlp.headers", "header-key=Bearer%20s3cr3t%-1");
    assertThatThrownBy(() -> createExporter(properties))
        .isInstanceOf(ConfigurationException.class)
        .hasMessage("Cannot decode header value for header: header-key");
  }

  @Test
  protected void testCreateExporterAwsServicePrecedence() {
    Map<String, String> properties = properties();
    properties.put("aws." + dataType + ".exporter.service", "data-service");
    properties.put("aws.exporter.service", "global-service");
    T exporter = createExporter(properties);
    try {
      assertThat(exporter.serviceName()).isEqualTo("global-service");
    } finally {
      shutdown(exporter);
    }
  }

  @ParameterizedTest
  @MethodSource("endpointConfigs")
  protected void testCreateExporterAwsRegionFromEndpoint(
      String globalEndpoint,
      String dataTypeEndpoint,
      String expectedEndpoint,
      String expectedRegion) {
    Map<String, String> properties = properties();
    properties.remove("aws.region");
    properties.put("otel.exporter.otlp.endpoint", globalEndpoint);
    if (dataTypeEndpoint != null) {
      properties.put("otel.exporter.otlp." + dataType + ".endpoint", dataTypeEndpoint);
    }
    T exporter = createExporter(properties);
    try {
      assertThat(exporter.endpoint).isEqualTo(String.format(expectedEndpoint, dataType));
      assertThat(exporter.awsRegion).isEqualTo(expectedRegion);
    } finally {
      shutdown(exporter);
    }
  }

  @Test
  protected void testCreateExporterInvalidEndpointOrTlsConfiguration() {
    Map<String, String> properties = properties();
    properties.put("otel.exporter.otlp.endpoint", "not-a-url");
    assertThatThrownBy(() -> createExporter(properties))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("valid URL");
    properties.remove("otel.exporter.otlp.endpoint");
    properties.put("otel.exporter.otlp." + dataType + ".client.key", "/unused/key.pem");
    assertThatThrownBy(() -> createExporter(properties))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("both client key and client certificate");
  }

  @ParameterizedTest
  @ValueSource(strings = {"otlp/sigv4", "otlp,otlp/sigv4"})
  protected void testCreateExporterNoMocks(String exporterNames) {
    Map<String, String> properties = properties();
    properties.put("otel.aws.application.signals.enabled", "false");
    properties.put("otel.traces.sampler", "always_on");
    properties.put("otel.propagators", "tracecontext");
    properties.put(
        "otel.java.enabled.resource.providers",
        "io.opentelemetry.sdk.autoconfigure.EnvironmentResourceProvider");
    for (String type : List.of("traces", "metrics", "logs")) {
      properties.put("otel." + type + ".exporter", type.equals(dataType) ? exporterNames : "none");
    }
    List<Object> exporters = new ArrayList<>();
    AutoConfiguredOpenTelemetrySdkBuilder builder =
        AutoConfiguredOpenTelemetrySdk.builder()
            .disableShutdownHook()
            .addPropertiesCustomizer(unused -> properties);
    addExporterCustomizer(builder, exporters::add);
    OpenTelemetrySdk sdk = builder.build().getOpenTelemetrySdk();
    try {
      if (exporterNames.equals("otlp/sigv4")) {
        assertThat(exporters).extracting(Object::getClass).containsExactly(exporterType);
      } else {
        assertThat(exporters)
            .extracting(Object::getClass)
            .containsExactlyInAnyOrder(upstreamExporterType, exporterType);
      }
    } finally {
      sdk.close();
    }
  }

  @Test
  protected void testMeterProviderRefInitializedWithNoop() throws Exception {
    assertThat(getMeterProviderRef().get()).isSameAs(MeterProvider.noop());
  }

  @Test
  protected void testAfterAutoConfigureUpdatesMeterProviderRef() throws Exception {
    try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().build()) {
      provider().afterAutoConfigure(sdk);
      assertThat(getMeterProviderRef().get()).isSameAs(sdk.getMeterProvider());
    }
  }

  @Test
  protected void testCreateExporterHttpSetsMeterProviderSupplier() {
    try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().build()) {
      provider().afterAutoConfigure(sdk);
      T exporter = createExporter(properties());
      try {
        assertThat(meterProviderSupplier().get()).isSameAs(sdk.getMeterProvider());
      } finally {
        shutdown(exporter);
      }
    }
  }

  @Test
  protected void testMeterProviderSupplierReturnsNoopBeforeAutoConfigure() {
    T exporter = createExporter(properties());
    try {
      assertThat(meterProviderSupplier().get()).isSameAs(MeterProvider.noop());
    } finally {
      shutdown(exporter);
    }
  }

  private static void assertImmutableMemoryMode(BaseOtlpAwsExporter exporter) {
    assertThat(exporter)
        .extracting("parentExporter")
        .extracting("marshaler")
        .extracting("memoryMode")
        .isEqualTo(MemoryMode.IMMUTABLE_DATA);
  }

  @SuppressWarnings("unchecked")
  private AtomicReference<MeterProvider> getMeterProviderRef() throws Exception {
    Field field = provider().getClass().getDeclaredField("meterProviderRef");
    field.setAccessible(true);
    return (AtomicReference<MeterProvider>) field.get(provider());
  }

  private static Stream<Arguments> endpointConfigs() {
    return Stream.of(
        Arguments.of(
            "https://global.us-west-2.amazonaws.com/prefix",
            null,
            "https://global.us-west-2.amazonaws.com/prefix/v1/%s",
            "us-west-2"),
        Arguments.of(
            "https://global.us-west-2.amazonaws.com/prefix/",
            null,
            "https://global.us-west-2.amazonaws.com/prefix/v1/%s",
            "us-west-2"),
        Arguments.of(
            "https://global.us-west-2.amazonaws.com/prefix",
            "https://signal.eu-west-1.amazonaws.com/custom",
            "https://signal.eu-west-1.amazonaws.com/custom",
            "eu-west-1"));
  }

  protected static Map<String, String> properties() {
    return new HashMap<>(
        Map.of("otel.exporter.otlp.protocol", "http/protobuf", "aws.region", "us-east-1"));
  }

  protected abstract String providerName();

  protected abstract AutoConfigureListener provider();

  protected abstract T createExporter(Map<String, String> properties);

  protected abstract void shutdown(T exporter);

  protected abstract void verifyDefaults(String endpoint);

  protected abstract void verifyConfiguration(
      String endpoint, Map<String, String> headers, boolean retryDisabled);

  protected abstract Supplier<MeterProvider> meterProviderSupplier();

  protected abstract void addExporterCustomizer(
      AutoConfiguredOpenTelemetrySdkBuilder builder, Consumer<Object> customizer);
}
