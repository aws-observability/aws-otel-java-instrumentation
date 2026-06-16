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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.IncidentMetadata;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.IncidentSnapshotRecordBuilder;
import software.amazon.opentelemetry.serviceevents.CallPathEntry;

class IncidentSnapshotEmitterTest {

  private IncidentSnapshotRecordBuilder newBuilder() {
    return new IncidentSnapshotRecordBuilder(
        "svc", "prod", "deploy-1", "2026-04-17T00:00:00Z", "", "abc123", "", 42L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void emitIncident_buildsRecordWithPythonCompatibleCallPath() {
    AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
    AtomicReference<IncidentMetadata> capturedMeta = new AtomicReference<>();

    ServiceEventsOtlpEmitter otlp =
        new ServiceEventsOtlpEmitter(null, null, null, "deploy-1", "abc123", "", "") {
          @Override
          public void emitIncidentSnapshot(Map<String, Object> record, IncidentMetadata incident) {
            captured.set(record);
            capturedMeta.set(incident);
          }
        };

    IncidentSnapshotEmitter emitter = new IncidentSnapshotEmitter(newBuilder(), otlp);

    List<CallPathEntry> callPath =
        Arrays.asList(
            new CallPathEntry("com.example.Svc.handle", null, 1000L, false, false),
            new CallPathEntry("com.example.Svc.fail", "com.example.Svc.handle", 500L, true, false));

    emitter.emitIncident(
        "snap_abc",
        "exception",
        "critical",
        "thread-1",
        1_000_000L,
        2_000_000L,
        "/api/x",
        "GET",
        "GET /api/x",
        500,
        1.23,
        "RuntimeException",
        "boom",
        "stack",
        "0123456789abcdef0123456789abcdef",
        "0123456789abcdef",
        callPath);

    Map<String, Object> rec = captured.get();
    assertNotNull(rec, "emitIncidentSnapshot should be invoked");

    List<Map<String, Object>> exInfo = (List<Map<String, Object>>) rec.get("exception_info");
    List<Map<String, Object>> emittedPath =
        (List<Map<String, Object>>) exInfo.get(0).get("call_path");
    assertEquals(2, emittedPath.size());
    Map<String, Object> first = emittedPath.get(0);
    assertEquals("com.example.Svc.handle", first.get("function_name"));
    // Null caller (outermost frame) is serialized as empty string because the downstream
    // OTLP conversion (ArrayDeque-based mapToValue) cannot accept nulls.
    assertEquals("", first.get("caller_function_name"));
    assertEquals(1000L, first.get("duration_ns"));
    assertEquals(false, first.get("error"));
    assertEquals(false, first.get("is_async"));
    assertEquals(true, emittedPath.get(1).get("error"));
    // Fully-timed call path (no zero durations) => not partial, every duration_ns survives.
    assertEquals(500L, emittedPath.get(1).get("duration_ns"));

    IncidentMetadata meta = capturedMeta.get();
    assertNotNull(meta);
    assertEquals("snap_abc", meta.snapshotId);
    assertEquals("GET /api/x", meta.operation);
    assertFalse(meta.isPartial, "fully-timed snapshot must report is_partial=false");
  }

  @Test
  @SuppressWarnings("unchecked")
  void emitIncident_partialCallPathStripsZeroDurations() {
    // A snapshot mixing a sampled frame (real duration) with an unsampled frame / truncation
    // sentinel (durationNs == 0) is partial: is_partial must be true, the zero duration_ns must
    // be omitted (it reads as misleading "instantaneous"), and the real timing must survive.
    // Mirrors the Python/JS to_dict strip — this is the cross-SDK wire-parity guarantee.
    AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
    AtomicReference<IncidentMetadata> capturedMeta = new AtomicReference<>();
    ServiceEventsOtlpEmitter otlp =
        new ServiceEventsOtlpEmitter(null, null, null, "deploy-1", "abc123", "", "") {
          @Override
          public void emitIncidentSnapshot(Map<String, Object> record, IncidentMetadata incident) {
            captured.set(record);
            capturedMeta.set(incident);
          }
        };
    IncidentSnapshotEmitter emitter = new IncidentSnapshotEmitter(newBuilder(), otlp);

    List<CallPathEntry> callPath =
        Arrays.asList(
            new CallPathEntry("com.example.Svc.handle", null, 1000L, false, false),
            // Unsampled frame: durationNs == 0.
            new CallPathEntry("com.example.Svc.mid", "com.example.Svc.handle", 0L, false, false),
            new CallPathEntry("com.example.Svc.fail", "com.example.Svc.mid", 700L, true, false));

    emitter.emitIncident(
        "snap_partial",
        "exception",
        "critical",
        "thread-1",
        1_000_000L,
        2_000_000L,
        "/api/x",
        "GET",
        "GET /api/x",
        500,
        1.23,
        "RuntimeException",
        "boom",
        "stack",
        "0123456789abcdef0123456789abcdef",
        "0123456789abcdef",
        callPath);

    IncidentMetadata meta = capturedMeta.get();
    assertNotNull(meta);
    assertTrue(
        meta.isPartial, "a call path with a zero-duration frame must report is_partial=true");

    List<Map<String, Object>> exInfo =
        (List<Map<String, Object>>) captured.get().get("exception_info");
    List<Map<String, Object>> path = (List<Map<String, Object>>) exInfo.get(0).get("call_path");
    assertEquals(3, path.size());
    // Real timings survive; the zero-duration frame omits duration_ns entirely.
    assertEquals(1000L, path.get(0).get("duration_ns"));
    assertFalse(path.get(1).containsKey("duration_ns"), "zero duration_ns must be stripped");
    assertEquals(700L, path.get(2).get("duration_ns"));
  }

  @Test
  void emitIncident_nullCallPathYieldsEmptyList() {
    AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
    ServiceEventsOtlpEmitter otlp =
        new ServiceEventsOtlpEmitter(null, null, null, "deploy-1", "abc123", "", "") {
          @Override
          public void emitIncidentSnapshot(Map<String, Object> record, IncidentMetadata incident) {
            captured.set(record);
          }
        };
    IncidentSnapshotEmitter emitter = new IncidentSnapshotEmitter(newBuilder(), otlp);

    emitter.emitIncident(
        "snap_x",
        "latency",
        "high",
        "t",
        0L,
        1L,
        "/r",
        "GET",
        "GET /r",
        504,
        100.0,
        null,
        null,
        null,
        null,
        null,
        Collections.<CallPathEntry>emptyList());

    Map<String, Object> rec = captured.get();
    assertNotNull(rec);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> exInfo = (List<Map<String, Object>>) rec.get("exception_info");
    // No exception type => exception_info is empty
    assertTrue(exInfo.isEmpty());
  }
}
