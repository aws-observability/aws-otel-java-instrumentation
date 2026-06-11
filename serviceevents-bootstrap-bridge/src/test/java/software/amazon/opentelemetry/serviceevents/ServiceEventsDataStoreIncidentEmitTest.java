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

package software.amazon.opentelemetry.serviceevents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ServiceEventsDataStore#recordPotentialIncident} routes to the direct emitter
 * bridge when byte-instrumentation mode is active, bypassing the metadata sidecar writer.
 *
 * <p>Byte-instrumentation activation is resolved from {@code
 * OTEL_AWS_SERVICE_EVENTS_FUNCTION_INSTRUMENT_ENABLED} at class-init time. Rather than fight that,
 * we verify behavior symmetry with whichever mode is active: if bytecode is enabled, the emitter
 * receives the call, the metadata writer does not; if disabled, the inverse.
 */
class ServiceEventsDataStoreIncidentEmitTest {

  private final AtomicInteger writeIncidentCount = new AtomicInteger(0);
  private final AtomicInteger emitIncidentCount = new AtomicInteger(0);
  private final AtomicReference<List<CallPathEntry>> lastCallPath = new AtomicReference<>();
  private final AtomicReference<String> lastSnapshotId = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    ServiceEventsDataStore.resetState();
    writeIncidentCount.set(0);
    emitIncidentCount.set(0);
    lastCallPath.set(null);
    lastSnapshotId.set(null);

    ServiceEventsDataStore.setMetadataWriterBridge(
        new MetadataWriterBridge() {
          @Override
          public void writeIncident(
              String threadName,
              long startTimeNs,
              long endTimeNs,
              String route,
              String method,
              int statusCode,
              double durationMs,
              String triggerType,
              String severity,
              String snapshotId,
              String exceptionType,
              String exceptionMessage,
              String stackTrace,
              String traceId,
              String spanId,
              String operation) {
            writeIncidentCount.incrementAndGet();
          }
        });

    ServiceEventsDataStore.setIncidentSnapshotEmitterBridge(
        new IncidentSnapshotEmitterBridge() {
          @Override
          public void emitIncident(
              String snapshotId,
              String triggerType,
              String severity,
              String threadName,
              long startTimeNs,
              long endTimeNs,
              String route,
              String method,
              String operation,
              int statusCode,
              double durationMs,
              String exceptionType,
              String exceptionMessage,
              String stackTrace,
              String traceId,
              String spanId,
              List<CallPathEntry> callPath) {
            emitIncidentCount.incrementAndGet();
            lastCallPath.set(callPath);
            lastSnapshotId.set(snapshotId);
          }
        });
  }

  @AfterEach
  void tearDown() {
    ServiceEventsDataStore.setMetadataWriterBridge(null);
    ServiceEventsDataStore.setIncidentSnapshotEmitterBridge(null);
    ServiceEventsDataStore.resetState();
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(100);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(1);
  }

  @Test
  void exactlyOnePathReceivesEachIncident() {
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(10);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(10);

    ServiceEventsDataStore.recordPotentialIncident(
        "/api/x",
        "GET",
        500,
        5.0,
        "RuntimeException",
        "boom",
        "stack",
        Collections.<String, String>emptyMap(),
        Collections.<String, Object>emptyMap(),
        null,
        0L,
        0L,
        null,
        null,
        null);

    // Exception incidents always emit synchronously (emitter is always installed)
    assertEquals(1, emitIncidentCount.get(), "Exception incident should use the direct emitter");
    assertEquals(0, writeIncidentCount.get(), "Exception incident must not write to sidecar");
    assertNotNull(lastSnapshotId.get());
    assertTrue(lastSnapshotId.get().startsWith("snap_"));
    assertNotNull(lastCallPath.get(), "Emitter must receive a (possibly empty) call path");
  }

  @Test
  void directEmitterReceivesCapturedCallPathWhenBytecodeEnabled() {
    if (!ServiceEventsDataStore.isBytecodeEnabled()) {
      // Skip: test is only meaningful when bytecode mode is the active path.
      return;
    }
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(10);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(10);

    ServiceEventsDataStore.beginInvestigation();
    try {
      InvestigationData inv = ServiceEventsDataStore.peekInvestigationData();
      inv.addEntry("com.example.Service.handle", null, 1234L, false, false);
      inv.addEntry("com.example.Service.fail", "com.example.Service.handle", 567L, true, false);

      ServiceEventsDataStore.recordPotentialIncident(
          "/api/y",
          "POST",
          500,
          12.0,
          "RuntimeException",
          "boom",
          "stack",
          Collections.<String, String>emptyMap(),
          Collections.<String, Object>emptyMap(),
          null,
          0L,
          0L,
          null,
          null,
          null);

      assertEquals(1, emitIncidentCount.get());
      List<CallPathEntry> path = lastCallPath.get();
      assertNotNull(path);
      assertEquals(2, path.size());
      assertEquals("com.example.Service.handle", path.get(0).getFunctionId());
      assertEquals("com.example.Service.fail", path.get(1).getFunctionId());
      assertTrue(path.get(1).isError());
    } finally {
      ServiceEventsDataStore.clearInvestigation();
    }
  }
}
