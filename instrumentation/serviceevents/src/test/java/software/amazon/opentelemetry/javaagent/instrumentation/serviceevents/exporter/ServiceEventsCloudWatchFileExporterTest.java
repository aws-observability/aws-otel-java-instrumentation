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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramData;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.SumData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableExponentialHistogramBuckets;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableExponentialHistogramData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableExponentialHistogramPointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableLongPointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceEventsCloudWatchFileExporterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tmpDir;

  @AfterEach
  void resetWriters() {
    ServiceEventsFileWriter.resetForTests();
  }

  @Test
  void logExporter_writesFlatShape() throws Exception {
    Path out = tmpDir.resolve("serviceevents.ndjson");
    ServiceEventsCloudWatchLogFileExporter exporter =
        new ServiceEventsCloudWatchLogFileExporter(out.toString());
    try {
      LogRecordData record = buildLogRecord("aws.service_events.function_call", false);
      CompletableResultCode result = exporter.export(Collections.singletonList(record));
      assertTrue(result.isSuccess());
    } finally {
      exporter.shutdown();
    }

    List<Map<String, Object>> lines = readNdjson(out);
    assertEquals(1, lines.size());
    Map<String, Object> line = lines.get(0);
    assertEquals("aws.service_events.function_call", line.get("eventName"));
    assertNotNull(line.get("timeUnixNano"));
    assertTrue(line.get("attributes") instanceof Map);
    assertTrue(line.get("body") instanceof Map);
    assertTrue(line.get("resource") instanceof Map);
    Map<String, Object> attrs = (Map<String, Object>) line.get("attributes");
    assertEquals("process_order", attrs.get("aws.service_events.function_name"));
    Map<String, Object> resource = (Map<String, Object>) line.get("resource");
    assertEquals("shoppingcart", resource.get("service.name"));
    assertFalse(line.containsKey("traceId"));
  }

  @Test
  void logExporter_includesTraceContextWhenPresent() throws Exception {
    Path out = tmpDir.resolve("serviceevents.ndjson");
    ServiceEventsCloudWatchLogFileExporter exporter =
        new ServiceEventsCloudWatchLogFileExporter(out.toString());
    try {
      LogRecordData record = buildLogRecord("aws.service_events.incident_snapshot", true);
      exporter.export(Collections.singletonList(record));
    } finally {
      exporter.shutdown();
    }

    Map<String, Object> line = readNdjson(out).get(0);
    assertEquals("aabbccddeeff00112233445566778899", line.get("traceId"));
    assertEquals("1122334455667788", line.get("spanId"));
    assertEquals(1, ((Number) line.get("flags")).intValue());
  }

  @Test
  void logExporter_multipleRecordsOneLinePerRecord() throws Exception {
    Path out = tmpDir.resolve("serviceevents.ndjson");
    ServiceEventsCloudWatchLogFileExporter exporter =
        new ServiceEventsCloudWatchLogFileExporter(out.toString());
    try {
      CompletableResultCode result =
          exporter.export(
              Arrays.asList(
                  buildLogRecord("aws.service_events.function_call", false),
                  buildLogRecord("aws.service_events.endpoint_summary", false)));
      assertTrue(result.isSuccess());
    } finally {
      exporter.shutdown();
    }

    List<Map<String, Object>> lines = readNdjson(out);
    assertEquals(2, lines.size());
    assertEquals("aws.service_events.function_call", lines.get(0).get("eventName"));
    assertEquals("aws.service_events.endpoint_summary", lines.get(1).get("eventName"));
  }

  @Test
  void logExporter_emptyBatchSucceeds() throws Exception {
    Path out = tmpDir.resolve("serviceevents.ndjson");
    ServiceEventsCloudWatchLogFileExporter exporter =
        new ServiceEventsCloudWatchLogFileExporter(out.toString());
    try {
      CompletableResultCode result = exporter.export(Collections.emptyList());
      assertTrue(result.isSuccess());
    } finally {
      exporter.shutdown();
    }
  }

  @Test
  void metricExporter_writesOtlpJsonPerBatch() throws Exception {
    Path out = tmpDir.resolve("serviceevents.ndjson");
    ServiceEventsCloudWatchMetricFileExporter exporter =
        new ServiceEventsCloudWatchMetricFileExporter(out.toString());
    try {
      MetricData metric = buildCountMetric();
      CompletableResultCode result = exporter.export(Collections.singletonList(metric));
      assertTrue(result.isSuccess());
    } finally {
      exporter.shutdown();
    }

    List<Map<String, Object>> lines = readNdjson(out);
    // One line per export batch (an ExportMetricsServiceRequest), not per data point.
    assertEquals(1, lines.size());
    Map<String, Object> req = lines.get(0);
    // No EMF envelope on the production wire.
    assertFalse(req.containsKey("_aws"));

    List<Map<String, Object>> resourceMetrics =
        (List<Map<String, Object>>) req.get("resourceMetrics");
    List<Map<String, Object>> scopeMetrics =
        (List<Map<String, Object>>) resourceMetrics.get(0).get("scopeMetrics");
    Map<String, Object> scope = (Map<String, Object>) scopeMetrics.get(0).get("scope");
    assertEquals("serviceevents", scope.get("name"));
    List<Map<String, Object>> metrics =
        (List<Map<String, Object>>) scopeMetrics.get(0).get("metrics");
    Map<String, Object> metric = metrics.get(0);
    // Metric name stays lowercase (no count -> Count capitalization).
    assertEquals("count", metric.get("name"));
    Map<String, Object> sum = (Map<String, Object>) metric.get("sum");
    // OTLP proto enum AGGREGATION_TEMPORALITY_DELTA = 1. The Java marshaler serializes enums
    // as their numeric value (unlike Python's MessageToJson, which uses the name) — both are
    // valid OTLP/JSON. The file exporter forces Delta to match the OTLP HTTP exporter.
    assertEquals(1, ((Number) sum.get("aggregationTemporality")).intValue());
    List<Map<String, Object>> dataPoints = (List<Map<String, Object>>) sum.get("dataPoints");
    assertEquals("5", dataPoints.get(0).get("asInt"));
  }

  @Test
  void metricExporter_writesExponentialHistogramAsOtlpJson() throws Exception {
    Path out = tmpDir.resolve("serviceevents.ndjson");
    ServiceEventsCloudWatchMetricFileExporter exporter =
        new ServiceEventsCloudWatchMetricFileExporter(out.toString());
    try {
      CompletableResultCode result =
          exporter.export(Collections.singletonList(buildDurationHistogramMetric()));
      assertTrue(result.isSuccess());
    } finally {
      exporter.shutdown();
    }

    List<Map<String, Object>> lines = readNdjson(out);
    assertEquals(1, lines.size());
    List<Map<String, Object>> resourceMetrics =
        (List<Map<String, Object>>) lines.get(0).get("resourceMetrics");
    List<Map<String, Object>> scopeMetrics =
        (List<Map<String, Object>>) resourceMetrics.get(0).get("scopeMetrics");
    List<Map<String, Object>> metrics =
        (List<Map<String, Object>>) scopeMetrics.get(0).get("metrics");
    Map<String, Object> metric = metrics.get(0);
    assertEquals("service.function.duration", metric.get("name"));
    // Histogram is not dropped, not EMF — serializes natively as exponentialHistogram.
    Map<String, Object> hist = (Map<String, Object>) metric.get("exponentialHistogram");
    assertNotNull(hist);
    List<Map<String, Object>> dataPoints = (List<Map<String, Object>>) hist.get("dataPoints");
    assertEquals("3", dataPoints.get(0).get("count"));
  }

  @Test
  void logAndMetricExporters_shareSingleFile() throws Exception {
    Path out = tmpDir.resolve("serviceevents.ndjson");
    ServiceEventsCloudWatchLogFileExporter logExp =
        new ServiceEventsCloudWatchLogFileExporter(out.toString());
    ServiceEventsCloudWatchMetricFileExporter metricExp =
        new ServiceEventsCloudWatchMetricFileExporter(out.toString());
    try {
      logExp.export(
          Collections.singletonList(buildLogRecord("aws.service_events.function_call", false)));
      metricExp.export(Collections.singletonList(buildCountMetric()));
    } finally {
      logExp.shutdown();
      metricExp.shutdown();
    }

    List<Map<String, Object>> lines = readNdjson(out);
    assertEquals(2, lines.size());
    // Logs keep the flat CloudWatch-Insights shape (eventName); metrics are OTLP JSON.
    boolean hasEvent =
        lines.stream().anyMatch(l -> "aws.service_events.function_call".equals(l.get("eventName")));
    boolean hasMetrics = lines.stream().anyMatch(l -> l.containsKey("resourceMetrics"));
    assertTrue(hasEvent);
    assertTrue(hasMetrics);
  }

  @Test
  void logExporter_unopenableFile_doesNotThrowAndReturnsFailure() {
    // /dev/full/<anything> on Linux and a path under a regular file on macOS both fail with
    // IOException. Use a file-as-directory path that the exporter can't create.
    Path notADir = tmpDir.resolve("plain-file");
    try {
      Files.write(notADir, new byte[] {0});
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    Path bogus = notADir.resolve("nested").resolve("svc.ndjson");

    ServiceEventsCloudWatchLogFileExporter exporter =
        new ServiceEventsCloudWatchLogFileExporter(bogus.toString());
    try {
      LogRecordData record = buildLogRecord("aws.service_events.function_call", false);
      // No exception escapes the exporter even though the underlying file can't be opened.
      CompletableResultCode result = exporter.export(Collections.singletonList(record));
      assertFalse(result.isSuccess());
      assertFalse(exporter.flush().isSuccess());
    } finally {
      // Shutdown must succeed (no-op) even when no writer was acquired.
      assertTrue(exporter.shutdown().isSuccess());
    }
  }

  @Test
  void metricExporter_unopenableFile_doesNotThrowAndReturnsFailure() {
    Path notADir = tmpDir.resolve("plain-metric-file");
    try {
      Files.write(notADir, new byte[] {0});
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    Path bogus = notADir.resolve("nested").resolve("svc.ndjson");

    ServiceEventsCloudWatchMetricFileExporter exporter =
        new ServiceEventsCloudWatchMetricFileExporter(bogus.toString());
    try {
      CompletableResultCode result = exporter.export(Collections.singletonList(buildCountMetric()));
      assertFalse(result.isSuccess());
      assertFalse(exporter.flush().isSuccess());
    } finally {
      assertTrue(exporter.shutdown().isSuccess());
    }
  }

  // ─── helpers ─────────────────────────────────────────────────────────

  private static LogRecordData buildLogRecord(String eventName, boolean withTrace) {
    Resource resource =
        Resource.create(
            Attributes.builder()
                .put("service.name", "shoppingcart")
                .put("deployment.environment", "prod")
                .put("telemetry.sdk.language", "java")
                .build());
    SpanContext spanCtx =
        withTrace
            ? SpanContext.create(
                "aabbccddeeff00112233445566778899",
                "1122334455667788",
                TraceFlags.getSampled(),
                TraceState.getDefault())
            : SpanContext.getInvalid();

    return new LogRecordData() {
      @Override
      public Resource getResource() {
        return resource;
      }

      @Override
      public InstrumentationScopeInfo getInstrumentationScopeInfo() {
        return InstrumentationScopeInfo.create("serviceevents", "1.0", null);
      }

      @Override
      public long getTimestampEpochNanos() {
        return 1744137998974205000L;
      }

      @Override
      public long getObservedTimestampEpochNanos() {
        return 1744137998974205000L;
      }

      @Override
      public SpanContext getSpanContext() {
        return spanCtx;
      }

      @Override
      public io.opentelemetry.api.logs.Severity getSeverity() {
        return io.opentelemetry.api.logs.Severity.UNDEFINED_SEVERITY_NUMBER;
      }

      @Override
      public String getSeverityText() {
        return null;
      }

      @SuppressWarnings("deprecation")
      @Override
      public io.opentelemetry.sdk.logs.data.Body getBody() {
        return io.opentelemetry.sdk.logs.data.Body.empty();
      }

      @Override
      public Value<?> getBodyValue() {
        return Value.of(Map.of("exceptions", Value.of(Map.of("RuntimeError", Value.of(3L)))));
      }

      @Override
      public Attributes getAttributes() {
        return Attributes.builder()
            .put("event.name", eventName)
            .put("aws.service_events.function_name", "process_order")
            .build();
      }

      @Override
      public int getTotalAttributeCount() {
        return 2;
      }

      @Override
      public String getEventName() {
        return eventName;
      }
    };
  }

  private static MetricData buildCountMetric() {
    Attributes dpAttrs =
        Attributes.builder()
            .put("Telemetry.Source", "ServiceEvents")
            .put("service_name", "shoppingcart")
            .put("environment", "prod")
            .put("operation", "POST /api/checkout")
            .put("exception", "RuntimeError")
            .build();
    LongPointData dp =
        ImmutableLongPointData.create(
            1744137900_000_000_000L, 1744137960_000_000_000L, dpAttrs, 5L);
    SumData<LongPointData> sumData =
        ImmutableSumData.create(
            /* isMonotonic= */ true, AggregationTemporality.DELTA, Collections.singletonList(dp));
    return ImmutableMetricData.createLongSum(
        Resource.empty(),
        InstrumentationScopeInfo.create("serviceevents", "1.0", null),
        "count",
        "ServiceEvents EndpointErrorMetrics counter",
        "Count",
        sumData);
  }

  private static MetricData buildDurationHistogramMetric() {
    Attributes dpAttrs =
        Attributes.builder()
            .put("Telemetry.Source", "ServiceEvents")
            .put("function.name", "app.handle")
            .put("status", "success")
            .build();
    // Two positive buckets (counts 1 + 2) + zero zeroCount => total count 3.
    ExponentialHistogramPointData dp =
        ImmutableExponentialHistogramPointData.create(
            /* scale= */ 4,
            /* sum= */ 16166.0,
            /* zeroCount= */ 0L,
            /* hasMin= */ true,
            /* min= */ 1500.0,
            /* hasMax= */ true,
            /* max= */ 3875.0,
            /* positiveBuckets= */ ImmutableExponentialHistogramBuckets.create(
                4, 47, Arrays.asList(1L, 2L)),
            /* negativeBuckets= */ ImmutableExponentialHistogramBuckets.create(
                4, 0, Collections.emptyList()),
            1744137900_000_000_000L,
            1744137960_000_000_000L,
            dpAttrs,
            Collections.emptyList());
    ExponentialHistogramData histData =
        ImmutableExponentialHistogramData.create(
            AggregationTemporality.DELTA, Collections.singletonList(dp));
    return ImmutableMetricData.createExponentialHistogram(
        Resource.empty(),
        InstrumentationScopeInfo.create("serviceevents", "1.0", null),
        "service.function.duration",
        "Function call duration",
        "Microseconds",
        histData);
  }

  private static List<Map<String, Object>> readNdjson(Path path) throws IOException {
    if (!Files.exists(path)) {
      return Collections.emptyList();
    }
    List<Map<String, Object>> result = new java.util.ArrayList<>();
    for (String line : Files.readAllLines(path)) {
      if (line.isBlank()) {
        continue;
      }
      result.add(MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {}));
    }
    return result;
  }
}
