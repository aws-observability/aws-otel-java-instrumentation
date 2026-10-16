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
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

public abstract class AbstractOtlpAwsExporterProviderTest<T extends BaseOtlpAwsExporter> {
  private final String dataType;
  private final String defaultAwsService;
  private final Class<T> exporterType;
  private final Class<?> upstreamExporterType;
  protected final String endpoint;
  protected Consumer<String> setAwsService;
  protected Supplier<T> buildExporter;

  protected AbstractOtlpAwsExporterProviderTest(
      String dataType,
      String defaultAwsService,
      Class<T> exporterType,
      Class<?> upstreamExporterType) {
    this.dataType = dataType;
    this.defaultAwsService = defaultAwsService;
    this.exporterType = exporterType;
    this.upstreamExporterType = upstreamExporterType;
    this.endpoint = "http://localhost:4318/v1/" + dataType;
  }

  @Test
  protected void testOtlpSigV4ExporterShouldApplySigningServicePrecedence() {
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
        // Describing a configured exporter must not attempt to resolve credentials or sign data.
        assertThat(exporter.toString()).contains("awsRegion=us-east-1");
        assertThat(exporter.getCompression()).isEqualTo(CompressionMethod.NONE);
      } finally {
        shutdown(exporter);
      }
    }
  }

  @Test
  protected void testAwsExporterShouldPreserveServiceWhenInvalidServiceRejected() {
    for (String expected : List.of(defaultAwsService, "custom-service")) {
      if (expected.equals("custom-service")) {
        setAwsService.accept(expected);
      }
      assertThatThrownBy(() -> setAwsService.accept(null)).isInstanceOf(NullPointerException.class);
      for (String invalid : List.of("", " ", "\t")) {
        assertThatThrownBy(() -> setAwsService.accept(invalid))
            .isInstanceOf(IllegalArgumentException.class);
      }
      T exporter = buildExporter.get();
      try {
        assertThat(exporter.serviceName()).isEqualTo(expected);
      } finally {
        shutdown(exporter);
      }
    }
  }

  @Test
  protected void testOtlpSigV4ExporterShouldApplyCompressionPrecedence() {
    Map<String, String> properties = properties();
    properties.put("otel.exporter.otlp.compression", "gzip");
    properties.put("otel.exporter.otlp." + dataType + ".compression", "none");
    T exporter = createExporter(properties);
    try {
      assertThat(exporter.getCompression()).isEqualTo(CompressionMethod.NONE);
    } finally {
      shutdown(exporter);
    }
  }

  @Test
  protected void
      testOtlpSigV4ExporterShouldFailToInitializeWithInvalidEndpointOrTlsConfiguration() {
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

  @Test
  protected void testOtlpSigV4ExporterShouldLoadAlongsideOtlpExporter() {
    Map<String, String> properties = properties();
    properties.put("otel.aws.application.signals.enabled", "false");
    properties.put("otel.traces.sampler", "always_on");
    properties.put("otel.propagators", "tracecontext");
    properties.put(
        "otel.java.enabled.resource.providers",
        "io.opentelemetry.sdk.autoconfigure.EnvironmentResourceProvider");
    for (String type : List.of("traces", "metrics", "logs")) {
      properties.put(
          "otel." + type + ".exporter", type.equals(dataType) ? "otlp,otlp/sigv4" : "none");
    }
    List<Object> exporters = new ArrayList<>();
    AutoConfiguredOpenTelemetrySdkBuilder builder =
        AutoConfiguredOpenTelemetrySdk.builder()
            .disableShutdownHook()
            .addPropertiesCustomizer(unused -> properties);
    addExporterCustomizer(builder, exporters::add);
    OpenTelemetrySdk sdk = builder.build().getOpenTelemetrySdk();
    try {
      assertThat(exporters)
          .extracting(Object::getClass)
          .containsExactlyInAnyOrder(upstreamExporterType, exporterType);
    } finally {
      sdk.close();
    }
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
