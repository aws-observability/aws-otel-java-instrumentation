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
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeLog;

/**
 * Tests for PROBE instrumentation (permanent, no hit limit) generating snapshots via OTLP.
 *
 * <p>Target: InstrumentedService.computeTotal() — PROBE config with locationHash
 * "aabb000000000001". Endpoint: GET /probe
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DIProbeTest extends DIContractTestBase {

  @Test
  @Order(1)
  void probeSnapshotGenerated() throws Exception {
    String response = sendRequest("/probe");
    assertNotNull(response);

    List<ResourceScopeLog> snapshots = waitForSnapshots(1);
    assertFalse(snapshots.isEmpty());

    List<ResourceScopeLog> probeSnapshots = logsForMethod(snapshots, "computeTotal");
    assertFalse(probeSnapshots.isEmpty(), "Expected snapshot for computeTotal probe");
  }

  @Test
  @Order(2)
  void probeGeneratesMultipleSnapshots() throws Exception {
    // Call the endpoint multiple times — probes have no hit limit
    sendRequest("/probe");
    sendRequest("/probe");
    sendRequest("/probe");

    List<ResourceScopeLog> snapshots = waitForSnapshots(3);
    List<ResourceScopeLog> probeSnapshots = logsForMethod(snapshots, "computeTotal");

    assertTrue(
        probeSnapshots.size() >= 3,
        "Expected at least 3 snapshots from probe, got: " + probeSnapshots.size());
  }

  @Test
  @Order(3)
  @SuppressWarnings("unchecked")
  void probeSnapshotHasMethodLevelCaptures() throws Exception {
    sendRequest("/probe");
    ResourceScopeLog log = waitForSnapshotForMethod("computeTotal");

    assertEquals("method", getAttr(log, "aws.di.instrumentation_level"), "Should be method-level");

    Map<String, Object> body = getBody(log);
    Map<String, Object> captures = (Map<String, Object>) body.get("captures");
    assertNotNull(captures, "Snapshot must have captures");
    assertTrue(
        captures.containsKey("entry") || captures.containsKey("return"),
        "Method-level snapshot should have entry or return captures");
  }

  @Test
  @Order(4)
  void probeSnapshotHasCorrectLocation() throws Exception {
    sendRequest("/probe");
    ResourceScopeLog log = waitForSnapshotForMethod("computeTotal");

    assertEquals("software.amazon.opentelemetry.di.app.service", getAttr(log, "aws.di.code_unit"));
    assertEquals("computeTotal", getAttr(log, "aws.di.method_name"));

    String filePath = getAttr(log, "aws.di.file_path");
    assertNotNull(filePath, "aws.di.file_path should be present");
    assertTrue(
        filePath.endsWith("InstrumentedService.java"),
        "file_path should end with InstrumentedService.java");
  }

  @Test
  @Order(5)
  void probeSnapshotHasLocationHash() throws Exception {
    sendRequest("/probe");
    ResourceScopeLog log = waitForSnapshotForMethod("computeTotal");

    assertTrue(hasAttr(log, "aws.di.location_hash"), "Snapshot must have aws.di.location_hash");
    assertEquals(
        "aabb000000000001",
        getAttr(log, "aws.di.location_hash"),
        "locationHash must match probe config");
  }
}
