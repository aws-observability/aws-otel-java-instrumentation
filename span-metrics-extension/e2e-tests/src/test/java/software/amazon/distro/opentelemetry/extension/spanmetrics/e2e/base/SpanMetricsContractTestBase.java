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

package software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.base;

import com.linecorp.armeria.client.WebClient;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.utils.MockCollectorClient;

/**
 * Base class for span-metrics extension e2e (contract) tests.
 *
 * <p>Boilerplate provided:
 *
 * <ol>
 *   <li>A shared {@link Network}.
 *   <li>A mock-collector container ({@code aws-otel-span-metrics-mock-collector}) that receives the
 *       application's OTLP telemetry over gRPC on port 4317; it is started once for the class and is
 *       reachable at network alias {@code collector}.
 *   <li>A per-test application container built from {@link #getApplicationImageName()}, exposing
 *       port 8080.
 * </ol>
 *
 * <p>The extension is wired into the application four different ways. Subclasses select the wiring
 * mode by overriding {@link #getApplicationImageName()} and, where the extension/agent jars must be
 * injected at runtime (the javaagent mode), {@link #configureContainer(GenericContainer)}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class SpanMetricsContractTestBase {

  private final Logger collectorLogger =
      LoggerFactory.getLogger("collector " + getApplicationOtelServiceName());
  protected final Logger applicationLogger =
      LoggerFactory.getLogger("application " + getApplicationOtelServiceName());

  /** Path to the plain OpenTelemetry javaagent jar, supplied by the {@code e2eTests} Gradle task. */
  protected static final String JAVAAGENT_JAR_PATH =
      System.getProperty("spanmetrics.javaagent.jar.path");

  /** Path to the built span-metrics extension jar, supplied by the {@code e2eTests} Gradle task. */
  protected static final String EXTENSION_JAR_PATH =
      System.getProperty("spanmetrics.extension.jar.path");

  /** In-container mount path for the javaagent jar (javaagent mode only). */
  protected static final String AGENT_MOUNT_PATH = "/agent.jar";

  /** In-container mount path for the extension jar (javaagent mode only). */
  protected static final String EXTENSION_MOUNT_PATH = "/span-metrics-extension.jar";

  protected static final String COLLECTOR_HOSTNAME = "collector";
  protected static final int COLLECTOR_PORT = 4317;
  protected static final String COLLECTOR_ENDPOINT =
      "http://" + COLLECTOR_HOSTNAME + ":" + COLLECTOR_PORT;

  protected static final int APPLICATION_PORT = 8080;

  protected final Network network = Network.newNetwork();

  protected final GenericContainer<?> mockCollector =
      new GenericContainer<>("aws-otel-span-metrics-mock-collector")
          .withExposedPorts(COLLECTOR_PORT)
          .waitingFor(Wait.forHttp("/health").forPort(COLLECTOR_PORT))
          .withLogConsumer(new Slf4jLogConsumer(collectorLogger))
          .withNetwork(network)
          .withNetworkAliases(COLLECTOR_HOSTNAME);

  protected GenericContainer<?> application;

  protected MockCollectorClient mockCollectorClient;
  protected WebClient appClient;

  @BeforeAll
  protected void startCollector() {
    mockCollector.start();
  }

  @AfterAll
  protected void stopCollector() {
    mockCollector.stop();
    network.close(); // release the Docker network; leaking these exhausts Docker over many runs
  }

  @BeforeEach
  protected void setup() {
    application = buildApplicationContainer();
    application.start();

    appClient = getApplicationClient();
    mockCollectorClient = getMockCollectorClient();
  }

  @AfterEach
  protected void cleanUp() {
    if (application != null) {
      application.stop();
    }
    if (mockCollectorClient != null) {
      mockCollectorClient.clearSignals();
    }
  }

  protected WebClient getApplicationClient() {
    return WebClient.of("http://localhost:" + application.getMappedPort(APPLICATION_PORT));
  }

  protected MockCollectorClient getMockCollectorClient() {
    return new MockCollectorClient(
        WebClient.of("http://localhost:" + mockCollector.getMappedPort(COLLECTOR_PORT)));
  }

  private GenericContainer<?> buildApplicationContainer() {
    GenericContainer<?> container =
        new GenericContainer<>(getApplicationImageName())
            .dependsOn(mockCollector)
            .withExposedPorts(APPLICATION_PORT)
            .withNetwork(network)
            .withLogConsumer(new Slf4jLogConsumer(applicationLogger))
            .waitingFor(getApplicationWaitCondition())
            .withEnv(getApplicationEnvironmentVariables())
            .withEnv(getApplicationExtraEnvironmentVariables());

    // Mode-specific customization (e.g. mounting the agent + extension jars for javaagent mode).
    configureContainer(container);
    return container;
  }

  /**
   * Default environment applied to every application container. Sends OTLP over gRPC to the mock
   * collector, samples 5% of traces (span metrics still reflect 100% of spans thanks to {@code
   * AlwaysRecordSampler}), and exports frequently so tests do not wait long for data.
   */
  protected Map<String, String> getApplicationEnvironmentVariables() {
    Map<String, String> env = new HashMap<>();
    env.put("OTEL_EXPORTER_OTLP_ENDPOINT", COLLECTOR_ENDPOINT);
    env.put("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc");
    env.put("OTEL_METRIC_EXPORT_INTERVAL", "1000");
    env.put("OTEL_BSP_SCHEDULE_DELAY", "0");
    env.put("OTEL_TRACES_SAMPLER", "parentbased_traceidratio");
    env.put("OTEL_TRACES_SAMPLER_ARG", "0.05");
    env.put("OTEL_SERVICE_NAME", getApplicationOtelServiceName());
    return env;
  }

  /** Hook: extra environment variables merged on top of the defaults. */
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    return Map.of();
  }

  /**
   * Hook to customize the application container before it starts. The default implementation is a
   * no-op; the javaagent subclass overrides it to mount the agent + extension jars and set {@code
   * JAVA_TOOL_OPTIONS} / {@code OTEL_JAVAAGENT_EXTENSIONS}.
   */
  protected void configureContainer(GenericContainer<?> container) {
    // no-op by default
  }

  protected WaitStrategy getApplicationWaitCondition() {
    return Wait.forLogMessage(getApplicationWaitPattern(), 1);
  }

  /** Regex matched against a single application log line to decide the container is ready. */
  protected String getApplicationWaitPattern() {
    return ".*SpanMetrics e2e application started.*";
  }

  protected String getApplicationOtelServiceName() {
    return getApplicationImageName();
  }

  /** The local docker image name of the application under test. */
  protected abstract String getApplicationImageName();
}
