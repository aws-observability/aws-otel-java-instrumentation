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
  // into the bootstrap bridge's private state. Python/JS expose the same four
  // knobs and the same four modes; see the cross-SDK §3.6 sampling table.

  /** Mode: "auto" (tiered), "adaptive" (auto + hot-endpoint boost), "always", or "never". */
  static volatile String samplingMode = "adaptive";

  static volatile long sampleTier1Threshold = 100;
  static volatile long sampleTier2Threshold = 1000;
  static volatile int sampleTier2Rate = 10;
  static volatile int sampleTier3Rate = 100;

  /** Countdown-based hot operation tracking for adaptive mode. */
  static volatile int hotEndpointCycles = 100;

  private static final ConcurrentHashMap<String, Integer> hotOperations =
      new ConcurrentHashMap<String, Integer>();

  static void setSamplingMode(String mode) {
    if (mode == null) {
      return;
    }
    String normalized = mode.toLowerCase(java.util.Locale.ROOT);
    if (!normalized.equals("auto")
        && !normalized.equals("adaptive")
        && !normalized.equals("always")
        && !normalized.equals("never")) {
      // Invalid mode — leave current setting unchanged.
      return;
    }
    samplingMode = normalized;
  }

  static void setSamplingThresholds(
      long tier1Threshold, long tier2Threshold, int tier2Rate, int tier3Rate, int hotCycles) {
    if (tier1Threshold > 0) sampleTier1Threshold = tier1Threshold;
    if (tier2Threshold > 0) sampleTier2Threshold = tier2Threshold;
    if (tier2Rate > 0) sampleTier2Rate = tier2Rate;
    if (tier3Rate > 0) sampleTier3Rate = tier3Rate;
    if (hotCycles > 0) hotEndpointCycles = hotCycles;
  }

  /**
   * Mark an operation "hot" so adaptive-mode function calls within it are 100% sampled for the next
   * {@link #hotEndpointCycles} flush cycles. Called after an incident is raised against the
   * operation (incident → operation needs fuller diagnostic data next time).
   */
  static void markOperationHot(String operation) {
    if (operation == null || operation.isEmpty()) {
      return;
    }
    hotOperations.put(operation, hotEndpointCycles);
  }

  /**
   * Decrement every hot-operation countdown by one. Called once per function-call collector flush.
   * Operations at zero are removed.
   *
   * <p>Race-safe under concurrent {@code markOperationHot} calls: each entry is updated atomically
   * via {@link ConcurrentHashMap#computeIfPresent} so a fresh countdown {@code put} from an
   * incident-emitter thread is never silently overwritten or removed by this iteration. Returning
   * {@code null} from the remapping function asks ConcurrentHashMap to remove the entry.
   * Package-private for tests.
   */
  static void tickHotOperations() {
    for (String op : hotOperations.keySet()) {
      hotOperations.computeIfPresent(op, (k, v) -> v <= 1 ? null : v - 1);
    }
  }

  private static boolean isOperationHot(String operation) {
    return operation != null && hotOperations.containsKey(operation);
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
   *   <li>{@code always} — every call sampled.
   *   <li>{@code never} — no calls sampled.
   *   <li>{@code adaptive} — same as {@code auto} unless the current operation is "hot" (an
   *       incident fired against it recently), in which case every call is sampled. Reads the
   *       thread-local {@code currentOperation}; only one CHM lookup when hot.
   *   <li>{@code auto} (default) — three-tier: all calls up to {@link #sampleTier1Threshold}, then
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
    if ("adaptive".equals(mode) && !hotOperations.isEmpty()) {
      String op = ServiceEventsDataStore.getCurrentOperation();
      if (isOperationHot(op)) {
        return true;
      }
    }
    // auto + adaptive (non-hot) fall through to tiered sampling.
    if (totalCalls <= sampleTier1Threshold) {
      return true;
    }
    if (totalCalls <= sampleTier2Threshold) {
      return (totalCalls % sampleTier2Rate == 0);
    }
    return (totalCalls % sampleTier3Rate == 0);
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
    hotOperations.clear();
    samplingMode = "adaptive";
    sampleTier1Threshold = 100;
    sampleTier2Threshold = 1000;
    sampleTier2Rate = 10;
    sampleTier3Rate = 100;
    hotEndpointCycles = 100;
  }
}
