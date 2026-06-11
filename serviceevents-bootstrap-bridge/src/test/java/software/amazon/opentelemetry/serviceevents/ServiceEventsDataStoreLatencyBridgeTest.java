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

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * Verifies that {@code ServiceEventsDataStore.recordPotentialIncident} consults {@link
 * LatencyThresholdBridge} when installed and falls back to the global threshold when absent.
 */
class ServiceEventsDataStoreLatencyBridgeTest {

  private final AtomicInteger incidentCount = new AtomicInteger(0);
  private Double originalDurationThreshold;

  @BeforeEach
  void setUp() throws Exception {
    ServiceEventsDataStore.resetState();
    incidentCount.set(0);
    originalDurationThreshold = getStaticField("INCIDENT_DURATION_THRESHOLD_MS", Double.class);
    setStaticField("INCIDENT_DURATION_THRESHOLD_MS", 5000.0);

    // Install emitter bridge so incidents emit synchronously
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
              java.util.List<CallPathEntry> callPath) {
            incidentCount.incrementAndGet();
          }
        });

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
              String operation) {}
        });
  }

  @AfterEach
  void tearDown() throws Exception {
    ServiceEventsDataStore.setMetadataWriterBridge(null);
    ServiceEventsDataStore.setIncidentSnapshotEmitterBridge(null);
    ServiceEventsDataStore.setLatencyThresholdBridge(null);
    setStaticField("INCIDENT_DURATION_THRESHOLD_MS", originalDurationThreshold);
    ServiceEventsDataStore.resetState();
  }

  @Test
  void noBridge_usesGlobalThreshold() {
    // No bridge installed. Global threshold is 5000ms. Request at 4000ms should NOT trigger.
    recordLatencyRequest("GET", "/api/slow", 4000.0);
    assertEquals(0, incidentCount.get());

    // Request at 6000ms SHOULD trigger.
    recordLatencyRequest("GET", "/api/slow", 6000.0);
    assertEquals(1, incidentCount.get());
  }

  @Test
  void bridgeReturnsOverride_usesOverride() {
    // Install a bridge that returns 100ms for GET /health, and NaN otherwise.
    ServiceEventsDataStore.setLatencyThresholdBridge(
        (method, route) -> "GET".equals(method) && "/health".equals(route) ? 100.0 : Double.NaN);

    // /health at 200ms triggers under the 100ms override.
    recordLatencyRequest("GET", "/health", 200.0);
    assertEquals(1, incidentCount.get());

    // /other at 200ms falls back to the 5000ms global (well above 200ms) → no trigger.
    recordLatencyRequest("GET", "/other", 200.0);
    assertEquals(1, incidentCount.get());

    // /other at 6000ms triggers under the global.
    recordLatencyRequest("GET", "/other", 6000.0);
    assertEquals(2, incidentCount.get());
  }

  @Test
  void bridgeReturnsNaN_fallsBackToGlobal() {
    ServiceEventsDataStore.setLatencyThresholdBridge((method, route) -> Double.NaN);

    // 4000ms below the 5000ms global → no trigger.
    recordLatencyRequest("GET", "/anything", 4000.0);
    assertEquals(0, incidentCount.get());
    // 6000ms above → trigger.
    recordLatencyRequest("GET", "/anything", 6000.0);
    assertEquals(1, incidentCount.get());
  }

  @Test
  void bridgeReturnsZero_fallsBackToGlobal() {
    // Non-positive overrides are ignored (defensive): treat as "no match".
    ServiceEventsDataStore.setLatencyThresholdBridge((method, route) -> 0.0);
    recordLatencyRequest("GET", "/anything", 4000.0);
    assertEquals(0, incidentCount.get());
  }

  private void recordLatencyRequest(String method, String route, double durationMs) {
    ServiceEventsDataStore.recordPotentialIncident(
        route,
        method,
        200,
        durationMs,
        null,
        null,
        null,
        Collections.<String, String>emptyMap(),
        Collections.<String, Object>emptyMap(),
        "test-thread",
        0L,
        0L,
        null,
        null,
        method + " " + route);
  }

  // ========================================================================
  // Unsafe-based helpers for modifying static final fields
  // ========================================================================

  private static final Unsafe UNSAFE;

  static {
    try {
      Field f = Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      UNSAFE = (Unsafe) f.get(null);
    } catch (Exception e) {
      throw new RuntimeException("Cannot obtain Unsafe instance", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T getStaticField(String fieldName, Class<T> type) throws Exception {
    Field field = ServiceEventsDataStore.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (T) field.get(null);
  }

  private static void setStaticField(String fieldName, Object value) throws Exception {
    Field field = ServiceEventsDataStore.class.getDeclaredField(fieldName);
    Object base = UNSAFE.staticFieldBase(field);
    long offset = UNSAFE.staticFieldOffset(field);
    if (value instanceof Double) {
      UNSAFE.putDouble(base, offset, (Double) value);
    } else {
      UNSAFE.putObject(base, offset, value);
    }
  }
}
