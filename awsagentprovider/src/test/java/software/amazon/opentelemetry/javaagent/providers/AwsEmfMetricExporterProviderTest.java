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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static software.amazon.opentelemetry.javaagent.providers.AwsApplicationSignalsCustomizerProvider.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClientBuilder;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.AwsCloudWatchEmfExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.ConsoleEmfExporter;

@ResourceLock(Resources.SYSTEM_OUT)
class AwsEmfMetricExporterProviderTest {
  private static final String CLOUDWATCH_HEADERS =
      "x-aws-log-group=test-group,x-aws-log-stream=test-stream,x-aws-metric-namespace=test-namespace";

  @ParameterizedTest
  @ValueSource(strings = {"awsemf", "otlp", "awsemf,otlp", "otlp,awsemf", "none"})
  void loadsOnlySelectedExportersThroughOpenTelemetry(String selectedExporters) {
    List<MetricExporter> exporters = new ArrayList<>();
    Map<String, String> config = lambdaConfig();
    config.put(OTEL_METRICS_EXPORTER, selectedExporters);

    try (OpenTelemetrySdk sdk = createSdk(config, exporters, InMemoryMetricExporter.create())) {
      List<Class<?>> expected = new ArrayList<>();
      if (selectedExporters.contains("awsemf")) {
        expected.add(ConsoleEmfExporter.class);
      }
      if (selectedExporters.contains("otlp")) {
        expected.add(OtlpGrpcMetricExporter.class);
      }
      assertThat(exporters)
          .extracting(Object::getClass)
          .containsExactlyInAnyOrderElementsOf(expected);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"awsemf", "awsemf,otlp", "otlp,awsemf"})
  void customizerPreservesSelectedExporters(String selectedExporters) {
    Map<String, String> config = lambdaConfig();
    config.put(OTEL_METRICS_EXPORTER, selectedExporters);
    config.put(APPLICATION_SIGNALS_ENABLED_CONFIG, "true");

    assertThat(
            new AwsApplicationSignalsCustomizerProvider()
                .customizeProperties(DefaultConfigProperties.createFromMap(config)))
        .doesNotContainKey(OTEL_METRICS_EXPORTER);
  }

  @ParameterizedTest
  @CsvSource({
    "aws.region, false",
    "aws.default.region, false",
    "aws.region, true",
    "aws.default.region, true"
  })
  void selectsCloudWatchWhenLogDestinationIsConfigured(String regionKey, boolean lambda) {
    Map<String, String> config = lambdaConfig();
    config.remove(AWS_REGION);
    config.put(regionKey, "us-west-2");
    config.put(OTEL_EXPORTER_OTLP_LOGS_HEADERS, CLOUDWATCH_HEADERS);
    if (!lambda) {
      config.remove(AWS_LAMBDA_FUNCTION_NAME_PROP_CONFIG);
    }
    List<MetricExporter> exporters = new ArrayList<>();

    try (LogCapture logs = new LogCapture();
        OpenTelemetrySdk sdk = createSdk(config, exporters, InMemoryMetricExporter.create())) {
      assertThat(exporters).hasSize(1);
      MetricExporter exporter = exporters.get(0);
      assertInstanceOf(AwsCloudWatchEmfExporter.class, exporter);
      assertEquals(
          AggregationTemporality.DELTA, exporter.getAggregationTemporality(InstrumentType.COUNTER));
      assertEquals(
          Aggregation.base2ExponentialBucketHistogram(),
          exporter.getDefaultAggregation(InstrumentType.HISTOGRAM));
      assertSelectionLog(
          logs,
          "Using the CloudWatch EMF metrics exporter; destination=CloudWatch Logs; authentication=AWS SDK SigV4.");
    }
  }

  @ParameterizedTest
  @CsvSource({"test-namespace, true", "another-namespace, false", "default, true"})
  void exportsConsoleEmfWithConfiguredNamespaceAndDimensions(
      String namespace, boolean addApplicationSignalsDimensions) throws Exception {
    Map<String, String> config = lambdaConfig();
    if (!namespace.equals("default")) {
      config.put(OTEL_EXPORTER_OTLP_LOGS_HEADERS, "x-aws-metric-namespace=" + namespace);
    }
    config.put(
        OTEL_METRICS_ADD_APPLICATION_SIGNALS_DIMENSIONS,
        Boolean.toString(addApplicationSignalsDimensions));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream original = System.out;
    try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        LogCapture logs = new LogCapture()) {
      System.setOut(stream);
      try (OpenTelemetrySdk sdk =
          createSdk(config, new ArrayList<>(), InMemoryMetricExporter.create())) {
        sdk.getMeter("test").counterBuilder("test_counter").build().add(7);
        assertTrue(sdk.getSdkMeterProvider().forceFlush().join(10, TimeUnit.SECONDS).isSuccess());
      }
      JsonNode record = new ObjectMapper().readTree(output.toString(StandardCharsets.UTF_8).trim());
      assertEquals(7, record.get("test_counter").asInt());
      assertEquals(namespace, record.at("/_aws/CloudWatchMetrics/0/Namespace").asText());
      assertEquals(addApplicationSignalsDimensions, record.has("Service"));
      assertEquals(addApplicationSignalsDimensions, record.has("Environment"));
      assertSelectionLog(
          logs,
          "Using the console EMF metrics exporter; destination=standard output; authentication=none because the exporter makes no network request.");
    } finally {
      System.setOut(original);
    }
  }

  @Test
  void exportsToBothEmfAndOtlpWhenBothAreSelected() throws Exception {
    Map<String, String> config = lambdaConfig();
    config.put(OTEL_METRICS_EXPORTER, "awsemf,otlp");
    InMemoryMetricExporter otlpExporter = InMemoryMetricExporter.create();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream original = System.out;
    try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(stream);
      try (OpenTelemetrySdk sdk = createSdk(config, new ArrayList<>(), otlpExporter)) {
        sdk.getMeter("test").counterBuilder("test_counter").build().add(7);
        assertTrue(sdk.getSdkMeterProvider().forceFlush().join(10, TimeUnit.SECONDS).isSuccess());
        assertThat(otlpExporter.getFinishedMetricItems())
            .anySatisfy(
                metric -> {
                  assertEquals("test_counter", metric.getName());
                  assertEquals(7, metric.getLongSumData().getPoints().iterator().next().getValue());
                });
      }
      JsonNode record = new ObjectMapper().readTree(output.toString(StandardCharsets.UTF_8).trim());
      assertEquals(7, record.get("test_counter").asInt());
    } finally {
      System.setOut(original);
    }
  }

  @Test
  void exportsCloudWatchEmfToConfiguredDestinationAndPrefersAwsRegion() throws Exception {
    Map<String, String> config = lambdaConfig();
    config.put(AWS_DEFAULT_REGION, "us-west-2");
    config.put(OTEL_EXPORTER_OTLP_LOGS_HEADERS, CLOUDWATCH_HEADERS);
    CloudWatchLogsClient client = mock(CloudWatchLogsClient.class);
    CloudWatchLogsClientBuilder builder = mock(CloudWatchLogsClientBuilder.class, RETURNS_SELF);
    when(builder.build()).thenReturn(client);

    try (MockedStatic<CloudWatchLogsClient> factory = mockStatic(CloudWatchLogsClient.class)) {
      factory.when(CloudWatchLogsClient::builder).thenReturn(builder);
      try (OpenTelemetrySdk sdk =
          createSdk(config, new ArrayList<>(), InMemoryMetricExporter.create())) {
        sdk.getMeter("test").counterBuilder("test_counter").build().add(7);
        assertTrue(sdk.getSdkMeterProvider().forceFlush().join(10, TimeUnit.SECONDS).isSuccess());
        verify(builder).region(Region.of("us-east-1"));
        ArgumentCaptor<PutLogEventsRequest> request =
            ArgumentCaptor.forClass(PutLogEventsRequest.class);
        verify(client).putLogEvents(request.capture());
        assertEquals("test-group", request.getValue().logGroupName());
        assertEquals("test-stream", request.getValue().logStreamName());
        JsonNode record =
            new ObjectMapper().readTree(request.getValue().logEvents().get(0).message());
        assertEquals(7, record.get("test_counter").asInt());
        assertEquals("test-namespace", record.at("/_aws/CloudWatchMetrics/0/Namespace").asText());
      }
    }
  }

  @Test
  void mixedExportersApplySigV4OnlyToOtlp() {
    Map<String, String> config = lambdaConfig();
    config.put(OTEL_METRICS_EXPORTER, "awsemf,otlp");
    config.put(OTEL_EXPORTER_OTLP_METRICS_PROTOCOL, "http/protobuf");
    config.put(
        OTEL_EXPORTER_OTLP_METRICS_ENDPOINT,
        "https://monitoring.us-east-1.amazonaws.com/v1/metrics");
    List<MetricExporter> exporters = new ArrayList<>();
    try (LogCapture logs =
            new LogCapture(
                Logger.getLogger(AwsApplicationSignalsCustomizerProvider.class.getName()));
        OpenTelemetrySdk sdk = createSdk(config, exporters, null)) {
      assertThat(exporters)
          .extracting(Object::getClass)
          .containsExactlyInAnyOrder(ConsoleEmfExporter.class, OtlpHttpMetricExporter.class);
      assertThat(logs.records)
          .filteredOn(record -> record.getMessage().startsWith("Using the "))
          .singleElement()
          .satisfies(
              record ->
                  assertEquals(
                      "Using the CloudWatch OTLP metrics exporter; destination=CloudWatch Metrics OTLP endpoint; authentication=ADOT SigV4.",
                      record.getMessage()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"region", "lambda-region", "headers", "log-group", "log-stream"})
  void invalidConfigurationWarnsWithoutFallingBackToOtlp(String missingSetting) {
    Map<String, String> config = lambdaConfig();
    if (!missingSetting.equals("lambda-region")) {
      config.remove(AWS_LAMBDA_FUNCTION_NAME_PROP_CONFIG);
    }
    config.put(OTEL_EXPORTER_OTLP_LOGS_HEADERS, CLOUDWATCH_HEADERS);
    switch (missingSetting) {
      case "region":
      case "lambda-region":
        config.remove(AWS_REGION);
        break;
      case "headers":
        config.remove(OTEL_EXPORTER_OTLP_LOGS_HEADERS);
        break;
      case "log-group":
        config.put(OTEL_EXPORTER_OTLP_LOGS_HEADERS, "x-aws-log-stream=test-stream");
        break;
      case "log-stream":
        config.put(OTEL_EXPORTER_OTLP_LOGS_HEADERS, "x-aws-log-group=test-group");
        break;
      default:
        throw new AssertionError(missingSetting);
    }
    List<MetricExporter> exporters = new ArrayList<>();
    try (LogCapture logs = new LogCapture();
        OpenTelemetrySdk sdk = createSdk(config, exporters, InMemoryMetricExporter.create())) {
      sdk.getMeter("test").counterBuilder("test_counter").build().add(7);
      assertTrue(sdk.getSdkMeterProvider().forceFlush().join(10, TimeUnit.SECONDS).isSuccess());
      assertThat(exporters)
          .hasSize(1)
          .noneMatch(
              exporter ->
                  exporter instanceof OtlpGrpcMetricExporter
                      || exporter instanceof OtlpHttpMetricExporter);
      assertThat(logs.records)
          .singleElement()
          .satisfies(
              record -> {
                assertEquals(Level.WARNING, record.getLevel());
                assertThat(record.getMessage()).contains("Improper EMF Exporter configuration");
              });
    }
  }

  private static Map<String, String> lambdaConfig() {
    Map<String, String> config = new HashMap<>();
    config.put(OTEL_METRICS_EXPORTER, "awsemf");
    config.put(OTEL_TRACES_EXPORTER, "none");
    config.put(OTEL_LOGS_EXPORTER, "none");
    config.put("otel.metric.export.interval", "600000");
    config.put(APPLICATION_SIGNALS_ENABLED_CONFIG, "false");
    config.put(AWS_REGION, "us-east-1");
    config.put(AWS_LAMBDA_FUNCTION_NAME_PROP_CONFIG, "test-function");
    return config;
  }

  private static OpenTelemetrySdk createSdk(
      Map<String, String> config,
      List<MetricExporter> exporters,
      InMemoryMetricExporter otlpExporter) {
    return AutoConfiguredOpenTelemetrySdk.builder()
        .disableShutdownHook()
        .addPropertiesSupplier(() -> config)
        .addMetricExporterCustomizer(
            (exporter, properties) -> {
              assertEquals(
                  config.get(OTEL_METRICS_EXPORTER), properties.getString(OTEL_METRICS_EXPORTER));
              exporters.add(exporter);
              if (otlpExporter != null
                  && (exporter instanceof OtlpGrpcMetricExporter
                      || exporter instanceof OtlpHttpMetricExporter)) {
                // Exercise upstream discovery without making an OTLP network request.
                return otlpExporter;
              }
              return exporter;
            })
        .build()
        .getOpenTelemetrySdk();
  }

  private static void assertSelectionLog(LogCapture logs, String expectedMessage) {
    assertThat(logs.records)
        .singleElement()
        .satisfies(
            record -> {
              assertEquals(Level.INFO, record.getLevel());
              assertEquals(expectedMessage, record.getMessage());
            });
  }

  private static final class LogCapture extends Handler implements AutoCloseable {
    private final Logger logger;
    private final List<LogRecord> records = new ArrayList<>();

    private LogCapture() {
      this(Logger.getLogger(AwsEmfMetricExporterProvider.class.getName()));
    }

    private LogCapture(Logger logger) {
      this.logger = logger;
      logger.addHandler(this);
    }

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
      logger.removeHandler(this);
    }
  }
}
