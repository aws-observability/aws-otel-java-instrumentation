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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages per-operation, per-function method call aggregation data.
 *
 * <p>Package-private: accessed only from {@link ServiceEventsDataStore}.
 */
final class MethodAggregationStore {

  private MethodAggregationStore() {}

  // =========================================================================
  // Sampling Configuration
  // =========================================================================

  // All four tier knobs and the mode are volatile so the agent-classloader init
  // (ServiceEventsInstrumentation) can set them at startup without needing to reach
  // into the bootstrap bridge's private state. Python/JS expose the same four knobs and the same
  // three modes: the SAMPLING_MODE enum is documented in SERVICE_EVENTS_ENV_VARS_RELEASE.md §6
  // (Sampling); the hardcoded auto-mode tier cutoffs/rates in SERVICE_EVENTS_INTERNAL_KNOBS.md
  // §1.3.

  /** Mode: "auto" (tiered), "always", or "never". */
  static volatile String samplingMode = "always";

  static volatile long sampleTier1Threshold = 100;
  static volatile long sampleTier2Threshold = 1000;
  static volatile int sampleTier2Rate = 10;
  static volatile int sampleTier3Rate = 100;

  static void setSamplingMode(String mode) {
    if (mode == null) {
      return;
    }
    String normalized = mode.toLowerCase(java.util.Locale.ROOT);
    if (!normalized.equals("auto") && !normalized.equals("always") && !normalized.equals("never")) {
      // Invalid mode (including the removed "adaptive") — leave current setting unchanged.
      return;
    }
    samplingMode = normalized;
  }

  static void setSamplingThresholds(
      long tier1Threshold, long tier2Threshold, int tier2Rate, int tier3Rate) {
    if (tier1Threshold > 0) sampleTier1Threshold = tier1Threshold;
    if (tier2Threshold > 0) sampleTier2Threshold = tier2Threshold;
    if (tier2Rate > 0) sampleTier2Rate = tier2Rate;
    if (tier3Rate > 0) sampleTier3Rate = tier3Rate;
  }

  // =========================================================================
  // Aggregation State
  // =========================================================================

  /** Method aggregation: operation -> functionId -> AggregationData. */
  private static final AtomicReference<
          ConcurrentHashMap<String, ConcurrentHashMap<String, AggregationData>>>
      aggregations =
          new AtomicReference<
              ConcurrentHashMap<String, ConcurrentHashMap<String, AggregationData>>>(
              new ConcurrentHashMap<String, ConcurrentHashMap<String, AggregationData>>());

  // =========================================================================
  // Aggregation Methods
  // =========================================================================

  /**
   * Get or create an AggregationData entry for the given operation and functionId. Used by both
   * methodEnter (for sampling) and recordMethodInvocation (for recording).
   */
  static AggregationData getOrCreateAggregation(String operation, String functionId) {
    ConcurrentHashMap<String, ConcurrentHashMap<String, AggregationData>> aggs = aggregations.get();
    ConcurrentHashMap<String, AggregationData> operationAggs = aggs.get(operation);
    if (operationAggs == null) {
      operationAggs = new ConcurrentHashMap<String, AggregationData>();
      ConcurrentHashMap<String, AggregationData> existing =
          aggs.putIfAbsent(operation, operationAggs);
      if (existing != null) {
        operationAggs = existing;
      }
    }

    AggregationData agg = operationAggs.get(functionId);
    if (agg == null) {
      agg = new AggregationData(functionId);
      AggregationData existingAgg = operationAggs.putIfAbsent(functionId, agg);
      if (existingAgg != null) {
        agg = existingAgg;
      }
    }
    return agg;
  }

  /**
   * Record a method invocation into aggregation storage.
   *
   * @param operation The operation (e.g., "GET /api/users")
   * @param functionId The function identifier
   * @param durationNs Duration in nanoseconds
   * @param caller The caller function ID, or null
   * @param exceptionType Exception type if any, or null
   */
  static void recordMethodInvocation(
      String operation, String functionId, long durationNs, String caller, String exceptionType) {
    AggregationData agg = getOrCreateAggregation(operation, functionId);

    agg.recordDuration(durationNs, caller);
    if (exceptionType != null && !exceptionType.isEmpty()) {
      agg.recordException(exceptionType);
    }
  }

  /**
   * Determine whether a method call should be sampled based on the total call count and the current
   * sampling mode. Hot path — invoked on every instrumented method entry. Reads {@link
   * #samplingMode} via volatile field (cheap; no synchronization).
   *
   * <ul>
   *   <li>{@code always} (default) — every call sampled.
   *   <li>{@code never} — no calls sampled.
   *   <li>{@code auto} — three-tier: all calls up to {@link #sampleTier1Threshold}, then
   *       1-in-{@link #sampleTier2Rate} up to {@link #sampleTier2Threshold}, then 1-in-{@link
   *       #sampleTier3Rate} thereafter.
   * </ul>
   */
  static boolean shouldSample(long totalCalls) {
    String mode = samplingMode;
    if ("always".equals(mode)) {
      return true;
    }
    if ("never".equals(mode)) {
      return false;
    }
    // auto — tiered sampling. Rates are "1-in-N"; a non-positive N is degenerate (it can only
    // arrive via the internal test-config hook, which doesn't validate) and would otherwise throw
    // ArithmeticException on the `%`. Treat N <= 0 as "sample none in this tier" so a misconfigured
    // rate degrades gracefully instead of crashing this hot path. Mirrors Python/JS.
    if (totalCalls <= sampleTier1Threshold) {
      return true;
    }
    if (totalCalls <= sampleTier2Threshold) {
      return sampleTier2Rate > 0 && (totalCalls % sampleTier2Rate == 0);
    }
    return sampleTier3Rate > 0 && (totalCalls % sampleTier3Rate == 0);
  }

  /**
   * Get and swap all method aggregations. Returns current data and resets storage.
   *
   * @return Map of operation to functionId to AggregationData, or null if empty
   */
  static Map<String, Map<String, AggregationData>> getAndSwapAggregations() {
    ConcurrentHashMap<String, ConcurrentHashMap<String, AggregationData>> old =
        aggregations.getAndSet(
            new ConcurrentHashMap<String, ConcurrentHashMap<String, AggregationData>>());
    if (old.isEmpty()) {
      return null;
    }

    Map<String, Map<String, AggregationData>> result =
        new HashMap<String, Map<String, AggregationData>>();
    for (Map.Entry<String, ConcurrentHashMap<String, AggregationData>> entry : old.entrySet()) {
      if (!entry.getValue().isEmpty()) {
        result.put(entry.getKey(), new HashMap<String, AggregationData>(entry.getValue()));
      }
    }

    return result.isEmpty() ? null : result;
  }

  /** Reset all aggregation state. Used for testing. */
  static void resetState() {
    aggregations.set(new ConcurrentHashMap<String, ConcurrentHashMap<String, AggregationData>>());
    samplingMode = "always";
    sampleTier1Threshold = 100;
    sampleTier2Threshold = 1000;
    sampleTier2Rate = 10;
    sampleTier3Rate = 100;
  }
}
