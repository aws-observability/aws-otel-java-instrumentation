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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.logs.ConfigurableLogRecordExporterProvider;
import io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.BaseOtlpAwsExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.CompressionMethod;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.logs.OtlpAwsLogRecordExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.metrics.OtlpAwsMetricExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.traces.OtlpAwsSpanExporter;

class OtlpAwsExporterProviderTest {
  static Object createExporter(String signal, Map<String, String> properties) {
    ConfigProperties config = DefaultConfigProperties.createFromMap(properties);
    switch (signal) {
      case "traces":
        return ServiceLoader.load(ConfigurableSpanExporterProvider.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(provider -> provider.getName().equals("otlp/sigv4"))
            .findFirst()
            .orElseThrow()
            .createExporter(config);
      case "metrics":
        return ServiceLoader.load(ConfigurableMetricExporterProvider.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(provider -> provider.getName().equals("otlp/sigv4"))
            .findFirst()
            .orElseThrow()
            .createExporter(config);
      case "logs":
        return ServiceLoader.load(ConfigurableLogRecordExporterProvider.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(provider -> provider.getName().equals("otlp/sigv4"))
            .findFirst()
            .orElseThrow()
            .createExporter(config);
      default:
        throw new IllegalArgumentException(signal);
    }
  }

  static Map<String, String> properties() {
    return new HashMap<>(
        Map.of("otel.exporter.otlp.protocol", "http/protobuf", "aws.region", "us-east-1"));
  }

  static void shutdown(Object exporter) {
    if (exporter instanceof SpanExporter) {
      ((SpanExporter) exporter).shutdown();
    } else if (exporter instanceof MetricExporter) {
      ((MetricExporter) exporter).shutdown();
    } else {
      ((LogRecordExporter) exporter).shutdown();
    }
  }

  @ParameterizedTest
  @CsvSource({"traces,xray", "metrics,monitoring", "logs,logs"})
  void testOtlpSigV4ExporterShouldApplySigningServicePrecedence(
      String signal, String defaultService) {
    Map<String, String> properties = properties();
    for (String expected : List.of(defaultService, "osis", "custom-service")) {
      if (expected.equals("osis")) {
        properties.put("otel.exporter.otlp.sigv4.service", expected);
      } else if (expected.equals("custom-service")) {
        properties.put("otel.exporter.otlp." + signal + ".sigv4.service", expected);
      }
      BaseOtlpAwsExporter exporter = (BaseOtlpAwsExporter) createExporter(signal, properties);
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

  @ParameterizedTest
  @ValueSource(strings = {"traces", "metrics", "logs"})
  void testOtlpSigV4ExporterShouldApplyProtocolAndCompressionPrecedence(String signal) {
    Map<String, String> properties = properties();
    properties.put("otel.exporter.otlp.protocol", "grpc");
    properties.put("otel.exporter.otlp." + signal + ".protocol", "http/protobuf");
    properties.put("otel.exporter.otlp.compression", "gzip");
    properties.put("otel.exporter.otlp." + signal + ".compression", "none");
    BaseOtlpAwsExporter exporter = (BaseOtlpAwsExporter) createExporter(signal, properties);
    try {
      assertThat(exporter.getCompression()).isEqualTo(CompressionMethod.NONE);
    } finally {
      shutdown(exporter);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"traces", "metrics", "logs"})
  void testOtlpSigV4ExporterShouldFailToInitializeWithUnsupportedProtocolOrCompression(
      String signal) {
    Map<String, String> properties = properties();
    properties.put("otel.exporter.otlp." + signal + ".protocol", "grpc");
    assertThatThrownBy(() -> createExporter(signal, properties))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("http/protobuf");
    properties.put("otel.exporter.otlp." + signal + ".protocol", "http/protobuf");
    properties.put("otel.exporter.otlp." + signal + ".compression", "unsupported");
    assertThatThrownBy(() -> createExporter(signal, properties))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("compression");
  }

  @ParameterizedTest
  @ValueSource(strings = {"traces", "metrics", "logs"})
  void testOtlpSigV4ExporterShouldFailToInitializeWithConflictingAuthorizationHeaders(
      String signal) {
    Map<String, String> properties = properties();
    for (String key :
        List.of("otel.exporter.otlp.headers", "otel.exporter.otlp." + signal + ".headers")) {
      properties.put(key, "aUtHoRiZaTiOn=Bearer%20test-token");
      assertThatThrownBy(() -> createExporter(signal, properties))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("Authorization");
      properties.remove(key);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"traces", "metrics", "logs"})
  void testOtlpSigV4ExporterShouldFailToInitializeWithInvalidEndpointOrTlsConfiguration(
      String signal) {
    Map<String, String> properties = properties();
    properties.put("otel.exporter.otlp.endpoint", "not-a-url");
    assertThatThrownBy(() -> createExporter(signal, properties))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("valid URL");
    properties.remove("otel.exporter.otlp.endpoint");
    properties.put("otel.exporter.otlp." + signal + ".client.key", "/unused/key.pem");
    assertThatThrownBy(() -> createExporter(signal, properties))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("both client key and client certificate");
  }

  @Test
  void testOtlpSigV4MetricExporterShouldPreserveTemporalityAndAggregation() {
    Map<String, String> properties = properties();
    properties.put("otel.exporter.otlp.metrics.temporality.preference", "DELTA");
    properties.put(
        "otel.exporter.otlp.metrics.default.histogram.aggregation",
        "base2_exponential_bucket_histogram");
    MetricExporter exporter = (MetricExporter) createExporter("metrics", properties);
    try {
      assertThat(exporter.getAggregationTemporality(InstrumentType.COUNTER))
          .isEqualTo(AggregationTemporality.DELTA);
      assertThat(exporter.getDefaultAggregation(InstrumentType.HISTOGRAM).toString())
          .contains("Base2ExponentialHistogram");
    } finally {
      exporter.shutdown();
    }
  }

  @Test
  void testOtlpSigV4ExporterShouldLoadAlongsideOtlpExporter() {
    Map<String, String> properties = properties();
    properties.put("otel.aws.application.signals.enabled", "false");
    properties.put("otel.traces.sampler", "always_on");
    properties.put("otel.propagators", "tracecontext");
    properties.put(
        "otel.java.enabled.resource.providers",
        "io.opentelemetry.sdk.autoconfigure.EnvironmentResourceProvider");
    for (String signal : List.of("traces", "metrics", "logs")) {
      properties.put("otel." + signal + ".exporter", "otlp,otlp/sigv4");
    }
    List<Object> exporters = new ArrayList<>();
    OpenTelemetrySdk sdk =
        AutoConfiguredOpenTelemetrySdk.builder()
            .disableShutdownHook()
            .addPropertiesCustomizer(unused -> properties)
            .addSpanExporterCustomizer(
                (exporter, unused) -> {
                  exporters.add(exporter);
                  return exporter;
                })
            .addMetricExporterCustomizer(
                (exporter, unused) -> {
                  exporters.add(exporter);
                  return exporter;
                })
            .addLogRecordExporterCustomizer(
                (exporter, unused) -> {
                  exporters.add(exporter);
                  return exporter;
                })
            .build()
            .getOpenTelemetrySdk();
    try {
      assertThat(exporters)
          .extracting(Object::getClass)
          .containsExactlyInAnyOrder(
              OtlpHttpSpanExporter.class, OtlpAwsSpanExporter.class,
              OtlpHttpMetricExporter.class, OtlpAwsMetricExporter.class,
              OtlpHttpLogRecordExporter.class, OtlpAwsLogRecordExporter.class);
    } finally {
      sdk.close();
    }
  }
}
