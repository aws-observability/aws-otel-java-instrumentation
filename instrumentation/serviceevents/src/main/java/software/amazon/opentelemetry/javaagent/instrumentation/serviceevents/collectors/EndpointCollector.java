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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsOtlpEmitter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.DurationMetrics;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.EndpointMetricEvent;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.EndpointMetricEvent.ErrorBreakdownEntry;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.EndpointMetricEvent.ErrorDetail;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.ExceptionMetricEvent;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.EndpointIdGenerator;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.ProcessUtils;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.SehHistogram;
import software.amazon.opentelemetry.serviceevents.EndpointAggregation;
import software.amazon.opentelemetry.serviceevents.EndpointErrorData;
import software.amazon.opentelemetry.serviceevents.IncidentExemplar;
import software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore;

/**
 * Collector for HTTP endpoint metrics.
 *
 * <p>Reads aggregated endpoint data from {@link ServiceEventsDataStore} (bootstrap classloader) and
 * periodically exports in CloudWatch EMF format.
 */
public class EndpointCollector extends BaseCollector {

  private static final Logger logger = Logger.getLogger(EndpointCollector.class.getName());
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
  private final boolean suppressEndpointSummary;

  /**
   * Initialize the endpoint metric collector.
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
   * @param suppressEndpointSummary When true, skip emitting EndpointSummary LogRecords (App Signals
   *     already carries equivalent per-endpoint metrics). The collector still runs to feed
   *     per-endpoint latency histograms into IncidentSnapshot's threshold triggering. Error-type
   *     metrics (EndpointErrorMetric) still emit.
   */
  public EndpointCollector(
      int flushIntervalMs,
      String environment,
      String serviceName,
      String deploymentId,
      String deploymentTimestamp,
      String deploymentUrl,
      String gitCommitSha,
      String gitRepoUrl,
      ServiceEventsOtlpEmitter otlpEmitter,
      boolean suppressEndpointSummary) {
    super(flushIntervalMs, "EndpointMetricCollector", otlpEmitter);
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
    this.suppressEndpointSummary = suppressEndpointSummary;
  }

  /** Maximum number of exception EMF metrics to emit per endpoint. */
  private static final int MAX_EXCEPTION_METRICS = 5;

  @Override
  protected void collect() {
    // Read directly from bootstrap-loaded ServiceEventsDataStore
    Map<String, EndpointAggregation> aggregations =
        ServiceEventsDataStore.getAndSwapEndpointAggregations();

    if (aggregations == null || aggregations.isEmpty()) {
      logger.fine("No endpoint metrics to export");
      return;
    }

    List<EndpointMetricEvent> events = formatEndpointMetrics(aggregations);

    if (!events.isEmpty()) {
      // OTLP export
      if (otlpEmitter != null) {
        // Suppress EndpointSummary when Application Signals is enabled —
        // App Signals emits equivalent per-endpoint duration + error metrics,
        // so emitting both would duplicate data on the backend. Error metrics
        // (EndpointErrorMetric) carry ServiceEvents-specific per-exception-type
        // breakdown that App Signals doesn't, so those still emit.
        if (!suppressEndpointSummary) {
          for (EndpointMetricEvent event : events) {
            otlpEmitter.emitEndpointSummary(event);
          }
        }
        long timestampMs = Instant.now().toEpochMilli();
        List<ExceptionMetricEvent> exceptionEvents = buildExceptionMetrics(events, timestampMs);
        if (!exceptionEvents.isEmpty()) {
          otlpEmitter.emitEndpointErrorMetrics(exceptionEvents);
        }
      } else {
        if (!suppressEndpointSummary) {
          exportToConsole(events);
        }
        long timestampMs = Instant.now().toEpochMilli();
        List<ExceptionMetricEvent> exceptionEvents = buildExceptionMetrics(events, timestampMs);
        if (!exceptionEvents.isEmpty()) {
          exportExceptionMetricsToConsole(exceptionEvents);
        }
      }
      logger.info("Exported " + events.size() + " endpoint metrics");
    }
  }

  private List<EndpointMetricEvent> formatEndpointMetrics(
      Map<String, EndpointAggregation> aggregations) {
    List<EndpointMetricEvent> events = new ArrayList<>();
    String timestamp = ISO_FORMATTER.format(Instant.now());

    for (Map.Entry<String, EndpointAggregation> entry : aggregations.entrySet()) {
      EndpointAggregation agg = entry.getValue();

      if (agg.getCount() == 0) {
        continue;
      }

      String operation = agg.getOperation();
      String endpointId = EndpointIdGenerator.generateEndpointId(agg.getRoute(), agg.getMethod());

      // Convert error_breakdown to list
      List<ErrorBreakdownEntry> errorBreakdownList = new ArrayList<>();
      for (Map.Entry<String, Map<String, EndpointErrorData>> failureEntry :
          agg.getErrorBreakdown().entrySet()) {
        String failureType = failureEntry.getKey();
        for (Map.Entry<String, EndpointErrorData> errorEntry : failureEntry.getValue().entrySet()) {
          EndpointErrorData errorData = errorEntry.getValue();
          if (errorData.getCount() > 0) {
            ErrorDetail detail = new ErrorDetail(errorData.errorType, errorData.functionId);
            ErrorBreakdownEntry breakdownEntry =
                new ErrorBreakdownEntry(Arrays.asList(detail), errorData.getCount(), failureType);
            errorBreakdownList.add(breakdownEntry);
          }
        }
      }

      // Convert duration list to DurationMetrics
      DurationMetrics duration = convertToDurationMetrics(agg);

      // Convert incident exemplars
      List<EndpointMetricEvent.IncidentExemplarEntry> exemplarEntries = new ArrayList<>();
      for (IncidentExemplar ex : agg.getIncidentExemplars()) {
        exemplarEntries.add(
            new EndpointMetricEvent.IncidentExemplarEntry(
                ex.snapshotId, ex.triggerType, ex.severity, ex.timestamp));
      }

      EndpointMetricEvent event =
          EndpointMetricEvent.builder()
              .environment(environment)
              .serviceName(serviceName)
              .deploymentId(deploymentId)
              .deploymentTimestamp(deploymentTimestamp)
              .deploymentUrl(deploymentUrl)
              .gitCommitSha(gitCommitSha)
              .gitRepoUrl(gitRepoUrl)
              .endpointId(endpointId)
              .method(agg.getMethod())
              .route(agg.getRoute())
              .operation(operation)
              .pid(pid)
              .timestamp(timestamp)
              .count(agg.getCount())
              .faults(agg.getFaultCount())
              .errors(agg.getErrorCount())
              .duration(duration)
              .errorBreakdown(errorBreakdownList)
              .incidentsExemplar(exemplarEntries)
              .build();

      events.add(event);
    }

    return events;
  }

  private DurationMetrics convertToDurationMetrics(EndpointAggregation agg) {
    List<Long> durations = agg.getDurations();
    if (durations == null || durations.isEmpty()) {
      return new DurationMetrics(new ArrayList<>(), new ArrayList<>(), 0, 0, 0, 0);
    }

    // Build a SehHistogram from the durations
    SehHistogram histogram = new SehHistogram();
    for (Long durationNs : durations) {
      histogram.record(durationNs.doubleValue());
    }

    SehHistogram.HistogramData data = histogram.getValuesAndCounts();
    SehHistogram.Statistics stats = histogram.getStatistics();

    // Convert from nanoseconds to microseconds for duration
    List<Double> valuesUs = new ArrayList<>();
    for (Double v : data.getValues()) {
      valuesUs.add(v / 1_000.0);
    }

    double maxUs = stats.getMax() / 1_000.0;
    double minUs = stats.getMin() / 1_000.0;
    double sumUs = agg.getSumDurationNs() / 1_000.0;

    return new DurationMetrics(valuesUs, data.getCounts(), maxUs, minUs, agg.getCount(), sumUs);
  }

  /**
   * Build exception EMF metric events from endpoint error breakdowns.
   *
   * <p>For each endpoint, sorts error breakdown entries by count descending and takes the top 5.
   * Each error detail produces a separate EMF event.
   */
  private List<ExceptionMetricEvent> buildExceptionMetrics(
      List<EndpointMetricEvent> endpointEvents, long timestampMs) {
    List<ExceptionMetricEvent> exceptionEvents = new ArrayList<>();

    for (EndpointMetricEvent endpointEvent : endpointEvents) {
      List<ErrorBreakdownEntry> breakdown = endpointEvent.getErrorBreakdown();
      if (breakdown == null || breakdown.isEmpty()) {
        continue;
      }

      // Sort by count descending and take top 5
      List<ErrorBreakdownEntry> sorted = new ArrayList<>(breakdown);
      sorted.sort(Comparator.comparingInt(ErrorBreakdownEntry::getCount).reversed());
      int limit = Math.min(sorted.size(), MAX_EXCEPTION_METRICS);

      for (int i = 0; i < limit; i++) {
        ErrorBreakdownEntry entry = sorted.get(i);
        if (entry.getCount() <= 0) {
          continue;
        }
        for (ErrorDetail detail : entry.getErrors()) {
          ExceptionMetricEvent exEvent =
              ExceptionMetricEvent.builder()
                  .serviceName(endpointEvent.getServiceName())
                  .environment(endpointEvent.getEnvironment())
                  .operation(endpointEvent.getOperation())
                  .functionId(detail.getFunctionId())
                  .errorType(detail.getErrorType())
                  .count(entry.getCount())
                  .timestampMs(timestampMs)
                  .build();
          exceptionEvents.add(exEvent);
        }
      }
    }

    return exceptionEvents;
  }

  private void exportToConsole(List<EndpointMetricEvent> events) {
    System.out.println("\n" + ProcessUtils.repeat("=", 80));
    System.out.println("SERVICE_EVENTS ENDPOINT TELEMETRY");
    System.out.println(ProcessUtils.repeat("=", 80));

    for (EndpointMetricEvent event : events) {
      try {
        String json = objectMapper.writeValueAsString(event.toMap());
        System.out.println(json);
      } catch (JsonProcessingException e) {
        logger.log(Level.WARNING, "Failed to serialize endpoint metrics", e);
      }
    }

    System.out.println("\n" + ProcessUtils.repeat("=", 80) + "\n");
  }

  private void exportExceptionMetricsToConsole(List<ExceptionMetricEvent> events) {
    System.out.println("\n" + ProcessUtils.repeat("=", 80));
    System.out.println("SERVICE_EVENTS EXCEPTION EMF METRICS");
    System.out.println(ProcessUtils.repeat("=", 80));

    for (ExceptionMetricEvent event : events) {
      try {
        String json = objectMapper.writeValueAsString(event.toMap());
        System.out.println(json);
      } catch (JsonProcessingException e) {
        logger.log(Level.WARNING, "Failed to serialize exception EMF metric", e);
      }
    }

    System.out.println("\n" + ProcessUtils.repeat("=", 80) + "\n");
  }

  /** Error info container. */
  public static class ErrorInfo {
    public final String errorType;
    public final String functionId;

    public ErrorInfo(String errorType, String functionId) {
      this.errorType = errorType;
      this.functionId = functionId;
    }
  }
}
