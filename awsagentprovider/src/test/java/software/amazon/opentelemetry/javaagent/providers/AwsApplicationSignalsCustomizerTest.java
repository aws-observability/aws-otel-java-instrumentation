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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static software.amazon.opentelemetry.javaagent.providers.AwsApplicationSignalsCustomizerProvider.*;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.logs.OtlpAwsLogsExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.traces.OtlpAwsSpanExporter;

@ExtendWith({MockitoExtension.class})
public class AwsApplicationSignalsCustomizerTest {
  private AwsApplicationSignalsCustomizerProvider provider;
  private final LogRecordExporter defaultHttpLogsExporter = OtlpHttpLogRecordExporter.getDefault();
  private final SpanExporter defaultHttpSpanExporter = OtlpHttpSpanExporter.getDefault();

  @BeforeEach
  void init() {
    this.provider = new AwsApplicationSignalsCustomizerProvider();
  }

  @AfterEach
  void reset() {}

  @ParameterizedTest
  @MethodSource("validSigv4LogsConfigProvider")
  void testShouldEnableSigV4LogsExporterIfConfigIsCorrect(Map<String, String> validSigv4Config) {
    customizeExporterTest(
        validSigv4Config,
        defaultHttpLogsExporter,
        this.provider::customizeLogsExporter,
        OtlpAwsLogsExporter.class);
  }

  @ParameterizedTest
  @MethodSource("invalidSigv4LogsConfigProvider")
  void testShouldNotUseSigv4LogsExporter(Map<String, String> invalidSigv4Config) {
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
  void testShouldNotUseSigv4SpanExporter(Map<String, String> invalidSigv4Config) {
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
}
