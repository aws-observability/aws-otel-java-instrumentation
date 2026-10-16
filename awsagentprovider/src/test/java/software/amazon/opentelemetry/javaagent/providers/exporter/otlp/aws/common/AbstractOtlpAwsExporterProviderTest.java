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

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public abstract class AbstractOtlpAwsExporterProviderTest<T extends BaseOtlpAwsExporter> {
  private final String dataType;
  private final String defaultAwsService;
  private final Class<T> exporterType;
  private final Class<?> upstreamExporterType;

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

  @Test
  protected void testOtlpAwsExporterShouldApplySigningServicePrecedence() {
    Map<String, String> properties = properties();
    for (String expected : List.of(defaultAwsService, "custom-service", "osis")) {
      if (expected.equals("custom-service")) {
        properties.put("aws." + dataType + ".exporter.service", expected);
      } else if (expected.equals("osis")) {
        properties.put("aws.exporter.service", expected);
      }
      T exporter = createExporter(properties);
      try {
        assertThat(exporter.serviceName()).isEqualTo(expected);
      } finally {
        shutdown(exporter);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("endpointConfigs")
  protected void testOtlpAwsExporterShouldConfigureEndpointAndRegion(
      String globalEndpoint,
      String dataTypeEndpoint,
      String expectedEndpoint,
      String expectedRegion) {
    Map<String, String> properties = properties();
    if (globalEndpoint != null) {
      properties.put("otel.exporter.otlp.endpoint", globalEndpoint);
      properties.remove("aws.region");
    }
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  protected void testOtlpAwsExporterShouldApplyCompressionPrecedence(boolean useDataTypeSettings) {
    Map<String, String> properties = properties();
    properties.put("otel.exporter.otlp.compression", "gzip");
    if (useDataTypeSettings) {
      properties.put("otel.exporter.otlp." + dataType + ".compression", "none");
    }
    T exporter = createExporter(properties);
    try {
      assertThat(exporter.getCompression())
          .isEqualTo(useDataTypeSettings ? CompressionMethod.NONE : CompressionMethod.GZIP);
    } finally {
      shutdown(exporter);
    }
  }

  @Test
  protected void testOtlpAwsExporterShouldFailToInitializeWithInvalidEndpointOrTlsConfiguration() {
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
  protected void testOtlpAwsExporterShouldLoadSelectedExporters(String exporterNames) {
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

  private static Stream<Arguments> endpointConfigs() {
    return Stream.of(
        Arguments.of(null, null, "http://localhost:4318/v1/%s", "us-east-1"),
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

  protected abstract T createExporter(Map<String, String> properties);

  protected abstract void shutdown(T exporter);

  protected abstract void addExporterCustomizer(
      AutoConfiguredOpenTelemetrySdkBuilder builder, Consumer<Object> customizer);
}
