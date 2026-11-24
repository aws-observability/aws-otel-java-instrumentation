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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Function call telemetry event using CloudWatch EMF format.
 *
 * <p>This schema represents aggregated metrics for a function over a collection period.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FunctionCallMetrics {

  @JsonProperty("version")
  private String version = "1";

  @JsonProperty("telemetry_type")
  private String telemetryType = "FunctionCall";

  @JsonProperty("environment")
  private String environment;

  @JsonProperty("service_name")
  private String serviceName;

  @JsonProperty("deployment_id")
  private String deploymentId;

  @JsonProperty("deployment_timestamp")
  private String deploymentTimestamp;

  @JsonProperty("deployment_url")
  private String deploymentUrl;

  @JsonProperty("git_commit_sha")
  private String gitCommitSha;

  @JsonProperty("git_repo_url")
  private String gitRepoUrl;

  @JsonProperty("function_id")
  private String functionId;

  @JsonProperty("operation")
  private String operation;

  @JsonProperty("caller")
  private String caller;

  @JsonProperty("pid")
  private long pid;

  @JsonProperty("timestamp")
  private String timestamp;

  @JsonProperty("exceptions")
  private Map<String, Integer> exceptions;

  @JsonProperty("duration")
  private DurationMetrics duration;

  @JsonProperty("_aws")
  private CloudWatchMetadata aws;

  public FunctionCallMetrics() {
    this.exceptions = new HashMap<>();
  }

  // Builder pattern
  public static Builder builder() {
    return new Builder();
  }

  // Getters and setters
  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getTelemetryType() {
    return telemetryType;
  }

  public void setTelemetryType(String telemetryType) {
    this.telemetryType = telemetryType;
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(String serviceName) {
    this.serviceName = serviceName;
  }

  public String getDeploymentId() {
    return deploymentId;
  }

  public void setDeploymentId(String deploymentId) {
    this.deploymentId = deploymentId;
  }

  public String getDeploymentTimestamp() {
    return deploymentTimestamp;
  }

  public void setDeploymentTimestamp(String deploymentTimestamp) {
    this.deploymentTimestamp = deploymentTimestamp;
  }

  public String getDeploymentUrl() {
    return deploymentUrl;
  }

  public void setDeploymentUrl(String deploymentUrl) {
    this.deploymentUrl = deploymentUrl;
  }

  public String getGitCommitSha() {
    return gitCommitSha;
  }

  public void setGitCommitSha(String gitCommitSha) {
    this.gitCommitSha = gitCommitSha;
  }

  public String getGitRepoUrl() {
    return gitRepoUrl;
  }

  public void setGitRepoUrl(String gitRepoUrl) {
    this.gitRepoUrl = gitRepoUrl;
  }

  public String getFunctionId() {
    return functionId;
  }

  public void setFunctionId(String functionId) {
    this.functionId = functionId;
  }

  public String getOperation() {
    return operation;
  }

  public void setOperation(String operation) {
    this.operation = operation;
  }

  public String getCaller() {
    return caller;
  }

  public void setCaller(String caller) {
    this.caller = caller;
  }

  public long getPid() {
    return pid;
  }

  public void setPid(long pid) {
    this.pid = pid;
  }

  public String getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(String timestamp) {
    this.timestamp = timestamp;
  }

  public Map<String, Integer> getExceptions() {
    return exceptions;
  }

  public void setExceptions(Map<String, Integer> exceptions) {
    this.exceptions = exceptions;
  }

  public DurationMetrics getDuration() {
    return duration;
  }

  public void setDuration(DurationMetrics duration) {
    this.duration = duration;
  }

  public CloudWatchMetadata getAws() {
    return aws;
  }

  public void setAws(CloudWatchMetadata aws) {
    this.aws = aws;
  }

  /** Convert to EMF-compliant map for JSON serialization. */
  public Map<String, Object> toEmfMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("version", version);
    map.put("telemetry_type", telemetryType);
    map.put("sdk_lang", "java");
    // Omit environment entirely when unset (no sentinel default). The matching EMF Dimensions
    // entry is dropped in CloudWatchMetadata.functionCallMetrics() under the same condition.
    if (environment != null && !environment.isEmpty()) {
      map.put("environment", environment);
    }
    map.put("service_name", serviceName);
    map.put("deployment_id", deploymentId);
    map.put("deployment_timestamp", deploymentTimestamp);
    map.put("deployment_url", deploymentUrl);
    map.put("git_commit_sha", gitCommitSha);
    map.put("git_repo_url", gitRepoUrl);
    map.put("function_id", functionId);
    if (operation != null) {
      map.put("operation", operation);
    }
    if (caller != null) {
      map.put("caller", caller);
    }
    map.put("pid", pid);
    map.put("timestamp", timestamp);
    if (exceptions != null && !exceptions.isEmpty()) {
      map.put("exceptions", exceptions);
    }
    if (duration != null) {
      map.put("duration", duration.toEmfMap());
    }
    if (aws != null) {
      map.put("_aws", aws);
    }
    return map;
  }

  /** Builder for FunctionCallMetrics. */
  public static class Builder {
    private final FunctionCallMetrics metrics = new FunctionCallMetrics();

    public Builder environment(String environment) {
      metrics.environment = environment;
      return this;
    }

    public Builder serviceName(String serviceName) {
      metrics.serviceName = serviceName;
      return this;
    }

    public Builder deploymentId(String deploymentId) {
      metrics.deploymentId = deploymentId;
      return this;
    }

    public Builder deploymentTimestamp(String deploymentTimestamp) {
      metrics.deploymentTimestamp = deploymentTimestamp;
      return this;
    }

    public Builder deploymentUrl(String deploymentUrl) {
      metrics.deploymentUrl = deploymentUrl;
      return this;
    }

    public Builder gitCommitSha(String gitCommitSha) {
      metrics.gitCommitSha = gitCommitSha;
      return this;
    }

    public Builder gitRepoUrl(String gitRepoUrl) {
      metrics.gitRepoUrl = gitRepoUrl;
      return this;
    }

    public Builder functionId(String functionId) {
      metrics.functionId = functionId;
      return this;
    }

    public Builder operation(String operation) {
      metrics.operation = operation;
      return this;
    }

    public Builder caller(String caller) {
      metrics.caller = caller;
      return this;
    }

    public Builder pid(long pid) {
      metrics.pid = pid;
      return this;
    }

    public Builder timestamp(String timestamp) {
      metrics.timestamp = timestamp;
      return this;
    }

    public Builder exceptions(Map<String, Integer> exceptions) {
      metrics.exceptions = exceptions;
      return this;
    }

    public Builder duration(DurationMetrics duration) {
      metrics.duration = duration;
      return this;
    }

    public Builder aws(CloudWatchMetadata aws) {
      metrics.aws = aws;
      return this;
    }

    public FunctionCallMetrics build() {
      return metrics;
    }
  }
}
