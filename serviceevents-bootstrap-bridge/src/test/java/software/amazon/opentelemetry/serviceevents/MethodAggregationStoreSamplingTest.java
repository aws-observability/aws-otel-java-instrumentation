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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests adaptive sampling: mode dispatch, tier thresholds, hot-operation lifecycle. Mirrors
 * Python/JS sampling semantics (see python_monitor_impl.py and serviceevents-monitor.ts).
 */
class MethodAggregationStoreSamplingTest {

  @BeforeEach
  @AfterEach
  void resetState() {
    MethodAggregationStore.resetState();
    ServiceEventsDataStore.setCurrentOperation(null);
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
    // Default is "adaptive"; invalid mode shouldn't change it.
    ServiceEventsDataStore.setSamplingMode("BOGUS");
    assertEquals("adaptive", MethodAggregationStore.samplingMode);
    ServiceEventsDataStore.setSamplingMode(null);
    assertEquals("adaptive", MethodAggregationStore.samplingMode);
  }

  @Test
  void mode_caseInsensitive() {
    ServiceEventsDataStore.setSamplingMode("ALWAYS");
    assertTrue(MethodAggregationStore.shouldSample(99_999));
  }

  // ───── Tiered sampling (auto + adaptive when not hot) ────────────────

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
    ServiceEventsDataStore.setSamplingThresholds(5, 20, 4, 50, 7);
    assertTrue(MethodAggregationStore.shouldSample(5)); // tier1
    assertFalse(MethodAggregationStore.shouldSample(6)); // tier2 first call
    assertTrue(MethodAggregationStore.shouldSample(8)); // tier2: 8 % 4 == 0
    assertEquals(7, MethodAggregationStore.hotEndpointCycles);
  }

  @Test
  void thresholds_nonPositiveIgnored() {
    int origTier1 = (int) MethodAggregationStore.sampleTier1Threshold;
    ServiceEventsDataStore.setSamplingThresholds(0, -1, 0, 0, -5);
    assertEquals(origTier1, (int) MethodAggregationStore.sampleTier1Threshold);
  }

  // ───── Adaptive mode + hot-operation lifecycle ───────────────────────

  @Test
  void adaptive_hotOperation_samplesEvenInTier3() {
    ServiceEventsDataStore.setSamplingMode("adaptive");
    ServiceEventsDataStore.markOperationHot("GET /api/checkout");
    ServiceEventsDataStore.setCurrentOperation("GET /api/checkout");

    // Tier3 territory — would normally only sample 1 in 100.
    assertTrue(MethodAggregationStore.shouldSample(1101));
    assertTrue(MethodAggregationStore.shouldSample(50_000));
  }

  @Test
  void adaptive_coldOperation_fallsBackToTieredSampling() {
    ServiceEventsDataStore.setSamplingMode("adaptive");
    ServiceEventsDataStore.setCurrentOperation("GET /api/cold");
    // No markOperationHot — should follow tier rules.
    assertTrue(MethodAggregationStore.shouldSample(50)); // tier1
    assertFalse(MethodAggregationStore.shouldSample(111)); // tier2 not on rate
  }

  @Test
  void hotOperation_tickDecrements() {
    ServiceEventsDataStore.setSamplingMode("adaptive");
    // Set thresholds BEFORE marking hot — markOperationHot snapshots the
    // current hotEndpointCycles into the operation's countdown.
    ServiceEventsDataStore.setSamplingThresholds(100, 1000, 10, 100, 3); // 3 cycles
    ServiceEventsDataStore.markOperationHot("GET /api/op");
    ServiceEventsDataStore.setCurrentOperation("GET /api/op");

    // Pick a callCount in tier3 that's NOT a tier3-rate hit (1101 % 100 != 0)
    // so a "true" result can only come from the hot-operation boost.
    long off = 1101;

    // Cycle 0: hot.
    assertTrue(MethodAggregationStore.shouldSample(off));
    ServiceEventsDataStore.tickHotOperations();
    // Cycle 1: still hot (countdown was 3, now 2).
    assertTrue(MethodAggregationStore.shouldSample(off));
    ServiceEventsDataStore.tickHotOperations();
    // Cycle 2: still hot (countdown 1).
    assertTrue(MethodAggregationStore.shouldSample(off));
    ServiceEventsDataStore.tickHotOperations();
    // Cycle 3: cold (countdown reached 0, removed) — falls back to tier rules,
    // and 1101 is not on the tier3 rate.
    assertFalse(MethodAggregationStore.shouldSample(off));
  }

  @Test
  void hotOperation_remarkResetsCountdown() {
    ServiceEventsDataStore.setSamplingMode("adaptive");
    // Set thresholds BEFORE marking hot.
    ServiceEventsDataStore.setSamplingThresholds(100, 1000, 10, 100, 2);
    ServiceEventsDataStore.markOperationHot("GET /api/op");
    ServiceEventsDataStore.setCurrentOperation("GET /api/op");

    long off = 5001; // tier3, not on rate (5001 % 100 != 0)
    ServiceEventsDataStore.tickHotOperations(); // 1 left
    ServiceEventsDataStore.markOperationHot("GET /api/op"); // back to 2
    ServiceEventsDataStore.tickHotOperations(); // 1
    assertTrue(MethodAggregationStore.shouldSample(off));
  }

  @Test
  void markOperationHot_nullOrEmpty_isNoop() {
    ServiceEventsDataStore.markOperationHot(null);
    ServiceEventsDataStore.markOperationHot("");
    ServiceEventsDataStore.setSamplingMode("adaptive");
    ServiceEventsDataStore.setCurrentOperation("GET /api/op");
    // Pick a tier3 callCount that's NOT on the rate so a hot-boost could be
    // distinguished from a regular tier3 hit (1501 % 100 != 0).
    assertFalse(MethodAggregationStore.shouldSample(1501));
  }
}
