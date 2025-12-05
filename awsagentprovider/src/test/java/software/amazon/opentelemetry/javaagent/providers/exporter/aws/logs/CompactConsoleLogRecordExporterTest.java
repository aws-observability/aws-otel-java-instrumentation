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

package software.amazon.opentelemetry.javaagent.providers.exporter.aws.logs;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.logs.TestLogRecordData;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class CompactConsoleLogRecordExporterTest {

  private static final String TRACE_ID_KEY = "traceId";
  private static final String SPAN_ID_KEY = "spanId";
  private static final String TRACE_FLAGS_KEY = "traceFlags";
  private static final String BODY_KEY = "body";
  private static final String SEVERITY_NUMBER_KEY = "severityNumber";
  private static final String SEVERITY_TEXT_KEY = "severityText";
  private static final String TIMESTAMP_KEY = "timestamp";
  private static final String OBSERVED_TIMESTAMP_KEY = "observedTimestamp";
  private static final String INSTRUMENTATION_SCOPE_KEY = "instrumentationScope";
  private static final String RESOURCE_KEY = "resource";
  private static final String ATTRIBUTES_KEY = "attributes";
  private static final String DROPPED_ATTRIBUTES_KEY = "droppedAttributes";
  private static final String SCOPE_NAME_KEY = "name";
  private static final String SCOPE_VERSION_KEY = "version";
  private static final String SCOPE_SCHEMA_URL_KEY = "schemaUrl";
  private static final String RESOURCE_SCHEMA_URL_KEY = "schemaUrl";

  private OutputStream outputStream;
  private PrintStream printStream;
  private CompactConsoleLogRecordExporter exporter;

  @BeforeEach
  void setUp() {
    this.outputStream = new ByteArrayOutputStream();
    this.printStream = new PrintStream(this.outputStream);
    this.exporter = new CompactConsoleLogRecordExporter(this.printStream);
  }

  @Test
  void testExportWithAllFieldsSet() {
    SpanContext spanContext =
        SpanContext.create(
            "12345678901234567890123456789012",
            "1234567890123456",
            TraceFlags.getSampled(),
            TraceState.getDefault());
    Resource resource =
        Resource.empty().toBuilder()
            .put("service.name", "test-service")
            .setSchemaUrl("https://opentelemetry.io/schemas/1.0.0")
            .build();
    InstrumentationScopeInfo scope =
        InstrumentationScopeInfo.builder("test-scope")
            .setVersion("1.0.0")
            .setSchemaUrl("https://opentelemetry.io/schemas/1.0.0")
            .build();

    LogRecordData logRecord =
        TestLogRecordData.builder()
            .setResource(resource)
            .setInstrumentationScopeInfo(scope)
            .setBody("Test log message")
            .setSeverity(Severity.INFO)
            .setAttributes(Attributes.builder().put("key", "value").build())
            .setTotalAttributeCount(3)
            .setTimestamp(Instant.ofEpochSecond(1000000000L))
            .setObservedTimestamp(Instant.ofEpochSecond(1000000000L))
            .setSpanContext(spanContext)
            .build();

    this.exporter.export(Collections.singletonList(logRecord));

    validateJsonOutput(this.outputStream.toString().trim(), logRecord);
  }

  @Test
  void testSpanContextValidation() {
    SpanContext spanContext =
        SpanContext.create(
            "12345678901234567890123456789012",
            "1234567890123456",
            TraceFlags.getSampled(),
            TraceState.getDefault());

    LogRecordData logRecord =
        TestLogRecordData.builder()
            .setResource(Resource.empty())
            .setInstrumentationScopeInfo(InstrumentationScopeInfo.builder("test-scope").build())
            .setBody("Test message")
            .setSeverity(Severity.INFO)
            .setTimestamp(Instant.ofEpochSecond(1000000000L))
            .setObservedTimestamp(Instant.ofEpochSecond(1000000000L))
            .setSpanContext(spanContext)
            .build();

    this.exporter.export(Collections.singletonList(logRecord));

    validateJsonOutput(this.outputStream.toString().trim(), logRecord);
  }

  @ParameterizedTest
  @MethodSource("nullFieldsTestCases")
  void testExportWithNullFields(LogRecordData logRecord) {
    this.exporter.export(Collections.singletonList(logRecord));
    validateJsonOutput(this.outputStream.toString().trim(), logRecord);
  }

  @Test
  void testFlushBehavior() {
    assertTrue(this.exporter.flush().isSuccess());
  }

  @Test
  void testShutdownBehavior() {
    assertTrue(this.exporter.shutdown().isSuccess());
  }

  @ParameterizedTest
  @MethodSource("invalidSpanContextTestCases")
  void testInvalidSpanContextExportsEmpty(String traceId, String spanId) {
    SpanContext spanContext =
        SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());

    LogRecordData logRecord =
        TestLogRecordData.builder()
            .setResource(Resource.empty())
            .setInstrumentationScopeInfo(InstrumentationScopeInfo.builder("test-scope").build())
            .setBody("Test message")
            .setSeverity(Severity.INFO)
            .setTimestamp(Instant.ofEpochSecond(1000000000L))
            .setObservedTimestamp(Instant.ofEpochSecond(1000000000L))
            .setSpanContext(spanContext)
            .build();

    this.exporter.export(Collections.singletonList(logRecord));
    validateJsonOutput(this.outputStream.toString().trim(), logRecord);
  }

  private static void validateJsonOutput(
      String actualJsonString, LogRecordData expectedLogRecordData) {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> actualParsedJson =
        assertDoesNotThrow(
            () -> mapper.readValue(actualJsonString, new TypeReference<Map<String, Object>>() {}));

    // Validate nested objects exist
    assertTrue(actualParsedJson.containsKey(INSTRUMENTATION_SCOPE_KEY));
    assertTrue(actualParsedJson.containsKey(RESOURCE_KEY));
    assertTrue(actualParsedJson.containsKey(ATTRIBUTES_KEY));

    // Validate body field and value
    assertTrue(actualParsedJson.containsKey(BODY_KEY));
    String expectedBody =
        expectedLogRecordData.getBodyValue() != null
            ? expectedLogRecordData.getBodyValue().asString()
            : null;
    assertEquals(expectedBody, actualParsedJson.get(BODY_KEY));

    // Validate instrumentationScope structure and values
    assertInstanceOf(Map.class, actualParsedJson.get(INSTRUMENTATION_SCOPE_KEY));
    Map<String, Object> instrumentationScope =
        (Map<String, Object>) actualParsedJson.get(INSTRUMENTATION_SCOPE_KEY);
    assertTrue(instrumentationScope.containsKey(SCOPE_NAME_KEY));
    assertTrue(instrumentationScope.containsKey(SCOPE_VERSION_KEY));
    assertTrue(instrumentationScope.containsKey(SCOPE_SCHEMA_URL_KEY));
    assertEquals(
        expectedLogRecordData.getInstrumentationScopeInfo().getName(),
        instrumentationScope.get(SCOPE_NAME_KEY));
    assertEquals(
        expectedLogRecordData.getInstrumentationScopeInfo().getVersion() != null
            ? expectedLogRecordData.getInstrumentationScopeInfo().getVersion()
            : "",
        instrumentationScope.get(SCOPE_VERSION_KEY));
    assertEquals(
        expectedLogRecordData.getInstrumentationScopeInfo().getSchemaUrl() != null
            ? expectedLogRecordData.getInstrumentationScopeInfo().getSchemaUrl()
            : "",
        instrumentationScope.get(SCOPE_SCHEMA_URL_KEY));

    // Validate resource structure and values
    assertInstanceOf(Map.class, actualParsedJson.get(RESOURCE_KEY));
    Map<String, Object> resource = (Map<String, Object>) actualParsedJson.get(RESOURCE_KEY);
    assertTrue(resource.containsKey(ATTRIBUTES_KEY));
    assertTrue(resource.containsKey(RESOURCE_SCHEMA_URL_KEY));
    assertInstanceOf(Map.class, resource.get(ATTRIBUTES_KEY));
    assertEquals(
        expectedLogRecordData.getResource().getSchemaUrl() != null
            ? expectedLogRecordData.getResource().getSchemaUrl()
            : "",
        resource.get(RESOURCE_SCHEMA_URL_KEY));

    // Validate attributes match expected
    assertInstanceOf(Map.class, actualParsedJson.get(ATTRIBUTES_KEY));
    Map<String, Object> actualAttributes =
        (Map<String, Object>) actualParsedJson.get(ATTRIBUTES_KEY);
    expectedLogRecordData
        .getAttributes()
        .forEach(
            (key, value) -> {
              assertTrue(actualAttributes.containsKey(key.getKey()));
              assertEquals(
                  String.valueOf(value), String.valueOf(actualAttributes.get(key.getKey())));
            });

    // Validate timestamp fields and values
    assertTrue(actualParsedJson.containsKey(TIMESTAMP_KEY));
    assertTrue(actualParsedJson.containsKey(OBSERVED_TIMESTAMP_KEY));
    assertEquals(
        expectedLogRecordData.getTimestampEpochNanos(),
        Instant.parse((String) actualParsedJson.get(TIMESTAMP_KEY)).toEpochMilli() * 1_000_000L);
    assertEquals(
        expectedLogRecordData.getObservedTimestampEpochNanos(),
        Instant.parse((String) actualParsedJson.get(OBSERVED_TIMESTAMP_KEY)).toEpochMilli()
            * 1_000_000L);

    // Validate droppedAttributes field and value
    assertTrue(actualParsedJson.containsKey(DROPPED_ATTRIBUTES_KEY));
    int expectedDroppedAttributes =
        expectedLogRecordData.getTotalAttributeCount()
            - expectedLogRecordData.getAttributes().size();
    assertEquals(expectedDroppedAttributes, actualParsedJson.get(DROPPED_ATTRIBUTES_KEY));

    // Validate traceId, spanId, and traceFlags fields
    assertTrue(actualParsedJson.containsKey(TRACE_ID_KEY));
    assertTrue(actualParsedJson.containsKey(SPAN_ID_KEY));
    assertTrue(actualParsedJson.containsKey(TRACE_FLAGS_KEY));

    SpanContext spanContext = expectedLogRecordData.getSpanContext();
    if (spanContext != null) {
      if (TraceId.isValid(spanContext.getTraceId()) && SpanId.isValid(spanContext.getSpanId())) {
        assertEquals(spanContext.getTraceId(), actualParsedJson.get(TRACE_ID_KEY));
        assertEquals(spanContext.getSpanId(), actualParsedJson.get(SPAN_ID_KEY));
      } else {
        assertEquals("", actualParsedJson.get(TRACE_ID_KEY));
        assertEquals("", actualParsedJson.get(SPAN_ID_KEY));
      }
      assertEquals(
          (int) spanContext.getTraceFlags().asByte(), actualParsedJson.get(TRACE_FLAGS_KEY));
    }

    // Validate severity fields
    assertTrue(actualParsedJson.containsKey(SEVERITY_NUMBER_KEY));
    assertTrue(actualParsedJson.containsKey(SEVERITY_TEXT_KEY));
    assertEquals(
        expectedLogRecordData.getSeverity().getSeverityNumber(),
        actualParsedJson.get(SEVERITY_NUMBER_KEY));
    assertEquals(
        expectedLogRecordData.getSeverity().name(), actualParsedJson.get(SEVERITY_TEXT_KEY));
  }

  static Stream<Arguments> invalidSpanContextTestCases() {
    return Stream.of(
        Arguments.of(
            "00000000000000000000000000000000",
            "1234567890123456"), // invalid traceId, valid spanId
        Arguments.of(
            "12345678901234567890123456789012",
            "0000000000000000"), // valid traceId, invalid spanId
        Arguments.of("00000000000000000000000000000000", "0000000000000000"), // both invalid
        Arguments.of(
            "1234567890123456789012345678901g", "1234567890123456"), // invalid hex in traceId
        Arguments.of(
            "12345678901234567890123456789012", "123456789012345g") // invalid hex in spanId
        );
  }

  static Stream<Arguments> nullFieldsTestCases() {
    return Stream.of(
        Arguments.of(
            TestLogRecordData.builder()
                .setResource(Resource.empty())
                .setInstrumentationScopeInfo(InstrumentationScopeInfo.builder("test-scope").build())
                .setBody("Test message")
                .setSeverity(Severity.INFO)
                .setTimestamp(Instant.ofEpochSecond(1000000000L))
                .setObservedTimestamp(Instant.ofEpochSecond(1000000000L))
                .build()),
        Arguments.of(
            TestLogRecordData.builder()
                .setResource(Resource.empty())
                .setInstrumentationScopeInfo(InstrumentationScopeInfo.builder("test-scope").build())
                .setSeverity(Severity.INFO)
                .setTimestamp(Instant.ofEpochSecond(1000000000L))
                .setObservedTimestamp(Instant.ofEpochSecond(1000000000L))
                .build()),
        Arguments.of(
            TestLogRecordData.builder()
                .setResource(Resource.empty())
                .setInstrumentationScopeInfo(InstrumentationScopeInfo.builder("test-scope").build())
                .setBody("Test message")
                .setSeverity(Severity.INFO)
                .setTimestamp(Instant.ofEpochSecond(1000000000L))
                .setObservedTimestamp(Instant.ofEpochSecond(1000000000L))
                .setSpanContext(
                    SpanContext.create(
                        "abcdef1234567890abcdef1234567890",
                        "abcdef1234567890",
                        TraceFlags.getSampled(),
                        TraceState.getDefault()))
                .build()),
        Arguments.of(
            TestLogRecordData.builder()
                .setResource(Resource.empty())
                .setInstrumentationScopeInfo(InstrumentationScopeInfo.builder("test-scope").build())
                .setBody("Test message")
                .setTimestamp(Instant.ofEpochSecond(1000000000L))
                .setObservedTimestamp(Instant.ofEpochSecond(1000000000L))
                .build()),
        Arguments.of(
            TestLogRecordData.builder()
                .setResource(Resource.empty())
                .setInstrumentationScopeInfo(InstrumentationScopeInfo.builder("test-scope").build())
                .setBody("Test message")
                .setSeverity(Severity.INFO)
                .setAttributes(Attributes.empty())
                .setTimestamp(Instant.ofEpochSecond(1000000000L))
                .setObservedTimestamp(Instant.ofEpochSecond(1000000000L))
                .build()),
        Arguments.of(
            TestLogRecordData.builder()
                .setResource(
                    Resource.empty().toBuilder().put("service.name", "test-service").build())
                .setInstrumentationScopeInfo(InstrumentationScopeInfo.builder("test-scope").build())
                .setBody("Test message")
                .setSeverity(Severity.INFO)
                .setTimestamp(Instant.ofEpochSecond(1000000000L))
                .setObservedTimestamp(Instant.ofEpochSecond(1000000000L))
                .build()),
        Arguments.of(
            TestLogRecordData.builder()
                .setResource(Resource.empty())
                .setInstrumentationScopeInfo(
                    InstrumentationScopeInfo.builder("test-scope").setVersion("1.0.0").build())
                .setBody("Test message")
                .setSeverity(Severity.INFO)
                .setTimestamp(Instant.ofEpochSecond(1000000000L))
                .setObservedTimestamp(Instant.ofEpochSecond(1000000000L))
                .build()));
  }
}
