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

package io.awsobservability.instrumentation.smoketests.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.client.WebClient;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers(disabledWithoutDocker = true)
class LogInjectionTest {

  private static final ToStringConsumer log4jString = new ToStringConsumer();
  private static final ToStringConsumer logbackString = new ToStringConsumer();

  private static final String AGENT_PATH =
      System.getProperty("io.awsobservability.instrumentation.smoketests.runner.agentPath");

  @Container
  private static final GenericContainer<?> log4jApp =
      new GenericContainer<>(
              "public.ecr.aws/aws-otel-test/aws-otel-java-spark:ae69a3fef3274282bc4e125d12e874d9330085d4")
          .withExposedPorts(4567)
          .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("log4j")))
          .withLogConsumer(log4jString)
          .withCopyFileToContainer(
              MountableFile.forHostPath(AGENT_PATH), "/opentelemetry-javaagent-all.jar")
          .withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opentelemetry-javaagent-all.jar")
          .withEnv("OTEL_JAVAAGENT_DEBUG", "true")
          .withEnv("AWS_REGION", "us-west-2")
          .withEnv("LISTEN_ADDRESS", "0.0.0.0:4567");

  @Container
  private static final GenericContainer<?> logbackApp =
      new GenericContainer<>(
              "public.ecr.aws/aws-otel-test/aws-otel-java-springboot:ae69a3fef3274282bc4e125d12e874d9330085d4")
          .withExposedPorts(8080)
          .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("logback")))
          .withLogConsumer(logbackString)
          .withCopyFileToContainer(
              MountableFile.forHostPath(AGENT_PATH), "/opentelemetry-javaagent-all.jar")
          .withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opentelemetry-javaagent-all.jar")
          .withEnv("OTEL_JAVAAGENT_DEBUG", "true")
          .withEnv("AWS_REGION", "us-west-2")
          .withEnv("LISTEN_ADDRESS", "0.0.0.0:8080");

  private WebClient appClient;

  @Test
  void log4j() {
    WebClient.of("http://localhost:" + log4jApp.getMappedPort(4567))
        .get("/outgoing-http-call")
        .aggregate()
        .join();

    // Log message has X-Ray trace ID.
    assertThat(log4jString.toUtf8String())
        .matches(
            Pattern.compile(
                ".*1-[0-9a-f]{8}-[0-9a-f]{24}@[0-9a-f]{16} - Executing outgoing-http-call.*",
                Pattern.DOTALL));
  }

  @Test
  void logback() {
    WebClient.of("http://localhost:" + logbackApp.getMappedPort(8080))
        .get("/outgoing-http-call")
        .aggregate()
        .join();

    // Log message has X-Ray trace ID.
    assertThat(logbackString.toUtf8String())
        .matches(
            Pattern.compile(
                ".*1-[0-9a-f]{8}-[0-9a-f]{24}@[0-9a-f]{16} : Executing outgoing-http-call.*",
                Pattern.DOTALL));
  }
}
