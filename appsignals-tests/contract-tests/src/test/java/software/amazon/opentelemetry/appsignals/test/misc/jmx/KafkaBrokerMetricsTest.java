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

package software.amazon.opentelemetry.appsignals.test.misc.jmx;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.opentelemetry.appsignals.test.base.JMXMetricsContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.JMXMetricsConstants;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaBrokerMetricsTest extends JMXMetricsContractTestBase {
  @Test
  void testKafkaMetrics() {
    assertMetrics();
  }

  @Override
  protected GenericContainer<?> getApplicationContainer() {
    return new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
        .withImagePullPolicy(PullPolicy.alwaysPull())
        .withNetworkAliases("kafkaBroker")
        .withNetwork(network)
        .withLogConsumer(new Slf4jLogConsumer(applicationLogger))
        .withCopyFileToContainer(MountableFile.forHostPath(AGENT_PATH), MOUNT_PATH)
        .withEnv(getApplicationEnvironmentVariables())
        .withEnv(getApplicationExtraEnvironmentVariables())
        .waitingFor(getApplicationWaitCondition())
        .withKraft();
  }

  @BeforeAll
  public void setup() throws IOException, InterruptedException {
    application.start();
    application.execInContainer(
        "/bin/sh",
        "-c",
        "/usr/bin/kafka-topics --bootstrap-server=localhost:9092 --create --topic kafka_topic --partitions 3 --replication-factor 1");
    mockCollectorClient = getMockCollectorClient();
  }

  // don't use the default clients
  @BeforeEach
  @Override
  protected void setupClients() {}

  @Override
  protected String getApplicationImageName() {
    return "aws-appsignals-tests-kafka";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".* Kafka Server started .*";
  }

  @Override
  protected Set<String> getExpectedMetrics() {
    return JMXMetricsConstants.KAFKA_METRICS_SET;
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    return Map.of(
        "JAVA_TOOL_OPTIONS", // kafka broker container will not complete startup if agent is set
        "",
        "KAFKA_OPTS", // replace java tool options with kafka opts
        "-javaagent:" + MOUNT_PATH,
        "KAFKA_AUTO_CREATE_TOPICS_ENABLE",
        "false",
        "OTEL_JMX_TARGET_SYSTEM",
        "kafka");
  }
}
