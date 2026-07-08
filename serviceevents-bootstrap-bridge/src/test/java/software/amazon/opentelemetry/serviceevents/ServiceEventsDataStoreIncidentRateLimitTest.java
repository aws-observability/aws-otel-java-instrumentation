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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for incident snapshot rate limiting and deduplication in {@link
 * ServiceEventsDataStore#recordPotentialIncident}.
 *
 * <p>IMPORTANT: This file MUST use Java 8 compatible syntax only.
 */
class ServiceEventsDataStoreIncidentRateLimitTest {

  private final AtomicInteger emitIncidentCallCount = new AtomicInteger(0);

  @BeforeEach
  void setUp() {
    ServiceEventsDataStore.resetState();
    emitIncidentCallCount.set(0);

    // Install emitter bridge that counts incident emissions
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
            emitIncidentCallCount.incrementAndGet();
          }
        });
  }

  @AfterEach
  void tearDown() {
    ServiceEventsDataStore.setIncidentSnapshotEmitterBridge(null);
    ServiceEventsDataStore.resetState();
    // Restore defaults
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(100);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(1);
  }

  // ========================================================================
  // Global rate limit (maxPerMinute)
  // ========================================================================

  @Test
  void globalRateLimit_blocksExcessIncidents() {
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(3);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(
        100); // high dedup limit so it doesn't interfere

    // Record 5 exception incidents with different routes to avoid dedup
    for (int i = 0; i < 5; i++) {
      recordExceptionIncident("/api/route" + i, "Exception" + i, "msg" + i);
    }

    assertEquals(3, emitIncidentCallCount.get(), "Only maxPerMinute incidents should pass through");
  }

  @Test
  void globalRateLimit_maxPerMinuteOfOne() {
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(1);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(100);

    recordExceptionIncident("/api/a", "ExceptionA", "msgA");
    recordExceptionIncident("/api/b", "ExceptionB", "msgB");

    assertEquals(1, emitIncidentCallCount.get(), "Only 1 incident should pass with maxPerMinute=1");
  }

  // ========================================================================
  // Per-error deduplication (maxSameError)
  // ========================================================================

  @Test
  void dedup_blocksSameErrorExceedingLimit() {
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(100); // high global limit
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(2);

    // Record 5 incidents with identical route + exception (same error hash)
    for (int i = 0; i < 5; i++) {
      recordExceptionIncident("/api/test", "RuntimeException", "same error");
    }

    assertEquals(
        2,
        emitIncidentCallCount.get(),
        "Only maxSameError incidents for the same error hash should pass");
  }

  @Test
  void dedup_differentErrorsAreIndependent() {
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(100);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(2);

    // Record 2 of error A (should all pass)
    recordExceptionIncident("/api/test", "ErrorA", "message A");
    recordExceptionIncident("/api/test", "ErrorA", "message A");
    // Record 2 of error B (should all pass — independent dedup bucket)
    recordExceptionIncident("/api/test", "ErrorB", "message B");
    recordExceptionIncident("/api/test", "ErrorB", "message B");

    assertEquals(
        4,
        emitIncidentCallCount.get(),
        "Different error types should have independent dedup limits");
  }

  @Test
  void dedup_differentRoutesAreIndependent() {
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(100);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(1);

    // Same exception type but different routes
    recordExceptionIncident("/api/users", "RuntimeException", "fail");
    recordExceptionIncident("/api/orders", "RuntimeException", "fail");

    assertEquals(
        2,
        emitIncidentCallCount.get(),
        "Same exception on different routes should be separate dedup buckets");
  }

  // ========================================================================
  // Combined rate limit + dedup
  // ========================================================================

  @Test
  void globalRateLimit_andDedup_combined() {
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(5);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(2);

    // 3 identical errors -> 2 pass (dedup blocks 3rd), but all 3 consume global rate-limit slots
    for (int i = 0; i < 3; i++) {
      recordExceptionIncident("/api/test", "RuntimeException", "same");
    }
    assertEquals(2, emitIncidentCallCount.get(), "Dedup should allow only 2 of 3 identical");

    // 4 different errors -> 2 pass (global slots 4 and 5), then slot 6+ blocked by global limit
    for (int i = 0; i < 4; i++) {
      recordExceptionIncident("/api/route" + i, "Exception" + i, "msg" + i);
    }

    assertEquals(
        4,
        emitIncidentCallCount.get(),
        "Combined: 2 from dedup + 2 from different errors before global limit = 4 total");
  }

  // ========================================================================
  // resetState clears rate-limiting state
  // ========================================================================

  @Test
  void resetState_clearsRateLimitState() {
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(2);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(100);

    recordExceptionIncident("/api/a", "ExceptionA", "msgA");
    recordExceptionIncident("/api/b", "ExceptionB", "msgB");
    assertEquals(2, emitIncidentCallCount.get());

    // This should be blocked
    recordExceptionIncident("/api/c", "ExceptionC", "msgC");
    assertEquals(2, emitIncidentCallCount.get(), "Should be at limit before reset");

    // Reset and re-install bridge
    ServiceEventsDataStore.resetState();
    setUp();

    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(2);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(100);

    recordExceptionIncident("/api/d", "ExceptionD", "msgD");
    assertEquals(1, emitIncidentCallCount.get(), "After reset, rate limit should be cleared");
  }

  // ========================================================================
  // Setter validation
  // ========================================================================

  @Test
  void setters_clampToMinimumOfOne() {
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(0);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(0);

    // maxPerMinute clamped to 1: first incident passes, second blocked
    recordExceptionIncident("/api/a", "ExceptionA", "msgA");
    recordExceptionIncident("/api/b", "ExceptionB", "msgB");
    assertEquals(1, emitIncidentCallCount.get(), "maxPerMinute=0 should be clamped to 1");
  }

  // ========================================================================
  // Helper
  // ========================================================================

  private void recordExceptionIncident(String route, String exceptionType, String message) {
    ServiceEventsDataStore.recordPotentialIncident(
        route,
        "GET",
        500,
        5.0,
        exceptionType,
        message,
        "stack trace",
        Collections.<String, String>emptyMap(),
        Collections.<String, Object>emptyMap(),
        null,
        0L,
        0L,
        null,
        null,
        null);
  }
}
