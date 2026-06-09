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

package software.amazon.opentelemetry.appsignals.test.serviceevents.springmvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.serviceevents.base.ServiceEventsContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeLog;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeMetric;

/**
 * ServiceEvents contract tests using a single-container phased architecture for speed.
 *
 * <p>Phase 0 ({@link #testSetupAndGenerateTraffic}): Starts ONE container, sends ALL traffic
 * patterns, waits for ALL telemetry types, and caches all NDJSON/OTLP records in memory.
 *
 * <p>Phase 1+ (all other tests): Verify different aspects of the cached telemetry data. Each
 * assertion runs in milliseconds since no container interaction or polling is needed.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServiceEventsSpringMvcTest extends ServiceEventsContractTestBase {

  // Cached telemetry data — populated once in Phase 0, read by all other tests
  private List<JsonNode> allRecords;
  private List<JsonNode> deploymentEventRecords;
  private List<JsonNode> endpointRecords;
  private List<JsonNode> incidentRecords;

  // Cached OTLP data from mock collector
  private List<ResourceScopeLog> otlpDeploymentLogs;
  private List<ResourceScopeLog> otlpEndpointLogs;
  private List<ResourceScopeLog> otlpFunctionCallLogs;
  private List<ResourceScopeLog> otlpIncidentLogs;
  private List<ResourceScopeMetric> otlpMetrics;

  @Override
  protected String getApplicationImageName() {
    return "aws-serviceevents-tests-http-server-spring-mvc";
  }

  // ---------------------------------------------------------------------------
  // Override base class lifecycle — we manage container manually
  // ---------------------------------------------------------------------------

  /** No-op: we start the container in testSetupAndGenerateTraffic. */
  @Override
  protected void setUp() {}

  /** No-op: we stop the container in tearDownOnce. */
  @Override
  protected void tearDown() {}

  @AfterAll
  void tearDownOnce() {
    if (application != null && application.isRunning()) {
      application.stop();
    }
    if (mockCollector != null && mockCollector.isRunning()) {
      mockCollector.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  // ===========================================================================
  // Phase 0: Setup, traffic generation, and telemetry harvest
  // ===========================================================================

  /**
   * Starts a single container, generates all traffic patterns, waits for all telemetry types
   * (DeploymentEvent, EndpointSummary, IncidentSnapshot), and caches records for subsequent tests.
   *
   * <p>This is the only test that interacts with the container. All subsequent tests read from
   * cached data and complete in milliseconds.
   */
  @Test
  @Order(0)
  void testSetupAndGenerateTraffic() throws Exception {
    // Start container using base class infrastructure
    super.setUp();

    // --- Phase 2: Generate ALL traffic patterns ---
    applicationLogger.info("=== Phase 2: Generating traffic ===");

    // Success endpoints (for EndpointSummary tests)
    for (int i = 0; i < 5; i++) {
      sendRequest("success");
    }

    // Fault endpoint (500 without exception)
    for (int i = 0; i < 3; i++) {
      sendRequest("fault");
    }

    // Client error (400)
    for (int i = 0; i < 2; i++) {
      sendRequest("client-error");
    }

    // Exception endpoints (for IncidentSnapshot tests)
    for (int i = 0; i < 10; i++) {
      sendRequest("exception");
    }

    // Nested exception
    for (int i = 0; i < 3; i++) {
      sendRequest("nested-exception");
    }

    // Different error types
    sendRequest("error/illegal-argument");
    sendRequest("error/null-pointer");
    sendRequest("error/state");

    // CPU-intensive work — additional EndpointSummary + service.function.duration coverage.
    for (int i = 0; i < 20; i++) {
      sendRequest("cpu-work");
      Thread.sleep(100);
    }

    // Moderate work
    for (int i = 0; i < 5; i++) {
      sendRequest("moderate-work");
    }

    // Slow success — exceeds the latency threshold without throwing, producing a pure
    // latency-triggered IncidentSnapshot with a populated byte-instrumentation call_path.
    for (int i = 0; i < 2; i++) {
      sendRequest("slow-success");
    }

    applicationLogger.info("=== Phase 2 complete: all traffic sent ===");

    // --- Phase 3: Wait for OTLP signals from mock collector ---
    // All signal data (DeploymentEvent, EndpointSummary, IncidentSnapshot)
    // goes via OTLP to the mock collector.
    applicationLogger.info("=== Phase 3: Waiting for telemetry ===");

    waitForOtlpLogs("aws.service_events.deployment_event", 1);
    waitForOtlpLogs("aws.service_events.endpoint_summary", 1);
    // FunctionCall LogRecord path is replaced by `service.function.duration` ExponentialHistogram
    // when an OTLP network endpoint is wired (the default in this test). The wait for the metric
    // is satisfied by the `getMetrics` poll below, since the periodic metric reader flushes on
    // its own schedule.
    // With byte-instrumentation mode, incident snapshots are emitted synchronously at request end
    // (Python-parity model). Wait for at least one so downstream assertions can validate the shape.
    // The /slow-success endpoint fires a latency-triggered snapshot in addition to the exception
    // ones, so we expect multiple.
    waitForOtlpLogs("aws.service_events.incident_snapshot", 2);

    applicationLogger.info("=== Phase 3 complete: all telemetry types present ===");

    // --- Phase 4: Harvest all records ---
    // File-based records (may be empty since signals now go through OTLP)
    allRecords = readServiceEventsRecords();
    deploymentEventRecords = filterByType(allRecords, "DeploymentEvent"); // will be empty (OTLP)
    endpointRecords = filterByType(allRecords, "EndpointSummary"); // will be empty (OTLP)
    incidentRecords = filterByType(allRecords, "IncidentSnapshot"); // will be empty (OTLP)

    applicationLogger.info(
        "=== Phase 4 complete: harvested "
            + allRecords.size()
            + " file-based records ("
            + deploymentEventRecords.size()
            + " DeploymentEvent, "
            + endpointRecords.size()
            + " EndpointSummary, "
            + incidentRecords.size()
            + " IncidentSnapshot) ===");

    // --- Phase 5: Harvest OTLP data from mock collector ---
    applicationLogger.info("=== Phase 5: Harvesting OTLP data from mock collector ===");
    otlpDeploymentLogs = getOtlpLogsByEventName("aws.service_events.deployment_event");
    otlpEndpointLogs = getOtlpLogsByEventName("aws.service_events.endpoint_summary");
    otlpFunctionCallLogs = getOtlpLogsByEventName("aws.service_events.function_call");
    otlpIncidentLogs = getOtlpLogsByEventName("aws.service_events.incident_snapshot");

    // Harvest metrics — get all from mock collector. We expect at minimum the
    // `service.function.duration` ExponentialHistogram (one data point per
    // function/status combination). The legacy `count` Sum (EndpointErrorMetrics) is also
    // present whenever errors occurred during the test window.
    //
    // The dedicated MeterProvider flushes on a 60s PeriodicMetricReader cadence, while a single
    // getMetrics() poll only waits ~20s. Retry across a deadline comfortably longer than one
    // flush interval so the first export window is always crossed before we give up.
    otlpMetrics = List.of();
    Instant metricsDeadline = Instant.now().plus(Duration.ofSeconds(90));
    while (Instant.now().isBefore(metricsDeadline)) {
      try {
        otlpMetrics = mockCollectorClient.getMetrics(Set.of("service.function.duration"));
        break;
      } catch (RuntimeException e) {
        // Duration metric hasn't flushed yet — keep polling until the deadline.
        applicationLogger.warn("Waiting for OTLP metrics to flush: " + e.getMessage());
      }
    }

    applicationLogger.info(
        "=== Phase 5 complete: OTLP ("
            + otlpDeploymentLogs.size()
            + " deployment, "
            + otlpEndpointLogs.size()
            + " endpoint, "
            + otlpFunctionCallLogs.size()
            + " function_call, "
            + otlpIncidentLogs.size()
            + " incident, "
            + otlpMetrics.size()
            + " metrics) ===");

    assertThat(otlpDeploymentLogs).as("OTLP deployment logs").isNotEmpty();
    assertThat(otlpEndpointLogs).as("OTLP endpoint logs").isNotEmpty();
    // FunctionCall is now emitted as the `service.function.duration` ExponentialHistogram
    // (asserted in Phase 1 below). The legacy LogRecord is intentionally suppressed when
    // the bridge is wired, so otlpFunctionCallLogs is expected to be empty here.
    assertThat(otlpMetrics)
        .as("OTLP metrics (must include service.function.duration ExponentialHistogram)")
        .isNotEmpty();
  }

  private List<JsonNode> filterByType(List<JsonNode> records, String type) {
    return records.stream()
        .filter(r -> type.equals(r.path("telemetry_type").asText("")))
        .collect(Collectors.toList());
  }

  /** Skip file-based signal tests — signals now go through OTLP, not the file exporter. */
  private void assumeFileSignalsPresent(List<?> records, String type) {
    assumeThat(records).as(type + " signals go through OTLP; file-based tests skipped").isNotNull();
    assumeThat(records)
        .as(type + " signals go through OTLP; file-based tests skipped")
        .isNotEmpty();
  }

  private JsonNode findEndpoint(String method, String route) {
    return endpointRecords.stream()
        .filter(
            r -> method.equals(r.path("method").asText()) && route.equals(r.path("route").asText()))
        .findFirst()
        .orElse(null);
  }

  /** Find the first incident record with the given trigger_type ("exception" or "latency"). */
  private JsonNode findIncidentByTrigger(String triggerType) {
    return incidentRecords.stream()
        .filter(r -> triggerType.equals(r.path("trigger_type").asText()))
        .findFirst()
        .orElse(null);
  }

  // ===========================================================================
  // Phase 1: DeploymentEvent verification tests (instant)
  // ===========================================================================

  @Test
  @Order(1)
  void testDeploymentEventEmittedAtStartup() {
    assumeFileSignalsPresent(deploymentEventRecords, "DeploymentEvent");
    assertDeploymentEventRecord(deploymentEventRecords.get(0), null, null);
  }

  @Test
  @Order(1)
  void testDeploymentEventStructure() {
    assumeFileSignalsPresent(deploymentEventRecords, "DeploymentEvent");
    JsonNode record = deploymentEventRecords.get(0);
    assertThat(record.path("telemetry_type").asText()).isEqualTo("DeploymentEvent");
    assertThat(record.path("sdk_lang").asText()).isEqualTo("java");
    assertThat(record.path("sdk_version").asText()).isNotEmpty();
    assertThat(record.path("pid").asLong()).isGreaterThan(0);
    assertThat(record.path("timestamp").asText()).isNotEmpty();
    assertThat(record.path("service_name").asText()).isNotEmpty();
    assertThat(record.path("environment").asText()).isNotEmpty();

    // Verify nested deployment_context
    JsonNode ctx = record.path("deployment_context");
    assertThat(ctx.isObject()).isTrue();
    assertThat(ctx.has("git_repo_url")).isTrue();
    assertThat(ctx.has("git_commit_sha")).isTrue();
    assertThat(ctx.has("deployment_url")).isTrue();
    assertThat(ctx.has("deployment_timestamp")).isTrue();
    assertThat(ctx.has("deployment_id")).isTrue();
  }

  @Test
  @Order(1)
  void testDeploymentEventServiceMetadata() {
    assumeFileSignalsPresent(deploymentEventRecords, "DeploymentEvent");
    JsonNode record = deploymentEventRecords.get(0);
    assertServiceMetadata(record, null, null);
  }

  // ===========================================================================
  // Phase 1: EndpointSummary verification tests (instant)
  // ===========================================================================

  @Test
  @Order(1)
  void testEndpointSummaryOnSuccess() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    JsonNode endpoint = findEndpoint("GET", "/success");
    assertThat(endpoint).as("EndpointSummary for GET /success").isNotNull();
    assertEndpointSummaryRecord(endpoint, "GET", "/success", "GET /success", 1, false, false);
  }

  @Test
  @Order(1)
  void testEndpointSummaryOnFault() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    JsonNode endpoint = findEndpoint("GET", "/fault");
    assertThat(endpoint).as("EndpointSummary for GET /fault").isNotNull();
    assertEndpointSummaryRecord(endpoint, "GET", "/fault", null, null, true, null);
  }

  @Test
  @Order(1)
  void testEndpointSummaryOnError() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    JsonNode endpoint = findEndpoint("GET", "/client-error");
    assertThat(endpoint).as("EndpointSummary for GET /client-error").isNotNull();
    assertEndpointSummaryRecord(endpoint, "GET", "/client-error", null, null, null, true);
  }

  @Test
  @Order(1)
  void testEndpointSummaryDuration() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    JsonNode endpoint = findEndpoint("GET", "/success");
    assertThat(endpoint).isNotNull();
    assertDurationStructure(endpoint.path("duration"));
  }

  @Test
  @Order(1)
  void testEndpointSummaryOperationFormat() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    JsonNode endpoint = findEndpoint("GET", "/success");
    assertThat(endpoint).isNotNull();
    String operation = endpoint.path("operation").asText();
    assertThat(operation).isEqualTo("GET /success");
    assertThat(operation).matches("^[A-Z]+ /.*");
  }

  @Test
  @Order(1)
  void testEndpointSummaryDurationHistogramValues() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    JsonNode endpoint = findEndpoint("GET", "/success");
    assertThat(endpoint).isNotNull();
    JsonNode duration = endpoint.path("duration");
    assertDurationStructure(duration);

    JsonNode values = duration.path("Values");
    assertThat(values.isArray()).isTrue();
    assertThat(values.size()).isGreaterThan(0);
    for (JsonNode val : values) {
      assertThat(val.asDouble()).isGreaterThan(0);
    }
  }

  @Test
  @Order(1)
  void testEndpointSummaryMultipleEndpointsIsolation() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    JsonNode successEndpoint = findEndpoint("GET", "/success");
    JsonNode faultEndpoint = findEndpoint("GET", "/fault");
    assertThat(successEndpoint).isNotNull();
    assertThat(faultEndpoint).isNotNull();

    assertThat(successEndpoint.path("endpoint_id").asText())
        .isNotEqualTo(faultEndpoint.path("endpoint_id").asText());
    assertThat(successEndpoint.path("faults").asInt(0)).isEqualTo(0);
    assertThat(faultEndpoint.path("faults").asInt(0)).isGreaterThan(0);
  }

  @Test
  @Order(1)
  void testEndpointSummaryAccumulatesAcrossRequests() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    JsonNode endpoint = findEndpoint("GET", "/success");
    assertThat(endpoint).isNotNull();
    assertThat(endpoint.path("count").asInt()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @Order(1)
  void testEndpointSummaryServiceMetadata() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    JsonNode endpoint = findEndpoint("GET", "/success");
    assertThat(endpoint).isNotNull();
    assertServiceMetadata(endpoint, null, null);
  }

  @Test
  @Order(1)
  void testEndpointSummaryErrorBreakdown() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    JsonNode endpoint = findEndpoint("GET", "/exception");
    assertThat(endpoint).as("EndpointSummary for GET /exception").isNotNull();
    // Verify error_breakdown structure when present
    JsonNode errorBreakdown = endpoint.path("error_breakdown");
    if (!errorBreakdown.isMissingNode() && errorBreakdown.isArray() && errorBreakdown.size() > 0) {
      // Each entry should have count > 0
      for (JsonNode entry : errorBreakdown) {
        assertThat(entry.has("count")).isTrue();
      }
    }
    // The /exception endpoint returns 500, so either faults or count should reflect errors
    assertThat(endpoint.path("count").asInt()).isGreaterThan(0);
  }

  // ===========================================================================
  // Phase 1: incidents_exemplar verification tests (instant)
  // ===========================================================================

  @Test
  @Order(1)
  void testIncidentsExemplarPresentOnSuccessEndpoint() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    JsonNode endpoint = findEndpoint("GET", "/success");
    assertThat(endpoint).as("EndpointSummary for GET /success").isNotNull();
    assertIncidentsExemplarPresent(endpoint);
    // Success endpoint should have an empty incidents_exemplar list
    assertThat(endpoint.path("incidents_exemplar").size()).isEqualTo(0);
  }

  @Test
  @Order(1)
  void testIncidentsExemplarPopulatedOnFaultEndpoint() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    JsonNode endpoint = findEndpoint("GET", "/fault");
    assertThat(endpoint).as("EndpointSummary for GET /fault").isNotNull();
    assertIncidentsExemplarPresent(endpoint);
    assertThat(endpoint.path("incidents_exemplar").size())
        .as("Fault endpoint should have at least one incidents_exemplar entry")
        .isGreaterThan(0);
    assertIncidentsExemplarEntries(endpoint);

    // All exemplars on the /fault endpoint should be exception-triggered
    for (JsonNode exemplar : endpoint.path("incidents_exemplar")) {
      assertThat(exemplar.path("trigger_type").asText()).isEqualTo("exception");
    }
  }

  @Test
  @Order(1)
  void testIncidentsExemplarCrossReferencesIncidentSnapshots() {
    assumeFileSignalsPresent(endpointRecords, "EndpointSummary");
    assumeFileSignalsPresent(incidentRecords, "IncidentSnapshot");
    JsonNode endpoint = findEndpoint("GET", "/fault");
    assertThat(endpoint).as("EndpointSummary for GET /fault").isNotNull();
    assertIncidentsExemplarPresent(endpoint);
    if (endpoint.path("incidents_exemplar").size() > 0) {
      assertIncidentsExemplarCrossReference(endpoint, incidentRecords);
    }
  }

  // ===========================================================================
  // Phase 1: IncidentSnapshot verification tests (instant)
  // ===========================================================================

  @Test
  @Order(1)
  void testIncidentSnapshotOnException() {
    assumeFileSignalsPresent(incidentRecords, "IncidentSnapshot");
    JsonNode snapshot = findIncidentByTrigger("exception");
    assertThat(snapshot).as("IncidentSnapshot with trigger_type=exception").isNotNull();
    assertThat(snapshot.path("telemetry_type").asText()).isEqualTo("IncidentSnapshot");
    assertThat(snapshot.path("trigger_type").asText()).isEqualTo("exception");
    assertThat(snapshot.has("snapshot_id")).isTrue();
    assertThat(snapshot.has("severity")).isTrue();
    assertThat(snapshot.has("affected_endpoint")).isTrue();
    assertThat(snapshot.has("request_context")).isTrue();
  }

  @Test
  @Order(1)
  void testIncidentSnapshotSnapshotIdFormat() {
    assumeFileSignalsPresent(incidentRecords, "IncidentSnapshot");
    assertThat(incidentRecords).isNotEmpty();
    String snapshotId = incidentRecords.get(0).path("snapshot_id").asText();
    assertThat(snapshotId).startsWith("snap_");
    String uuidPart = snapshotId.substring(5);
    assertThat(uuidPart).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  /** Verifies exception-triggered incidents (500 status) have "critical" severity. */
  @Test
  @Order(1)
  void testIncidentSnapshotSeverityCritical() {
    assumeFileSignalsPresent(incidentRecords, "IncidentSnapshot");
    JsonNode snapshot = findIncidentByTrigger("exception");
    assertThat(snapshot).as("IncidentSnapshot with trigger_type=exception").isNotNull();
    String severity = snapshot.path("severity").asText();
    assertThat(severity).isEqualTo("critical");
  }

  @Test
  @Order(1)
  void testIncidentSnapshotExceptionInfoPresent() {
    assumeFileSignalsPresent(incidentRecords, "IncidentSnapshot");
    assertThat(incidentRecords).isNotEmpty();
    JsonNode excInfo = incidentRecords.get(0).path("exception_info");
    assertThat(excInfo.isArray()).isTrue();
    if (excInfo.size() > 0) {
      JsonNode firstExc = excInfo.get(0);
      assertThat(firstExc.has("exception_type")).isTrue();
      if (!firstExc.path("exception_type").isNull()
          && !firstExc.path("exception_type").asText().isEmpty()) {
        assertThat(firstExc.has("exception_message")).isTrue();
        assertThat(firstExc.has("stack_trace")).isTrue();
      }
    }
  }

  /** Verifies request_context on an exception-triggered incident has status_code=500. */
  @Test
  @Order(1)
  void testIncidentSnapshotRequestContext() {
    assumeFileSignalsPresent(incidentRecords, "IncidentSnapshot");
    JsonNode snapshot = findIncidentByTrigger("exception");
    assertThat(snapshot).as("IncidentSnapshot with trigger_type=exception").isNotNull();
    JsonNode reqContext = snapshot.path("request_context");
    assertThat(reqContext.isMissingNode()).isFalse();
    assertThat(reqContext.path("type").asText()).isEqualTo("http");
    assertThat(reqContext.has("status_code")).isTrue();
    assertThat(reqContext.path("status_code").asInt()).isEqualTo(500);
    assertThat(reqContext.has("timestamp")).isTrue();
    assertThat(reqContext.path("timestamp").asLong()).isGreaterThan(0);
  }

  /**
   * Verifies telemetry_correlation field is present on incident snapshots. The correlation object
   * may contain trace_id/span_id (from SpanProcessor) or request_id (from direct collector), or may
   * be empty if correlation data was not available at capture time.
   */
  @Test
  @Order(1)
  void testIncidentSnapshotTelemetryCorrelation() {
    assumeFileSignalsPresent(incidentRecords, "IncidentSnapshot");
    assertThat(incidentRecords).isNotEmpty();
    JsonNode correlation = incidentRecords.get(0).path("telemetry_correlation");
    assertThat(correlation.isMissingNode()).isFalse();
    // The telemetry_correlation object should exist as a JSON object.
    // It may contain trace_id, span_id, request_id, or be empty if the incident
    // was recorded without an active OTel span context.
    assertThat(correlation.isObject()).isTrue();
  }

  @Test
  @Order(1)
  void testIncidentSnapshotServiceMetadata() {
    assumeFileSignalsPresent(incidentRecords, "IncidentSnapshot");
    assertThat(incidentRecords).isNotEmpty();
    JsonNode snapshot = incidentRecords.get(0);
    assertServiceMetadata(snapshot, null, null);
    assertThat(snapshot.has("service")).isTrue();
    assertThat(snapshot.path("service").asText()).isNotEmpty();
    assertThat(snapshot.has("environment")).isTrue();
    assertThat(snapshot.has("endpoint_id")).isTrue();
    assertThat(snapshot.has("duration_ms")).isTrue();
    assertThat(snapshot.path("duration_ms").asDouble()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @Order(1)
  void testIncidentSnapshotDeduplication() {
    assumeFileSignalsPresent(incidentRecords, "IncidentSnapshot");
    Set<String> uniqueIds = new HashSet<>();
    for (JsonNode record : incidentRecords) {
      String id = record.path("snapshot_id").asText();
      assertThat(uniqueIds.add(id)).as("Duplicate snapshot_id found: " + id).isTrue();
    }
  }

  @Test
  @Order(1)
  void testIncidentSnapshotMultipleErrorEndpoints() {
    assumeFileSignalsPresent(incidentRecords, "IncidentSnapshot");
    Set<String> affectedEndpoints =
        incidentRecords.stream()
            .map(r -> r.path("affected_endpoint").asText())
            .collect(Collectors.toSet());
    assertThat(affectedEndpoints.size()).isGreaterThanOrEqualTo(2);
  }

  @Test
  @Order(1)
  void testIncidentSnapshotDistinctErrorRoutes() {
    assumeFileSignalsPresent(incidentRecords, "IncidentSnapshot");
    // We sent errors to /exception, /nested-exception, /error/illegal-argument, etc.
    // Verify we got at least 2 distinct incidents
    assertThat(incidentRecords.size()).isGreaterThanOrEqualTo(2);
    for (JsonNode record : incidentRecords) {
      assertThat(record.path("telemetry_type").asText()).isEqualTo("IncidentSnapshot");
      assertThat(record.has("snapshot_id")).isTrue();
      assertThat(record.has("trigger_type")).isTrue();
      assertThat(record.has("severity")).isTrue();
    }
  }

  // ===========================================================================
  // Phase 1: Integration / cross-cutting tests (instant)
  // ===========================================================================

  @Test
  @Order(1)
  void testAllCoreTelemetryTypesPresent() {
    // All signal data goes through OTLP to mock collector
    assertThat(otlpDeploymentLogs).as("OTLP DeploymentEvent").isNotEmpty();
    assertThat(otlpEndpointLogs).as("OTLP EndpointSummary").isNotEmpty();
    assertThat(otlpIncidentLogs).as("OTLP IncidentSnapshot").isNotEmpty();
  }

  @Test
  @Order(1)
  void testSdkLangPresentOnAllRecords() {
    assumeThat(allRecords)
        .as("File-based records may be empty when signals go through OTLP")
        .isNotEmpty();
    for (JsonNode record : allRecords) {
      String telemetryType = record.path("telemetry_type").asText("unknown");
      assertThat(record.has("sdk_lang"))
          .as("sdk_lang should be present on " + telemetryType + " record")
          .isTrue();
      assertThat(record.path("sdk_lang").asText())
          .as("sdk_lang should be 'java' on " + telemetryType + " record")
          .isEqualTo("java");
    }
  }

  @Test
  @Order(1)
  void testPidConsistentAcrossRecords() {
    assumeThat(allRecords)
        .as("File-based records may be empty when signals go through OTLP")
        .isNotEmpty();
    Set<Long> pids = new HashSet<>();
    for (JsonNode record : allRecords) {
      if (record.has("pid")) {
        pids.add(record.path("pid").asLong());
      }
    }
    assertThat(pids.size()).isEqualTo(1);
    assertThat(pids.iterator().next()).isGreaterThan(0);
  }

  @Test
  @Order(1)
  void testTimestampsAreReasonable() {
    assumeThat(allRecords)
        .as("File-based records may be empty when signals go through OTLP")
        .isNotEmpty();
    long year2020 = 1577836800000L;
    for (JsonNode record : allRecords) {
      if (record.has("timestamp")) {
        long ts = record.path("timestamp").asLong(0);
        if (ts > 0) {
          assertThat(ts)
              .as(
                  "Timestamp for "
                      + record.path("telemetry_type").asText()
                      + " should be after 2020")
              .isGreaterThan(year2020);
        } else {
          String tsStr = record.path("timestamp").asText("");
          assertThat(tsStr)
              .as(
                  "Timestamp string should not be empty for "
                      + record.path("telemetry_type").asText())
              .isNotEmpty();
        }
      }
    }
  }

  @Test
  @Order(1)
  void testIncidentSnapshotUniqueSnapshotIds() {
    assumeFileSignalsPresent(incidentRecords, "IncidentSnapshot");
    Set<String> snapshotIds = new HashSet<>();
    for (JsonNode record : incidentRecords) {
      String id = record.path("snapshot_id").asText();
      assertThat(id).isNotEmpty();
      assertThat(snapshotIds.add(id))
          .as("All snapshot_ids must be unique, but found duplicate: " + id)
          .isTrue();
    }
  }

  // ===========================================================================
  // Phase 1: OTLP DeploymentEvent verification (instant — from cached data)
  // ===========================================================================

  @Test
  @Order(1)
  void testOtlpDeploymentEventEmitted() {
    assertThat(otlpDeploymentLogs).as("OTLP deployment_event logs").isNotEmpty();
    ResourceScopeLog log = otlpDeploymentLogs.get(0);
    assertThat(getLogAttribute(log, "event.name")).isEqualTo("aws.service_events.deployment_event");
  }

  @Test
  @Order(1)
  void testOtlpDeploymentEventInstrumentationScope() {
    assertThat(otlpDeploymentLogs).isNotEmpty();
    assertOtlpInstrumentationScope(otlpDeploymentLogs.get(0));
  }

  @Test
  @Order(1)
  void testOtlpDeploymentEventAttributes() {
    assertThat(otlpDeploymentLogs).isNotEmpty();
    ResourceScopeLog log = otlpDeploymentLogs.get(0);

    // VCS attributes should be present (may be empty strings if not configured)
    assertThat(getLogAttribute(log, "aws.service_events.deployment.id")).isNotNull();
  }

  @Test
  @Order(1)
  void testOtlpDeploymentEventNoBody() {
    assertThat(otlpDeploymentLogs).isNotEmpty();
    ResourceScopeLog log = otlpDeploymentLogs.get(0);
    // DeploymentEvent should have no body per spec
    assertThat(log.getLog().getBody().getStringValue()).isEmpty();
  }

  // ===========================================================================
  // Phase 1: OTLP EndpointSummary verification (instant — from cached data)
  // ===========================================================================

  @Test
  @Order(1)
  void testOtlpEndpointSummaryEmitted() {
    assertThat(otlpEndpointLogs).as("OTLP endpoint_summary logs").isNotEmpty();
    for (ResourceScopeLog log : otlpEndpointLogs) {
      assertThat(getLogAttribute(log, "event.name"))
          .isEqualTo("aws.service_events.endpoint_summary");
    }
  }

  @Test
  @Order(1)
  void testOtlpEndpointSummaryInstrumentationScope() {
    assertThat(otlpEndpointLogs).isNotEmpty();
    assertOtlpInstrumentationScope(otlpEndpointLogs.get(0));
  }

  @Test
  @Order(1)
  void testOtlpEndpointSummaryAttributes() {
    assertThat(otlpEndpointLogs).isNotEmpty();
    // Find an endpoint summary with a known route
    ResourceScopeLog successLog = null;
    for (ResourceScopeLog log : otlpEndpointLogs) {
      if ("/success".equals(getLogAttribute(log, "url.route"))) {
        successLog = log;
        break;
      }
    }
    if (successLog != null) {
      assertThat(getLogAttribute(successLog, "http.request.method")).isEqualTo("GET");
      assertThat(getLogAttribute(successLog, "url.route")).isEqualTo("/success");
      assertThat(getLogAttribute(successLog, "aws.service_events.operation"))
          .isEqualTo("GET /success");
      Long requestCount = getLogAttributeLong(successLog, "aws.service_events.request.count");
      assertThat(requestCount).isNotNull();
      assertThat(requestCount).isGreaterThanOrEqualTo(1);
    }
  }

  @Test
  @Order(1)
  void testOtlpEndpointSummaryHasBody() {
    assertThat(otlpEndpointLogs).isNotEmpty();
    // EndpointSummary should have body with duration data
    ResourceScopeLog log = otlpEndpointLogs.get(0);
    assertThat(
            log.getLog().getBody().hasKvlistValue()
                || log.getLog().getBody().getStringValue().length() > 0)
        .as("EndpointSummary should have a body with duration data")
        .isTrue();
  }

  // ===========================================================================
  // Phase 1: OTLP service.function.duration histogram verification
  // (replaces the legacy aws.service_events.function_call LogRecord assertions —
  //  the bridge wired in OTLP-network mode emits the histogram instead.)
  // ===========================================================================

  @Test
  @Order(1)
  void testFunctionCallLogRecordSuppressedWhenBridgeWired() {
    // The agent wires FunctionMetricsBridgeImpl whenever an OTLP endpoint is configured and
    // OUTPUT_FILE is unset (this test's configuration). In that mode the legacy
    // aws.service_events.function_call LogRecord MUST NOT be emitted — methodExit records
    // directly into the histogram and the FunctionCallCollector observes an empty SEH map.
    assertThat(otlpFunctionCallLogs)
        .as(
            "Legacy aws.service_events.function_call LogRecord must be suppressed when the OTel"
                + " histogram bridge is wired (OTLP-network mode)")
        .isEmpty();
  }

  @Test
  @Order(1)
  void testServiceFunctionDurationHistogramEmitted() {
    Metric duration = findMetricByName("service.function.duration");
    assertThat(duration.hasExponentialHistogram())
        .as("service.function.duration must be exported as an ExponentialHistogram")
        .isTrue();
    assertThat(duration.getUnit()).isEqualTo("Microseconds");
    assertThat(duration.getDescription()).isEqualTo("Function call duration");

    List<ExponentialHistogramDataPoint> dpList =
        duration.getExponentialHistogram().getDataPointsList();
    assertThat(dpList).as("at least one data point should be present").isNotEmpty();
    long totalCount = dpList.stream().mapToLong(ExponentialHistogramDataPoint::getCount).sum();
    assertThat(totalCount)
        .as("Histogram should record sampled function calls (count > 0)")
        .isGreaterThan(0L);
  }

  @Test
  @Order(1)
  void testServiceFunctionDurationHistogramAttributes() {
    Metric duration = findMetricByName("service.function.duration");
    List<ExponentialHistogramDataPoint> dpList =
        duration.getExponentialHistogram().getDataPointsList();
    assertThat(dpList).isNotEmpty();

    for (ExponentialHistogramDataPoint dp : dpList) {
      Map<String, String> attrs = toStringAttrs(dp.getAttributesList());
      // Required per-data-point attributes (see SERVICE_FUNCTION_DURATION_METRIC_SPEC.md):
      assertThat(attrs)
          .as("data point attributes")
          .containsKey("function.name")
          .containsKey("status")
          .containsEntry("Telemetry.Source", "ServiceEvents");
      assertThat(attrs.get("status")).isIn("success", "error");

      // Process-constants must NOT appear on per-data-point attributes — they ride on the
      // MeterProvider's Resource so the wire payload doesn't carry duplicate copies.
      assertThat(attrs)
          .as("Process-constants must live on Resource, not per-data-point attributes")
          .doesNotContainKey("aws.service_events.version")
          .doesNotContainKey("aws.service_events.deployment.id")
          .doesNotContainKey("vcs.ref.head.revision")
          .doesNotContainKey("vcs.repository.url.full")
          .doesNotContainKey("service.name")
          // exception.type is intentionally omitted (cardinality control); status="error" is
          // the only error-side breakdown.
          .doesNotContainKey("exception.type");
    }
  }

  @Test
  @Order(1)
  void testServiceFunctionDurationHistogramResourceAttributes() {
    // The 4 ServiceEvents process-constants are layered onto the dedicated MeterProvider's
    // Resource (see ServiceEventsInstrumentation#createMeterProvider). Verify they reach the
    // wire on the ResourceMetrics envelope rather than on each data point.
    ResourceScopeMetric duration =
        otlpMetrics.stream()
            .filter(rsm -> "service.function.duration".equals(rsm.getMetric().getName()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "service.function.duration metric not exported by the agent"));

    Map<String, String> resourceAttrs =
        toStringAttrs(duration.getResource().getResource().getAttributesList());
    assertThat(resourceAttrs)
        .as("Resource attributes on the metrics envelope")
        .containsEntry("aws.service_events.version", "1")
        .containsKey("service.name");
  }

  @Test
  @Order(1)
  void testServiceFunctionDurationInstrumentationScope() {
    ResourceScopeMetric duration =
        otlpMetrics.stream()
            .filter(rsm -> "service.function.duration".equals(rsm.getMetric().getName()))
            .findFirst()
            .orElseThrow();
    assertThat(duration.getScope().getScope().getName()).isEqualTo("serviceevents");
    assertThat(duration.getScope().getScope().getVersion()).isEqualTo("1.0");
  }

  /** Locate a single Metric by name from the cached otlpMetrics list. */
  private Metric findMetricByName(String name) {
    return otlpMetrics.stream()
        .map(rsm -> rsm.getMetric())
        .filter(m -> name.equals(m.getName()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "metric '"
                        + name
                        + "' not exported. Got: "
                        + otlpMetrics.stream()
                            .map(rsm -> rsm.getMetric().getName())
                            .collect(Collectors.toList())));
  }

  private static Map<String, String> toStringAttrs(List<KeyValue> kvs) {
    Map<String, String> out = new HashMap<>();
    for (KeyValue kv : kvs) {
      out.put(kv.getKey(), kv.getValue().getStringValue());
    }
    return out;
  }

  // ===========================================================================
  // Phase 1: OTLP IncidentSnapshot verification (instant — from cached data)
  // ===========================================================================

  @Test
  @Order(1)
  void testOtlpIncidentSnapshotEmitted() {
    // Byte-instrumentation mode emits incidents synchronously → OTLP logs are required, not
    // optional.
    assertThat(otlpIncidentLogs)
        .as("OTLP incident_snapshot logs (byte-instrumentation mode emits synchronously)")
        .isNotEmpty();
    for (ResourceScopeLog log : otlpIncidentLogs) {
      assertThat(getLogAttribute(log, "event.name"))
          .isEqualTo("aws.service_events.incident_snapshot");
      assertOtlpInstrumentationScope(log);
    }
  }

  @Test
  @Order(1)
  void testOtlpIncidentSnapshotAttributes() {
    assertThat(otlpIncidentLogs).isNotEmpty();
    ResourceScopeLog log = otlpIncidentLogs.get(0);
    assertThat(getLogAttribute(log, "aws.service_events.snapshot_id")).isNotNull();
    assertThat(getLogAttribute(log, "aws.service_events.trigger_type")).isNotNull();
    assertThat(getLogAttribute(log, "aws.service_events.operation")).isNotNull();
  }

  /**
   * In byte-instrumentation mode, at least one incident must carry a populated {@code
   * exception_info[0].call_path} with Python-compatible entries (regression gate for the
   * nested-servlet-dispatch fix).
   *
   * <p>With {@code /slow-success} in the traffic set we always get at least one latency-triggered
   * incident with a non-empty call path, even if every exception path happens to empty it.
   */
  @Test
  @Order(1)
  @SuppressWarnings("unchecked")
  void testOtlpIncidentSnapshotCallPathIsPythonCompatible() {
    assertThat(otlpIncidentLogs).isNotEmpty();

    boolean foundPopulatedCallPath = false;
    for (ResourceScopeLog log : otlpIncidentLogs) {
      Map<String, Object> body = decodeLogBody(log);

      Object exceptionInfoObj = body.get("exception_info");
      if (!(exceptionInfoObj instanceof List)) continue;
      List<Object> exceptionInfo = (List<Object>) exceptionInfoObj;
      if (exceptionInfo.isEmpty()) continue;

      Map<String, Object> first = (Map<String, Object>) exceptionInfo.get(0);
      Object callPathObj = first.get("call_path");
      assertThat(callPathObj)
          .as("exception_info[0].call_path must be an array")
          .isInstanceOf(List.class);
      List<Object> callPath = (List<Object>) callPathObj;
      if (callPath.isEmpty()) continue;

      // Validate Python CallPathEntry schema on every entry.
      for (Object raw : callPath) {
        Map<String, Object> entry = (Map<String, Object>) raw;
        assertThat(entry).containsKey("function_name");
        assertThat(entry).containsKey("caller_function_name");
        assertThat(entry).containsKey("duration_ns");
        assertThat(entry).containsKey("error");
        assertThat(entry).containsKey("is_async");
        assertThat(entry.get("function_name")).isInstanceOf(String.class);
        assertThat((String) entry.get("function_name")).isNotEmpty();
      }
      foundPopulatedCallPath = true;
    }

    assertThat(foundPopulatedCallPath)
        .as(
            "At least one incident snapshot must carry a populated call_path when "
                + "byte-instrumentation mode is active (latency incidents always do).")
        .isTrue();
  }

  /**
   * Exercises {@code OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS}: the global threshold is 30s (well
   * above /slow-success's 3s sleep) so the only way a latency incident can arrive for {@code GET
   * /slow-success} is if the per-endpoint override (500ms) was applied.
   */
  @Test
  @Order(1)
  void testOtlpIncidentSnapshotPerEndpointLatencyOverride() {
    assertThat(otlpIncidentLogs).isNotEmpty();
    boolean foundLatencyOnSlowSuccess = false;
    for (ResourceScopeLog log : otlpIncidentLogs) {
      if (!"latency".equals(getLogAttribute(log, "aws.service_events.trigger_type"))) continue;
      if (!"GET /slow-success".equals(getLogAttribute(log, "aws.service_events.operation")))
        continue;
      foundLatencyOnSlowSuccess = true;
      break;
    }
    assertThat(foundLatencyOnSlowSuccess)
        .as(
            "Per-endpoint latency override should have triggered an incident for GET "
                + "/slow-success despite the 30s global threshold.")
        .isTrue();
  }

  // ===========================================================================
  // Phase 1: OTLP EndpointErrorMetrics verification (instant — from cached data)
  // ===========================================================================

  @Test
  @Order(1)
  void testOtlpEndpointErrorMetricsEmitted() {
    if (!otlpMetrics.isEmpty()) {
      boolean hasErrorMetric =
          otlpMetrics.stream().anyMatch(rsm -> "count".equals(rsm.getMetric().getName()));
      if (hasErrorMetric) {
        ResourceScopeMetric errorMetric =
            otlpMetrics.stream()
                .filter(rsm -> "count".equals(rsm.getMetric().getName()))
                .findFirst()
                .orElseThrow();
        Metric metric = errorMetric.getMetric();
        assertThat(metric.getUnit()).isEqualTo("Count");
        assertThat(metric.hasSum()).as("Error metric should be a Sum type").isTrue();
        // Verify data points have operation and exception attributes
        for (NumberDataPoint dp : metric.getSum().getDataPointsList()) {
          boolean hasOperation =
              dp.getAttributesList().stream().anyMatch(kv -> "operation".equals(kv.getKey()));
          boolean hasException =
              dp.getAttributesList().stream().anyMatch(kv -> "exception".equals(kv.getKey()));
          assertThat(hasOperation)
              .as("Error metric data point should have operation attribute")
              .isTrue();
          assertThat(hasException)
              .as("Error metric data point should have exception attribute")
              .isTrue();
        }
      }
    } else {
      applicationLogger.warn(
          "No OTLP metrics harvested — error metrics may not have been emitted in test window.");
    }
  }
}
