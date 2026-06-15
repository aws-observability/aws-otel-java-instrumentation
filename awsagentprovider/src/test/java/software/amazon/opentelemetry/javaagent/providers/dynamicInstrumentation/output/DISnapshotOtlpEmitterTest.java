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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.output;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.CapturedContext;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.CapturedValue;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.Captures;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.Snapshot;

/** Unit tests for {@link DISnapshotOtlpEmitter}. */
class DISnapshotOtlpEmitterTest {

  private InMemoryLogRecordExporter logExporter;
  private SdkLoggerProvider loggerProvider;
  private DISnapshotOtlpEmitter emitter;

  @BeforeEach
  void setUp() {
    logExporter = InMemoryLogRecordExporter.create();

    Resource resource =
        Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "test-service"));

    loggerProvider =
        SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter))
            .build();

    emitter = new DISnapshotOtlpEmitter(loggerProvider);
  }

  @AfterEach
  void tearDown() {
    loggerProvider.shutdown();
  }

  @Test
  void testMethodLevelSnapshot_attributesAndBody() {
    Map<String, CapturedValue> args = new HashMap<>();
    args.put("arg0", CapturedValue.ofPrimitive("java.lang.Integer", "10"));
    args.put("arg1", CapturedValue.ofPrimitive("java.lang.Integer", "5"));

    Captures captures =
        Captures.builder()
            .entry(CapturedContext.builder().arguments(args).build())
            .methodReturn(
                CapturedContext.builder()
                    .returnValue(CapturedValue.ofPrimitive("java.lang.Integer", "15"))
                    .build())
            .build();

    Snapshot snapshot =
        Snapshot.create(
            "test-key",
            "com.example",
            "com.example.Calculator",
            "add",
            "Calculator.java",
            0, // method-level
            "hash123",
            5L,
            "aabb00112233445566778899aabbccdd",
            "1122334455667788",
            captures);

    InstrumentationConfiguration config = createApiConfig("PROBE", 0);

    emitter.emitSnapshot(snapshot, config);

    List<LogRecordData> records = logExporter.getFinishedLogRecordItems();
    assertEquals(1, records.size());

    LogRecordData record = records.get(0);

    // Verify attributes
    assertEquals(
        "aws.dynamic_instrumentation.snapshot",
        record.getAttributes().get(AttributeKey.stringKey("event.name")));
    assertNotNull(record.getAttributes().get(AttributeKey.stringKey("aws.di.snapshot_id")));
    assertEquals(
        "hash123", record.getAttributes().get(AttributeKey.stringKey("aws.di.location_hash")));
    assertEquals(
        "method",
        record.getAttributes().get(AttributeKey.stringKey("aws.di.instrumentation_level")));
    assertEquals(5L, record.getAttributes().get(AttributeKey.longKey("aws.di.duration_ms")));
    assertEquals(
        "com.example", record.getAttributes().get(AttributeKey.stringKey("aws.di.code_unit")));
    assertEquals(
        "com.example.Calculator",
        record.getAttributes().get(AttributeKey.stringKey("aws.di.class_name")));
    assertEquals("add", record.getAttributes().get(AttributeKey.stringKey("aws.di.method_name")));
    assertEquals(
        "com/example/Calculator.java",
        record.getAttributes().get(AttributeKey.stringKey("aws.di.file_path")));
    assertEquals(
        "PROBE", record.getAttributes().get(AttributeKey.stringKey("aws.di.instrumentation_type")));

    // line_number should NOT be present for method-level
    assertNull(record.getAttributes().get(AttributeKey.longKey("aws.di.line_number")));

    // Verify trace context
    assertEquals("aabb00112233445566778899aabbccdd", record.getSpanContext().getTraceId());
    assertEquals("1122334455667788", record.getSpanContext().getSpanId());

    // Verify body is structured (not null)
    assertNotNull(record.getBodyValue());
  }

  @Test
  void testLineLevelSnapshot_hasLineNumber() {
    Map<String, CapturedValue> locals = new HashMap<>();
    locals.put("x", CapturedValue.ofPrimitive("int", "42"));
    locals.put("result", CapturedValue.ofPrimitive("int", "84"));

    Captures captures =
        Captures.builder().addLine(98, CapturedContext.builder().locals(locals).build()).build();

    Snapshot snapshot =
        Snapshot.create(
            "test-key",
            "com.example",
            "com.example.Calculator",
            "compute",
            "Calculator.java",
            98, // line-level
            "hash456",
            null, // no duration for line-level
            null,
            null,
            captures);

    InstrumentationConfiguration config = createApiConfig("BREAKPOINT", 98);

    emitter.emitSnapshot(snapshot, config);

    List<LogRecordData> records = logExporter.getFinishedLogRecordItems();
    assertEquals(1, records.size());

    LogRecordData record = records.get(0);

    // Verify line-level attributes
    assertEquals(
        "line", record.getAttributes().get(AttributeKey.stringKey("aws.di.instrumentation_level")));
    assertEquals(98L, record.getAttributes().get(AttributeKey.longKey("aws.di.line_number")));
    assertEquals(
        "BREAKPOINT",
        record.getAttributes().get(AttributeKey.stringKey("aws.di.instrumentation_type")));

    // duration should NOT be present for line-level
    assertNull(record.getAttributes().get(AttributeKey.longKey("aws.di.duration_ms")));

    // No trace context
    assertFalse(record.getSpanContext().isValid());
  }

  @Test
  void testDeriveFilePath_simpleClass() {
    assertEquals(
        "com/example/Calculator.java",
        DISnapshotOtlpEmitter.deriveFilePath("com.example.Calculator"));
  }

  @Test
  void testDeriveFilePath_innerClass() {
    assertEquals(
        "com/example/Foo.java", DISnapshotOtlpEmitter.deriveFilePath("com.example.Foo$Bar"));
  }

  @Test
  void testDeriveFilePath_nestedInnerClass() {
    assertEquals(
        "com/example/Foo.java", DISnapshotOtlpEmitter.deriveFilePath("com.example.Foo$Bar$Baz"));
  }

  @Test
  void testDeriveFilePath_nullOrEmpty() {
    assertNull(DISnapshotOtlpEmitter.deriveFilePath(null));
    assertNull(DISnapshotOtlpEmitter.deriveFilePath(""));
  }

  @Test
  void testDeriveFilePath_defaultPackage() {
    assertEquals("MyClass.java", DISnapshotOtlpEmitter.deriveFilePath("MyClass"));
  }

  @Test
  void testSnapshotWithObjectCapture_bodyStructure() {
    Map<String, CapturedValue> personFields = new HashMap<>();
    personFields.put("name", CapturedValue.ofString("Alice", false, 5));
    personFields.put("age", CapturedValue.ofPrimitive("java.lang.Integer", "30"));

    Map<String, CapturedValue> args = new HashMap<>();
    args.put("person", CapturedValue.ofObject("com.example.Person", personFields));

    Captures captures =
        Captures.builder().entry(CapturedContext.builder().arguments(args).build()).build();

    Snapshot snapshot =
        Snapshot.create(
            "test-key",
            "com.example",
            "com.example.Service",
            "process",
            "Service.java",
            0,
            "hash789",
            2L,
            null,
            null,
            captures);

    emitter.emitSnapshot(snapshot, createApiConfig("PROBE", 0));

    List<LogRecordData> records = logExporter.getFinishedLogRecordItems();
    assertEquals(1, records.size());

    // Verify body value is present and structured
    Value<?> body = records.get(0).getBodyValue();
    assertNotNull(body);
  }

  @Test
  void testSnapshotWithNullConfig_noInstrumentationType() {
    Captures captures =
        Captures.builder()
            .entry(
                CapturedContext.builder()
                    .arguments(Map.of("x", CapturedValue.ofPrimitive("int", "1")))
                    .build())
            .build();

    Snapshot snapshot =
        Snapshot.create(
            "test-key",
            "com.example",
            "com.example.Foo",
            "bar",
            "Foo.java",
            0,
            "hash000",
            0L,
            null,
            null,
            captures);

    // Emit with null config — should not throw
    emitter.emitSnapshot(snapshot, null);

    List<LogRecordData> records = logExporter.getFinishedLogRecordItems();
    assertEquals(1, records.size());

    // instrumentation_type should not be set
    assertNull(
        records.get(0).getAttributes().get(AttributeKey.stringKey("aws.di.instrumentation_type")));
  }

  /**
   * Helper to create an InstrumentationConfiguration from a minimal API config map. Uses the
   * standard fromApiConfig() factory method.
   */
  private static InstrumentationConfiguration createApiConfig(
      String instrumentationType, int lineNumber) {
    Map<String, Object> apiConfig = new HashMap<>();

    Map<String, Object> location = new HashMap<>();
    location.put("CodeUnit", "com.example");
    location.put("ClassName", "com.example.Calculator");
    location.put("MethodName", "add");
    location.put("LineNumber", lineNumber);
    location.put("Language", "java");

    Map<String, Object> locationWrapper = new HashMap<>();
    locationWrapper.put("CodeLocation", location);
    apiConfig.put("Location", locationWrapper);

    apiConfig.put("LocationHash", "hash123");
    apiConfig.put("InstrumentationType", instrumentationType);

    Map<String, Object> captureWrapper = new HashMap<>();
    captureWrapper.put("CodeCapture", Map.of());
    apiConfig.put("CaptureConfiguration", captureWrapper);

    return InstrumentationConfiguration.fromApiConfig(apiConfig);
  }
}
