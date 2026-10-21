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

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.AwsCloudWatchEmfExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.ConsoleEmfExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.common.emitter.CloudWatchLogsClientEmitter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.common.emitter.ConsoleEmitter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.metrics.OtlpAwsMetricExporter;

class AwsEmfMetricExporterProviderTest {
  private static final String CLOUDWATCH_HEADERS =
      "x-aws-log-group=test-group,x-aws-log-stream=test-stream,x-aws-metric-namespace=test-namespace";

  private final AwsEmfMetricExporterProvider provider = new AwsEmfMetricExporterProvider();

  @Test
  void testGetName() {
    assertThat(provider.getName()).isEqualTo("awsemf");
  }

  @Test
  void testCreateExporterCloudWatchDefaults() {
    MetricExporter exporter = createExporter(properties());
    try {
      assertThat(exporter).isExactlyInstanceOf(AwsCloudWatchEmfExporter.class);
      assertThat(exporter).extracting("namespace").isEqualTo("test-namespace");
      assertThat(exporter).extracting("shouldAddApplicationSignalsDimensions").isEqualTo(true);
      assertThat(exporter.getAggregationTemporality(InstrumentType.COUNTER))
          .isEqualTo(AggregationTemporality.DELTA);
      assertThat(exporter.getDefaultAggregation(InstrumentType.HISTOGRAM))
          .isEqualTo(Aggregation.base2ExponentialBucketHistogram());
      assertThat(exporter)
          .extracting("emitter")
          .isExactlyInstanceOf(CloudWatchLogsClientEmitter.class)
          .extracting("logGroupName", "logStreamName", "awsRegion")
          .containsExactly("test-group", "test-stream", "us-east-1");
    } finally {
      exporter.shutdown();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "aws.region, false, true",
    "aws.default.region, false, false",
    "aws.region, true, false",
    "aws.default.region, true, true"
  })
  void testCreateExporterCloudWatchWithConfiguration(
      String regionKey, boolean lambda, boolean addApplicationSignalsDimensions) {
    Map<String, String> properties = properties();
    properties.remove("aws.region");
    properties.put(regionKey, "us-west-2");
    properties.put(
        "otel.exporter.otlp.logs.headers",
        "x-aws-log-group=another-group,x-aws-log-stream=another-stream,x-aws-metric-namespace=another-namespace");
    properties.put(
        "otel.metrics.add.application.signals.dimensions",
        Boolean.toString(addApplicationSignalsDimensions));
    if (lambda) {
      properties.put("aws.lambda.function.name", "test-function");
    }
    MetricExporter exporter = createExporter(properties);
    try {
      assertThat(exporter).isExactlyInstanceOf(AwsCloudWatchEmfExporter.class);
      assertThat(exporter).extracting("namespace").isEqualTo("another-namespace");
      assertThat(exporter)
          .extracting("shouldAddApplicationSignalsDimensions")
          .isEqualTo(addApplicationSignalsDimensions);
      assertThat(exporter)
          .extracting("emitter")
          .extracting("logGroupName", "logStreamName", "awsRegion")
          .containsExactly("another-group", "another-stream", "us-west-2");
    } finally {
      exporter.shutdown();
    }
  }

  @Test
  void testCreateExporterAwsRegionPrecedence() {
    Map<String, String> properties = properties();
    properties.put("aws.default.region", "us-west-2");
    MetricExporter exporter = createExporter(properties);
    try {
      assertThat(exporter).extracting("emitter").extracting("awsRegion").isEqualTo("us-east-1");
    } finally {
      exporter.shutdown();
    }
  }

  @Test
  void testCreateExporterConsoleDefaults() {
    Map<String, String> properties = properties();
    properties.remove("otel.exporter.otlp.logs.headers");
    properties.put("aws.lambda.function.name", "test-function");
    MetricExporter exporter = createExporter(properties);
    try {
      assertThat(exporter).isExactlyInstanceOf(ConsoleEmfExporter.class);
      assertThat(exporter).extracting("namespace").isEqualTo("default");
      assertThat(exporter).extracting("shouldAddApplicationSignalsDimensions").isEqualTo(true);
      assertThat(exporter).extracting("emitter").isExactlyInstanceOf(ConsoleEmitter.class);
    } finally {
      exporter.shutdown();
    }
  }

  @ParameterizedTest
  @CsvSource({"aws.region, true", "aws.default.region, false"})
  void testCreateExporterConsoleWithConfiguration(
      String regionKey, boolean addApplicationSignalsDimensions) {
    Map<String, String> properties = properties();
    properties.remove("aws.region");
    properties.put(regionKey, "us-west-2");
    properties.put("aws.lambda.function.name", "test-function");
    properties.put("otel.exporter.otlp.logs.headers", "x-aws-metric-namespace=another-namespace");
    properties.put(
        "otel.metrics.add.application.signals.dimensions",
        Boolean.toString(addApplicationSignalsDimensions));
    MetricExporter exporter = createExporter(properties);
    try {
      assertThat(exporter).isExactlyInstanceOf(ConsoleEmfExporter.class);
      assertThat(exporter).extracting("namespace").isEqualTo("another-namespace");
      assertThat(exporter)
          .extracting("shouldAddApplicationSignalsDimensions")
          .isEqualTo(addApplicationSignalsDimensions);
    } finally {
      exporter.shutdown();
    }
  }

  @ParameterizedTest
  @MethodSource("invalidConfigs")
  void testCreateExporterInvalidConfiguration(Map<String, String> properties) {
    MetricExporter exporter = createExporter(properties);
    try {
      assertThat(exporter).isNotInstanceOf(AwsCloudWatchEmfExporter.class);
      assertThat(exporter).isNotInstanceOf(ConsoleEmfExporter.class);
      assertThat(exporter).isNotInstanceOf(OtlpHttpMetricExporter.class);
      assertThat(exporter.getDefaultAggregation(InstrumentType.COUNTER))
          .isEqualTo(Aggregation.drop());
      assertThat(exporter.export(Collections.emptyList()).isSuccess()).isTrue();
      assertThat(exporter.flush().isSuccess()).isTrue();
    } finally {
      assertThat(exporter.shutdown().isSuccess()).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"awsemf", "awsemf,otlp", "otlp,awsemf", "otlp", "none"})
  void testCreateExporterNoMocks(String exporterNames) {
    List<MetricExporter> exporters = new ArrayList<>();
    try (OpenTelemetrySdk sdk = createSdk(exporterNames, exporters)) {
      List<Class<?>> expected = new ArrayList<>();
      for (String exporterName : exporterNames.split(",")) {
        switch (exporterName) {
          case "awsemf":
            expected.add(AwsCloudWatchEmfExporter.class);
            break;
          case "otlp":
            expected.add(OtlpHttpMetricExporter.class);
            break;
          default:
            break;
        }
      }
      assertThat(exporters)
          .extracting(Object::getClass)
          .containsExactlyInAnyOrderElementsOf(expected);
    }
  }

  @Test
  void testCreateExporterShouldCreateSeparateEmfAndSigV4Exporters() {
    List<MetricExporter> exporters = new ArrayList<>();
    try (OpenTelemetrySdk sdk = createSdk("awsemf,otlp/sigv4", exporters)) {
      assertThat(exporters).hasSize(2);
      assertThat(exporters)
          .extracting(Object::getClass)
          .containsExactlyInAnyOrder(AwsCloudWatchEmfExporter.class, OtlpAwsMetricExporter.class);
    }
  }

  private static OpenTelemetrySdk createSdk(String exporterNames, List<MetricExporter> exporters) {
    Map<String, String> properties = properties();
    properties.put("otel.aws.application.signals.enabled", "false");
    properties.put("otel.traces.sampler", "always_on");
    properties.put("otel.propagators", "tracecontext");
    properties.put(
        "otel.java.enabled.resource.providers",
        "io.opentelemetry.sdk.autoconfigure.EnvironmentResourceProvider");
    properties.put("otel.metrics.exporter", exporterNames);
    properties.put("otel.traces.exporter", "none");
    properties.put("otel.logs.exporter", "none");
    properties.put("otel.exporter.otlp.protocol", "http/protobuf");
    return AutoConfiguredOpenTelemetrySdk.builder()
        .disableShutdownHook()
        .addPropertiesCustomizer(unused -> properties)
        .addMetricExporterCustomizer(
            (exporter, unused) -> {
              exporters.add(exporter);
              return exporter;
            })
        .build()
        .getOpenTelemetrySdk();
  }

  private MetricExporter createExporter(Map<String, String> properties) {
    return provider.createExporter(DefaultConfigProperties.createFromMap(properties));
  }

  private static Map<String, String> properties() {
    return new HashMap<>(
        Map.of("aws.region", "us-east-1", "otel.exporter.otlp.logs.headers", CLOUDWATCH_HEADERS));
  }

  private static Stream<Arguments> invalidConfigs() {
    Map<String, String> missingRegion = properties();
    missingRegion.remove("aws.region");
    Map<String, String> missingLambdaRegion = new HashMap<>(missingRegion);
    missingLambdaRegion.put("aws.lambda.function.name", "test-function");
    Map<String, String> missingHeaders = properties();
    missingHeaders.remove("otel.exporter.otlp.logs.headers");
    Map<String, String> missingLogGroup = properties();
    missingLogGroup.put("otel.exporter.otlp.logs.headers", "x-aws-log-stream=test-stream");
    Map<String, String> missingLogStream = properties();
    missingLogStream.put("otel.exporter.otlp.logs.headers", "x-aws-log-group=test-group");
    Map<String, String> emptyRegion = properties();
    emptyRegion.put("aws.region", "");
    Map<String, String> emptyLogGroup = properties();
    emptyLogGroup.put(
        "otel.exporter.otlp.logs.headers", "x-aws-log-group=,x-aws-log-stream=test-stream");
    Map<String, String> emptyLogStream = properties();
    emptyLogStream.put(
        "otel.exporter.otlp.logs.headers", "x-aws-log-group=test-group,x-aws-log-stream=");
    Map<String, String> malformedHeaders = properties();
    malformedHeaders.put("otel.exporter.otlp.logs.headers", "x-aws-log-group");
    return Stream.of(
        Arguments.of(missingRegion),
        Arguments.of(missingLambdaRegion),
        Arguments.of(emptyRegion),
        Arguments.of(missingHeaders),
        Arguments.of(missingLogGroup),
        Arguments.of(missingLogStream),
        Arguments.of(emptyLogGroup),
        Arguments.of(emptyLogStream),
        Arguments.of(malformedHeaders));
  }
}
