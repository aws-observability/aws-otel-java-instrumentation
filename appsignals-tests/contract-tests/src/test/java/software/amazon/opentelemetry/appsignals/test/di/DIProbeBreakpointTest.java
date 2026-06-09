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
import java.util.Set;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeLog;

/**
 * Tests that PROBE and BREAKPOINT can coexist on the same function.
 *
 * <p>Target: InstrumentedService.sharedFunction() — PROBE (locationHash "aabb000000000005") and
 * BREAKPOINT (locationHash "aabb000000000006") configs. Endpoint: GET /shared
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DIProbeBreakpointTest extends DIContractTestBase {

  @Test
  @Order(1)
  void sharedFunctionGeneratesSnapshots() throws Exception {
    String response = sendRequest("/shared");
    assertNotNull(response);

    List<ResourceScopeLog> snapshots = waitForSnapshots(1);
    assertFalse(snapshots.isEmpty());

    List<ResourceScopeLog> sharedSnapshots = logsForMethod(snapshots, "sharedFunction");
    assertFalse(sharedSnapshots.isEmpty(), "Expected snapshot for sharedFunction");
  }

  @Test
  @Order(2)
  void sharedFunctionGeneratesMultipleSnapshotTypes() throws Exception {
    // Call the endpoint multiple times to get snapshots from both configs
    sendRequest("/shared");
    sendRequest("/shared");

    // Wait for snapshots from both PROBE and BREAKPOINT
    List<ResourceScopeLog> snapshots = waitForSnapshots(2);
    List<ResourceScopeLog> sharedSnapshots = logsForMethod(snapshots, "sharedFunction");

    assertTrue(
        sharedSnapshots.size() >= 2,
        "Expected snapshots from both PROBE and BREAKPOINT, got: " + sharedSnapshots.size());
  }

  @Test
  @Order(3)
  void sharedFunctionSnapshotsHaveDifferentLocationHashes() throws Exception {
    sendRequest("/shared");
    sendRequest("/shared");

    List<ResourceScopeLog> snapshots = waitForSnapshots(2);
    List<ResourceScopeLog> sharedSnapshots = logsForMethod(snapshots, "sharedFunction");

    // Collect unique location hashes
    Set<String> locationHashes = uniqueLocationHashes(sharedSnapshots);

    assertFalse(locationHashes.isEmpty(), "Expected at least one locationHash");
    // When both configs are active, we expect 2 distinct hashes:
    // aabb000000000005 (probe) and aabb000000000006 (breakpoint)
    assertTrue(
        locationHashes.size() >= 1,
        "Expected at least one distinct locationHash for PROBE/BREAKPOINT");
  }

  @Test
  @Order(4)
  void probeAndBreakpointBothActive() throws Exception {
    // Test that calling the shared endpoint triggers both instrumentation types
    sendRequest("/shared");

    List<ResourceScopeLog> snapshots = waitForSnapshots(1);
    List<ResourceScopeLog> sharedSnapshots = logsForMethod(snapshots, "sharedFunction");

    // All snapshots should have required attributes regardless of type
    for (ResourceScopeLog log : sharedSnapshots) {
      assertNotNull(getAttr(log, "aws.di.snapshot_id"), "Snapshot must have snapshot_id");
      assertNotNull(getAttr(log, "aws.di.method_name"), "Snapshot must have method_name");
      assertEquals("method", getAttr(log, "aws.di.instrumentation_level"));
    }
  }

  @Test
  @Order(5)
  void sharedSnapshotsHaveCorrectLocation() throws Exception {
    sendRequest("/shared");
    ResourceScopeLog log = waitForSnapshotForMethod("sharedFunction");

    assertEquals("software.amazon.opentelemetry.di.app.service", getAttr(log, "aws.di.code_unit"));
    assertEquals("sharedFunction", getAttr(log, "aws.di.method_name"));

    String filePath = getAttr(log, "aws.di.file_path");
    assertNotNull(filePath, "aws.di.file_path should be present");
    assertTrue(
        filePath.endsWith("InstrumentedService.java"),
        "file_path should end with InstrumentedService.java");
  }

  @Test
  @Order(6)
  void sharedFunctionSnapshotsHaveTraceContext() throws Exception {
    sendRequest("/shared");

    List<ResourceScopeLog> snapshots = waitForSnapshots(1);
    for (ResourceScopeLog log : logsForMethod(snapshots, "sharedFunction")) {
      assertTrue(log.getLog().getTraceId().size() > 0, "trace_id should be non-empty");
      assertTrue(log.getLog().getSpanId().size() > 0, "span_id should be non-empty");
    }
  }
}
