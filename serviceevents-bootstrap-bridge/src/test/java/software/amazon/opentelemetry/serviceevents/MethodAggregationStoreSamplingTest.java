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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests ServiceEvents sampling: mode dispatch (always/auto/never), tier thresholds, and the
 * never-mode call_path capture. Mirrors Python/JS sampling semantics (see python_monitor_impl.py
 * and serviceevents-monitor.ts).
 */
class MethodAggregationStoreSamplingTest {

  @BeforeEach
  @AfterEach
  void resetState() {
    // Full reset: clears sampling config, counters, call stack, and investigation data so the
    // never-mode call_path tests below start from a clean thread-local state.
    ServiceEventsDataStore.resetState();
  }

  // ───── Mode dispatch ─────────────────────────────────────────────────

  @Test
  void mode_always_samplesEveryCall() {
    ServiceEventsDataStore.setSamplingMode("always");
    for (long n : new long[] {1, 100, 101, 1000, 1001, 100_000}) {
      assertTrue(MethodAggregationStore.shouldSample(n), "always: callCount=" + n);
    }
  }

  @Test
  void mode_never_samplesNothing() {
    ServiceEventsDataStore.setSamplingMode("never");
    for (long n : new long[] {1, 50, 100, 1000, 100_000}) {
      assertFalse(MethodAggregationStore.shouldSample(n), "never: callCount=" + n);
    }
  }

  @Test
  void mode_invalidValue_silentlyIgnored() {
    // Default is "always"; invalid modes leave it unchanged. The removed "adaptive" mode is now
    // just another invalid value — this doubles as the hard-removal regression check.
    ServiceEventsDataStore.setSamplingMode("BOGUS");
    assertEquals("always", MethodAggregationStore.samplingMode);
    ServiceEventsDataStore.setSamplingMode("adaptive");
    assertEquals("always", MethodAggregationStore.samplingMode);
    ServiceEventsDataStore.setSamplingMode(null);
    assertEquals("always", MethodAggregationStore.samplingMode);
  }

  @Test
  void mode_caseInsensitive() {
    ServiceEventsDataStore.setSamplingMode("ALWAYS");
    assertTrue(MethodAggregationStore.shouldSample(99_999));
  }

  // ───── Tiered sampling (auto mode) ───────────────────────────────────

  @Test
  void auto_tier1_belowThresholdAlwaysSampled() {
    ServiceEventsDataStore.setSamplingMode("auto");
    for (long n = 1; n <= 100; n++) {
      assertTrue(MethodAggregationStore.shouldSample(n), "tier1: " + n);
    }
  }

  @Test
  void auto_tier2_oneInTenSampled() {
    ServiceEventsDataStore.setSamplingMode("auto");
    // tier1 = 100, tier2 = 1000, tier2Rate = 10
    assertTrue(MethodAggregationStore.shouldSample(110));
    assertFalse(MethodAggregationStore.shouldSample(111));
    assertFalse(MethodAggregationStore.shouldSample(119));
    assertTrue(MethodAggregationStore.shouldSample(120));
    assertTrue(MethodAggregationStore.shouldSample(1000));
  }

  @Test
  void auto_tier3_oneInHundredSampled() {
    ServiceEventsDataStore.setSamplingMode("auto");
    // tier3 = anything > 1000, rate = 100
    assertTrue(MethodAggregationStore.shouldSample(1100));
    assertFalse(MethodAggregationStore.shouldSample(1101));
    assertFalse(MethodAggregationStore.shouldSample(1199));
    assertTrue(MethodAggregationStore.shouldSample(1200));
  }

  @Test
  void thresholds_setterUpdatesTiers() {
    ServiceEventsDataStore.setSamplingMode("auto");
    ServiceEventsDataStore.setSamplingThresholds(5, 20, 4, 50);
    assertTrue(MethodAggregationStore.shouldSample(5)); // tier1
    assertFalse(MethodAggregationStore.shouldSample(6)); // tier2 first call
    assertTrue(MethodAggregationStore.shouldSample(8)); // tier2: 8 % 4 == 0
  }

  @Test
  void thresholds_nonPositiveIgnored() {
    int origTier1 = (int) MethodAggregationStore.sampleTier1Threshold;
    ServiceEventsDataStore.setSamplingThresholds(0, -1, 0, 0);
    assertEquals(origTier1, (int) MethodAggregationStore.sampleTier1Threshold);
  }

  @Test
  void auto_zeroRate_samplesNoneInThatTierWithoutCrashing() {
    // The public setter rejects non-positive rates, but the internal test-config hook (and direct
    // field writes) don't validate, so shouldSample must not divide by zero. A zero rate degrades
    // to "sample none in this tier" — mirrors Python/JS, which guard the modulo the same way.
    ServiceEventsDataStore.setSamplingMode("auto");
    MethodAggregationStore.sampleTier2Rate = 0;
    MethodAggregationStore.sampleTier3Rate = 0;

    // tier1 still samples everything up to its threshold (unaffected by the rates).
    assertTrue(MethodAggregationStore.shouldSample(1));
    // tier2 (100 < n <= 1000) and tier3 (n > 1000): zero rate → no crash, sample none.
    assertFalse(MethodAggregationStore.shouldSample(500));
    assertFalse(MethodAggregationStore.shouldSample(5000));
  }

  // ───── never-mode call_path capture (Python/JS parity) ───────────────

  @Test
  void never_recordsCallPathWithZeroDuration() {
    ServiceEventsDataStore.setSamplingMode("never");
    ServiceEventsDataStore.beginInvestigation();
    ServiceEventsDataStore.setCurrentOperation("GET /api/op");

    // A single instrumented call under "never": no duration metric is recorded, but the call_path
    // entry must still be captured (durationNs == 0) so incident snapshots retain who-called-whom.
    Object ctx = ServiceEventsDataStore.methodEnter("com.example.Svc.handle");
    ServiceEventsDataStore.methodExit("com.example.Svc.handle", ctx, null);

    InvestigationData inv = ServiceEventsDataStore.peekInvestigationData();
    assertNotNull(inv);
    List<CallPathEntry> path = inv.getCallPath();
    assertEquals(1, path.size());
    CallPathEntry entry = path.get(0);
    assertEquals("com.example.Svc.handle", entry.functionId);
    assertNull(entry.caller);
    assertEquals(0L, entry.durationNs);
    assertFalse(entry.error);

    // The duration metric path is sampling-gated, so the aggregation map stays empty under "never".
    assertNull(MethodAggregationStore.getAndSwapAggregations());
  }

  @Test
  void never_nestedCallsPreserveCallerAttribution() {
    ServiceEventsDataStore.setSamplingMode("never");
    ServiceEventsDataStore.beginInvestigation();
    ServiceEventsDataStore.setCurrentOperation("GET /api/op");

    // A -> B, both unsampled. The call stack is still maintained for every frame, so B resolves its
    // caller to A even though neither call produced a duration metric.
    Object ctxA = ServiceEventsDataStore.methodEnter("A");
    Object ctxB = ServiceEventsDataStore.methodEnter("B");
    ServiceEventsDataStore.methodExit("B", ctxB, null);
    ServiceEventsDataStore.methodExit("A", ctxA, null);

    List<CallPathEntry> path = ServiceEventsDataStore.peekInvestigationData().getCallPath();
    assertEquals(2, path.size());
    // B exits first (caller A), then A (no caller).
    assertEquals("B", path.get(0).functionId);
    assertEquals("A", path.get(0).caller);
    assertEquals(0L, path.get(0).durationNs);
    assertEquals("A", path.get(1).functionId);
    assertNull(path.get(1).caller);
  }

  @Test
  void never_errorEntryFlaggedInCallPath() {
    ServiceEventsDataStore.setSamplingMode("never");
    ServiceEventsDataStore.beginInvestigation();
    ServiceEventsDataStore.setCurrentOperation("GET /api/op");

    // An exception on an unsampled call still flags the call_path entry as an error.
    Object ctx = ServiceEventsDataStore.methodEnter("com.example.Svc.boom");
    ServiceEventsDataStore.methodExit("com.example.Svc.boom", ctx, "java.lang.RuntimeException");

    List<CallPathEntry> path = ServiceEventsDataStore.peekInvestigationData().getCallPath();
    assertEquals(1, path.size());
    assertTrue(path.get(0).error);
    assertEquals(0L, path.get(0).durationNs);
  }

  // ───── active-count gate on the methodExit call_path lookup (JS parity) ──

  @Test
  void noInvestigation_methodExitDoesNotCaptureCallPath() {
    // With no beginInvestigation(), the active-count gate keeps methodExit from recording anything.
    // (beginInvestigation also short-circuits on a null ThreadLocal, but the gate is what lets the
    // hot path skip the ThreadLocal lookup entirely — this asserts the no-capture behavior.)
    ServiceEventsDataStore.setSamplingMode("always");
    ServiceEventsDataStore.setCurrentOperation("GET /api/op");

    Object ctx = ServiceEventsDataStore.methodEnter("com.example.Svc.handle");
    ServiceEventsDataStore.methodExit("com.example.Svc.handle", ctx, null);

    assertNull(ServiceEventsDataStore.peekInvestigationData());
  }

  @Test
  void investigationClearedMidFlight_subsequentCallsDoNotCapture() {
    // After the investigation is cleared, the active-count returns to zero, so a stray instrumented
    // call exiting on the same thread (e.g. cleanup code) records nothing — no resurrected data.
    ServiceEventsDataStore.setSamplingMode("always");
    ServiceEventsDataStore.beginInvestigation();
    ServiceEventsDataStore.setCurrentOperation("GET /api/op");

    Object ctx1 = ServiceEventsDataStore.methodEnter("com.example.Svc.handle");
    ServiceEventsDataStore.methodExit("com.example.Svc.handle", ctx1, null);
    assertEquals(1, ServiceEventsDataStore.peekInvestigationData().getCallPath().size());

    ServiceEventsDataStore.clearInvestigation();

    // A late call after clear must not re-create or append to investigation data.
    Object ctx2 = ServiceEventsDataStore.methodEnter("com.example.Svc.cleanup");
    ServiceEventsDataStore.methodExit("com.example.Svc.cleanup", ctx2, null);
    assertNull(ServiceEventsDataStore.peekInvestigationData());
  }
}
