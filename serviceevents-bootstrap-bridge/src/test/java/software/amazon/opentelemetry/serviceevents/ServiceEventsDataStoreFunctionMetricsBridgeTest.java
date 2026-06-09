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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the methodEnter / methodExit routing contract when a {@link FunctionMetricsBridge} is
 * wired:
 *
 * <ul>
 *   <li>Bridge receives only sampled calls (matches the histogram-only spec).
 *   <li>SEH aggregation map stays empty (so {@code FunctionCallCollector} no-ops).
 *   <li>Non-sampled calls fast-path return {@code null} from {@code methodEnter} (zero overhead).
 *   <li>When the bridge is null, the legacy SEH path is preserved.
 * </ul>
 */
class ServiceEventsDataStoreFunctionMetricsBridgeTest {

  private RecordingBridge bridge;

  @BeforeEach
  void setUp() {
    ServiceEventsDataStore.resetState();
    ServiceEventsDataStore.setSamplingMode("auto");
    bridge = new RecordingBridge();
    ServiceEventsDataStore.setCurrentOperation("GET /api/x");
  }

  @AfterEach
  void tearDown() {
    ServiceEventsDataStore.setFunctionMetricsBridge(null);
    ServiceEventsDataStore.setCurrentOperation(null);
    ServiceEventsDataStore.resetState();
  }

  @Test
  void bridgeNull_legacyPathOnly_sampledCallsFlowToAggregations() {
    // No bridge wired: only sampled calls reach methodExit, and they go to MethodAggregationStore.
    String functionId = "com.example.Service.handle";
    Object ctx = ServiceEventsDataStore.methodEnter(functionId);
    assertNotNull(ctx, "tier1 first call should be sampled");
    ServiceEventsDataStore.methodExit(functionId, ctx, null);

    Map<String, Map<String, AggregationData>> aggs =
        ServiceEventsDataStore.getAndSwapAggregations();
    assertNotNull(aggs, "Sampled calls must land in the SEH aggregation map when bridge is null");
    assertTrue(aggs.containsKey("GET /api/x"));
  }

  @Test
  void bridgeWired_sampledCallRecordsHistogramAndSkipsAggregations() {
    ServiceEventsDataStore.setFunctionMetricsBridge(bridge);

    String functionId = "com.example.Service.handle";
    Object ctx = ServiceEventsDataStore.methodEnter(functionId);
    assertNotNull(ctx, "tier1 first call must be sampled");
    ServiceEventsDataStore.methodExit(functionId, ctx, null);

    assertEquals(1, bridge.records.size());
    RecordingBridge.Record rec = bridge.records.get(0);
    assertEquals(functionId, rec.functionId);
    assertEquals("GET /api/x", rec.operation);
    assertNull(rec.exceptionType);
    assertTrue(rec.durationNs > 0L, "Duration must be measured for sampled calls");

    // Bridge-routed calls bypass the SEH map.
    Map<String, Map<String, AggregationData>> aggs =
        ServiceEventsDataStore.getAndSwapAggregations();
    assertNull(aggs, "Bridge-wired path must NOT populate SEH aggregations");
  }

  @Test
  void bridgeWired_nonSampledCallsAreFastPathSkipped() {
    ServiceEventsDataStore.setFunctionMetricsBridge(bridge);
    String functionId = "com.example.HotMethod.run";

    // Burn through tier1 (100 sampled) so subsequent calls fall into tier2 (1-in-10) and beyond.
    int sampledCallsObserved = 0;
    int nullContexts = 0;
    for (int i = 0; i < 1500; i++) {
      Object ctx = ServiceEventsDataStore.methodEnter(functionId);
      if (ctx == null) {
        nullContexts++;
      } else {
        sampledCallsObserved++;
      }
      ServiceEventsDataStore.methodExit(functionId, ctx, null);
    }

    assertTrue(sampledCallsObserved > 0, "Should observe some sampled calls (tier1)");
    assertTrue(
        nullContexts > 0,
        "Non-sampled calls must return null context — bridge does not need them since the "
            + "histogram only records sampled calls");

    // Bridge sees only sampled calls.
    assertEquals(
        sampledCallsObserved,
        bridge.records.size(),
        "Bridge must receive exactly the sampled calls — no non-sampled invocations");
  }

  @Test
  void bridgeWired_callerCapturedFromCallStack() {
    ServiceEventsDataStore.setFunctionMetricsBridge(bridge);

    Object outerCtx = ServiceEventsDataStore.methodEnter("com.example.Outer.handle");
    Object innerCtx = ServiceEventsDataStore.methodEnter("com.example.Inner.process");
    ServiceEventsDataStore.methodExit("com.example.Inner.process", innerCtx, null);
    ServiceEventsDataStore.methodExit("com.example.Outer.handle", outerCtx, null);

    assertEquals(2, bridge.records.size());
    // Inner exit fires first; its caller is Outer.
    assertEquals("com.example.Inner.process", bridge.records.get(0).functionId);
    assertEquals("com.example.Outer.handle", bridge.records.get(0).caller);
    // Outer is the outermost frame: caller is null.
    assertEquals("com.example.Outer.handle", bridge.records.get(1).functionId);
    assertNull(bridge.records.get(1).caller);
  }

  @Test
  void bridgeWired_exceptionTypePropagatesToBridge() {
    ServiceEventsDataStore.setFunctionMetricsBridge(bridge);

    Object ctx = ServiceEventsDataStore.methodEnter("com.example.Fail.boom");
    assertNotNull(ctx);
    ServiceEventsDataStore.methodExit("com.example.Fail.boom", ctx, "RuntimeException");

    assertEquals(1, bridge.records.size());
    assertEquals("RuntimeException", bridge.records.get(0).exceptionType);
  }

  /** Captures all bridge invocations for assertion. Thread-confined in these tests. */
  private static final class RecordingBridge implements FunctionMetricsBridge {
    static final class Record {
      final String functionId;
      final String operation;
      final String caller;
      final long durationNs;
      final String exceptionType;

      Record(
          String functionId,
          String operation,
          String caller,
          long durationNs,
          String exceptionType) {
        this.functionId = functionId;
        this.operation = operation;
        this.caller = caller;
        this.durationNs = durationNs;
        this.exceptionType = exceptionType;
      }
    }

    final List<Record> records = new ArrayList<>();

    @Override
    public void recordFunctionCall(
        String functionId, String operation, String caller, long durationNs, String exceptionType) {
      records.add(new Record(functionId, operation, caller, durationNs, exceptionType));
    }
  }
}
