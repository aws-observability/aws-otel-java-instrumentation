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
  // Origin-method keying (bounded substitute for the unbounded exception message)
  // ========================================================================

  @Test
  void dedup_sameTypeDifferentOriginMethodsAreIndependent() {
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(100);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(1);

    // Same route + same exception type, but thrown from two different methods → distinct hashes.
    recordExceptionIncidentWithStack(
        "/api/test", "RuntimeException", "boom", "\tat com.example.Foo.alpha(Foo.java:10)\n");
    recordExceptionIncidentWithStack(
        "/api/test", "RuntimeException", "boom", "\tat com.example.Foo.beta(Foo.java:20)\n");

    assertEquals(
        2,
        emitIncidentCallCount.get(),
        "Same type from different origin methods should be independent dedup buckets");
  }

  @Test
  void dedup_sameTypeAndOriginMethodDifferentMessagesCollapse() {
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(100);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(1);

    // Same route + type + origin method, differing only in request-specific message text. The
    // message is NOT part of the key, so these dedup together (the unbounded-key fix).
    recordExceptionIncidentWithStack(
        "/api/test",
        "RuntimeException",
        "user 1 not found",
        "\tat com.example.Foo.lookup(Foo.java:10)\n");
    recordExceptionIncidentWithStack(
        "/api/test",
        "RuntimeException",
        "user 2 not found",
        "\tat com.example.Foo.lookup(Foo.java:10)\n");

    assertEquals(
        1,
        emitIncidentCallCount.get(),
        "Same type + origin method with different messages should dedup together");
  }

  @Test
  void dedup_lineNumberDoesNotAffectHash() {
    ServiceEventsDataStore.setIncidentSnapshotMaxPerMinute(100);
    ServiceEventsDataStore.setIncidentSnapshotMaxSameError(1);

    // Same class.method but a shifted line number (e.g. after an edit above the throw site) must
    // still dedup — the line is deliberately excluded so a recurring bug doesn't re-fire per
    // deploy.
    recordExceptionIncidentWithStack(
        "/api/test", "RuntimeException", "boom", "\tat com.example.Foo.lookup(Foo.java:10)\n");
    recordExceptionIncidentWithStack(
        "/api/test", "RuntimeException", "boom", "\tat com.example.Foo.lookup(Foo.java:99)\n");

    assertEquals(
        1, emitIncidentCallCount.get(), "A shifted line number must not change the dedup hash");
  }

  @Test
  void extractOriginMethod_parsesTopFrameClassAndMethod() {
    String stack =
        "java.lang.RuntimeException: boom\n"
            + "\tat com.example.Service.handle(Service.java:42)\n"
            + "\tat com.example.Controller.route(Controller.java:7)\n";
    assertEquals("com.example.Service.handle", IncidentRateLimiter.extractOriginMethod(stack));
  }

  @Test
  void extractOriginMethod_ignoresHeaderMessageContainingAtToken() {
    // The header line contains "at " inside the message; the parser must skip it and match the
    // frame.
    String stack =
        "java.lang.IllegalStateException: failed at startup\n"
            + "\tat com.example.Boot.start(Boot.java:1)\n";
    assertEquals("com.example.Boot.start", IncidentRateLimiter.extractOriginMethod(stack));
  }

  @Test
  void extractOriginMethod_ignoresMultiLineMessageWithTruncatedFrameFragment() {
    // A wrapped exception whose MESSAGE embeds a truncated frame fragment ("\tat evil.pwn(") must
    // not hijack the parse: the fragment lacks the full "(...)"-to-line-end grammar, so the parser
    // skips it and matches the real top frame. Guards against request-specific text in the message
    // re-inflating the dedup key.
    String stack =
        "java.lang.RuntimeException: db failed\n"
            + "\tat evil.Injected.pwn(\n"
            + "\tat com.example.Real.throwHere(Real.java:10)\n";
    assertEquals("com.example.Real.throwHere", IncidentRateLimiter.extractOriginMethod(stack));
  }

  @Test
  void extractOriginMethod_parsesConstructorAndStaticInitializerFrames() {
    // Constructor (<init>) and static-initializer (<clinit>) throw sites are real top frames; the
    // grammar must accept the angle-bracketed names, not skip past them to the caller.
    assertEquals(
        "com.example.Foo.<init>",
        IncidentRateLimiter.extractOriginMethod(
            "java.lang.IllegalArgumentException: bad\n"
                + "\tat com.example.Foo.<init>(Foo.java:10)\n"
                + "\tat com.example.Handler.build(Handler.java:5)\n"));
    assertEquals(
        "com.example.Foo.<clinit>",
        IncidentRateLimiter.extractOriginMethod(
            "X: y\n\tat com.example.Foo.<clinit>(Foo.java:3)\n"));
  }

  @Test
  void extractOriginMethod_parsesModulePrefixedAndLambdaFrames() {
    // Module-prefixed frames (java.base/...) and synthetic lambda methods are real JVM frame shapes
    // the tightened grammar must still accept.
    assertEquals(
        "java.base/java.lang.Thread.run",
        IncidentRateLimiter.extractOriginMethod(
            "X: y\n\tat java.base/java.lang.Thread.run(Thread.java:829)\n"));
    assertEquals(
        "com.example.Svc.lambda$doWork$0",
        IncidentRateLimiter.extractOriginMethod(
            "X: y\n\tat com.example.Svc.lambda$doWork$0(Svc.java:42)\n"));
  }

  @Test
  void extractOriginMethod_returnsEmptyForNullOrUnparseable() {
    assertEquals("", IncidentRateLimiter.extractOriginMethod(null));
    assertEquals("", IncidentRateLimiter.extractOriginMethod(""));
    assertEquals("", IncidentRateLimiter.extractOriginMethod("no frames here"));
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
    recordExceptionIncidentWithStack(route, exceptionType, message, "stack trace");
  }

  private void recordExceptionIncidentWithStack(
      String route, String exceptionType, String message, String stackTrace) {
    ServiceEventsDataStore.recordPotentialIncident(
        route,
        "GET",
        500,
        5.0,
        exceptionType,
        message,
        stackTrace,
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
