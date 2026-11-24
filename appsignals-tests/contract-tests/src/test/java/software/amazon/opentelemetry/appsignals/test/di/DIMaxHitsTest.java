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

package software.amazon.opentelemetry.appsignals.test.di;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeLog;

/**
 * Tests that BREAKPOINT with MaxHits=3 enforces the hit limit.
 *
 * <p>Target: InstrumentedService.limitedFunction() — BREAKPOINT config with MaxHits=3, locationHash
 * "aabb000000000004". Endpoint: GET /limited
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DIMaxHitsTest extends DIContractTestBase {

  private static final int MAX_HITS = 3;

  @Test
  @Order(1)
  void limitedBreakpointGeneratesSnapshots() throws Exception {
    String response = sendRequest("/limited");
    assertNotNull(response);

    List<ResourceScopeLog> snapshots = waitForSnapshots(1);
    assertFalse(snapshots.isEmpty());

    List<ResourceScopeLog> limitedSnapshots = logsForMethod(snapshots, "limitedFunction");
    assertFalse(limitedSnapshots.isEmpty(), "Expected snapshot for limitedFunction");
  }

  @Test
  @Order(2)
  void limitedBreakpointRespectsMaxHits() throws Exception {
    // Call the endpoint more times than maxHits
    for (int i = 0; i < MAX_HITS + 2; i++) {
      sendRequest("/limited");
      Thread.sleep(100); // Small delay between calls
    }

    // Wait a bit for all snapshots to be emitted
    Thread.sleep(2000);

    List<ResourceScopeLog> snapshots = peekSnapshots();
    List<ResourceScopeLog> limitedSnapshots = logsForMethod(snapshots, "limitedFunction");

    // Should not exceed maxHits
    assertTrue(
        limitedSnapshots.size() <= MAX_HITS,
        String.format(
            "Expected at most %d snapshots due to maxHits, got: %d",
            MAX_HITS, limitedSnapshots.size()));
  }

  @Test
  @Order(3)
  void limitedBreakpointStopsAfterMaxHits() throws Exception {
    // Trigger the breakpoint maxHits times
    for (int i = 0; i < MAX_HITS; i++) {
      sendRequest("/limited");
    }

    // Wait for snapshots
    waitForSnapshots(MAX_HITS);
    int countBefore = logsForMethod(peekSnapshots(), "limitedFunction").size();

    // Trigger more calls
    for (int i = 0; i < 3; i++) {
      sendRequest("/limited");
    }
    Thread.sleep(2000);

    // Count should not have increased beyond maxHits
    int countAfter = logsForMethod(peekSnapshots(), "limitedFunction").size();

    assertTrue(
        countAfter <= MAX_HITS,
        String.format("Snapshot count should be limited to %d, got: %d", MAX_HITS, countAfter));
  }

  @Test
  @Order(4)
  void limitedSnapshotHasCorrectLocation() throws Exception {
    sendRequest("/limited");
    ResourceScopeLog log = waitForSnapshotForMethod("limitedFunction");

    assertEquals("software.amazon.opentelemetry.di.app.service", getAttr(log, "aws.di.code_unit"));
    assertEquals("limitedFunction", getAttr(log, "aws.di.method_name"));
    assertEquals("method", getAttr(log, "aws.di.instrumentation_level"));
  }
}
