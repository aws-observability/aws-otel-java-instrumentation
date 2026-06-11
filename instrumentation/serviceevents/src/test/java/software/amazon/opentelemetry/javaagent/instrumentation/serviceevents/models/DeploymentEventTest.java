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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for DeploymentEvent model and its nested DeploymentContext. */
class DeploymentEventTest {

  @Test
  void testBuilderSetsAllFields() {
    DeploymentEvent.DeploymentContext ctx =
        new DeploymentEvent.DeploymentContext(
            "https://github.com/org/repo",
            "abc123",
            "https://deploy.example.com/42",
            "2026-04-09T10:00:00Z",
            "deploy-42");

    DeploymentEvent event =
        DeploymentEvent.builder()
            .timestamp("2026-04-09T12:00:00Z")
            .serviceName("my-service")
            .environment("production")
            .sdkVersion("2.20.0")
            .pid(12345)
            .deploymentContext(ctx)
            .build();

    assertEquals("DeploymentEvent", event.getTelemetryType());
    assertEquals("java", event.getSdkLang());
    assertEquals("2026-04-09T12:00:00Z", event.getTimestamp());
    assertEquals("my-service", event.getServiceName());
    assertEquals("production", event.getEnvironment());
    assertEquals("2.20.0", event.getSdkVersion());
    assertEquals(12345, event.getPid());
    assertNotNull(event.getDeploymentContext());
    assertEquals("abc123", event.getDeploymentContext().getGitCommitSha());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testToMapStructure() {
    DeploymentEvent.DeploymentContext ctx =
        new DeploymentEvent.DeploymentContext(
            "https://github.com/org/repo",
            "sha256",
            "https://ci/run/1",
            "2026-01-01T00:00:00Z",
            "dep-1");

    DeploymentEvent event =
        DeploymentEvent.builder()
            .timestamp("2026-04-09T12:00:00Z")
            .serviceName("svc")
            .environment("staging")
            .sdkVersion("1.0.0")
            .pid(999)
            .deploymentContext(ctx)
            .build();

    Map<String, Object> map = event.toMap();

    assertEquals("DeploymentEvent", map.get("telemetry_type"));
    assertEquals("java", map.get("sdk_lang"));
    assertEquals("2026-04-09T12:00:00Z", map.get("timestamp"));
    assertEquals("svc", map.get("service_name"));
    assertEquals("staging", map.get("environment"));
    assertEquals("1.0.0", map.get("sdk_version"));
    assertEquals(999L, map.get("pid"));

    // Verify nested deployment_context
    assertTrue(map.containsKey("deployment_context"));
    Map<String, Object> ctxMap = (Map<String, Object>) map.get("deployment_context");
    assertEquals("https://github.com/org/repo", ctxMap.get("git_repo_url"));
    assertEquals("sha256", ctxMap.get("git_commit_sha"));
    assertEquals("https://ci/run/1", ctxMap.get("deployment_url"));
    assertEquals("2026-01-01T00:00:00Z", ctxMap.get("deployment_timestamp"));
    assertEquals("dep-1", ctxMap.get("deployment_id"));
    assertEquals(5, ctxMap.size());
  }

  @Test
  void testToMapWithoutDeploymentContext() {
    DeploymentEvent event =
        DeploymentEvent.builder()
            .timestamp("2026-04-09T12:00:00Z")
            .serviceName("svc")
            .environment("dev")
            .sdkVersion("1.0.0")
            .pid(1)
            .build();

    Map<String, Object> map = event.toMap();

    assertFalse(map.containsKey("deployment_context"));
    assertEquals(7, map.size());
  }

  @Test
  void testDeploymentContextToMap() {
    DeploymentEvent.DeploymentContext ctx =
        new DeploymentEvent.DeploymentContext("repo-url", "commit", "deploy-url", "ts", "id");

    Map<String, Object> map = ctx.toMap();

    assertEquals("repo-url", map.get("git_repo_url"));
    assertEquals("commit", map.get("git_commit_sha"));
    assertEquals("deploy-url", map.get("deployment_url"));
    assertEquals("ts", map.get("deployment_timestamp"));
    assertEquals("id", map.get("deployment_id"));
    assertEquals(5, map.size());
  }

  @Test
  void testDefaultConstructor() {
    DeploymentEvent event = new DeploymentEvent();

    assertEquals("DeploymentEvent", event.getTelemetryType());
    assertEquals("java", event.getSdkLang());
    assertNull(event.getDeploymentContext());
  }
}
