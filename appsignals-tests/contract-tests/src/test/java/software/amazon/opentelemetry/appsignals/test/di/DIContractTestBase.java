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

package software.amazon.opentelemetry.appsignals.test.di;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import software.amazon.opentelemetry.appsignals.test.utils.MockCollectorClient;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeLog;

/**
 * Base class for DI contract tests using OTLP LogRecord verification.
 *
 * <p>Uses the di-spring-boot sample app which includes a built-in mock DI API (serving
 * probe/breakpoint configs from classpath). Snapshots are emitted as OTLP LogRecords to the mock
 * collector, and tests query via the MockCollectorClient.
 *
 * <p>Snapshot pipeline: App function hit -> DISnapshotOtlpEmitter -> POST /v1/logs -> mock
 * collector. Tests -> MockCollectorClient.getLogsByEventName() -> ResourceScopeLog.
 */
@Testcontainers
public abstract class DIContractTestBase {

  protected static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String AGENT_PATH =
      System.getProperty("io.awsobservability.di.contracttests.agentPath");

  protected static final String SERVICE_NAME = "di-contract-test";
  protected static final String ENVIRONMENT_NAME = "contract-test-env";
  protected static final String DI_EVENT_NAME = "aws.dynamic_instrumentation.snapshot";

  private static final String APP_IMAGE = "aws-di-tests-spring-boot-app";
  private static final String MOCK_COLLECTOR_IMAGE = "aws-appsignals-mock-collector";
  private static final int MOCK_COLLECTOR_PORT = 4317;
  private static final int APP_PORT = 8080;

  private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
  private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

  /** DI poll interval in seconds — configs are fetched every N seconds. */
  private static final int DI_POLL_INTERVAL_SECONDS = 3;

  protected static Network network;
  protected static GenericContainer<?> mockCollector;
  protected static GenericContainer<?> appContainer;

  protected static WebClient appClient;
  protected static MockCollectorClient collectorClient;

  @BeforeAll
  static void startInfra() {
    assertNotNull(AGENT_PATH, "Agent path must be set via system property");
    assertTrue(new File(AGENT_PATH).exists(), "Agent JAR must exist: " + AGENT_PATH);

    network = Network.newNetwork();

    // Start mock OTLP collector (receives DI snapshot LogRecords)
    mockCollector =
        new GenericContainer<>(MOCK_COLLECTOR_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("collector")
            .withExposedPorts(MOCK_COLLECTOR_PORT)
            .waitingFor(
                Wait.forHttp("/health")
                    .forPort(MOCK_COLLECTOR_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(30)));
    mockCollector.start();

    // Start sample app with ADOT agent.
    // The di-spring-boot app has a built-in mock DI API (MockDIApiController) that serves
    // probe/breakpoint configs from classpath resources on localhost:8080.
    appContainer =
        new GenericContainer<>(APP_IMAGE)
            .withNetwork(network)
            .withExposedPorts(APP_PORT)
            .withCopyFileToContainer(
                MountableFile.forHostPath(AGENT_PATH), "/opentelemetry-javaagent-all.jar")
            .withEnv(getAppEnvironment())
            .waitingFor(
                Wait.forHttp("/success")
                    .forPort(APP_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(90)));
    appContainer.start();

    appClient = WebClient.of("http://127.0.0.1:" + appContainer.getMappedPort(APP_PORT));
    collectorClient =
        new MockCollectorClient(
            WebClient.of("http://127.0.0.1:" + mockCollector.getMappedPort(MOCK_COLLECTOR_PORT)));

    // Wait for DI to poll and apply configs
    try {
      Thread.sleep((DI_POLL_INTERVAL_SECONDS + 12) * 1000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @AfterAll
  static void stopInfra() {
    if (appContainer != null) {
      appContainer.stop();
    }
    if (mockCollector != null) {
      mockCollector.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  protected static Map<String, String> getAppEnvironment() {
    String javaToolOptions = "-javaagent:/opentelemetry-javaagent-all.jar";

    // DI snapshots are emitted as OTLP LogRecords to the mock collector
    String logsEndpoint = "http://collector:" + MOCK_COLLECTOR_PORT + "/v1/logs";

    return Map.ofEntries(
        Map.entry("JAVA_TOOL_OPTIONS", javaToolOptions),
        // DI is opt-in; explicitly enable for these tests
        Map.entry("OTEL_AWS_DYNAMIC_INSTRUMENTATION_ENABLED", "true"),
        // DI config — mock API runs inside the app on localhost:8080
        Map.entry("OTEL_AWS_DYNAMIC_INSTRUMENTATION_API_URL", "http://localhost:" + APP_PORT),
        Map.entry(
            "OTEL_AWS_DYNAMIC_INSTRUMENTATION_PROBE_POLL_INTERVAL",
            String.valueOf(DI_POLL_INTERVAL_SECONDS)),
        Map.entry(
            "OTEL_AWS_DYNAMIC_INSTRUMENTATION_BREAKPOINT_POLL_INTERVAL",
            String.valueOf(DI_POLL_INTERVAL_SECONDS)),
        Map.entry("OTEL_AWS_OTLP_LOGS_ENDPOINT", logsEndpoint),
        // OTel config
        Map.entry("OTEL_SERVICE_NAME", SERVICE_NAME),
        Map.entry("OTEL_RESOURCE_ATTRIBUTES", "deployment.environment.name=" + ENVIRONMENT_NAME),
        Map.entry("OTEL_TRACES_EXPORTER", "none"),
        Map.entry("OTEL_METRICS_EXPORTER", "none"),
        Map.entry("OTEL_LOGS_EXPORTER", "none"));
  }

  // ---------------------------------------------------------------------------
  // HTTP helpers
  // ---------------------------------------------------------------------------

  /** Send an HTTP GET to the sample app. */
  protected String sendRequest(String path) {
    return appClient.get(path).aggregate().join().contentUtf8();
  }

  // ---------------------------------------------------------------------------
  // OTLP snapshot retrieval
  // ---------------------------------------------------------------------------

  /** Get all DI snapshot LogRecords from the mock collector. */
  protected List<ResourceScopeLog> peekSnapshots() {
    return collectorClient.getLogsByEventName(DI_EVENT_NAME);
  }

  /** Wait until at least minCount snapshot LogRecords appear, with timeout. */
  protected List<ResourceScopeLog> waitForSnapshots(int minCount) throws Exception {
    long deadline = System.currentTimeMillis() + WAIT_TIMEOUT.toMillis();
    List<ResourceScopeLog> snapshots = List.of();
    while (System.currentTimeMillis() < deadline) {
      snapshots = peekSnapshots();
      if (snapshots.size() >= minCount) {
        return snapshots;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    }
    fail(
        "Timed out waiting for "
            + minCount
            + " snapshot(s). Got "
            + snapshots.size()
            + " after "
            + WAIT_TIMEOUT.toSeconds()
            + "s");
    return snapshots; // unreachable
  }

  /** Filter snapshot logs by aws.di.method_name attribute. */
  protected List<ResourceScopeLog> logsForMethod(List<ResourceScopeLog> logs, String methodName) {
    return logs.stream()
        .filter(log -> methodName.equals(getAttr(log, "aws.di.method_name")))
        .collect(Collectors.toList());
  }

  /** Filter snapshot logs by aws.di.location_hash attribute. */
  protected List<ResourceScopeLog> logsForLocationHash(
      List<ResourceScopeLog> logs, String locationHash) {
    return logs.stream()
        .filter(log -> locationHash.equals(getAttr(log, "aws.di.location_hash")))
        .collect(Collectors.toList());
  }

  /**
   * Wait for a snapshot whose method_name matches and whose body captures.return.return_value.value
   * contains the given marker string.
   */
  protected ResourceScopeLog waitForSnapshotWithReturnValue(String methodName, String marker)
      throws Exception {
    long deadline = System.currentTimeMillis() + WAIT_TIMEOUT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      List<ResourceScopeLog> logs = logsForMethod(peekSnapshots(), methodName);
      for (int i = logs.size() - 1; i >= 0; i--) {
        Map<String, Object> body = getBody(logs.get(i));
        String rv = extractReturnValue(body);
        if (rv != null && rv.contains(marker)) {
          return logs.get(i);
        }
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    }
    fail("Timed out waiting for snapshot with return value containing: " + marker);
    return null; // unreachable
  }

  /** Wait for a snapshot on the given method. Returns the first one found. */
  protected ResourceScopeLog waitForSnapshotForMethod(String methodName) throws Exception {
    long deadline = System.currentTimeMillis() + WAIT_TIMEOUT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      List<ResourceScopeLog> logs = logsForMethod(peekSnapshots(), methodName);
      if (!logs.isEmpty()) {
        return logs.get(0);
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    }
    fail("Timed out waiting for snapshot for method: " + methodName);
    return null; // unreachable
  }

  /**
   * Wait for a snapshot on the given method whose entry captures include the named argument.
   *
   * <p>The mock collector accumulates every snapshot across the test run and is never cleared, and
   * a method can produce more than one snapshot for the same method name (for example a transient
   * one captured before the breakpoint's argument-capture configuration is fully applied).
   * Selecting the first snapshot blindly is therefore racy. This helper polls until a snapshot that
   * actually contains the expected argument arrives, which makes argument-capture assertions
   * deterministic.
   */
  @SuppressWarnings("unchecked")
  protected ResourceScopeLog waitForSnapshotWithArgument(String methodName, String argumentName)
      throws Exception {
    long deadline = System.currentTimeMillis() + WAIT_TIMEOUT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      for (ResourceScopeLog log : logsForMethod(peekSnapshots(), methodName)) {
        Map<String, Object> body = getBody(log);
        Object captures = body.get("captures");
        if (!(captures instanceof Map)) {
          continue;
        }
        Object entry = ((Map<String, Object>) captures).get("entry");
        if (!(entry instanceof Map)) {
          continue;
        }
        Object arguments = ((Map<String, Object>) entry).get("arguments");
        if (arguments instanceof Map
            && ((Map<String, Object>) arguments).get(argumentName) != null) {
          return log;
        }
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    }
    fail(
        "Timed out waiting for snapshot for method "
            + methodName
            + " containing argument: "
            + argumentName);
    return null; // unreachable
  }

  // ---------------------------------------------------------------------------
  // OTLP attribute and body extraction helpers
  // ---------------------------------------------------------------------------

  /** Get a string attribute value from a ResourceScopeLog. */
  protected String getAttr(ResourceScopeLog log, String key) {
    return log.getLog().getAttributesList().stream()
        .filter(kv -> key.equals(kv.getKey()))
        .map(kv -> kv.getValue().getStringValue())
        .findFirst()
        .orElse(null);
  }

  /** Get a long attribute value from a ResourceScopeLog. */
  protected Long getAttrLong(ResourceScopeLog log, String key) {
    return log.getLog().getAttributesList().stream()
        .filter(kv -> key.equals(kv.getKey()))
        .map(kv -> kv.getValue().getIntValue())
        .findFirst()
        .orElse(null);
  }

  /** Check if an attribute exists on a ResourceScopeLog. */
  protected boolean hasAttr(ResourceScopeLog log, String key) {
    return log.getLog().getAttributesList().stream().anyMatch(kv -> key.equals(kv.getKey()));
  }

  /** Get the structured body as a nested Map from a ResourceScopeLog. */
  protected Map<String, Object> getBody(ResourceScopeLog log) {
    AnyValue body = log.getLog().getBody();
    Object parsed = anyValueToObject(body);
    if (parsed instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) parsed;
      return map;
    }
    return Map.of();
  }

  /** Convert an OTLP AnyValue to a Java object (String, Long, Map, List, etc.). */
  private Object anyValueToObject(AnyValue value) {
    if (value == null) return null;
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
        return value.getArrayValue().getValuesList().stream()
            .map(this::anyValueToObject)
            .collect(Collectors.toList());
      case KVLIST_VALUE:
        Map<String, Object> map = new HashMap<>();
        for (KeyValue kv : value.getKvlistValue().getValuesList()) {
          map.put(kv.getKey(), anyValueToObject(kv.getValue()));
        }
        return map;
      case BYTES_VALUE:
        return value.getBytesValue().toByteArray();
      default:
        return null;
    }
  }

  /** Extract return value string from body: captures.return.return_value.value */
  @SuppressWarnings("unchecked")
  protected String extractReturnValue(Map<String, Object> body) {
    try {
      Map<String, Object> captures = (Map<String, Object>) body.get("captures");
      if (captures == null) return null;
      Map<String, Object> ret = (Map<String, Object>) captures.get("return");
      if (ret == null) return null;
      Map<String, Object> retVal = (Map<String, Object>) ret.get("return_value");
      if (retVal == null) return null;
      Object value = retVal.get("value");
      return value != null ? value.toString() : null;
    } catch (ClassCastException e) {
      return null;
    }
  }

  /** Collect unique location hashes from a list of logs. */
  protected Set<String> uniqueLocationHashes(List<ResourceScopeLog> logs) {
    return logs.stream()
        .map(log -> getAttr(log, "aws.di.location_hash"))
        .filter(h -> h != null)
        .collect(Collectors.toSet());
  }
}
