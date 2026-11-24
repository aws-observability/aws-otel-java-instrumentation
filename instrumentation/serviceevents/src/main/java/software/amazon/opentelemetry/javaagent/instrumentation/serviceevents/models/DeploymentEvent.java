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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deployment event telemetry.
 *
 * <p>Captures deployment metadata (git commit, CI/CD info, SDK version) for the instrumented
 * service. Emitted once at startup and periodically every 24 hours.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeploymentEvent {

  @JsonProperty("telemetry_type")
  private String telemetryType = "DeploymentEvent";

  @JsonProperty("sdk_lang")
  private String sdkLang = "java";

  @JsonProperty("timestamp")
  private String timestamp;

  @JsonProperty("service_name")
  private String serviceName;

  @JsonProperty("environment")
  private String environment;

  @JsonProperty("sdk_version")
  private String sdkVersion;

  @JsonProperty("pid")
  private long pid;

  @JsonProperty("deployment_context")
  private DeploymentContext deploymentContext;

  public DeploymentEvent() {}

  // Getters
  public String getTelemetryType() {
    return telemetryType;
  }

  public String getSdkLang() {
    return sdkLang;
  }

  public String getTimestamp() {
    return timestamp;
  }

  public String getServiceName() {
    return serviceName;
  }

  public String getEnvironment() {
    return environment;
  }

  public String getSdkVersion() {
    return sdkVersion;
  }

  public long getPid() {
    return pid;
  }

  public DeploymentContext getDeploymentContext() {
    return deploymentContext;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Convert to map for JSON serialization. */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("telemetry_type", telemetryType);
    map.put("sdk_lang", sdkLang);
    map.put("timestamp", timestamp);
    map.put("service_name", serviceName);
    // Omit environment entirely when unset (no sentinel default).
    if (environment != null && !environment.isEmpty()) {
      map.put("environment", environment);
    }
    map.put("sdk_version", sdkVersion);
    map.put("pid", pid);

    if (deploymentContext != null) {
      map.put("deployment_context", deploymentContext.toMap());
    }

    return map;
  }

  /** Deployment context with git and CI/CD metadata. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class DeploymentContext {

    @JsonProperty("git_repo_url")
    private String gitRepoUrl;

    @JsonProperty("git_commit_sha")
    private String gitCommitSha;

    @JsonProperty("deployment_url")
    private String deploymentUrl;

    @JsonProperty("deployment_timestamp")
    private String deploymentTimestamp;

    @JsonProperty("deployment_id")
    private String deploymentId;

    public DeploymentContext() {}

    public DeploymentContext(
        String gitRepoUrl,
        String gitCommitSha,
        String deploymentUrl,
        String deploymentTimestamp,
        String deploymentId) {
      this.gitRepoUrl = gitRepoUrl;
      this.gitCommitSha = gitCommitSha;
      this.deploymentUrl = deploymentUrl;
      this.deploymentTimestamp = deploymentTimestamp;
      this.deploymentId = deploymentId;
    }

    public String getGitRepoUrl() {
      return gitRepoUrl;
    }

    public String getGitCommitSha() {
      return gitCommitSha;
    }

    public String getDeploymentUrl() {
      return deploymentUrl;
    }

    public String getDeploymentTimestamp() {
      return deploymentTimestamp;
    }

    public String getDeploymentId() {
      return deploymentId;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("git_repo_url", gitRepoUrl);
      map.put("git_commit_sha", gitCommitSha);
      map.put("deployment_url", deploymentUrl);
      map.put("deployment_timestamp", deploymentTimestamp);
      map.put("deployment_id", deploymentId);
      return map;
    }
  }

  /** Builder for DeploymentEvent. */
  public static class Builder {
    private final DeploymentEvent event = new DeploymentEvent();

    public Builder timestamp(String timestamp) {
      event.timestamp = timestamp;
      return this;
    }

    public Builder serviceName(String serviceName) {
      event.serviceName = serviceName;
      return this;
    }

    public Builder environment(String environment) {
      event.environment = environment;
      return this;
    }

    public Builder sdkVersion(String sdkVersion) {
      event.sdkVersion = sdkVersion;
      return this;
    }

    public Builder pid(long pid) {
      event.pid = pid;
      return this;
    }

    public Builder deploymentContext(DeploymentContext deploymentContext) {
      event.deploymentContext = deploymentContext;
      return this;
    }

    public DeploymentEvent build() {
      return event;
    }
  }
}
