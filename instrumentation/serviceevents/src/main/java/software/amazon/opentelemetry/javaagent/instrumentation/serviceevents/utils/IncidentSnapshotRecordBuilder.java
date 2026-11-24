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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an IncidentSnapshot telemetry record. Shared by the synchronous direct emitter
 * (byte-instrumentation mode, which attaches a {@code call_path} list) and the lite-mode drainer
 * (which emits an empty {@code call_path}).
 */
public class IncidentSnapshotRecordBuilder {

  private final String serviceName;
  private final String environment;
  private final String deploymentId;
  private final String deploymentTimestamp;
  private final String deploymentUrl;
  private final String gitCommitSha;
  private final String gitRepoUrl;
  private final long pid;

  public IncidentSnapshotRecordBuilder(
      String serviceName,
      String environment,
      String deploymentId,
      String deploymentTimestamp,
      String deploymentUrl,
      String gitCommitSha,
      String gitRepoUrl,
      long pid) {
    this.serviceName = serviceName;
    this.environment = environment;
    this.deploymentId = deploymentId;
    this.deploymentTimestamp = deploymentTimestamp;
    this.deploymentUrl = deploymentUrl;
    this.gitCommitSha = gitCommitSha;
    this.gitRepoUrl = gitRepoUrl;
    this.pid = pid;
  }

  /** Incident attributes consumed by both emitters. */
  public static class Inputs {
    public final String snapshotId;
    public final String severity;
    public final String triggerType;
    public final String operation;
    public final String route;
    public final String method;
    public final double durationMs;
    public final int statusCode;
    public final String exceptionType;
    public final String exceptionMessage;
    public final String stackTrace;
    public final String traceId;
    public final String spanId;

    public Inputs(
        String snapshotId,
        String severity,
        String triggerType,
        String operation,
        String route,
        String method,
        double durationMs,
        int statusCode,
        String exceptionType,
        String exceptionMessage,
        String stackTrace,
        String traceId,
        String spanId) {
      this.snapshotId = snapshotId;
      this.severity = severity;
      this.triggerType = triggerType;
      this.operation = operation;
      this.route = route;
      this.method = method;
      this.durationMs = durationMs;
      this.statusCode = statusCode;
      this.exceptionType = exceptionType;
      this.exceptionMessage = exceptionMessage;
      this.stackTrace = stackTrace;
      this.traceId = traceId;
      this.spanId = spanId;
    }
  }

  /**
   * Build the IncidentSnapshot record map.
   *
   * @param inputs Incident attributes
   * @param callPath Byte-instrumentation call path entries (nullable/empty = omit from payload)
   * @param timestamp Event timestamp (epoch ms)
   */
  public Map<String, Object> build(
      Inputs inputs, List<Map<String, Object>> callPath, long timestamp) {

    Map<String, Object> record = new LinkedHashMap<>();
    record.put("telemetry_type", "IncidentSnapshot");
    record.put("sdk_lang", "java");
    record.put("snapshot_id", inputs.snapshotId);
    record.put("timestamp", timestamp);
    record.put("severity", inputs.severity);
    record.put("trigger_type", inputs.triggerType);
    record.put("service", serviceName);
    // Omit environment entirely when unset (no sentinel default).
    if (environment != null && !environment.isEmpty()) {
      record.put("environment", environment);
    }
    record.put("deployment_id", deploymentId);
    record.put("deployment_timestamp", deploymentTimestamp);
    record.put("deployment_url", deploymentUrl);
    record.put("git_commit_sha", gitCommitSha);
    record.put("git_repo_url", gitRepoUrl);
    record.put("affected_endpoint", inputs.operation != null ? inputs.operation : inputs.route);
    record.put("endpoint_id", EndpointIdGenerator.generateEndpointId(inputs.route, inputs.method));
    record.put("pid", pid);
    record.put("duration_ms", inputs.durationMs);

    // Always emit a single exception_info entry when there is either an exception to report
    // or a captured call path to attach. Latency-triggered incidents have no exception but
    // still carry the byte-instrumentation call path, which consumers expect inside
    // exception_info[0].call_path (matches Python serviceevents schema).
    List<Map<String, Object>> exceptionInfoList = new ArrayList<>();
    boolean hasCallPath = callPath != null && !callPath.isEmpty();
    if (inputs.exceptionType != null || hasCallPath) {
      Map<String, Object> exInfo = new LinkedHashMap<>();
      exInfo.put("exception_type", inputs.exceptionType != null ? inputs.exceptionType : "");
      exInfo.put(
          "exception_message", inputs.exceptionMessage != null ? inputs.exceptionMessage : "");
      exInfo.put("stack_trace", inputs.stackTrace != null ? inputs.stackTrace : "");
      exInfo.put("call_path", callPath != null ? callPath : new ArrayList<>());
      exceptionInfoList.add(exInfo);
    }
    record.put("exception_info", exceptionInfoList);

    Map<String, Object> requestContext = new LinkedHashMap<>();
    requestContext.put("type", "http");
    requestContext.put("timestamp", timestamp);
    requestContext.put("status_code", inputs.statusCode);
    record.put("request_context", requestContext);

    Map<String, Object> correlation = new LinkedHashMap<>();
    if (inputs.traceId != null) {
      correlation.put("trace_id", inputs.traceId);
    }
    if (inputs.spanId != null) {
      correlation.put("span_id", inputs.spanId);
    }
    record.put("telemetry_correlation", correlation);

    return record;
  }
}
