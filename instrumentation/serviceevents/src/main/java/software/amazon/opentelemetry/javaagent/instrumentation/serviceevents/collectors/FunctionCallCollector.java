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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsOtlpEmitter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.CloudWatchMetadata;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.DurationMetrics;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.FunctionCallMetrics;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.ProcessUtils;
import software.amazon.opentelemetry.serviceevents.AggregationData;
import software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore;

/**
 * Collector for function call metrics using CloudWatch EMF format.
 *
 * <p>Periodically polls aggregated function telemetry from {@link ServiceEventsDataStore}
 * (bootstrap classloader) and exports to console or file.
 */
public class FunctionCallCollector extends BaseCollector {

  private static final Logger logger = Logger.getLogger(FunctionCallCollector.class.getName());
  private static final DateTimeFormatter ISO_FORMATTER =
      DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

  private final String environment;
  private final String serviceName;
  private final String deploymentId;
  private final String deploymentTimestamp;
  private final String deploymentUrl;
  private final String gitCommitSha;
  private final String gitRepoUrl;
  private final long pid;
  private final ObjectMapper objectMapper;

  /**
   * Initialize the function call collector.
   *
   * @param flushIntervalMs How often to collect and export data
   * @param environment Deployment environment
   * @param serviceName Service name
   * @param deploymentId Deployment identifier
   * @param deploymentTimestamp Deployment timestamp
   * @param deploymentUrl Deployment URL
   * @param gitCommitSha Git commit SHA
   * @param gitRepoUrl Git repository URL
   * @param otlpEmitter Optional OTLP emitter for sending OTLP signals
   */
  public FunctionCallCollector(
      int flushIntervalMs,
      String environment,
      String serviceName,
      String deploymentId,
      String deploymentTimestamp,
      String deploymentUrl,
      String gitCommitSha,
      String gitRepoUrl,
      ServiceEventsOtlpEmitter otlpEmitter) {
    super(flushIntervalMs, "FunctionCallCollector", otlpEmitter);
    // No sentinel: environment stays null/empty when unset so emit paths omit it.
    this.environment =
        environment != null ? environment : System.getenv().getOrDefault("ENVIRONMENT", null);
    this.serviceName = serviceName != null ? serviceName : "UnknownService";
    this.deploymentId = deploymentId != null ? deploymentId : "unknown-deployment-id";
    this.deploymentTimestamp = deploymentTimestamp != null ? deploymentTimestamp : "";
    this.deploymentUrl = deploymentUrl != null ? deploymentUrl : "";
    this.gitCommitSha = gitCommitSha != null ? gitCommitSha : "";
    this.gitRepoUrl = gitRepoUrl != null ? gitRepoUrl : "";
    this.pid = ProcessUtils.currentPid();
    this.objectMapper = new ObjectMapper();
  }

  @Override
  protected void collect() {
    // Read directly from bootstrap-loaded ServiceEventsDataStore
    Map<String, Map<String, AggregationData>> aggregations =
        ServiceEventsDataStore.getAndSwapAggregations();

    if (aggregations == null || aggregations.isEmpty()) {
      // Either no function call captured, or methodExit is recording into the
      // OTel histogram bridge directly (in which case the
      // pre-aggregation map stays empty by design).
      logger.fine("No function call data to export");
      return;
    }

    // Format and export each function's metrics
    List<FunctionCallMetrics> events = formatFunctionCalls(aggregations);

    if (!events.isEmpty()) {
      if (otlpEmitter != null) {
        for (FunctionCallMetrics event : events) {
          otlpEmitter.emitFunctionCall(event);
        }
      } else {
        exportToConsole(events);
      }
      logger.info("Exported " + events.size() + " function call metrics");
    }
  }

  private List<FunctionCallMetrics> formatFunctionCalls(
      Map<String, Map<String, AggregationData>> aggregations) {
    List<FunctionCallMetrics> events = new ArrayList<>();
    String timestamp = ISO_FORMATTER.format(Instant.now());
    long timestampMs = System.currentTimeMillis();

    for (Map.Entry<String, Map<String, AggregationData>> operationEntry : aggregations.entrySet()) {
      String operation = operationEntry.getKey();
      Map<String, AggregationData> functionMap = operationEntry.getValue();

      for (Map.Entry<String, AggregationData> aggEntry : functionMap.entrySet()) {
        String functionId = aggEntry.getKey();
        AggregationData agg = aggEntry.getValue();

        if (agg.getSampledCalls() == 0) {
          continue;
        }

        // Get most common caller
        String caller = null;
        Map<String, Integer> callers = agg.getCallers();
        if (callers != null && !callers.isEmpty()) {
          caller =
              callers.entrySet().stream()
                  .max(Map.Entry.comparingByValue())
                  .map(Map.Entry::getKey)
                  .orElse(null);
        }

        // Create simple duration metrics
        DurationMetrics duration = createDurationMetrics(agg);

        // Create CloudWatch EMF metadata. Include the environment dimension only when set, to
        // match the root-level environment member dropped in FunctionCallMetrics.toEmfMap().
        CloudWatchMetadata awsMetadata =
            CloudWatchMetadata.forFunctionCallMetrics(
                timestampMs, environment != null && !environment.isEmpty());

        // Create FunctionCallMetrics object
        FunctionCallMetrics metrics =
            FunctionCallMetrics.builder()
                .environment(environment)
                .serviceName(serviceName)
                .deploymentId(deploymentId)
                .deploymentTimestamp(deploymentTimestamp)
                .deploymentUrl(deploymentUrl)
                .gitCommitSha(gitCommitSha)
                .gitRepoUrl(gitRepoUrl)
                .functionId(functionId)
                .operation(operation)
                .caller(caller)
                .pid(pid)
                .timestamp(timestamp)
                .exceptions(agg.getExceptions())
                .duration(duration)
                .aws(awsMetadata)
                .build();

        events.add(metrics);
      }
    }

    return events;
  }

  /** Create duration metrics from AggregationData. */
  private DurationMetrics createDurationMetrics(AggregationData agg) {
    long sampledCalls = agg.getSampledCalls();
    long totalTimeNs = agg.getTotalTimeNs();

    double avgUs = (sampledCalls > 0) ? (totalTimeNs / (double) sampledCalls) / 1000.0 : 0;
    double sumUs = totalTimeNs / 1000.0;

    List<Double> values = new ArrayList<>();
    List<Double> counts = new ArrayList<>();
    if (sampledCalls > 0) {
      values.add(avgUs);
      counts.add((double) sampledCalls);
    }
    return new DurationMetrics(values, counts, avgUs, avgUs, (int) sampledCalls, sumUs);
  }

  private void exportToConsole(List<FunctionCallMetrics> events) {
    System.out.println("\n" + ProcessUtils.repeat("=", 80));
    System.out.println("SERVICE_EVENTS FUNCTION CALL TELEMETRY (EMF FORMAT)");
    System.out.println(ProcessUtils.repeat("=", 80));

    for (FunctionCallMetrics event : events) {
      try {
        String json = objectMapper.writeValueAsString(event.toEmfMap());
        System.out.println(json);
      } catch (JsonProcessingException e) {
        logger.log(Level.WARNING, "Failed to serialize function call metrics", e);
      }
    }

    System.out.println("\n" + ProcessUtils.repeat("=", 80) + "\n");
  }
}
