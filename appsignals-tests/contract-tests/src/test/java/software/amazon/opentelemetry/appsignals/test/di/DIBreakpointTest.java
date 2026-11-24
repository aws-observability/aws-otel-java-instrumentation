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
 * Tests for function-level BREAKPOINT (lineNumber=0) generating method-level snapshots via OTLP.
 *
 * <p>Target: InstrumentedService.processData() — BREAKPOINT config with locationHash
 * "aabb000000000002". Endpoint: GET /success
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DIBreakpointTest extends DIContractTestBase {

  @Test
  @Order(1)
  void functionLevelSnapshotGenerated() throws Exception {
    String response = sendRequest("/success");
    assertNotNull(response);

    List<ResourceScopeLog> snapshots = waitForSnapshots(1);
    assertFalse(snapshots.isEmpty(), "At least one snapshot LogRecord should be produced");

    List<ResourceScopeLog> methodSnapshots = logsForMethod(snapshots, "processData");
    assertFalse(methodSnapshots.isEmpty(), "Expected snapshot for processData");
  }

  @Test
  @Order(2)
  void snapshotHasRequiredAttributes() throws Exception {
    sendRequest("/success");
    ResourceScopeLog log = waitForSnapshotForMethod("processData");

    assertEquals(
        DI_EVENT_NAME,
        getAttr(log, "event.name"),
        "event.name must be aws.dynamic_instrumentation.snapshot");
    assertNotNull(getAttr(log, "aws.di.snapshot_id"), "aws.di.snapshot_id must be present");
    assertNotNull(getAttr(log, "aws.di.method_name"), "aws.di.method_name must be present");
    assertNotNull(getAttr(log, "aws.di.code_unit"), "aws.di.code_unit must be present");
    assertEquals("method", getAttr(log, "aws.di.instrumentation_level"), "Should be method-level");

    // snapshot_id should be UUID format (36 chars)
    String snapshotId = getAttr(log, "aws.di.snapshot_id");
    assertEquals(36, snapshotId.length(), "snapshot_id should be UUID format (36 chars)");
  }

  @Test
  @Order(3)
  void snapshotHasTraceContext() throws Exception {
    sendRequest("/success");
    ResourceScopeLog log = waitForSnapshotForMethod("processData");

    // LogRecord should carry non-zero trace_id and span_id
    assertTrue(log.getLog().getTraceId().size() > 0, "trace_id should be non-empty");
    assertTrue(log.getLog().getSpanId().size() > 0, "span_id should be non-empty");
  }

  @Test
  @Order(4)
  void snapshotHasCorrectLocation() throws Exception {
    sendRequest("/success");
    ResourceScopeLog log = waitForSnapshotForMethod("processData");

    assertEquals(
        "software.amazon.opentelemetry.di.app.service",
        getAttr(log, "aws.di.code_unit"),
        "code_unit should match package");
    assertEquals("processData", getAttr(log, "aws.di.method_name"));

    String filePath = getAttr(log, "aws.di.file_path");
    assertNotNull(filePath, "aws.di.file_path should be present");
    assertTrue(
        filePath.endsWith("InstrumentedService.java"),
        "file_path should end with InstrumentedService.java, got: " + filePath);
  }

  @Test
  @Order(5)
  @SuppressWarnings("unchecked")
  void snapshotBodyHasCapturesAndStack() throws Exception {
    sendRequest("/success");
    ResourceScopeLog log = waitForSnapshotForMethod("processData");

    Map<String, Object> body = getBody(log);
    assertFalse(body.isEmpty(), "Body should not be empty");
    assertTrue(body.containsKey("captures"), "Body should have captures");
    assertTrue(body.containsKey("stack"), "Body should have stack");

    // Stack should be a non-empty list
    Object stack = body.get("stack");
    assertInstanceOf(List.class, stack, "stack should be a list");
    assertFalse(((List<?>) stack).isEmpty(), "stack should not be empty");

    // captures should have entry with arguments
    Map<String, Object> captures = (Map<String, Object>) body.get("captures");
    assertTrue(
        captures.containsKey("entry") || captures.containsKey("return"),
        "Method-level captures should have entry or return");
  }
}
