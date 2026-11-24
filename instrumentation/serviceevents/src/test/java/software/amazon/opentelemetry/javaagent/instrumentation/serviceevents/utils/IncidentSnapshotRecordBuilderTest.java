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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IncidentSnapshotRecordBuilderTest {

  private IncidentSnapshotRecordBuilder newBuilder() {
    return new IncidentSnapshotRecordBuilder(
        "svc", "prod", "deploy-1", "2026-04-17T00:00:00Z", "", "abc123", "", 42L);
  }

  private IncidentSnapshotRecordBuilder.Inputs newInputs() {
    return new IncidentSnapshotRecordBuilder.Inputs(
        "snap_abc",
        "critical",
        "exception",
        "GET /api/users",
        "/api/users",
        "GET",
        123.4,
        500,
        "java.lang.RuntimeException",
        "boom",
        "stack...",
        "0123456789abcdef0123456789abcdef",
        "0123456789abcdef");
  }

  @Test
  @SuppressWarnings("unchecked")
  void byteInstrumentation_populatesCallPath() {
    List<Map<String, Object>> callPath = new ArrayList<>();
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("function_name", "com.example.Svc.handle");
    entry.put("caller_function_name", null);
    entry.put("duration_ns", 1000L);
    entry.put("error", false);
    entry.put("is_async", false);
    callPath.add(entry);

    Map<String, Object> record = newBuilder().build(newInputs(), callPath, 1234567890L);

    assertEquals("IncidentSnapshot", record.get("telemetry_type"));
    assertEquals("snap_abc", record.get("snapshot_id"));

    List<Map<String, Object>> exceptionInfo =
        (List<Map<String, Object>>) record.get("exception_info");
    assertNotNull(exceptionInfo);
    assertEquals(1, exceptionInfo.size());
    List<Map<String, Object>> actualCallPath =
        (List<Map<String, Object>>) exceptionInfo.get(0).get("call_path");
    assertEquals(1, actualCallPath.size());
    assertEquals("com.example.Svc.handle", actualCallPath.get(0).get("function_name"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void nullCallPath_yieldsEmptyCallPath() {
    // Lite mode (and any caller passing a null call path) must yield an empty call_path list.
    Map<String, Object> record = newBuilder().build(newInputs(), null, 1234567890L);

    List<Map<String, Object>> exceptionInfo =
        (List<Map<String, Object>>) record.get("exception_info");
    assertNotNull(exceptionInfo);
    assertEquals(1, exceptionInfo.size());
    List<Map<String, Object>> callPath =
        (List<Map<String, Object>>) exceptionInfo.get(0).get("call_path");
    assertTrue(callPath.isEmpty(), "call_path should be empty when no bytecode call path is given");
  }

  @Test
  void deploymentAndServiceFieldsAreThreaded() {
    Map<String, Object> record = newBuilder().build(newInputs(), null, 100L);
    assertEquals("svc", record.get("service"));
    assertEquals("prod", record.get("environment"));
    assertEquals("deploy-1", record.get("deployment_id"));
    assertEquals("abc123", record.get("git_commit_sha"));
    assertEquals(42L, record.get("pid"));
  }
}
