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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.logs;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.testing.logs.TestLogRecordData;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.AbstractOtlpAwsExporterTest;

class OtlpAwsLogRecordExporterTest extends AbstractOtlpAwsExporterTest<LogRecordData> {
  private static final String LOGS_OTLP_ENDPOINT = "https://logs.us-east-1.amazonaws.com/v1/logs";

  private LogRecordExporter exporter;

  OtlpAwsLogRecordExporterTest() {
    super(LOGS_OTLP_ENDPOINT, "us-east-1", "logs");
  }

  @Override
  protected LogRecordData createOtlpData(int index) {
    return TestLogRecordData.builder()
        .setResource(TEST_RESOURCE)
        .setInstrumentationScopeInfo(
            InstrumentationScopeInfo.builder("test.scope." + index).setVersion("1.2.3").build())
        .setBody("test.log." + index)
        .setSeverity(Severity.INFO)
        .setSeverityText("INFO")
        .setAttributes(
            Attributes.builder()
                .put("test.name", "log")
                .put("test.count", (long) index)
                .put("test.enabled", true)
                .build())
        .setTotalAttributeCount(3)
        .setSpanContext(
            SpanContext.create(
                "0123456789abcdef0123456789abcdef",
                String.format("%016x", index + 1),
                TraceFlags.getSampled(),
                TraceState.getDefault()))
        .setTimestamp(Instant.ofEpochSecond(1L))
        .setObservedTimestamp(Instant.ofEpochSecond(2L))
        .build();
  }

  @Override
  protected void validateOtlpPayload(byte[] payload, Collection<LogRecordData> data)
      throws Exception {
    ExportLogsServiceRequest decoded = ExportLogsServiceRequest.parseFrom(payload);
    assertEquals(1, decoded.getResourceLogsCount());
    ResourceLogs resource = decoded.getResourceLogs(0);
    assertResource(resource.getResource());
    assertEquals(data.size(), resource.getScopeLogsCount());
    Map<String, LogRecordData> expected =
        data.stream()
            .collect(
                Collectors.toMap(
                    expectedData -> expectedData.getInstrumentationScopeInfo().getName(),
                    expectedData -> expectedData));
    for (ScopeLogs scope : resource.getScopeLogsList()) {
      LogRecordData expectedData = expected.remove(scope.getScope().getName());
      assertNotNull(expectedData, "unexpected or repeated scope " + scope.getScope().getName());
      assertScope(expectedData.getInstrumentationScopeInfo(), scope.getScope());
      assertEquals(1, scope.getLogRecordsCount());
      LogRecord log = scope.getLogRecords(0);
      assertEquals(expectedData.getBodyValue().asString(), log.getBody().getStringValue());
      assertEquals(expectedData.getSeverity().getSeverityNumber(), log.getSeverityNumberValue());
      assertEquals(expectedData.getSeverityText(), log.getSeverityText());
      assertEquals(expectedData.getTimestampEpochNanos(), log.getTimeUnixNano());
      assertEquals(expectedData.getObservedTimestampEpochNanos(), log.getObservedTimeUnixNano());
      assertArrayEquals(
          expectedData.getSpanContext().getTraceIdBytes(), log.getTraceId().toByteArray());
      assertArrayEquals(
          expectedData.getSpanContext().getSpanIdBytes(), log.getSpanId().toByteArray());
      assertEquals(expectedData.getSpanContext().getTraceFlags().asByte(), log.getFlags());
      assertAttributes(expectedData.getAttributes(), log.getAttributesList());
      assertEquals(0, log.getDroppedAttributesCount());
    }
    assertTrue(expected.isEmpty());
  }

  @Override
  protected CompletableResultCode export(Collection<LogRecordData> data) {
    if (exporter == null) {
      exporter =
          OtlpAwsLogRecordExporterBuilder.create(
                  OtlpHttpLogRecordExporter.builder()
                      .setComponentLoader(componentLoader)
                      .setEndpoint(endpoint)
                      .setCompression(otlpCompression)
                      .build(),
                  endpoint)
              .setCompression(compression)
              .build();
    }
    return exporter.export(data);
  }

  @Override
  protected void shutdownExporter() {
    if (exporter != null) {
      exporter.shutdown();
    }
  }
}
