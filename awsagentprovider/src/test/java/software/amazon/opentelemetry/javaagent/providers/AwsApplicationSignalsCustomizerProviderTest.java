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
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static software.amazon.opentelemetry.javaagent.providers.AwsApplicationSignalsCustomizerProvider.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.opentelemetry.contrib.awsxray.AwsXrayAdaptiveSamplingConfig;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.logs.CompactConsoleLogRecordExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.AwsCloudWatchEmfExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.ConsoleEmfExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.logs.OtlpAwsLogRecordExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.metrics.OtlpAwsMetricExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.traces.OtlpAwsSpanExporter;

class AwsApplicationSignalsCustomizerProviderTest {
  private AwsApplicationSignalsCustomizerProvider provider;
  private final LogRecordExporter defaultHttpLogsExporter = OtlpHttpLogRecordExporter.getDefault();
  private final SpanExporter defaultHttpSpanExporter = OtlpHttpSpanExporter.getDefault();
  private final MetricExporter defaultHttpMetricsExporter = OtlpHttpMetricExporter.getDefault();

  @BeforeEach
  void init() {
    this.provider = new AwsApplicationSignalsCustomizerProvider();
  }

  @ParameterizedTest
  @MethodSource("validSigv4LogsConfigProvider")
  void testShouldEnableSigV4LogsExporterIfConfigIsCorrect(Map<String, String> validSigv4Config) {
    customizeExporterTest(
        validSigv4Config,
        defaultHttpLogsExporter,
        this.provider::customizeLogsExporter,
        OtlpAwsLogRecordExporter.class);
  }

  @ParameterizedTest
  @MethodSource("invalidSigv4LogsConfigProvider")
  void testShouldNotUseSigv4LogsExporterIfConfigIsIncorrect(
      Map<String, String> invalidSigv4Config) {
    customizeExporterTest(
        invalidSigv4Config,
        defaultHttpLogsExporter,
        this.provider::customizeLogsExporter,
        OtlpHttpLogRecordExporter.class);
  }

  @Test
  void testShouldNotUseSigv4LogsExporterIfValidatorThrows() {
    try (MockedStatic<Pattern> ignored = mockStatic(Pattern.class)) {
      when(Pattern.compile(any())).thenThrow(PatternSyntaxException.class);
      customizeExporterTest(
          Map.of(
              OTEL_EXPORTER_OTLP_LOGS_ENDPOINT,
              "https://logs.us-east-1.amazonaws.com/v1/logs",
              OTEL_EXPORTER_OTLP_LOGS_HEADERS,
              "x-aws-log-group=test1,x-aws-log-stream=test2",
              OTEL_EXPORTER_OTLP_LOGS_PROTOCOL,
              "http/protobuf",
              OTEL_LOGS_EXPORTER,
              "otlp"),
          defaultHttpSpanExporter,
          this.provider::customizeSpanExporter,
          OtlpHttpSpanExporter.class);
    }
  }

  @Test
  void testLambdaShouldEnableCompactLogsExporterIfConfigIsCorrect() {
    Map<String, String> lambdaConfig =
        Map.of(
            OTEL_LOGS_EXPORTER, "console", AWS_LAMBDA_FUNCTION_NAME_PROP_CONFIG, "test-function");
    DefaultConfigProperties configProps = DefaultConfigProperties.createFromMap(lambdaConfig);
    this.provider.customizeProperties(configProps);

    // The customizer checks class simple name equals "SystemOutLogRecordExporter"
    LogRecordExporter result =
        this.provider.customizeLogsExporter(new SystemOutLogRecordExporter(), configProps);
    assertInstanceOf(CompactConsoleLogRecordExporter.class, result);
  }

  /** Stub that mimics the real SystemOutLogRecordExporter's class name. */
  private static class SystemOutLogRecordExporter implements LogRecordExporter {
    @Override
    public CompletableResultCode export(java.util.Collection<LogRecordData> logs) {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }

  @ParameterizedTest
  @MethodSource("invalidCompactLogsConfigProvider")
  void testShouldNotUseCompactLogsExporterIfConfigIsIncorrect(Map<String, String> invalidConfig) {
    customizeExporterTest(
        invalidConfig,
        defaultHttpLogsExporter,
        this.provider::customizeLogsExporter,
        OtlpHttpLogRecordExporter.class);
  }

  @ParameterizedTest
  @MethodSource("validSigv4TracesConfigProvider")
  void testShouldEnableSigV4SpanExporterIfConfigIsCorrect(Map<String, String> validSigv4Config) {
    customizeExporterTest(
        validSigv4Config,
        defaultHttpSpanExporter,
        this.provider::customizeSpanExporter,
        OtlpAwsSpanExporter.class);
  }

  @ParameterizedTest
  @MethodSource("invalidSigv4TracesConfigProvider")
  void testShouldNotUseSigv4SpanExporterIfConfigIsIncorrect(
      Map<String, String> invalidSigv4Config) {
    customizeExporterTest(
        invalidSigv4Config,
        defaultHttpSpanExporter,
        this.provider::customizeSpanExporter,
        OtlpHttpSpanExporter.class);
  }

  @Test
  void testShouldNotUseSigv4SpanExporterIfValidatorThrows() {
    try (MockedStatic<Pattern> ignored = mockStatic(Pattern.class)) {
      when(Pattern.compile(any())).thenThrow(PatternSyntaxException.class);
      customizeExporterTest(
          Map.of(
              OTEL_EXPORTER_OTLP_TRACES_ENDPOINT,
              "http://xray.us-east-1.amazonaws.com/v1/traces",
              OTEL_EXPORTER_OTLP_TRACES_PROTOCOL,
              "http/protobuf",
              OTEL_TRACES_EXPORTER,
              "otlp"),
          defaultHttpSpanExporter,
          this.provider::customizeSpanExporter,
          OtlpHttpSpanExporter.class);
    }
  }

  // This technically should never happen as the validator checks for the correct env variables. But
  // just to be safe.
  @Test
  void testShouldThrowIllegalStateExceptionIfIncorrectSpanExporter() {
    assertThrows(
        IllegalStateException.class,
        () ->
            customizeExporterTest(
                Map.of(
                    OTEL_EXPORTER_OTLP_TRACES_ENDPOINT,
                    "https://xray.us-east-1.amazonaws.com/v1/traces",
                    OTEL_EXPORTER_OTLP_TRACES_PROTOCOL,
                    "http/protobuf",
                    OTEL_TRACES_EXPORTER,
                    "otlp"),
                OtlpGrpcSpanExporter.getDefault(),
                this.provider::customizeSpanExporter,
                OtlpHttpSpanExporter.class));
  }

  // When SigV4 is enabled but the exporter is not OtlpHttpLogRecordExporter,
  // the customizer should pass through the exporter unchanged (no exception).
  @Test
  void testShouldPassThroughNonHttpLogsExporterWhenSigV4Enabled() {
    customizeExporterTest(
        Map.of(
            OTEL_EXPORTER_OTLP_LOGS_ENDPOINT,
            "https://logs.us-east-1.amazonaws.com/v1/logs",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
            "x-aws-log-group=test1,x-aws-log-stream=test2",
            OTEL_EXPORTER_OTLP_LOGS_PROTOCOL,
            "http/protobuf",
            OTEL_LOGS_EXPORTER,
            "otlp"),
        OtlpGrpcLogRecordExporter.getDefault(),
        this.provider::customizeLogsExporter,
        OtlpGrpcLogRecordExporter.class);
  }

  @Test
  void testShouldPreserveSignalSpecificTracesAuthorizationHeader() {
    customizeExporterTest(
        Map.of(
            OTEL_EXPORTER_OTLP_TRACES_ENDPOINT,
            "https://xray.us-east-1.amazonaws.com/v1/traces",
            OTEL_EXPORTER_OTLP_TRACES_PROTOCOL,
            "http/protobuf",
            OTEL_EXPORTER_OTLP_TRACES_HEADERS,
            "Authorization=Bearer%20traces-token",
            OTEL_TRACES_EXPORTER,
            "otlp"),
        defaultHttpSpanExporter,
        this.provider::customizeSpanExporter,
        OtlpHttpSpanExporter.class);
  }

  /**
   * A global Authorization header is not a statement about the X-Ray endpoint, so it must not
   * disable SigV4. Only OTEL_EXPORTER_OTLP_TRACES_HEADERS selects bearer authentication.
   */
  @Test
  void testShouldIgnoreGlobalAuthorizationHeaderForTraces() {
    customizeExporterTest(
        Map.of(
            OTEL_EXPORTER_OTLP_TRACES_ENDPOINT,
            "https://xray.us-east-1.amazonaws.com/v1/traces",
            OTEL_EXPORTER_OTLP_TRACES_PROTOCOL,
            "http/protobuf",
            OTEL_EXPORTER_OTLP_HEADERS,
            "authorization=Bearer%20global-token",
            OTEL_TRACES_EXPORTER,
            "otlp"),
        defaultHttpSpanExporter,
        this.provider::customizeSpanExporter,
        OtlpAwsSpanExporter.class);
  }

  @Test
  void testShouldPreserveLogsAuthorizationHeader() {
    customizeExporterTest(
        Map.of(
            OTEL_EXPORTER_OTLP_LOGS_ENDPOINT,
            "https://logs.us-east-1.amazonaws.com/v1/logs",
            OTEL_EXPORTER_OTLP_LOGS_PROTOCOL,
            "http/protobuf",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
            "Authorization=Bearer%20logs-token,x-aws-log-group=test1,x-aws-log-stream=test2",
            OTEL_LOGS_EXPORTER,
            "otlp"),
        defaultHttpLogsExporter,
        this.provider::customizeLogsExporter,
        OtlpHttpLogRecordExporter.class);
  }

  @Test
  void testShouldEnableSigV4MetricsExporterIfConfigIsCorrect() {
    assertMetricsExporterSelectionLog(
        () ->
            customizeExporterTest(
                Map.of(
                    OTEL_EXPORTER_OTLP_METRICS_ENDPOINT,
                    "https://monitoring.us-east-1.amazonaws.com/v1/metrics",
                    OTEL_EXPORTER_OTLP_METRICS_PROTOCOL,
                    "http/protobuf",
                    OTEL_METRICS_EXPORTER,
                    "otlp"),
                defaultHttpMetricsExporter,
                this.provider::customizeMetricExporter,
                OtlpAwsMetricExporter.class),
        "Using the CloudWatch OTLP metrics exporter; destination=CloudWatch Metrics OTLP endpoint; authentication=ADOT SigV4.");
  }

  @ParameterizedTest
  @MethodSource("invalidSigv4MetricsConfigProvider")
  void testShouldNotUseSigV4MetricsExporterForInvalidConfig(
      Map<String, String> invalidSigv4Config) {
    customizeExporterTest(
        invalidSigv4Config,
        defaultHttpMetricsExporter,
        this.provider::customizeMetricExporter,
        OtlpHttpMetricExporter.class);
  }

  /**
   * Endpoint matching is case-insensitive: endpointMatches lowercases the endpoint before applying
   * the pattern. This documents that behavior rather than assuming it.
   *
   * <p>Discovered while adding the invalid-endpoint provider above. Note that the logs suite lists
   * the equivalent uppercase endpoints as invalid, but those cases pass only because their config
   * omits OTEL_EXPORTER_OTLP_LOGS_HEADERS and the validator bails at the required-header check
   * instead of the endpoint check.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://MONITORING.us-east-1.amazonaws.com/v1/metrics",
        "https://monitoring.US-EAST-1.amazonaws.com/v1/metrics",
        "https://monitoring.us-east-1.amazonaws.com/V1/METRICS"
      })
  void testShouldEnableSigV4MetricsExporterForUppercaseEndpoint(String endpoint) {
    customizeExporterTest(
        Map.of(
            OTEL_EXPORTER_OTLP_METRICS_ENDPOINT,
            endpoint,
            OTEL_EXPORTER_OTLP_METRICS_PROTOCOL,
            "http/protobuf",
            OTEL_METRICS_EXPORTER,
            "otlp"),
        defaultHttpMetricsExporter,
        this.provider::customizeMetricExporter,
        OtlpAwsMetricExporter.class);
  }

  @Test
  void testShouldNotUseSigV4MetricsExporterIfValidatorThrows() {
    try (MockedStatic<Pattern> ignored = mockStatic(Pattern.class)) {
      when(Pattern.compile(any())).thenThrow(PatternSyntaxException.class);
      customizeExporterTest(
          Map.of(
              OTEL_EXPORTER_OTLP_METRICS_ENDPOINT,
              "https://monitoring.us-east-1.amazonaws.com/v1/metrics",
              OTEL_EXPORTER_OTLP_METRICS_PROTOCOL,
              "http/protobuf",
              OTEL_METRICS_EXPORTER,
              "otlp"),
          defaultHttpMetricsExporter,
          this.provider::customizeMetricExporter,
          OtlpHttpMetricExporter.class);
    }
  }

  @Test
  void testShouldPassThroughNonHttpMetricsExporterWhenSigV4Enabled() {
    customizeExporterTest(
        Map.of(
            OTEL_EXPORTER_OTLP_METRICS_ENDPOINT,
            "https://monitoring.us-east-1.amazonaws.com/v1/metrics",
            OTEL_EXPORTER_OTLP_METRICS_PROTOCOL,
            "http/protobuf",
            OTEL_METRICS_EXPORTER,
            "otlp"),
        OtlpGrpcMetricExporter.getDefault(),
        this.provider::customizeMetricExporter,
        OtlpGrpcMetricExporter.class);
  }

  @Test
  void testShouldPreserveSignalSpecificMetricsAuthorizationHeader() {
    String authorizationHeader = "Authorization=Bearer%20metrics-token";
    assertMetricsExporterSelectionLog(
        () ->
            customizeExporterTest(
                Map.of(
                    OTEL_EXPORTER_OTLP_METRICS_ENDPOINT,
                    "https://monitoring.us-east-1.amazonaws.com/v1/metrics",
                    OTEL_EXPORTER_OTLP_METRICS_PROTOCOL,
                    "http/protobuf",
                    OTEL_EXPORTER_OTLP_METRICS_HEADERS,
                    authorizationHeader,
                    OTEL_METRICS_EXPORTER,
                    "otlp"),
                defaultHttpMetricsExporter,
                this.provider::customizeMetricExporter,
                OtlpHttpMetricExporter.class),
        "Using the CloudWatch OTLP metrics exporter; destination=CloudWatch Metrics OTLP endpoint; authentication=signal-specific Authorization header; ADOT SigV4=disabled.",
        "metrics-token",
        authorizationHeader);
  }

  /**
   * A global Authorization header is not a statement about the CloudWatch metrics endpoint, so it
   * must not disable SigV4. Only OTEL_EXPORTER_OTLP_METRICS_HEADERS selects bearer authentication.
   *
   * <p>Accepted consequence: upstream still attaches the global header to the exporter, so this
   * configuration sends two Authorization values. Out of scope for this change.
   */
  @Test
  void testShouldIgnoreGlobalAuthorizationHeaderForMetrics() {
    customizeExporterTest(
        Map.of(
            OTEL_EXPORTER_OTLP_METRICS_ENDPOINT,
            "https://monitoring.us-east-1.amazonaws.com/v1/metrics",
            OTEL_EXPORTER_OTLP_METRICS_PROTOCOL,
            "http/protobuf",
            OTEL_EXPORTER_OTLP_HEADERS,
            "authorization=Bearer%20global-token",
            OTEL_METRICS_EXPORTER,
            "otlp"),
        defaultHttpMetricsExporter,
        this.provider::customizeMetricExporter,
        OtlpAwsMetricExporter.class);
  }

  /**
   * A global Authorization header is not a statement about the CloudWatch Logs endpoint, so it must
   * not disable SigV4. Only OTEL_EXPORTER_OTLP_LOGS_HEADERS selects bearer authentication. The
   * required log group and stream headers stay in the signal-specific variable.
   */
  @Test
  void testShouldIgnoreGlobalAuthorizationHeaderForLogs() {
    customizeExporterTest(
        Map.of(
            OTEL_EXPORTER_OTLP_LOGS_ENDPOINT,
            "https://logs.us-east-1.amazonaws.com/v1/logs",
            OTEL_EXPORTER_OTLP_LOGS_PROTOCOL,
            "http/protobuf",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
            "x-aws-log-group=test1,x-aws-log-stream=test2",
            OTEL_EXPORTER_OTLP_HEADERS,
            "authorization=Bearer%20global-token",
            OTEL_LOGS_EXPORTER,
            "otlp"),
        defaultHttpLogsExporter,
        this.provider::customizeLogsExporter,
        OtlpAwsLogRecordExporter.class);
  }

  /**
   * Signal-specific headers without an Authorization entry still get SigV4, even when a global
   * Authorization exists.
   */
  @Test
  void testSignalSpecificMetricsHeadersWithoutAuthorizationStillUseSigV4() {
    customizeExporterTest(
        Map.of(
            OTEL_EXPORTER_OTLP_METRICS_ENDPOINT,
            "https://monitoring.us-east-1.amazonaws.com/v1/metrics",
            OTEL_EXPORTER_OTLP_METRICS_PROTOCOL,
            "http/protobuf",
            OTEL_EXPORTER_OTLP_METRICS_HEADERS,
            "x-test=value",
            OTEL_EXPORTER_OTLP_HEADERS,
            "Authorization=Bearer%20global-token",
            OTEL_METRICS_EXPORTER,
            "otlp"),
        defaultHttpMetricsExporter,
        this.provider::customizeMetricExporter,
        OtlpAwsMetricExporter.class);
  }

  @Test
  void testApplicationSignalsPreservesExplicitOtlpMetricsExporter() {
    Map<String, String> overrides =
        this.provider.customizeProperties(
            DefaultConfigProperties.createFromMap(
                Map.of(
                    APPLICATION_SIGNALS_ENABLED_CONFIG,
                    "true",
                    OTEL_METRICS_EXPORTER,
                    "otlp",
                    OTEL_EXPORTER_OTLP_METRICS_ENDPOINT,
                    "https://monitoring.us-east-1.amazonaws.com/v1/metrics")));

    assertFalse(overrides.containsKey(OTEL_METRICS_EXPORTER));
  }

  @Test
  void testApplicationSignalsWarnsWhenCloudWatchMetricsExporterDefaultsToNone() {
    Logger customizerLogger =
        Logger.getLogger(AwsApplicationSignalsCustomizerProvider.class.getName());
    List<LogRecord> logRecords = new ArrayList<>();
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logRecords.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    customizerLogger.addHandler(handler);
    try {
      Map<String, String> overrides =
          this.provider.customizeProperties(
              DefaultConfigProperties.createFromMap(
                  Map.of(
                      APPLICATION_SIGNALS_ENABLED_CONFIG,
                      "true",
                      OTEL_EXPORTER_OTLP_METRICS_ENDPOINT,
                      "https://monitoring.us-east-1.amazonaws.com/v1/metrics")));

      assertEquals("none", overrides.get(OTEL_METRICS_EXPORTER));
      assertTrue(
          logRecords.stream()
              .anyMatch(
                  record ->
                      record
                          .getMessage()
                          .contains("Set OTEL_METRICS_EXPORTER=otlp to enable collector-less")));
    } finally {
      customizerLogger.removeHandler(handler);
    }
  }

  @Test
  void testEnableApplicationSignalsSpanExporter() {
    customizeExporterTest(
        Map.of(
            APPLICATION_SIGNALS_ENABLED_CONFIG,
            "true",
            OTEL_EXPORTER_OTLP_TRACES_ENDPOINT,
            "http://localhost:4318/v1/traces",
            OTEL_EXPORTER_OTLP_TRACES_PROTOCOL,
            "http/protobuf",
            OTEL_TRACES_EXPORTER,
            "otlp"),
        defaultHttpSpanExporter,
        this.provider::customizeSpanExporter,
        AwsMetricAttributesSpanExporter.class);
  }

  @Test
  void testSigv4ShouldNotDisableApplicationSignalsSpanExporter() {
    customizeExporterTest(
        Map.of(
            APPLICATION_SIGNALS_ENABLED_CONFIG,
            "true",
            OTEL_EXPORTER_OTLP_TRACES_ENDPOINT,
            "https://xray.us-east-1.amazonaws.com/v1/traces",
            OTEL_EXPORTER_OTLP_TRACES_PROTOCOL,
            "http/protobuf",
            OTEL_TRACES_EXPORTER,
            "otlp"),
        defaultHttpSpanExporter,
        this.provider::customizeSpanExporter,
        AwsMetricAttributesSpanExporter.class);
  }

  @ParameterizedTest
  @MethodSource("validCloudWatchEmfConfigProvider")
  void testShouldEnableCloudWatchEmfExporterIfConfigIsCorrect(Map<String, String> validEmfConfig) {
    DefaultConfigProperties configProps = DefaultConfigProperties.createFromMap(validEmfConfig);
    this.provider.customizeProperties(configProps);

    assertMetricsExporterSelectionLog(
        () ->
            customizeExporterTest(
                validEmfConfig,
                defaultHttpMetricsExporter,
                this.provider::customizeMetricExporter,
                AwsCloudWatchEmfExporter.class),
        "Using the CloudWatch EMF metrics exporter; destination=CloudWatch Logs; authentication=AWS SDK SigV4.");
  }

  @ParameterizedTest
  @MethodSource("validCloudWatchEmfConfigProvider")
  void testLambdaShouldEnableCloudWatchEmfExporterIfConfigIsCorrect(
      Map<String, String> validEmfConfig) {
    Map<String, String> lambdaCloudWatchEmfConfig = new HashMap<>(validEmfConfig);
    lambdaCloudWatchEmfConfig.put(AWS_LAMBDA_FUNCTION_NAME_PROP_CONFIG, "test-function");
    DefaultConfigProperties configProps =
        DefaultConfigProperties.createFromMap(lambdaCloudWatchEmfConfig);
    this.provider.customizeProperties(configProps);

    customizeExporterTest(
        lambdaCloudWatchEmfConfig,
        defaultHttpMetricsExporter,
        this.provider::customizeMetricExporter,
        AwsCloudWatchEmfExporter.class);
  }

  @ParameterizedTest
  @MethodSource("validConsoleEmfConfigProvider")
  void testLambdaShouldEnableConsoleEmfExporterIfConfigIsCorrect(
      Map<String, String> lambdaConsoleEmfConfig) {
    DefaultConfigProperties configProps =
        DefaultConfigProperties.createFromMap(lambdaConsoleEmfConfig);
    this.provider.customizeProperties(configProps);

    assertMetricsExporterSelectionLog(
        () ->
            customizeExporterTest(
                lambdaConsoleEmfConfig,
                defaultHttpMetricsExporter,
                this.provider::customizeMetricExporter,
                ConsoleEmfExporter.class),
        "Using the console EMF metrics exporter; destination=standard output; authentication=none because the exporter makes no network request.");
  }

  @ParameterizedTest
  @MethodSource("invalidCloudWatchEmfConfigProvider")
  void testShouldNotUseCloudWatchEmfExporterIfConfigIsIncorrect(
      Map<String, String> invalidEmfConfig) {
    DefaultConfigProperties configProps = DefaultConfigProperties.createFromMap(invalidEmfConfig);
    this.provider.customizeProperties(configProps);

    customizeExporterTest(
        invalidEmfConfig,
        defaultHttpMetricsExporter,
        this.provider::customizeMetricExporter,
        OtlpHttpMetricExporter.class);
  }

  @ParameterizedTest
  @MethodSource("invalidConsoleEmfConfigProvider")
  void testShouldNotUseConsoleEmfExporterIfConfigIsIncorrect(
      Map<String, String> invalidConsoleEmfConfig) {
    DefaultConfigProperties configProps =
        DefaultConfigProperties.createFromMap(invalidConsoleEmfConfig);
    this.provider.customizeProperties(configProps);

    customizeExporterTest(
        invalidConsoleEmfConfig,
        defaultHttpMetricsExporter,
        this.provider::customizeMetricExporter,
        OtlpHttpMetricExporter.class);
  }

  @ParameterizedTest
  @MethodSource("invalidLambdaCloudWatchEmfConfigProvider")
  void testLambdaShouldNotUseCloudWatchEmfExporterIfConfigIsIncorrect(
      Map<String, String> invalidEmfConfig) {
    Map<String, String> lambdaCloudWatchEmfConfig = new HashMap<>(invalidEmfConfig);
    lambdaCloudWatchEmfConfig.put(AWS_LAMBDA_FUNCTION_NAME_PROP_CONFIG, "test-function");

    DefaultConfigProperties configProps =
        DefaultConfigProperties.createFromMap(lambdaCloudWatchEmfConfig);
    this.provider.customizeProperties(configProps);

    customizeExporterTest(
        lambdaCloudWatchEmfConfig,
        defaultHttpMetricsExporter,
        this.provider::customizeMetricExporter,
        OtlpHttpMetricExporter.class);
  }

  @Test
  void testApplicationSignalsDimensionsEnabled() {
    ConfigProperties props =
        DefaultConfigProperties.createFromMap(
            Map.of(OTEL_METRICS_ADD_APPLICATION_SIGNALS_DIMENSIONS, "true"));
    assertTrue(
        AwsApplicationSignalsCustomizerProvider.shouldAddApplicationSignalsDimensionsEnabled(
            props));
  }

  @Test
  void testApplicationSignalsDimensionsDisabled() {
    ConfigProperties props =
        DefaultConfigProperties.createFromMap(
            Map.of(OTEL_METRICS_ADD_APPLICATION_SIGNALS_DIMENSIONS, "false"));
    assertFalse(
        AwsApplicationSignalsCustomizerProvider.shouldAddApplicationSignalsDimensionsEnabled(
            props));
  }

  @Test
  void testApplicationSignalsDimensionsDefaultsToTrue() {
    ConfigProperties props = DefaultConfigProperties.createFromMap(Map.of());
    assertTrue(
        AwsApplicationSignalsCustomizerProvider.shouldAddApplicationSignalsDimensionsEnabled(
            props));
  }

  @Test
  void setAdaptiveSamplingConfigFromString_validConfig() throws JsonProcessingException {
    assertThat(AwsApplicationSignalsCustomizerProvider.parseConfigString("version: 1").getVersion())
        .isEqualTo(1);
  }

  @Test
  void setAdaptiveSamplingConfigFromString_nullConfig() {
    assertThatNoException()
        .isThrownBy(() -> AwsApplicationSignalsCustomizerProvider.parseConfigString(null));
  }

  @Test
  void setAdaptiveSamplingConfigFromString_missingVersion() {
    assertThatException()
        .isThrownBy(() -> AwsApplicationSignalsCustomizerProvider.parseConfigString(""));
  }

  @Test
  void setAdaptiveSamplingConfigFromString_unsupportedVersion() {
    assertThatException()
        .isThrownBy(
            () -> AwsApplicationSignalsCustomizerProvider.parseConfigString("{version: 5000.1}"));
  }

  @Test
  void setAdaptiveSamplingConfigFromString_invalidYaml() {
    assertThatException()
        .isThrownBy(
            () ->
                AwsApplicationSignalsCustomizerProvider.parseConfigString(
                    "{version: 1, invalid: yaml: structure}"));
  }

  @Test
  void setAdaptiveSamplingConfigFromFile_validYaml()
      throws JsonProcessingException, URISyntaxException {
    // Get the resource file path
    URL resourceUrl =
        getClass().getClassLoader().getResource("adaptive-sampling-config-valid.yaml");
    assertThat(resourceUrl).isNotNull();

    // Get the absolute file path
    File configFile = new File(resourceUrl.toURI());
    String absolutePath = configFile.getAbsolutePath();

    // Parse the config using the file path
    AwsXrayAdaptiveSamplingConfig config =
        AwsApplicationSignalsCustomizerProvider.parseConfigString(absolutePath);

    // Assert the configuration was parsed correctly
    assertThat(config).isNotNull();
    assertThat(config.getVersion()).isEqualTo(1);
    assertThat(config.getAnomalyCaptureLimit().getAnomalyTracesPerSecond()).isEqualTo(10);
  }

  @Test
  void setAdaptiveSamplingConfigFromFile_invalidYaml() throws URISyntaxException {
    // Get the resource file path
    URL resourceUrl =
        getClass().getClassLoader().getResource("adaptive-sampling-config-invalid.yaml");
    assertThat(resourceUrl).isNotNull();

    // Get the absolute file path
    File configFile = new File(resourceUrl.toURI());
    String absolutePath = configFile.getAbsolutePath();

    // Parse the config using the file path
    assertThatException()
        .isThrownBy(() -> AwsApplicationSignalsCustomizerProvider.parseConfigString(absolutePath));
  }

  private static void assertMetricsExporterSelectionLog(
      Runnable action, String expectedMessage, String... sensitiveValues) {
    Logger customizerLogger =
        Logger.getLogger(AwsApplicationSignalsCustomizerProvider.class.getName());
    List<LogRecord> logRecords = new ArrayList<>();
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logRecords.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    customizerLogger.addHandler(handler);
    try {
      action.run();

      List<LogRecord> selectionLogs = new ArrayList<>();
      for (LogRecord record : logRecords) {
        if (isMetricsExporterSelectionLog(record.getMessage())) {
          selectionLogs.add(record);
        }
      }

      assertEquals(1, selectionLogs.size(), "expected exactly one metrics exporter selection log");
      LogRecord selectionLog = selectionLogs.get(0);
      assertEquals(Level.INFO, selectionLog.getLevel());
      assertEquals(expectedMessage, selectionLog.getMessage());

      for (String sensitiveValue : sensitiveValues) {
        assertTrue(
            logRecords.stream()
                .map(LogRecord::getMessage)
                .noneMatch(message -> message != null && message.contains(sensitiveValue)),
            () -> "log output contained sensitive value: " + sensitiveValue);
      }
    } finally {
      customizerLogger.removeHandler(handler);
    }
  }

  private static boolean isMetricsExporterSelectionLog(String message) {
    return message != null
        && message.startsWith("Using the ")
        && message.contains(" metrics exporter; destination=");
  }

  private static <Exporter> void customizeExporterTest(
      Map<String, String> config,
      Exporter defaultExporter,
      BiFunction<Exporter, ConfigProperties, Exporter> executor,
      Class<?> expectedExporterType) {

    DefaultConfigProperties configProps = DefaultConfigProperties.createFromMap(config);
    Exporter result = executor.apply(defaultExporter, configProps);
    assertEquals(expectedExporterType, result.getClass());
  }

  static Stream<Arguments> validSigv4TracesConfigProvider() {
    List<Map<String, String>> args = new ArrayList<>();
    String[] tracesGoodEndpoints = {
      "https://xray.us-east-1.amazonaws.com/v1/traces",
      "https://XRAY.US-EAST-1.AMAZONAWS.COM/V1/TRACES",
      "https://xray.us-east-1.amazonaws.com/v1/traces",
      "https://XRAY.US-EAST-1.amazonaws.com/v1/traces",
      "https://xray.US-EAST-1.AMAZONAWS.com/v1/traces",
      "https://Xray.Us-East-1.amazonaws.com/v1/traces",
      "https://xRAY.us-EAST-1.amazonaws.com/v1/traces",
      "https://XRAY.us-EAST-1.AMAZONAWS.com/v1/TRACES",
      "https://xray.US-EAST-1.amazonaws.com/V1/Traces",
      "https://xray.us-east-1.AMAZONAWS.COM/v1/traces",
      "https://XrAy.Us-EaSt-1.AmAzOnAwS.cOm/V1/TrAcEs",
      "https://xray.US-EAST-1.amazonaws.com/v1/traces",
      "https://xray.us-east-1.amazonaws.com/V1/TRACES",
      "https://XRAY.US-EAST-1.AMAZONAWS.COM/v1/traces",
      "https://xray.us-east-1.AMAZONAWS.COM/V1/traces"
    };

    for (String endpoint : tracesGoodEndpoints) {
      Map<String, String> badEndpoint =
          Map.of(
              OTEL_EXPORTER_OTLP_TRACES_ENDPOINT, endpoint,
              OTEL_EXPORTER_OTLP_TRACES_PROTOCOL, "http/protobuf",
              OTEL_TRACES_EXPORTER, "otlp");

      args.add(badEndpoint);
    }

    return args.stream().map(Arguments::of);
  }

  static Stream<Arguments> invalidSigv4TracesConfigProvider() {
    List<Map<String, String>> args = new ArrayList<>();
    String[] tracesBadEndpoints = {
      "http://localhost:4318/v1/traces",
      "http://xray.us-east-1.amazonaws.com/v1/traces",
      "ftp://xray.us-east-1.amazonaws.com/v1/traces",
      "https://ray.us-east-1.amazonaws.com/v1/traces",
      "https://xra.us-east-1.amazonaws.com/v1/traces",
      "https://x-ray.us-east-1.amazonaws.com/v1/traces",
      "https://xray.amazonaws.com/v1/traces",
      "https://xray.us-east-1.amazon.com/v1/traces",
      "https://xray.us-east-1.aws.com/v1/traces",
      "https://xray.us_east_1.amazonaws.com/v1/traces",
      "https://xray.us.east.1.amazonaws.com/v1/traces",
      "https://xray..amazonaws.com/v1/traces",
      "https://xray.us-east-1.amazonaws.com/traces",
      "https://xray.us-east-1.amazonaws.com/v2/traces",
      "https://xray.us-east-1.amazonaws.com/v1/trace",
      "https://xray.us-east-1.amazonaws.com/v1/traces/",
      "https://xray.us-east-1.amazonaws.com//v1/traces",
      "https://xray.us-east-1.amazonaws.com/v1//traces",
      "https://xray.us-east-1.amazonaws.com/v1/traces?param=value",
      "https://xray.us-east-1.amazonaws.com/v1/traces#fragment",
      "https://xray.us-east-1.amazonaws.com:443/v1/traces",
      "https:/xray.us-east-1.amazonaws.com/v1/traces",
      "https:://xray.us-east-1.amazonaws.com/v1/traces",
    };

    Map<String, String> invalidProtocol =
        Map.of(
            OTEL_EXPORTER_OTLP_TRACES_ENDPOINT, "https://xray.us-east-1.amazonaws.com/v1/traces",
            OTEL_EXPORTER_OTLP_TRACES_PROTOCOL, "http/json",
            OTEL_TRACES_EXPORTER, "otlp");

    Map<String, String> consoleExporter =
        Map.of(
            OTEL_EXPORTER_OTLP_TRACES_ENDPOINT, "https://xray.us-east-1.amazonaws.com/v1/traces",
            OTEL_EXPORTER_OTLP_TRACES_PROTOCOL, "http/protobuf",
            OTEL_TRACES_EXPORTER, "console");

    for (String endpoint : tracesBadEndpoints) {
      Map<String, String> badEndpoint =
          Map.of(
              OTEL_EXPORTER_OTLP_TRACES_ENDPOINT, endpoint,
              OTEL_EXPORTER_OTLP_TRACES_PROTOCOL, "http/protobuf",
              OTEL_TRACES_EXPORTER, "otlp");

      args.add(badEndpoint);
    }

    args.add(consoleExporter);
    args.add(invalidProtocol);

    return args.stream().map(Arguments::of);
  }

  static Stream<Arguments> invalidSigv4MetricsConfigProvider() {
    List<Map<String, String>> args = new ArrayList<>();
    String[] metricsBadEndpoints = {
      "http://localhost:4318/v1/metrics",
      "http://monitoring.us-east-1.amazonaws.com/v1/metrics",
      "ftp://monitoring.us-east-1.amazonaws.com/v1/metrics",
      "https://monitor.us-east-1.amazonaws.com/v1/metrics",
      "https://monitoring-.us-east-1.amazonaws.com/v1/metrics",
      "https://cloudwatch.us-east-1.amazonaws.com/v1/metrics",
      "https://metrics.us-east-1.amazonaws.com/v1/metrics",
      "https://monitoring.amazonaws.com/v1/metrics",
      "https://monitoring.us-east-1.amazon.com/v1/metrics",
      "https://monitoring.us-east-1.aws.com/v1/metrics",
      "https://monitoring.us_east_1.amazonaws.com/v1/metrics",
      "https://monitoring.us.east.1.amazonaws.com/v1/metrics",
      "https://monitoring..amazonaws.com/v1/metrics",
      "https://monitoring.us-east-1.amazonaws.com/metrics",
      "https://monitoring.us-east-1.amazonaws.com/v2/metrics",
      "https://monitoring.us-east-1.amazonaws.com/v1/metric",
      "https://monitoring.us-east-1.amazonaws.com/v1/metrics/",
      "https://monitoring.us-east-1.amazonaws.com//v1/metrics",
      "https://monitoring.us-east-1.amazonaws.com/v1//metrics",
      "https://monitoring.us-east-1.amazonaws.com/v1/metrics?param=value",
      "https://monitoring.us-east-1.amazonaws.com/v1/metrics#fragment",
      "https://monitoring.us-east-1.amazonaws.com:443/v1/metrics",
      "https:/monitoring.us-east-1.amazonaws.com/v1/metrics",
      "https:://monitoring.us-east-1.amazonaws.com/v1/metrics",
      "https://monitoring.us-east-1.amazonaws.com/v1/cloudwatchmetrics",
      "https://monitoring.us-east-1.amazonaws.com/v1/cwmetrics",
      // NOTE: uppercase variants are deliberately absent. endpointMatches lowercases the endpoint
      // before matching, so they are VALID. See
      // testShouldEnableSigV4MetricsExporterForUppercaseEndpoint.
    };

    Map<String, String> invalidProtocol =
        Map.of(
            OTEL_EXPORTER_OTLP_METRICS_ENDPOINT,
                "https://monitoring.us-east-1.amazonaws.com/v1/metrics",
            OTEL_EXPORTER_OTLP_METRICS_PROTOCOL, "grpc",
            OTEL_METRICS_EXPORTER, "otlp");

    Map<String, String> consoleExporter =
        Map.of(
            OTEL_EXPORTER_OTLP_METRICS_ENDPOINT,
                "https://monitoring.us-east-1.amazonaws.com/v1/metrics",
            OTEL_EXPORTER_OTLP_METRICS_PROTOCOL, "http/protobuf",
            OTEL_METRICS_EXPORTER, "console");

    for (String endpoint : metricsBadEndpoints) {
      Map<String, String> badEndpoint =
          Map.of(
              OTEL_EXPORTER_OTLP_METRICS_ENDPOINT, endpoint,
              OTEL_EXPORTER_OTLP_METRICS_PROTOCOL, "http/protobuf",
              OTEL_METRICS_EXPORTER, "otlp");

      args.add(badEndpoint);
    }

    args.add(consoleExporter);
    args.add(invalidProtocol);

    return args.stream().map(Arguments::of);
  }

  static Stream<Arguments> validSigv4LogsConfigProvider() {
    List<Map<String, String>> args = new ArrayList<>();
    String[] logsGoodEndpoints = {
      "https://logs.us-east-1.amazonaws.com/v1/logs",
      "https://LOGS.US-EAST-1.AMAZONAWS.COM/V1/LOGS",
      "https://logs.us-east-1.amazonaws.com/v1/logs",
      "https://LOGS.US-EAST-1.amazonaws.com/v1/logs",
      "https://logs.US-EAST-1.AMAZONAWS.com/v1/logs",
      "https://Logs.Us-East-1.amazonaws.com/v1/logs",
      "https://lOGS.us-EAST-1.amazonaws.com/v1/logs",
      "https://LOGS.us-EAST-1.AMAZONAWS.com/v1/LOGS",
      "https://logs.US-EAST-1.amazonaws.com/V1/Logs",
      "https://logs.us-east-1.AMAZONAWS.COM/v1/logs",
      "https://LoGs.Us-EaSt-1.AmAzOnAwS.cOm/V1/LoGs",
      "https://logs.US-EAST-1.amazonaws.com/v1/logs",
      "https://logs.us-east-1.amazonaws.com/V1/LOGS",
      "https://LOGS.US-EAST-1.AMAZONAWS.COM/v1/logs",
      "https://logs.us-east-1.AMAZONAWS.COM/V1/logs"
    };

    for (String endpoint : logsGoodEndpoints) {
      Map<String, String> badEndpoint =
          Map.of(
              OTEL_EXPORTER_OTLP_LOGS_ENDPOINT,
              endpoint,
              OTEL_EXPORTER_OTLP_LOGS_HEADERS,
              "x-aws-log-group=test1,x-aws-log-stream=test2",
              OTEL_EXPORTER_OTLP_LOGS_PROTOCOL,
              "http/protobuf",
              OTEL_LOGS_EXPORTER,
              "otlp");

      args.add(badEndpoint);
    }

    return args.stream().map(Arguments::of);
  }

  static Stream<Arguments> invalidSigv4LogsConfigProvider() {
    List<Map<String, String>> args = new ArrayList<>();
    String[] logsBadEndpoints = {
      "http://localhost:4318/v1/logs",
      "http://logs.us-east-1.amazonaws.com/v1/logs",
      "ftp://logs.us-east-1.amazonaws.com/v1/logs",
      "https://log.us-east-1.amazonaws.com/v1/logs",
      "https://logging.us-east-1.amazonaws.com/v1/logs",
      "https://cloud-logs.us-east-1.amazonaws.com/v1/logs",
      "https://logs.amazonaws.com/v1/logs",
      "https://logs.us-east-1.amazon.com/v1/logs",
      "https://logs.us-east-1.aws.com/v1/logs",
      "https://logs.US-EAST-1.amazonaws.com/v1/logs",
      "https://logs.us_east_1.amazonaws.com/v1/logs",
      "https://logs.us.east.1.amazonaws.com/v1/logs",
      "https://logs..amazonaws.com/v1/logs",
      "https://logs.us-east-1.amazonaws.com/logs",
      "https://logs.us-east-1.amazonaws.com/v2/logs",
      "https://logs.us-east-1.amazonaws.com/v1/log",
      "https://logs.us-east-1.amazonaws.com/v1/logs/",
      "https://logs.us-east-1.amazonaws.com//v1/logs",
      "https://logs.us-east-1.amazonaws.com/v1//logs",
      "https://logs.us-east-1.amazonaws.com/v1/logs?param=value",
      "https://logs.us-east-1.amazonaws.com/v1/logs#fragment",
      "https://logs.us-east-1.amazonaws.com:443/v1/logs",
      "https:/logs.us-east-1.amazonaws.com/v1/logs",
      "https:://logs.us-east-1.amazonaws.com/v1/logs",
      "https://LOGS.us-east-1.amazonaws.com/v1/logs",
      "https://logs.us-east-1.amazonaws.com/V1/LOGS",
      "https://logs.us-east-1.amazonaws.com/v1/logging",
      "https://logs.us-east-1.amazonaws.com/v1/cloudwatchlogs",
      "https://logs.us-east-1.amazonaws.com/v1/cwlogs"
    };

    Map<String, String> noLogGroupHeader =
        Map.of(
            OTEL_EXPORTER_OTLP_LOGS_ENDPOINT,
            "https://logs.us-east-1.amazonaws.com/v1/logs",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
            "x-aws-log-stream=test2",
            OTEL_EXPORTER_OTLP_LOGS_PROTOCOL,
            "http/protobuf",
            OTEL_LOGS_EXPORTER,
            "otlp");

    Map<String, String> noLogStreamHeader =
        Map.of(
            OTEL_EXPORTER_OTLP_LOGS_ENDPOINT,
            "https://logs.us-east-1.amazonaws.com/v1/logs",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
            "x-aws-log-group=test2",
            OTEL_EXPORTER_OTLP_LOGS_PROTOCOL,
            "http/protobuf",
            OTEL_LOGS_EXPORTER,
            "otlp");

    Map<String, String> badLogStreamHeader =
        Map.of(
            OTEL_EXPORTER_OTLP_LOGS_ENDPOINT,
            "https://logs.us-east-1.amazonaws.com/v1/logs",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
            "x-aws-log-group=test1,x-aws-log-strea21=test2",
            OTEL_EXPORTER_OTLP_LOGS_PROTOCOL,
            "http/protobuf",
            OTEL_LOGS_EXPORTER,
            "otlp");

    Map<String, String> invalidProtocol =
        Map.of(
            OTEL_EXPORTER_OTLP_LOGS_ENDPOINT,
            "https://logs.us-east-1.amazonaws.com/v1/logs",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
            "x-aws-log-group=test1,x-aws-log-stream=test2",
            OTEL_EXPORTER_OTLP_LOGS_PROTOCOL,
            "grpc",
            OTEL_LOGS_EXPORTER,
            "otlp");

    Map<String, String> consoleExporter =
        Map.of(
            OTEL_EXPORTER_OTLP_LOGS_ENDPOINT, "https://logs.us-east-1.amazonaws.com/v1/logs",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS, "x-aws-log-stream=test2",
            OTEL_EXPORTER_OTLP_LOGS_PROTOCOL, "http/protobuf",
            OTEL_LOGS_EXPORTER, "console");

    for (String endpoint : logsBadEndpoints) {
      Map<String, String> badEndpoint =
          Map.of(
              OTEL_EXPORTER_OTLP_LOGS_ENDPOINT, endpoint,
              OTEL_EXPORTER_OTLP_LOGS_PROTOCOL, "http/protobuf",
              OTEL_LOGS_EXPORTER, "otlp");

      args.add(badEndpoint);
    }

    args.add(badLogStreamHeader);
    args.add(noLogStreamHeader);
    args.add(noLogGroupHeader);
    args.add(invalidProtocol);
    args.add(consoleExporter);

    return args.stream().map(Arguments::of);
  }

  static Stream<Arguments> invalidCloudWatchEmfConfigProvider() {
    List<Map<String, String>> args = new ArrayList<>();

    Map<String, String> wrongExporter =
        Map.of(
            OTEL_METRICS_EXPORTER,
            "otlp",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
            "x-aws-log-group=test-group,x-aws-log-stream=test-stream,x-aws-metric-namespace=test-namespace",
            AWS_REGION,
            "us-east-1");

    Map<String, String> missingHeaders =
        Map.of(OTEL_METRICS_EXPORTER, "awsemf", AWS_REGION, "us-east-1");

    Map<String, String> missingRegion =
        Map.of(
            OTEL_METRICS_EXPORTER, "awsemf",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
                "x-aws-log-group=test-group,x-aws-log-stream=test-stream,x-aws-metric-namespace=test-namespace");

    Map<String, String> missingLogGroup =
        Map.of(
            OTEL_METRICS_EXPORTER,
            "awsemf",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
            "x-aws-log-stream=test-stream,x-aws-metric-namespace=test-namespace",
            AWS_REGION,
            "us-east-1");

    Map<String, String> missingLogStream =
        Map.of(
            OTEL_METRICS_EXPORTER,
            "awsemf",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
            "x-aws-log-group=test-group,x-aws-metric-namespace=test-namespace",
            AWS_REGION,
            "us-east-1");

    args.add(wrongExporter);
    args.add(missingHeaders);
    args.add(missingRegion);
    args.add(missingLogGroup);
    args.add(missingLogStream);

    return args.stream().map(Arguments::of);
  }

  static Stream<Arguments> validCloudWatchEmfConfigProvider() {
    List<Map<String, String>> args = new ArrayList<>();

    Map<String, String> awsRegionConfig =
        Map.of(
            OTEL_METRICS_EXPORTER,
            "awsemf",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
            "x-aws-log-group=test-group,x-aws-log-stream=test-stream,x-aws-metric-namespace=test-namespace",
            AWS_REGION,
            "us-east-1");

    Map<String, String> awsDefaultRegionConfig =
        Map.of(
            OTEL_METRICS_EXPORTER,
            "awsemf",
            OTEL_EXPORTER_OTLP_LOGS_HEADERS,
            "x-aws-log-group=test-group,x-aws-log-stream=test-stream,x-aws-metric-namespace=test-namespace",
            AWS_DEFAULT_REGION,
            "us-west-2");

    args.add(awsRegionConfig);
    args.add(awsDefaultRegionConfig);

    return args.stream().map(Arguments::of);
  }

  static Stream<Arguments> invalidCompactLogsConfigProvider() {
    return Stream.of(
        Arguments.of(Map.of(OTEL_LOGS_EXPORTER, "console")),
        Arguments.of(
            Map.of(
                OTEL_LOGS_EXPORTER, "otlp", AWS_LAMBDA_FUNCTION_NAME_PROP_CONFIG, "test-function")),
        Arguments.of(
            Map.of(
                OTEL_LOGS_EXPORTER, "none", AWS_LAMBDA_FUNCTION_NAME_PROP_CONFIG, "test-function")),
        Arguments.of(Map.of(AWS_LAMBDA_FUNCTION_NAME_PROP_CONFIG, "test-function")),
        Arguments.of(Map.of()));
  }

  static Stream<Arguments> validConsoleEmfConfigProvider() {
    return Stream.of(
        Arguments.of(
            Map.of(
                OTEL_METRICS_EXPORTER, "awsemf",
                OTEL_EXPORTER_OTLP_LOGS_HEADERS, "x-aws-metric-namespace=test-namespace",
                AWS_REGION, "us-east-1",
                AWS_LAMBDA_FUNCTION_NAME_PROP_CONFIG, "test-function")),
        Arguments.of(
            Map.of(
                OTEL_METRICS_EXPORTER, "awsemf",
                OTEL_EXPORTER_OTLP_LOGS_HEADERS, "x-aws-metric-namespace=another-namespace",
                AWS_DEFAULT_REGION, "us-west-2",
                AWS_LAMBDA_FUNCTION_NAME_PROP_CONFIG, "another-function")));
  }

  static Stream<Arguments> invalidConsoleEmfConfigProvider() {
    return Stream.of(
        Arguments.of(
            Map.of(
                OTEL_METRICS_EXPORTER, "otlp",
                OTEL_EXPORTER_OTLP_LOGS_HEADERS, "x-aws-metric-namespace=test-namespace",
                AWS_REGION, "us-east-1",
                AWS_LAMBDA_FUNCTION_NAME_PROP_CONFIG, "test-function")),
        Arguments.of(
            Map.of(
                OTEL_METRICS_EXPORTER, "awsemf",
                AWS_REGION, "us-east-1")),
        Arguments.of(
            Map.of(
                OTEL_METRICS_EXPORTER, "awsemf",
                OTEL_EXPORTER_OTLP_LOGS_HEADERS, "x-aws-metric-namespace=test-namespace")));
  }

  static Stream<Arguments> invalidLambdaCloudWatchEmfConfigProvider() {
    return Stream.of(
        Arguments.of(
            Map.of(
                OTEL_METRICS_EXPORTER, "otlp",
                OTEL_EXPORTER_OTLP_LOGS_HEADERS, "x-aws-metric-namespace=test-namespace",
                AWS_REGION, "us-east-1")),
        Arguments.of(
            Map.of(
                OTEL_METRICS_EXPORTER, "awsemf",
                OTEL_EXPORTER_OTLP_LOGS_HEADERS, "x-aws-metric-namespace=test-namespace")));
  }
}
