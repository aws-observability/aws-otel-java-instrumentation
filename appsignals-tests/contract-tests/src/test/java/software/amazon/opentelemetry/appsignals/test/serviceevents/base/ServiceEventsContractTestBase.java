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

package software.amazon.opentelemetry.appsignals.test.serviceevents.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import software.amazon.opentelemetry.appsignals.test.utils.MockCollectorClient;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeLog;

/**
 * Base class for serviceevents contract tests.
 *
 * <p>Unlike ContractTestBase, this class does NOT use a mock OTLP collector. ServiceEvents
 * telemetry is written as NDJSON to a file inside the container. Each test gets a fresh container
 * for clean state.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ServiceEventsContractTestBase {

  private static final String AGENT_PATH =
      System.getProperty("io.awsobservability.instrumentation.contracttests.agentPath");
  private static final String MOUNT_PATH = "/opentelemetry-javaagent-all.jar";
  private static final String SERVICE_EVENTS_LOG_PATH = "/tmp/serviceevents.log";
  private static final String SERVICE_EVENTS_FLUSH_INTERVAL_MS = "2000";
  // Signals emit synchronously (bytecode mode) or on the periodic flush cadence; 60s covers
  // worst-case CI scheduling/batch latency.
  private static final Duration SERVICE_EVENTS_WAIT_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration SERVICE_EVENTS_POLL_INTERVAL = Duration.ofMillis(500);

  private static final String MOCK_COLLECTOR_IMAGE = "aws-appsignals-mock-collector";
  private static final String MOCK_COLLECTOR_ALIAS = "mock-collector";
  private static final int MOCK_COLLECTOR_PORT = 4317;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  protected final Logger applicationLogger =
      LoggerFactory.getLogger("application " + getApplicationOtelServiceName());
  protected final Logger collectorLogger = LoggerFactory.getLogger("mock-collector");

  protected Network network;
  protected GenericContainer<?> mockCollector;
  protected MockCollectorClient mockCollectorClient;
  protected GenericContainer<?> application;
  protected WebClient appClient;

  @BeforeEach
  protected void setUp() throws Exception {
    network = Network.newNetwork();

    // Start mock OTLP collector for receiving ServiceEvents OTLP signals
    mockCollector =
        new GenericContainer<>(MOCK_COLLECTOR_IMAGE)
            .withExposedPorts(MOCK_COLLECTOR_PORT)
            .withNetwork(network)
            .withNetworkAliases(MOCK_COLLECTOR_ALIAS)
            .withLogConsumer(new Slf4jLogConsumer(collectorLogger))
            .waitingFor(Wait.forHttp("/health").forPort(MOCK_COLLECTOR_PORT));
    mockCollector.start();

    mockCollectorClient =
        new MockCollectorClient(
            WebClient.builder(
                    "http://localhost:" + mockCollector.getMappedPort(MOCK_COLLECTOR_PORT))
                .maxResponseLength(0)
                .build());

    application =
        new GenericContainer<>(getApplicationImageName())
            .withExposedPorts(getApplicationPort())
            .withNetwork(network)
            .withLogConsumer(new Slf4jLogConsumer(applicationLogger))
            .withCopyFileToContainer(MountableFile.forHostPath(AGENT_PATH), MOUNT_PATH)
            .waitingFor(Wait.forHttp("/health").forPort(getApplicationPort()))
            .withEnv(getEnvironmentVariables())
            .withEnv(getApplicationExtraEnvironmentVariables());

    application.start();
    appClient = WebClient.of("http://localhost:" + application.getMappedPort(getApplicationPort()));

    // Let serviceevents initialization complete
    Thread.sleep(500);
  }

  @AfterEach
  protected void tearDown() {
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

  // ---------------------------------------------------------------------------
  // Environment configuration
  // ---------------------------------------------------------------------------

  private Map<String, String> getEnvironmentVariables() {
    // Build the OTLP endpoint URLs pointing to mock collector via Docker network alias
    String collectorBaseUrl = "http://" + MOCK_COLLECTOR_ALIAS + ":" + MOCK_COLLECTOR_PORT;

    Map<String, String> env = new HashMap<>();
    // Agent config
    env.put("JAVA_TOOL_OPTIONS", "-javaagent:" + MOUNT_PATH);
    // Disable standard OTel exporters
    env.put("OTEL_TRACES_EXPORTER", "none");
    env.put("OTEL_METRICS_EXPORTER", "none");
    env.put("OTEL_LOGS_EXPORTER", "none");
    env.put("OTEL_AWS_APPLICATION_SIGNALS_ENABLED", "false");
    env.put("OTEL_TRACES_SAMPLER", "always_on");
    // Service identification
    env.put(
        "OTEL_RESOURCE_ATTRIBUTES",
        "service.name=" + getApplicationOtelServiceName() + ",deployment.environment.name=test");
    // ServiceEvents config. Note: `OTEL_AWS_SERVICE_EVENTS_OUTPUT_FILE` is intentionally NOT set
    // here — the test asserts against the OTLP mock collector, and setting
    // OUTPUT_FILE would switch the SDK into file-only mode and skip the network export.
    env.put("OTEL_AWS_SERVICE_EVENTS_ENABLED", "true");
    env.put("OTEL_AWS_SERVICE_EVENTS_FUNCTION_INSTRUMENT_ENABLED", "true");
    // Keep the global threshold comfortably above /slow-success's 3s sleep so any incident the
    // test observes for that endpoint MUST come from the per-endpoint override below. This
    // exercises OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS end-to-end.
    env.put("OTEL_AWS_SERVICE_EVENTS_INCIDENT_SNAPSHOT_DURATION_THRESHOLD_MS", "30000");
    // Per-endpoint override: trigger a latency incident when /slow-success exceeds 500ms.
    // The trailing "bad-entry:notanumber" malformed segment exercises the skip-and-log path.
    // Entries are comma-separated (matching the Python and JS SDKs).
    env.put(
        "OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS", "GET /slow-success:500,bad-entry:notanumber");
    env.put(
        "OTEL_AWS_SERVICE_EVENTS_PACKAGES_INCLUDE",
        "software.amazon.opentelemetry.appsignals.tests.images.serviceevents");
    // Flush intervals are internal now; inject the fast test cadence via the test-config hook.
    // (Java has no INCIDENT_SNAPSHOT_FLUSH_INTERVAL field — that key was a no-op here, so it's
    // dropped rather than carried into the hook.)
    env.put(
        "DEBUG_SE_TEST_CONFIG",
        "FUNCTION_CALL_FLUSH_INTERVAL="
            + SERVICE_EVENTS_FLUSH_INTERVAL_MS
            + ";ENDPOINT_FLUSH_INTERVAL="
            + SERVICE_EVENTS_FLUSH_INTERVAL_MS);
    env.put("SERVICE_EVENTS_SAMPLING_MODE", "always");
    // Relax dedup limits for testing
    env.put("OTEL_AWS_SERVICE_EVENTS_INCIDENT_SNAPSHOT_MAX_PER_MINUTE", "1000");
    env.put("OTEL_AWS_SERVICE_EVENTS_INCIDENT_SNAPSHOT_MAX_SAME_ERROR", "100");
    // OTLP endpoints pointing to mock collector
    env.put("OTEL_AWS_OTLP_LOGS_ENDPOINT", collectorBaseUrl + "/v1/logs");
    env.put("OTEL_AWS_OTLP_METRICS_ENDPOINT", collectorBaseUrl + "/v1/metrics");
    return env;
  }

  // ---------------------------------------------------------------------------
  // NDJSON reading
  // ---------------------------------------------------------------------------

  protected List<JsonNode> readServiceEventsRecords() throws Exception {
    var execResult =
        application.execInContainer("sh", "-c", "cat " + SERVICE_EVENTS_LOG_PATH + "*");
    if (execResult.getExitCode() != 0) {
      return List.of();
    }
    String content = execResult.getStdout().trim();
    if (content.isEmpty()) {
      return List.of();
    }
    List<JsonNode> records = new ArrayList<>();
    for (String line : content.split("\n")) {
      line = line.trim();
      if (!line.isEmpty()) {
        try {
          records.add(OBJECT_MAPPER.readTree(line));
        } catch (Exception e) {
          applicationLogger.warn(
              "Failed to parse NDJSON line: {}", line.substring(0, Math.min(200, line.length())));
        }
      }
    }
    return records;
  }

  protected List<JsonNode> getRecordsByType(String telemetryType) throws Exception {
    return readServiceEventsRecords().stream()
        .filter(r -> telemetryType.equals(r.path("telemetry_type").asText("")))
        .collect(Collectors.toList());
  }

  protected List<JsonNode> waitForServiceEventsRecords(String telemetryType) throws Exception {
    return waitForServiceEventsRecords(telemetryType, 1);
  }

  protected List<JsonNode> waitForServiceEventsRecords(String telemetryType, int minCount)
      throws Exception {
    long deadline = System.currentTimeMillis() + SERVICE_EVENTS_WAIT_TIMEOUT.toMillis();
    List<JsonNode> records = List.of();
    while (System.currentTimeMillis() < deadline) {
      records = getRecordsByType(telemetryType);
      if (records.size() >= minCount) {
        return records;
      }
      Thread.sleep(SERVICE_EVENTS_POLL_INTERVAL.toMillis());
    }

    // Final attempt
    records = getRecordsByType(telemetryType);
    if (records.size() < minCount) {
      List<JsonNode> allRecords = readServiceEventsRecords();
      Set<String> allTypes =
          allRecords.stream()
              .map(r -> r.path("telemetry_type").asText("unknown"))
              .collect(Collectors.toSet());
      fail(
          "Timed out waiting for "
              + minCount
              + " "
              + telemetryType
              + " record(s). Found "
              + records.size()
              + " after "
              + SERVICE_EVENTS_WAIT_TIMEOUT.getSeconds()
              + "s. All record types present: "
              + allTypes);
    }
    return records;
  }

  // ---------------------------------------------------------------------------
  // Request helpers
  // ---------------------------------------------------------------------------

  protected AggregatedHttpResponse sendRequest(String path) {
    return appClient.get("/" + path).aggregate().join();
  }

  // ---------------------------------------------------------------------------
  // Assertion helpers
  // ---------------------------------------------------------------------------

  protected void assertFunctionCallRecord(JsonNode record, String serviceName, String environment) {
    assertThat(record.path("telemetry_type").asText()).isEqualTo("FunctionCall");
    assertThat(record.has("function_id")).isTrue();
    assertThat(record.has("duration")).isTrue();
    assertThat(record.has("service_name")).isTrue();
    assertThat(record.has("environment")).isTrue();
    assertThat(record.has("timestamp")).isTrue();
    assertThat(record.has("pid")).isTrue();

    if (serviceName != null) {
      assertThat(record.path("service_name").asText()).isEqualTo(serviceName);
    }
    if (environment != null) {
      assertThat(record.path("environment").asText()).isEqualTo(environment);
    }
  }

  protected void assertEndpointSummaryRecord(
      JsonNode record,
      String method,
      String route,
      String operation,
      Integer minCount,
      Boolean hasFaults,
      Boolean hasErrors) {
    assertThat(record.path("telemetry_type").asText()).isEqualTo("EndpointSummary");
    assertThat(record.has("endpoint_id")).isTrue();
    assertThat(record.has("method")).isTrue();
    assertThat(record.has("route")).isTrue();
    assertThat(record.has("operation")).isTrue();
    assertThat(record.has("count")).isTrue();
    assertThat(record.has("duration")).isTrue();

    if (method != null) {
      assertThat(record.path("method").asText()).isEqualTo(method);
    }
    if (route != null) {
      assertThat(record.path("route").asText()).isEqualTo(route);
    }
    if (operation != null) {
      assertThat(record.path("operation").asText()).isEqualTo(operation);
    }
    if (minCount != null) {
      assertThat(record.path("count").asInt()).isGreaterThanOrEqualTo(minCount);
    }
    if (hasFaults != null && hasFaults) {
      assertThat(record.path("faults").asInt(0)).isGreaterThan(0);
    }
    if (hasFaults != null && !hasFaults) {
      assertThat(record.path("faults").asInt(0)).isEqualTo(0);
    }
    if (hasErrors != null && hasErrors) {
      assertThat(record.path("errors").asInt(0)).isGreaterThan(0);
    }
    if (hasErrors != null && !hasErrors) {
      assertThat(record.path("errors").asInt(0)).isEqualTo(0);
    }
  }

  protected void assertIncidentSnapshotRecord(
      JsonNode record, String triggerType, String exceptionType, String affectedEndpoint) {
    assertThat(record.path("telemetry_type").asText()).isEqualTo("IncidentSnapshot");
    assertThat(record.has("snapshot_id")).isTrue();
    assertThat(record.has("severity")).isTrue();
    assertThat(record.has("trigger_type")).isTrue();
    assertThat(record.has("affected_endpoint")).isTrue();
    assertThat(record.has("exception_info")).isTrue();
    assertThat(record.has("request_context")).isTrue();

    if (triggerType != null) {
      assertThat(record.path("trigger_type").asText()).isEqualTo(triggerType);
    }
    if (affectedEndpoint != null) {
      assertThat(record.path("affected_endpoint").asText()).isEqualTo(affectedEndpoint);
    }
    if (exceptionType != null) {
      JsonNode excInfo = record.path("exception_info");
      assertThat(excInfo.isArray() && excInfo.size() > 0)
          .as("Expected exception_info to be non-empty")
          .isTrue();
      assertThat(excInfo.get(0).path("exception_type").asText()).isEqualTo(exceptionType);
    }
  }

  /**
   * Assert that an EndpointSummary record has the incidents_exemplar field present.
   *
   * <p>The field is always present: an empty array when no incidents occurred, or a populated array
   * with exemplar entries when incidents were captured during the summary window.
   */
  protected void assertIncidentsExemplarPresent(JsonNode endpointRecord) {
    assertThat(endpointRecord.has("incidents_exemplar"))
        .as("EndpointSummary should have incidents_exemplar field")
        .isTrue();
    assertThat(endpointRecord.path("incidents_exemplar").isArray())
        .as("incidents_exemplar should be an array")
        .isTrue();
  }

  /**
   * Assert that each entry in incidents_exemplar has the expected fields and valid values.
   *
   * @param endpointRecord An EndpointSummary record with a non-empty incidents_exemplar array
   */
  protected void assertIncidentsExemplarEntries(JsonNode endpointRecord) {
    JsonNode exemplars = endpointRecord.path("incidents_exemplar");
    assertThat(exemplars.isArray()).isTrue();
    assertThat(exemplars.size()).isGreaterThan(0);

    for (JsonNode exemplar : exemplars) {
      assertThat(exemplar.has("snapshot_id")).as("Exemplar should have snapshot_id").isTrue();
      assertThat(exemplar.path("snapshot_id").asText()).startsWith("snap_");

      assertThat(exemplar.has("trigger_type")).as("Exemplar should have trigger_type").isTrue();
      assertThat(exemplar.path("trigger_type").asText()).isIn("exception", "latency");

      assertThat(exemplar.has("severity")).as("Exemplar should have severity").isTrue();
      assertThat(exemplar.path("severity").asText()).isNotEmpty();

      assertThat(exemplar.has("timestamp")).as("Exemplar should have timestamp").isTrue();
      assertThat(exemplar.path("timestamp").asLong()).isGreaterThan(0);
    }
  }

  /**
   * Assert that snapshot_id values in incidents_exemplar match snapshot_id values from
   * IncidentSnapshot records.
   *
   * @param endpointRecord An EndpointSummary record with a non-empty incidents_exemplar array
   * @param incidentSnapshots List of IncidentSnapshot records to cross-reference against
   */
  protected void assertIncidentsExemplarCrossReference(
      JsonNode endpointRecord, List<JsonNode> incidentSnapshots) {
    JsonNode exemplars = endpointRecord.path("incidents_exemplar");
    assertThat(exemplars.isArray()).isTrue();
    assertThat(exemplars.size()).isGreaterThan(0);

    Set<String> incidentSnapshotIds =
        incidentSnapshots.stream()
            .map(r -> r.path("snapshot_id").asText())
            .collect(Collectors.toSet());

    for (JsonNode exemplar : exemplars) {
      String exemplarSnapshotId = exemplar.path("snapshot_id").asText();
      assertThat(incidentSnapshotIds)
          .as(
              "Exemplar snapshot_id '"
                  + exemplarSnapshotId
                  + "' should match an IncidentSnapshot record")
          .contains(exemplarSnapshotId);
    }
  }

  /**
   * Assert the structure of a DeploymentEvent record.
   *
   * @param record The JSON record
   * @param serviceName Expected service name (null to skip)
   * @param environment Expected environment (null to skip)
   */
  protected void assertDeploymentEventRecord(
      JsonNode record, String serviceName, String environment) {
    assertThat(record.path("telemetry_type").asText()).isEqualTo("DeploymentEvent");
    assertThat(record.has("sdk_lang")).isTrue();
    assertThat(record.path("sdk_lang").asText()).isEqualTo("java");
    assertThat(record.has("timestamp")).isTrue();
    assertThat(record.path("timestamp").asText()).isNotEmpty();
    assertThat(record.has("service_name")).isTrue();
    assertThat(record.has("environment")).isTrue();
    assertThat(record.has("sdk_version")).isTrue();
    assertThat(record.path("sdk_version").asText()).isNotEmpty();
    assertThat(record.has("pid")).isTrue();
    assertThat(record.path("pid").asLong()).isGreaterThan(0);

    // Verify nested deployment_context
    assertThat(record.has("deployment_context")).isTrue();
    JsonNode ctx = record.path("deployment_context");
    assertThat(ctx.isObject()).isTrue();
    assertThat(ctx.has("git_repo_url")).isTrue();
    assertThat(ctx.has("git_commit_sha")).isTrue();
    assertThat(ctx.has("deployment_url")).isTrue();
    assertThat(ctx.has("deployment_timestamp")).isTrue();
    assertThat(ctx.has("deployment_id")).isTrue();

    if (serviceName != null) {
      assertThat(record.path("service_name").asText()).isEqualTo(serviceName);
    }
    if (environment != null) {
      assertThat(record.path("environment").asText()).isEqualTo(environment);
    }
  }

  protected void assertDurationStructure(JsonNode duration) {
    assertThat(duration.has("Values")).isTrue();
    assertThat(duration.has("Counts")).isTrue();
    assertThat(duration.has("Max")).isTrue();
    assertThat(duration.has("Min")).isTrue();
    assertThat(duration.has("Count")).isTrue();
    assertThat(duration.has("Sum")).isTrue();
    assertThat(duration.path("Count").asInt()).isGreaterThan(0);
    assertThat(duration.path("Sum").asDouble()).isGreaterThan(0);
  }

  protected JsonNode findEndpointRecord(List<JsonNode> records, String method, String route) {
    for (JsonNode record : records) {
      if (method.equals(record.path("method").asText())
          && route.equals(record.path("route").asText())) {
        return record;
      }
    }
    List<String> available =
        records.stream()
            .map(r -> r.path("method").asText() + " " + r.path("route").asText())
            .collect(Collectors.toList());
    fail("No EndpointSummary found for " + method + " " + route + ". Available: " + available);
    return null; // unreachable
  }

  /**
   * Wait for an EndpointSummary record matching the given method and route.
   *
   * <p>Unlike {@link #waitForServiceEventsRecords}, this polls until the specific endpoint appears,
   * avoiding races where an earlier endpoint (e.g. /health) satisfies the minimum count.
   */
  protected JsonNode waitForEndpointRecord(String method, String route) throws Exception {
    long deadline = System.currentTimeMillis() + SERVICE_EVENTS_WAIT_TIMEOUT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      List<JsonNode> records = getRecordsByType("EndpointSummary");
      for (JsonNode record : records) {
        if (method.equals(record.path("method").asText())
            && route.equals(record.path("route").asText())) {
          return record;
        }
      }
      Thread.sleep(SERVICE_EVENTS_POLL_INTERVAL.toMillis());
    }
    // Final attempt with detailed error
    List<JsonNode> records = getRecordsByType("EndpointSummary");
    return findEndpointRecord(records, method, route);
  }

  /**
   * Assert that an IncidentSnapshot has valid telemetry correlation fields.
   *
   * <p>Validates that request_id is present (always generated) and optionally checks trace/span
   * IDs.
   */
  protected void assertTelemetryCorrelation(JsonNode record) {
    JsonNode correlation = record.path("telemetry_correlation");
    assertThat(correlation.isMissingNode()).isFalse();
    // telemetry_correlation should have at least one identifier present: the synchronous
    // IncidentSnapshot path provides request_id, and trace_id/span_id when the request is sampled.
    boolean hasAnyCorrelation =
        correlation.has("request_id") || correlation.has("trace_id") || correlation.has("span_id");
    assertThat(hasAnyCorrelation)
        .as("telemetry_correlation should have at least one of: request_id, trace_id, span_id")
        .isTrue();
  }

  /**
   * Assert the structure of an ExceptionMetric record.
   *
   * @param record The JSON record
   * @param operation Expected operation (null to skip)
   * @param errorType Expected error type (null to skip)
   */
  protected void assertExceptionMetricRecord(JsonNode record, String operation, String errorType) {
    assertThat(record.path("telemetry_type").asText()).isEqualTo("ExceptionMetric");
    assertThat(record.has("operation")).isTrue();
    assertThat(record.has("error_type")).isTrue();
    assertThat(record.has("count")).isTrue();
    assertThat(record.path("count").asInt()).isGreaterThan(0);

    if (operation != null) {
      assertThat(record.path("operation").asText()).isEqualTo(operation);
    }
    if (errorType != null) {
      assertThat(record.path("error_type").asText()).isEqualTo(errorType);
    }
  }

  /**
   * Assert that common service metadata fields are present and correct across any telemetry record.
   *
   * @param record Any telemetry record
   * @param serviceName Expected service name (null to skip check, but field must exist)
   * @param environment Expected environment (null to skip check, but field must exist)
   */
  protected void assertServiceMetadata(JsonNode record, String serviceName, String environment) {
    // service/service_name field may vary by telemetry type
    boolean hasService = record.has("service") || record.has("service_name");
    assertThat(hasService).as("Record should have service or service_name field").isTrue();
    assertThat(record.has("pid")).isTrue();
    assertThat(record.path("pid").asLong()).isGreaterThan(0);

    // sdk_lang must be present and set to "java" for all telemetry types
    assertThat(record.has("sdk_lang")).as("Record should have sdk_lang field").isTrue();
    assertThat(record.path("sdk_lang").asText()).isEqualTo("java");

    if (serviceName != null) {
      String actualService =
          record.has("service")
              ? record.path("service").asText()
              : record.path("service_name").asText();
      assertThat(actualService).isEqualTo(serviceName);
    }
    if (environment != null && record.has("environment")) {
      assertThat(record.path("environment").asText()).isEqualTo(environment);
    }
  }

  /** Wait for ExceptionMetric records to appear. */
  protected List<JsonNode> waitForExceptionMetrics() throws Exception {
    return waitForServiceEventsRecords("ExceptionMetric", 1);
  }

  // ---------------------------------------------------------------------------
  // OTLP log record helpers
  // ---------------------------------------------------------------------------

  /**
   * Get OTLP log records from the mock collector filtered by event.name attribute.
   *
   * @param eventName The event.name attribute value (e.g. "aws.service_events.endpoint_summary")
   * @return List of ResourceScopeLog matching the event name
   */
  protected List<ResourceScopeLog> getOtlpLogsByEventName(String eventName) {
    return mockCollectorClient.getLogsByEventName(eventName);
  }

  /**
   * Poll the mock collector until at least {@code minCount} OTLP log records with the given
   * event.name appear, or timeout.
   */
  protected List<ResourceScopeLog> waitForOtlpLogs(String eventName, int minCount)
      throws Exception {
    long deadline = System.currentTimeMillis() + SERVICE_EVENTS_WAIT_TIMEOUT.toMillis();
    List<ResourceScopeLog> logs = List.of();
    while (System.currentTimeMillis() < deadline) {
      logs = getOtlpLogsByEventName(eventName);
      if (logs.size() >= minCount) {
        return logs;
      }
      Thread.sleep(SERVICE_EVENTS_POLL_INTERVAL.toMillis());
    }
    // Final attempt
    logs = getOtlpLogsByEventName(eventName);
    if (logs.size() < minCount) {
      fail(
          "Timed out waiting for "
              + minCount
              + " OTLP log(s) with event.name='"
              + eventName
              + "'. Found "
              + logs.size()
              + " after "
              + SERVICE_EVENTS_WAIT_TIMEOUT.getSeconds()
              + "s.");
    }
    return logs;
  }

  /**
   * Get a specific attribute value from an OTLP log record.
   *
   * @param log The ResourceScopeLog to search
   * @param key The attribute key
   * @return The string value, or null if not found
   */
  protected String getLogAttribute(ResourceScopeLog log, String key) {
    return log.getLog().getAttributesList().stream()
        .filter(kv -> key.equals(kv.getKey()))
        .map(kv -> kv.getValue().getStringValue())
        .findFirst()
        .orElse(null);
  }

  /**
   * Get a specific long attribute value from an OTLP log record.
   *
   * @param log The ResourceScopeLog to search
   * @param key The attribute key
   * @return The long value, or null if not found
   */
  protected Long getLogAttributeLong(ResourceScopeLog log, String key) {
    return log.getLog().getAttributesList().stream()
        .filter(kv -> key.equals(kv.getKey()))
        .map(kv -> kv.getValue().getIntValue())
        .findFirst()
        .orElse(null);
  }

  /**
   * Assert that an OTLP log record has the expected instrumentation scope.
   *
   * @param log The ResourceScopeLog to check
   */
  protected void assertOtlpInstrumentationScope(ResourceScopeLog log) {
    assertThat(log.getScope().getScope().getName()).isEqualTo("serviceevents");
    assertThat(log.getScope().getScope().getVersion()).isEqualTo("1.0");
  }

  // ---------------------------------------------------------------------------
  // OTLP body decoding (KvlistValue → plain JSON-like Object tree)
  // ---------------------------------------------------------------------------

  /**
   * Decode an OTLP {@link AnyValue} into a plain Java tree ({@link Map}, {@link List}, or leaf
   * primitive) so tests can navigate bodies without OTLP proto wrappers.
   */
  protected static Object decodeAnyValue(AnyValue value) {
    switch (value.getValueCase()) {
      case STRING_VALUE:
        return value.getStringValue();
      case BOOL_VALUE:
        return value.getBoolValue();
      case INT_VALUE:
        return value.getIntValue();
      case DOUBLE_VALUE:
        return value.getDoubleValue();
      case ARRAY_VALUE:
        {
          List<Object> out = new ArrayList<>(value.getArrayValue().getValuesCount());
          for (AnyValue elem : value.getArrayValue().getValuesList()) {
            out.add(decodeAnyValue(elem));
          }
          return out;
        }
      case KVLIST_VALUE:
        {
          Map<String, Object> out = new HashMap<>();
          KeyValueList kv = value.getKvlistValue();
          for (KeyValue pair : kv.getValuesList()) {
            out.put(pair.getKey(), decodeAnyValue(pair.getValue()));
          }
          return out;
        }
      case BYTES_VALUE:
        return value.getBytesValue().toByteArray();
      case VALUE_NOT_SET:
      default:
        return null;
    }
  }

  /** Decode the body of an OTLP log record into a plain Map. */
  @SuppressWarnings("unchecked")
  protected static Map<String, Object> decodeLogBody(ResourceScopeLog log) {
    Object decoded = decodeAnyValue(log.getLog().getBody());
    return decoded instanceof Map ? (Map<String, Object>) decoded : new HashMap<>();
  }

  // ---------------------------------------------------------------------------
  // Overridable methods
  // ---------------------------------------------------------------------------

  protected abstract String getApplicationImageName();

  protected int getApplicationPort() {
    return 8080;
  }

  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    return Map.of();
  }

  protected String getApplicationOtelServiceName() {
    return getApplicationImageName();
  }
}
