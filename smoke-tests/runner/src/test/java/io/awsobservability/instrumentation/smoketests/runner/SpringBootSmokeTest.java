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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT;
import static io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_INTERNAL;
import static io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleDeserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Uninterruptibles;
import com.linecorp.armeria.client.WebClient;
import io.netty.buffer.ByteBufAllocator;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers(disabledWithoutDocker = true)
class SpringBootSmokeTest {

  private static final Logger logger = LoggerFactory.getLogger(SpringBootSmokeTest.class);
  private static final Logger backendLogger = LoggerFactory.getLogger("backend");
  private static final Logger applicationLogger = LoggerFactory.getLogger("application");

  private static final String AGENT_PATH =
      System.getProperty("io.awsobservability.instrumentation.smoketests.runner.agentPath");

  private static final JsonMapper OBJECT_MAPPER;

  static {
    var marshaller =
        MessageMarshaller.builder()
            .register(ExportTraceServiceRequest.getDefaultInstance())
            .build();

    var mapper = JsonMapper.builder();
    var module = new SimpleModule();
    var deserializers = new SimpleDeserializers();
    deserializers.addDeserializer(
        ExportTraceServiceRequest.class,
        new StdDeserializer<ExportTraceServiceRequest>(ExportTraceServiceRequest.class) {
          @Override
          public ExportTraceServiceRequest deserialize(
              JsonParser parser, DeserializationContext ctxt)
              throws IOException, JsonProcessingException {
            var builder = ExportTraceServiceRequest.newBuilder();
            marshaller.mergeValue(parser, builder);
            return builder.build();
          }
        });
    module.setDeserializers(deserializers);
    mapper.addModule(module);
    OBJECT_MAPPER = mapper.build();
  }

  private static final int START_TIME_SECS =
      (int) TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());

  private static final Network network = Network.newNetwork();

  @Container
  private static final GenericContainer<?> backend =
      new GenericContainer<>("public.ecr.aws/u0d6r4y4/aws-otel-java-test-fakebackend:alpha")
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp("/health").forPort(8080))
          .withLogConsumer(new Slf4jLogConsumer(backendLogger))
          .withNetwork(network)
          .withNetworkAliases("backend");

  @Container
  private static final GenericContainer<?> application =
      new GenericContainer<>("public.ecr.aws/u0d6r4y4/aws-otel-java-smoketests-springboot:latest")
          .dependsOn(backend)
          .withExposedPorts(8080)
          .withNetwork(network)
          .withLogConsumer(new Slf4jLogConsumer(applicationLogger))
          .withCopyFileToContainer(
              MountableFile.forHostPath(AGENT_PATH), "/opentelemetry-javaagent-all.jar")
          .withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opentelemetry-javaagent-all.jar")
          .withEnv("OTEL_BSP_MAX_EXPORT_BATCH", "1")
          .withEnv("OTEL_BSP_SCHEDULE_DELAY", "10")
          .withEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://backend:8080");

  @Container
  private static final GenericContainer<?> applicationXraySampler =
      new GenericContainer<>("public.ecr.aws/u0d6r4y4/aws-otel-java-smoketests-springboot:latest")
          .dependsOn(backend)
          .withExposedPorts(8080)
          .withNetwork(network)
          .withLogConsumer(new Slf4jLogConsumer(applicationLogger))
          .withCopyFileToContainer(
              MountableFile.forHostPath(AGENT_PATH), "/opentelemetry-javaagent-all.jar")
          .withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opentelemetry-javaagent-all.jar")
          .withEnv("OTEL_BSP_MAX_EXPORT_BATCH", "1")
          .withEnv("OTEL_BSP_SCHEDULE_DELAY", "10")
          .withEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://backend:8080")
          .withEnv("OTEL_TRACES_SAMPLER", "xray");

  private static final TypeReference<List<ExportTraceServiceRequest>>
      EXPORT_TRACE_SERVICE_REQUEST_LIST = new TypeReference<>() {};

  private WebClient appClient;
  private WebClient backendClient;

  @BeforeEach
  void setUp() {
    appClient = WebClient.of("http://localhost:" + application.getMappedPort(8080));
    backendClient = WebClient.of("http://localhost:" + backend.getMappedPort(8080));
  }

  @AfterEach
  void tearDown() {
    backendClient.get("/clear-requests").aggregate().join();
  }

  @Test
  void hello() {
    var response = appClient.get("/hello").aggregate().join();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.headers())
        .extracting(e -> e.getKey().toString())
        .contains(
            "received-x-amzn-trace-id",
            "received-b3",
            "received-x-b3-traceid",
            "received-traceparent");

    var exported = getExported();
    assertThat(exported)
        .anySatisfy(
            span -> {
              assertThat(span.getKind()).isEqualTo(SPAN_KIND_SERVER);
              assertThat(span.getName()).isEqualTo("/hello");
            })
        .anySatisfy(
            span -> {
              assertThat(span.getKind()).isEqualTo(SPAN_KIND_SERVER);
              assertThat(span.getName()).isEqualTo("/backend");
            })
        .anySatisfy(
            span -> {
              assertThat(span.getKind()).isEqualTo(SPAN_KIND_CLIENT);
              assertThat(span.getName()).isEqualTo("HTTP GET");
            })
        .anySatisfy(
            span -> {
              assertThat(span.getKind()).isEqualTo(SPAN_KIND_INTERNAL);
              assertThat(span.getName()).isEqualTo("AppController.hello");
            })
        .anySatisfy(
            span -> {
              assertThat(span.getKind()).isEqualTo(SPAN_KIND_INTERNAL);
              assertThat(span.getName()).isEqualTo("AppController.backend");
            })
        .allSatisfy(
            span -> {
              var traceId = span.getTraceId();
              int epoch =
                  Ints.fromBytes(
                      traceId.byteAt(0), traceId.byteAt(1), traceId.byteAt(2), traceId.byteAt(3));
              assertThat(epoch).isGreaterThanOrEqualTo(START_TIME_SECS);
            });
  }

  @Test
  void defaultSamplingRate() {
    int numRequests = 100;
    for (int i = 0; i < numRequests; i++) {
      appClient.get("/hello").aggregate().join();
    }

    var exported = getExported();
    // 5 spans per request (1 CLIENT, 2 SERVER, 2 INTERNAL)
    assertThat(exported).hasSize(numRequests * 5);
  }

  @Test
  void remoteSamplingRate() {
    // We just want to make sure OTEL_TRACES_SAMPLER=xray is using the XRay sampler. It will fail to
    // fetch rules and fallback to the default, which will not sample all requests.
    WebClient client =
        WebClient.of("http://localhost:" + applicationXraySampler.getMappedPort(8080));
    int numRequests = 100;
    for (int i = 0; i < numRequests; i++) {
      client.get("/hello").aggregate().join();
    }

    var exported = getExported();
    // 5 spans per request (1 CLIENT, 2 SERVER, 2 INTERNAL) if sampled. The default sampler is
    // around 5%, we do a very rough check that there are no more than 10% sampled.
    assertThat(exported).isNotEmpty().hasSizeLessThanOrEqualTo(numRequests * 5 / 10);
  }

  private List<Span> getExported() {
    List<ExportTraceServiceRequest> exported = ImmutableList.of();
    for (int i = 0; i < 100; i++) {
      try (var content =
          backendClient
              .get("/get-requests")
              .aggregateWithPooledObjects(ByteBufAllocator.DEFAULT)
              .join()
              .content()) {
        List<ExportTraceServiceRequest> currentExported =
            OBJECT_MAPPER.readValue(content.toInputStream(), EXPORT_TRACE_SERVICE_REQUEST_LIST);
        if (!exported.isEmpty() && currentExported.size() == exported.size()) {
          break;
        }
        exported = currentExported;
      } catch (IOException e) {
        logger.warn("Error reading JSON response.", e);
      }
      Uninterruptibles.sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
    }
    if (exported == null) {
      throw new AssertionError("No traces after 20 attempts.");
    }
    return exported.stream()
        .flatMap(req -> req.getResourceSpansList().stream())
        .flatMap(rs -> rs.getInstrumentationLibrarySpansList().stream())
        .flatMap(ils -> ils.getSpansList().stream())
        .collect(toImmutableList());
  }
}
