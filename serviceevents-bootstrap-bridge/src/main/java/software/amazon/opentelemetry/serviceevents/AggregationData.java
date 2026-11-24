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
import java.util.concurrent.atomic.AtomicLong;

/** Aggregation data for a single function within an operation context. */
public class AggregationData {
  private final String functionId;

  /** Total calls (sampled + unsampled), used for sampling decisions. */
  private final AtomicLong callCount = new AtomicLong(0);

  private long sampledCalls;
  private long totalTimeNs;
  private final ConcurrentHashMap<String, Integer> exceptions =
      new ConcurrentHashMap<String, Integer>();
  private final ConcurrentHashMap<String, Integer> callers =
      new ConcurrentHashMap<String, Integer>();

  public AggregationData(String functionId) {
    this.functionId = functionId;
  }

  /**
   * Atomically increment and return the call count. Called from methodEnter for the sampling
   * decision. Resets when aggregations are swapped.
   */
  public long incrementCallCount() {
    return callCount.incrementAndGet();
  }

  public synchronized void recordDuration(long durationNs, String caller) {
    sampledCalls++;
    totalTimeNs += durationNs;
    if (caller != null && !caller.isEmpty()) {
      // Atomic read-modify-write so the count is correct even if a future caller records outside
      // this synchronized method.
      callers.merge(caller, 1, Integer::sum);
    }
  }

  public void recordException(String exceptionType) {
    if (exceptionType == null) {
      return;
    }
    // Not synchronized — use an atomic read-modify-write so concurrent calls don't lose increments.
    exceptions.merge(exceptionType, 1, Integer::sum);
  }

  public String getFunctionId() {
    return functionId;
  }

  public long getTotalCalls() {
    return callCount.get();
  }

  public long getSampledCalls() {
    return sampledCalls;
  }

  public long getTotalTimeNs() {
    return totalTimeNs;
  }

  public Map<String, Integer> getExceptions() {
    return new HashMap<String, Integer>(exceptions);
  }

  public Map<String, Integer> getCallers() {
    return new HashMap<String, Integer>(callers);
  }
}
