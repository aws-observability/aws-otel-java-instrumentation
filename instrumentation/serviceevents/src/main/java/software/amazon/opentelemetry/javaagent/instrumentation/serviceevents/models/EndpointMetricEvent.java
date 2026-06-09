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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoint metric telemetry event.
 *
 * <p>Represents aggregated metrics for an HTTP endpoint over a collection period.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EndpointMetricEvent {

  @JsonProperty("telemetry_type")
  private String telemetryType = "EndpointSummary";

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

  @JsonProperty("endpoint_id")
  private String endpointId;

  @JsonProperty("method")
  private String method;

  @JsonProperty("route")
  private String route;

  @JsonProperty("operation")
  private String operation;

  @JsonProperty("pid")
  private long pid;

  @JsonProperty("timestamp")
  private String timestamp;

  @JsonProperty("count")
  private int count;

  @JsonProperty("faults")
  private int faults;

  @JsonProperty("errors")
  private int errors;

  @JsonProperty("duration")
  private DurationMetrics duration;

  @JsonProperty("error_breakdown")
  private List<ErrorBreakdownEntry> errorBreakdown;

  @JsonProperty("incidents_exemplar")
  private List<IncidentExemplarEntry> incidentsExemplar;

  public EndpointMetricEvent() {
    this.errorBreakdown = new ArrayList<>();
    this.incidentsExemplar = new ArrayList<>();
  }

  // Builder pattern
  public static Builder builder() {
    return new Builder();
  }

  // Getters and setters
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

  public String getEndpointId() {
    return endpointId;
  }

  public void setEndpointId(String endpointId) {
    this.endpointId = endpointId;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public String getRoute() {
    return route;
  }

  public void setRoute(String route) {
    this.route = route;
  }

  public String getOperation() {
    return operation;
  }

  public void setOperation(String operation) {
    this.operation = operation;
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

  public int getCount() {
    return count;
  }

  public void setCount(int count) {
    this.count = count;
  }

  public int getFaults() {
    return faults;
  }

  public void setFaults(int faults) {
    this.faults = faults;
  }

  public int getErrors() {
    return errors;
  }

  public void setErrors(int errors) {
    this.errors = errors;
  }

  public DurationMetrics getDuration() {
    return duration;
  }

  public void setDuration(DurationMetrics duration) {
    this.duration = duration;
  }

  public List<ErrorBreakdownEntry> getErrorBreakdown() {
    return errorBreakdown;
  }

  public void setErrorBreakdown(List<ErrorBreakdownEntry> errorBreakdown) {
    this.errorBreakdown = errorBreakdown;
  }

  public List<IncidentExemplarEntry> getIncidentsExemplar() {
    return incidentsExemplar;
  }

  public void setIncidentsExemplar(List<IncidentExemplarEntry> incidentsExemplar) {
    this.incidentsExemplar = incidentsExemplar;
  }

  /** Convert to map for JSON serialization. */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    // Omit the environment dimension entirely when unset (no sentinel default). The root-level
    // member and the EMF Dimensions entry must be dropped together to stay EMF-valid.
    boolean hasEnvironment = environment != null && !environment.isEmpty();
    map.put("telemetry_type", telemetryType);
    map.put("sdk_lang", "java");
    if (hasEnvironment) {
      map.put("environment", environment);
    }
    map.put("service_name", serviceName);
    map.put("deployment_id", deploymentId);
    map.put("deployment_timestamp", deploymentTimestamp);
    map.put("deployment_url", deploymentUrl);
    map.put("git_commit_sha", gitCommitSha);
    map.put("git_repo_url", gitRepoUrl);
    map.put("endpoint_id", endpointId);
    map.put("method", method);
    map.put("route", route);
    map.put("operation", operation);
    map.put("pid", pid);
    map.put("timestamp", timestamp);
    map.put("count", count);
    map.put("faults", faults);
    map.put("errors", errors);
    if (duration != null) {
      map.put("duration", duration.toEmfMap());
    }

    List<Map<String, Object>> errorBreakdownMaps = new ArrayList<>();
    for (ErrorBreakdownEntry entry : errorBreakdown) {
      errorBreakdownMaps.add(entry.toMap());
    }
    map.put("error_breakdown", errorBreakdownMaps);

    List<Map<String, Object>> exemplarMaps = new ArrayList<>();
    if (incidentsExemplar != null) {
      for (IncidentExemplarEntry entry : incidentsExemplar) {
        exemplarMaps.add(entry.toMap());
      }
    }
    map.put("incidents_exemplar", exemplarMaps);

    // MetricsStats - EMF dimensions and metrics definition
    Map<String, Object> metricsStatEntry = new LinkedHashMap<>();
    List<String> dimensionKeys = new ArrayList<>();
    if (hasEnvironment) {
      dimensionKeys.add("environment");
    }
    dimensionKeys.add("service_name");
    dimensionKeys.add("operation");
    metricsStatEntry.put("Dimensions", Arrays.asList(dimensionKeys));
    List<Map<String, String>> metrics = new ArrayList<>();
    Map<String, String> durationMetric = new LinkedHashMap<>();
    durationMetric.put("Name", "duration");
    durationMetric.put("Unit", "Microseconds");
    metrics.add(durationMetric);
    Map<String, String> faultsMetric = new LinkedHashMap<>();
    faultsMetric.put("Name", "faults");
    faultsMetric.put("Unit", "Count");
    metrics.add(faultsMetric);
    Map<String, String> errorsMetric = new LinkedHashMap<>();
    errorsMetric.put("Name", "errors");
    errorsMetric.put("Unit", "Count");
    metrics.add(errorsMetric);
    metricsStatEntry.put("Metrics", metrics);
    map.put("MetricsStats", Arrays.asList(metricsStatEntry));

    return map;
  }

  /** Error detail with type and origin function. */
  public static class ErrorDetail {
    @JsonProperty("error_type")
    private String errorType;

    @JsonProperty("function_id")
    private String functionId;

    public ErrorDetail() {}

    public ErrorDetail(String errorType, String functionId) {
      this.errorType = errorType;
      this.functionId = functionId;
    }

    public String getErrorType() {
      return errorType;
    }

    public void setErrorType(String errorType) {
      this.errorType = errorType;
    }

    public String getFunctionId() {
      return functionId;
    }

    public void setFunctionId(String functionId) {
      this.functionId = functionId;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("error_type", errorType);
      map.put("function_id", functionId);
      return map;
    }
  }

  /** Error breakdown entry grouping errors by HTTP status code. */
  public static class ErrorBreakdownEntry {
    @JsonProperty("errors")
    private List<ErrorDetail> errors;

    @JsonProperty("count")
    private int count;

    @JsonProperty("failure_type")
    private String failureType;

    public ErrorBreakdownEntry() {
      this.errors = new ArrayList<>();
    }

    public ErrorBreakdownEntry(List<ErrorDetail> errors, int count, String failureType) {
      this.errors = errors;
      this.count = count;
      this.failureType = failureType;
    }

    public List<ErrorDetail> getErrors() {
      return errors;
    }

    public void setErrors(List<ErrorDetail> errors) {
      this.errors = errors;
    }

    public int getCount() {
      return count;
    }

    public void setCount(int count) {
      this.count = count;
    }

    public String getFailureType() {
      return failureType;
    }

    public void setFailureType(String failureType) {
      this.failureType = failureType;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      List<Map<String, Object>> errorMaps = new ArrayList<>();
      for (ErrorDetail error : errors) {
        errorMaps.add(error.toMap());
      }
      map.put("errors", errorMaps);
      map.put("count", count);
      map.put("failure_type", failureType);
      return map;
    }
  }

  /** Incident exemplar reference for linking EndpointSummary to IncidentSnapshots. */
  public static class IncidentExemplarEntry {
    @JsonProperty("snapshot_id")
    private String snapshotId;

    @JsonProperty("trigger_type")
    private String triggerType;

    @JsonProperty("severity")
    private String severity;

    @JsonProperty("timestamp")
    private long timestamp;

    public IncidentExemplarEntry() {}

    public IncidentExemplarEntry(
        String snapshotId, String triggerType, String severity, long timestamp) {
      this.snapshotId = snapshotId;
      this.triggerType = triggerType;
      this.severity = severity;
      this.timestamp = timestamp;
    }

    public String getSnapshotId() {
      return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
      this.snapshotId = snapshotId;
    }

    public String getTriggerType() {
      return triggerType;
    }

    public void setTriggerType(String triggerType) {
      this.triggerType = triggerType;
    }

    public String getSeverity() {
      return severity;
    }

    public void setSeverity(String severity) {
      this.severity = severity;
    }

    public long getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(long timestamp) {
      this.timestamp = timestamp;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("snapshot_id", snapshotId);
      map.put("trigger_type", triggerType);
      map.put("severity", severity);
      map.put("timestamp", timestamp);
      return map;
    }
  }

  /** Builder for EndpointMetricEvent. */
  public static class Builder {
    private final EndpointMetricEvent event = new EndpointMetricEvent();

    public Builder environment(String environment) {
      event.environment = environment;
      return this;
    }

    public Builder serviceName(String serviceName) {
      event.serviceName = serviceName;
      return this;
    }

    public Builder deploymentId(String deploymentId) {
      event.deploymentId = deploymentId;
      return this;
    }

    public Builder deploymentTimestamp(String deploymentTimestamp) {
      event.deploymentTimestamp = deploymentTimestamp;
      return this;
    }

    public Builder deploymentUrl(String deploymentUrl) {
      event.deploymentUrl = deploymentUrl;
      return this;
    }

    public Builder gitCommitSha(String gitCommitSha) {
      event.gitCommitSha = gitCommitSha;
      return this;
    }

    public Builder gitRepoUrl(String gitRepoUrl) {
      event.gitRepoUrl = gitRepoUrl;
      return this;
    }

    public Builder endpointId(String endpointId) {
      event.endpointId = endpointId;
      return this;
    }

    public Builder method(String method) {
      event.method = method;
      return this;
    }

    public Builder route(String route) {
      event.route = route;
      return this;
    }

    public Builder operation(String operation) {
      event.operation = operation;
      return this;
    }

    public Builder pid(long pid) {
      event.pid = pid;
      return this;
    }

    public Builder timestamp(String timestamp) {
      event.timestamp = timestamp;
      return this;
    }

    public Builder count(int count) {
      event.count = count;
      return this;
    }

    public Builder faults(int faults) {
      event.faults = faults;
      return this;
    }

    public Builder errors(int errors) {
      event.errors = errors;
      return this;
    }

    public Builder duration(DurationMetrics duration) {
      event.duration = duration;
      return this;
    }

    public Builder errorBreakdown(List<ErrorBreakdownEntry> errorBreakdown) {
      event.errorBreakdown = errorBreakdown;
      return this;
    }

    public Builder incidentsExemplar(List<IncidentExemplarEntry> incidentsExemplar) {
      event.incidentsExemplar = incidentsExemplar;
      return this;
    }

    public EndpointMetricEvent build() {
      return event;
    }
  }
}
