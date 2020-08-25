package io.awsobservability.instrumentation.smoketests.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.client.WebClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class SpringBootSmokeTest {

  private static final Logger logger = LoggerFactory.getLogger(SpringBootSmokeTest.class);

  private static final String AGENT_PATH =
      System.getProperty("io.awsobservability.instrumentation.smoketests.runner.agentPath");

  private Network network = Network.newNetwork();

  // We have to recreate collector for every test to wipe exported file with traces so not static.
  @Container
  private GenericContainer<?> collector =
      new GenericContainer<>("otel/opentelemetry-collector-dev")
          .withNetwork(network)
          .withNetworkAliases("collector")
          .withLogConsumer(new Slf4jLogConsumer(logger))
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("/otel.yaml"), "/etc/otel.yaml")
          .withCommand("--config /etc/otel.yaml");

  @Container
  private GenericContainer<?> application =
      new GenericContainer<>(
              "docker.pkg.github.com/anuraaga/aws-opentelemetry-java-instrumentation/smoke-tests-spring-boot:master")
          .withExposedPorts(8080)
          .withNetwork(network)
          .withLogConsumer(new Slf4jLogConsumer(logger))
          .withCopyFileToContainer(
              MountableFile.forHostPath(AGENT_PATH), "/opentelemetry-javaagent-all.jar")
          .withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opentelemetry-javaagent-all.jar")
          .withEnv("OTEL_BSP_MAX_EXPORT_BATCH", "1")
          .withEnv("OTEL_BSP_SCHEDULE_DELAY", "10")
          .withEnv("OTEL_OTLP_ENDPOINT", "collector:55680");

  private WebClient client;

  @BeforeEach
  void setUp() {
    client = WebClient.of("http://localhost:" + application.getMappedPort(8080));
  }

  @Test
  void sendRequest() {
    var response = client.get("/hello").aggregate().join();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.contentUtf8()).isEqualTo("Hi there!");
  }
}
