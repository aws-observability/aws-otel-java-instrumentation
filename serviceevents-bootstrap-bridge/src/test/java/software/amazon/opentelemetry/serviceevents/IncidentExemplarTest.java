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

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link IncidentExemplar} and {@link EndpointAggregation} incident exemplar methods.
 *
 * <p>IMPORTANT: This file MUST use Java 8 compatible syntax only.
 */
class IncidentExemplarTest {

  // ========================================================================
  // IncidentExemplar construction and fields
  // ========================================================================

  @Test
  void testIncidentExemplarFields() {
    IncidentExemplar exemplar =
        new IncidentExemplar("snap_abc123", "exception", "critical", 1700000000L);

    assertEquals("snap_abc123", exemplar.snapshotId);
    assertEquals("exception", exemplar.triggerType);
    assertEquals("critical", exemplar.severity);
    assertEquals(1700000000L, exemplar.timestamp);
  }

  @Test
  void testIncidentExemplarWithLatencyTrigger() {
    IncidentExemplar exemplar =
        new IncidentExemplar("snap_def456", "latency", "medium", 1700000001L);

    assertEquals("snap_def456", exemplar.snapshotId);
    assertEquals("latency", exemplar.triggerType);
    assertEquals("medium", exemplar.severity);
    assertEquals(1700000001L, exemplar.timestamp);
  }

  // ========================================================================
  // EndpointAggregation.addIncidentExemplar and getIncidentExemplars
  // ========================================================================

  @Test
  void testAddAndGetIncidentExemplars() {
    EndpointAggregation agg = new EndpointAggregation("/api/orders", "GET");

    agg.addIncidentExemplar("snap_1", "exception", "critical", 1000L);
    agg.addIncidentExemplar("snap_2", "latency", "medium", 2000L);

    List<IncidentExemplar> exemplars = agg.getIncidentExemplars();
    assertEquals(2, exemplars.size());
    assertEquals("snap_1", exemplars.get(0).snapshotId);
    assertEquals("exception", exemplars.get(0).triggerType);
    assertEquals("snap_2", exemplars.get(1).snapshotId);
    assertEquals("latency", exemplars.get(1).triggerType);
  }

  @Test
  void testGetIncidentExemplarsReturnsCopy() {
    EndpointAggregation agg = new EndpointAggregation("/api/orders", "GET");

    agg.addIncidentExemplar("snap_1", "exception", "critical", 1000L);

    List<IncidentExemplar> copy1 = agg.getIncidentExemplars();
    List<IncidentExemplar> copy2 = agg.getIncidentExemplars();

    assertNotSame(copy1, copy2);
    assertEquals(copy1.size(), copy2.size());

    // Modifying the returned list should not affect the aggregation
    copy1.clear();
    assertEquals(1, agg.getIncidentExemplars().size());
  }

  @Test
  void testEmptyExemplarsInitially() {
    EndpointAggregation agg = new EndpointAggregation("/api/orders", "GET");

    List<IncidentExemplar> exemplars = agg.getIncidentExemplars();
    assertNotNull(exemplars);
    assertTrue(exemplars.isEmpty());
  }

  // ========================================================================
  // Per-trigger-type cap of 10
  // ========================================================================

  @Test
  void testPerTriggerTypeCap_exceptionCapped() {
    EndpointAggregation agg = new EndpointAggregation("/api/orders", "GET");

    // Add 15 exception exemplars - only 10 should be kept
    for (int i = 0; i < 15; i++) {
      agg.addIncidentExemplar("snap_exc_" + i, "exception", "critical", 1000L + i);
    }

    List<IncidentExemplar> exemplars = agg.getIncidentExemplars();
    assertEquals(10, exemplars.size());

    // All should be exception type
    for (IncidentExemplar ex : exemplars) {
      assertEquals("exception", ex.triggerType);
    }

    // Verify the first 10 were kept (cap rejects new ones after limit)
    assertEquals("snap_exc_0", exemplars.get(0).snapshotId);
    assertEquals("snap_exc_9", exemplars.get(9).snapshotId);
  }

  @Test
  void testPerTriggerTypeCap_independentPerType() {
    EndpointAggregation agg = new EndpointAggregation("/api/orders", "GET");

    // Add 10 exception exemplars
    for (int i = 0; i < 10; i++) {
      agg.addIncidentExemplar("snap_exc_" + i, "exception", "critical", 1000L + i);
    }

    // Add 10 latency exemplars - should still be allowed since different trigger type
    for (int i = 0; i < 10; i++) {
      agg.addIncidentExemplar("snap_lat_" + i, "latency", "medium", 2000L + i);
    }

    List<IncidentExemplar> exemplars = agg.getIncidentExemplars();
    assertEquals(20, exemplars.size());

    int exceptionCount = 0;
    int latencyCount = 0;
    for (IncidentExemplar ex : exemplars) {
      if ("exception".equals(ex.triggerType)) {
        exceptionCount++;
      } else if ("latency".equals(ex.triggerType)) {
        latencyCount++;
      }
    }
    assertEquals(10, exceptionCount);
    assertEquals(10, latencyCount);
  }

  @Test
  void testPerTriggerTypeCap_latencyCappedIndependently() {
    EndpointAggregation agg = new EndpointAggregation("/api/orders", "GET");

    // Add 5 exception exemplars
    for (int i = 0; i < 5; i++) {
      agg.addIncidentExemplar("snap_exc_" + i, "exception", "critical", 1000L + i);
    }

    // Add 12 latency exemplars - only 10 should be kept
    for (int i = 0; i < 12; i++) {
      agg.addIncidentExemplar("snap_lat_" + i, "latency", "medium", 2000L + i);
    }

    List<IncidentExemplar> exemplars = agg.getIncidentExemplars();
    assertEquals(15, exemplars.size());

    int exceptionCount = 0;
    int latencyCount = 0;
    for (IncidentExemplar ex : exemplars) {
      if ("exception".equals(ex.triggerType)) {
        exceptionCount++;
      } else if ("latency".equals(ex.triggerType)) {
        latencyCount++;
      }
    }
    assertEquals(5, exceptionCount);
    assertEquals(10, latencyCount);
  }

  @Test
  void testPerTriggerTypeCap_exactlyAtLimit() {
    EndpointAggregation agg = new EndpointAggregation("/api/orders", "GET");

    // Add exactly 10
    for (int i = 0; i < 10; i++) {
      agg.addIncidentExemplar("snap_" + i, "exception", "high", 1000L + i);
    }

    assertEquals(10, agg.getIncidentExemplars().size());

    // 11th should be rejected
    agg.addIncidentExemplar("snap_overflow", "exception", "high", 9999L);
    assertEquals(10, agg.getIncidentExemplars().size());
  }
}
