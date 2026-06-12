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
 * Tests for line-level BREAKPOINT (lineNumber > 0) generating line-level snapshots via OTLP.
 *
 * <p>Target: InstrumentedService.calculateSum() — BREAKPOINT at line 62, locationHash
 * "aabb000000000003". Endpoint: GET /line-level
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DILineBreakpointTest extends DIContractTestBase {

  @Test
  @Order(1)
  void lineLevelSnapshotGenerated() throws Exception {
    String response = sendRequest("/line-level");
    assertNotNull(response);

    List<ResourceScopeLog> snapshots = waitForSnapshots(1);
    assertFalse(snapshots.isEmpty());

    List<ResourceScopeLog> lineSnapshots = logsForMethod(snapshots, "calculateSum");
    assertFalse(lineSnapshots.isEmpty(), "Expected snapshot for calculateSum");
  }

  @Test
  @Order(2)
  void lineLevelSnapshotHasCorrectLevel() throws Exception {
    sendRequest("/line-level");
    ResourceScopeLog log = waitForSnapshotForMethod("calculateSum");

    assertEquals("line", getAttr(log, "aws.di.instrumentation_level"), "Should be line-level");
  }

  @Test
  @Order(3)
  void lineLevelSnapshotHasLineNumber() throws Exception {
    sendRequest("/line-level");
    ResourceScopeLog log = waitForSnapshotForMethod("calculateSum");

    assertTrue(
        hasAttr(log, "aws.di.line_number"), "Line-level snapshot must have aws.di.line_number");
    Long lineNumber = getAttrLong(log, "aws.di.line_number");
    assertNotNull(lineNumber, "aws.di.line_number should be present");
    assertTrue(
        lineNumber > 0, "Line-level snapshot should have lineNumber > 0, got: " + lineNumber);
  }

  @Test
  @Order(4)
  @SuppressWarnings("unchecked")
  void lineLevelSnapshotHasLinesCaptures() throws Exception {
    sendRequest("/line-level");
    ResourceScopeLog log = waitForSnapshotForMethod("calculateSum");

    Map<String, Object> body = getBody(log);
    Map<String, Object> captures = (Map<String, Object>) body.get("captures");
    assertNotNull(captures, "Snapshot should have captures");
    assertTrue(
        captures.containsKey("lines") || captures.containsKey("entry"),
        "Line-level snapshot should have lines or entry captures");
  }

  @Test
  @Order(5)
  void lineLevelSnapshotHasCorrectMethod() throws Exception {
    sendRequest("/line-level");
    ResourceScopeLog log = waitForSnapshotForMethod("calculateSum");

    assertEquals("calculateSum", getAttr(log, "aws.di.method_name"));
    assertEquals("software.amazon.opentelemetry.di.app.service", getAttr(log, "aws.di.code_unit"));
  }
}
