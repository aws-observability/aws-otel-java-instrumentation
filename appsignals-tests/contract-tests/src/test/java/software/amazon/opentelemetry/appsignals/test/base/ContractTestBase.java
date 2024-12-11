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

package software.amazon.opentelemetry.appsignals.test.base;

import com.linecorp.armeria.client.WebClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.utility.MountableFile;
import software.amazon.opentelemetry.appsignals.test.utils.MockCollectorClient;

/**
 * Base class for implementing a contract test.
 *
 * <p>This class will create all the boilerplate necessary to run a contract test. It will: 1.Create
 * a mock collector container that receives telemetry data of the application being tested. 2.
 * Create an application container which will be used to exercise the library under test. A Java
 * agent is is injected into this application under test.
 *
 * <p>Several methods are provided that can be overridden to customize the test scenario.
 */
public abstract class ContractTestBase {

  private final Logger collectorLogger =
      LoggerFactory.getLogger("collector " + getApplicationOtelServiceName());
  protected final Logger applicationLogger =
      LoggerFactory.getLogger("application " + getApplicationOtelServiceName());

  protected static final String AGENT_PATH =
      System.getProperty("io.awsobservability.instrumentation.contracttests.agentPath");
  protected static final String MOUNT_PATH = "/opentelemetry-javaagent-all.jar";

  protected final Network network = Network.newNetwork();

  private static final String COLLECTOR_HOSTNAME = "collector";
  private static final int COLLECTOR_PORT = 4317;
  protected static final String COLLECTOR_HTTP_ENDPOINT =
      "http://" + COLLECTOR_HOSTNAME + ":" + COLLECTOR_PORT;

  protected final GenericContainer<?> mockCollector =
      new GenericContainer<>("aws-appsignals-mock-collector")
          .withExposedPorts(COLLECTOR_PORT)
          .waitingFor(Wait.forHttp("/health").forPort(COLLECTOR_PORT))
          .withLogConsumer(new Slf4jLogConsumer(collectorLogger))
          .withNetwork(network)
          .withNetworkAliases(COLLECTOR_HOSTNAME);

  protected final GenericContainer<?> application = getApplicationContainer();

  protected MockCollectorClient mockCollectorClient;
  protected WebClient appClient;

  @BeforeAll
  private void startCollector() {
    mockCollector.start();
  }

  @AfterAll
  private void stopCollector() {
    mockCollector.stop();
  }

  @BeforeEach
  protected void setupClients() {
    application.start();

    appClient = getApplicationClient();
    mockCollectorClient = getMockCollectorClient();
  }

  @AfterEach
  private void cleanUp() {
    application.stop();
    mockCollectorClient.clearSignals();
  }

  private List<Startable> getDependsOn() {
    ArrayList<Startable> dependencies = new ArrayList<>();
    dependencies.add(mockCollector);
    dependencies.addAll(getApplicationDependsOnContainers());
    return dependencies;
  }

  protected WebClient getApplicationClient() {
    return WebClient.of("http://localhost:" + application.getMappedPort(8080));
  }

  protected MockCollectorClient getMockCollectorClient() {
    return new MockCollectorClient(
        WebClient.of("http://localhost:" + mockCollector.getMappedPort(4317)));
  }

  protected GenericContainer<?> getApplicationContainer() {
    return new GenericContainer<>(getApplicationImageName())
        .dependsOn(getDependsOn())
        .withExposedPorts(getApplicationPort())
        .withNetwork(network)
        .withLogConsumer(new Slf4jLogConsumer(applicationLogger))
        .withCopyFileToContainer(MountableFile.forHostPath(AGENT_PATH), MOUNT_PATH)
        .waitingFor(getApplicationWaitCondition())
        .withEnv(getApplicationEnvironmentVariables())
        .withEnv(getApplicationExtraEnvironmentVariables())
        .withNetworkAliases(getApplicationNetworkAliases().toArray(new String[0]));
  }

  /** Methods that should be overridden in sub classes * */
  protected int getApplicationPort() {
    return 8080;
  }

  protected Map<String, String> getApplicationEnvironmentVariables() {
    return Map.of(
        "JAVA_TOOL_OPTIONS",
        "-javaagent:" + MOUNT_PATH,
        "OTEL_METRIC_EXPORT_INTERVAL",
        "100", // 100 ms
        "OTEL_AWS_APPLICATION_SIGNALS_ENABLED",
        "true",
        "OTEL_AWS_APPLICATION_SIGNALS_RUNTIME_ENABLED",
        isRuntimeEnabled(),
        "OTEL_METRICS_EXPORTER",
        "none",
        "OTEL_BSP_SCHEDULE_DELAY",
        "0", // Don't wait to export spans to the collector
        "OTEL_AWS_APPLICATION_SIGNALS_EXPORTER_ENDPOINT",
        COLLECTOR_HTTP_ENDPOINT,
        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
        COLLECTOR_HTTP_ENDPOINT,
        "OTEL_RESOURCE_ATTRIBUTES",
        getApplicationOtelResourceAttributes(),
        //        //            The default OTLP protocol has been changed from grpc to
        // http/protobuf in
        // order
        //        // to align with the specification. You can switch to the grpc protocol using
        //        // OTEL_EXPORTER_OTLP_PROTOCOL=grpc or -Dotel.exporter.otlp.protocol=grpc.
        "OTEL_EXPORTER_OTLP_PROTOCOL",
        "grpc");
  }

  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    return Map.of();
  }

  protected List<Startable> getApplicationDependsOnContainers() {
    return List.of();
  }

  protected List<String> getApplicationNetworkAliases() {
    return List.of();
  }

  protected abstract String getApplicationImageName();

  protected abstract String getApplicationWaitPattern();

  protected WaitStrategy getApplicationWaitCondition() {
    return Wait.forLogMessage(getApplicationWaitPattern(), 1);
  }

  protected String getApplicationOtelServiceName() {
    return getApplicationImageName();
  }

  protected String getApplicationOtelResourceAttributes() {
    return "service.name=" + getApplicationOtelServiceName();
  }

  protected String isRuntimeEnabled() {
    return "false";
  }
}
