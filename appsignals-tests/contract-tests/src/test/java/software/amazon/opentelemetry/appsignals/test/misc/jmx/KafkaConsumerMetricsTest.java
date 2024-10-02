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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.utility.DockerImageName;
import software.amazon.opentelemetry.appsignals.test.base.JMXMetricsContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.JMXMetricsConstants;

/**
 * Tests in this class validate that the SDK will emit JVM metrics when Application Signals runtime
 * metrics are enabled.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaConsumerMetricsTest extends JMXMetricsContractTestBase {
  private KafkaContainer kafka;

  @Test
  void testKafkaConsumerMetrics() {
    doTestMetrics();
  }

  @Override
  protected List<Startable> getApplicationDependsOnContainers() {
    kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
            .withImagePullPolicy(PullPolicy.alwaysPull())
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withNetworkAliases("kafkaBroker")
            .withNetwork(network)
            .waitingFor(Wait.forLogMessage(".* Kafka Server started .*", 1))
            .withKraft();
    return List.of(kafka);
  }

  @BeforeAll
  public void setup() throws IOException, InterruptedException {
    kafka.start();
    kafka.execInContainer(
        "/bin/sh",
        "-c",
        "/usr/bin/kafka-topics --bootstrap-server=localhost:9092 --create --topic kafka_topic --partitions 1 --replication-factor 1");
  }

  @AfterAll
  public void tearDown() {
    kafka.stop();
  }

  @Override
  protected String getApplicationImageName() {
    return "aws-appsignals-tests-kafka-kafka-consumers";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*Routes ready.*";
  }

  @Override
  protected Set<String> getExpectedMetrics() {
    return JMXMetricsConstants.KAFKA_CONSUMER_METRICS_SET;
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    return Map.of("OTEL_JMX_TARGET_SYSTEM", "kafka-consumer");
  }
}
