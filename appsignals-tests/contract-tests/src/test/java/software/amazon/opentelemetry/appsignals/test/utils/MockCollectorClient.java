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

package software.amazon.opentelemetry.appsignals.test.utils;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleDeserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.collect.ImmutableList;
import com.linecorp.armeria.client.WebClient;
import io.netty.buffer.ByteBufAllocator;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAmount;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import kotlin.Pair;
import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.shaded.com.google.common.util.concurrent.Uninterruptibles;

/**
 * The mock collector client is used to interact with the Mock collector image, used in the tests.
 */
public class MockCollectorClient {
  private final TemporalAmount TIMEOUT_DELAY = Duration.of(20, ChronoUnit.SECONDS);
  private static final Logger logger = LoggerFactory.getLogger(MockCollectorClient.class);

  private static final TypeReference<List<ExportTraceServiceRequest>>
      EXPORT_TRACE_SERVICE_REQUEST_LIST = new TypeReference<>() {};
  private static final TypeReference<List<ExportMetricsServiceRequest>>
      EXPORT_METRICS_SERVICE_REQUEST_LIST = new TypeReference<>() {};
  private static final int WAIT_INTERVAL_MS = 100;

  private static final JsonMapper OBJECT_MAPPER;

  static {
    var marshaller =
        MessageMarshaller.builder()
            .register(ExportTraceServiceRequest.getDefaultInstance())
            .register(ExportMetricsServiceRequest.getDefaultInstance())
            .build();

    var mapper = JsonMapper.builder();
    var module = new SimpleModule();
    var deserializers = new SimpleDeserializers();

    // Configure specific deserializers for the telemetry signals.
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
    deserializers.addDeserializer(
        ExportMetricsServiceRequest.class,
        new StdDeserializer<ExportMetricsServiceRequest>(ExportMetricsServiceRequest.class) {
          @Override
          public ExportMetricsServiceRequest deserialize(
              JsonParser parser, DeserializationContext ctxt)
              throws IOException, JsonProcessingException {
            var builder = ExportMetricsServiceRequest.newBuilder();
            marshaller.mergeValue(parser, builder);
            return builder.build();
          }
        });
    module.setDeserializers(deserializers);
    mapper.addModule(module);
    OBJECT_MAPPER = mapper.build();
  }

  private WebClient client;

  public MockCollectorClient(WebClient client) {
    this.client = client;
  }

  /** Clear all the signals in the backend collector */
  public void clearSignals() {
    this.client.get("/clear").collect().join();
  }

  /**
   * Get all traces that are currently stored in the collector
   *
   * @return List of `ResourceScopeSpan` which is essentially a flat list containing all the spans
   *     and their related scope and resources.
   */
  public List<ResourceScopeSpan> getTraces() {
    List<ExportTraceServiceRequest> exportedTraces =
        waitForContent("/get-traces", EXPORT_TRACE_SERVICE_REQUEST_LIST);

    return exportedTraces.stream()
        .flatMap(req -> req.getResourceSpansList().stream())
        .flatMap(rs -> rs.getScopeSpansList().stream().map(x -> new Pair<>(rs, x)))
        .flatMap(
            ss ->
                ss.getSecond().getSpansList().stream()
                    .map(x -> new ResourceScopeSpan(ss.getFirst(), ss.getSecond(), x)))
        .collect(toImmutableList());
  }

  public List<ResourceScopeMetric> getRuntimeMetrics(Set<String> presentMetrics) {
    return fetchMetrics(presentMetrics, false);
  }

  public List<ResourceScopeMetric> getMetrics(Set<String> presentMetrics) {
    return fetchMetrics(presentMetrics, true);
  }

  /**
   * Get all metrics that are currently stored in the mock collector.
   *
   * @return List of `ResourceScopeMetric` which is a flat list containing all metrics and their
   *     related scope and resources.
   */
  private List<ResourceScopeMetric> fetchMetrics(Set<String> presentMetrics, boolean exactMatch) {
    List<ExportMetricsServiceRequest> exportedMetrics =
        waitForContent(
            "/get-metrics",
            EXPORT_METRICS_SERVICE_REQUEST_LIST,
            (exported, current) -> {
              Set<String> receivedMetrics =
                  current.stream()
                      .flatMap(x -> x.getResourceMetricsList().stream())
                      .flatMap(x -> x.getScopeMetricsList().stream())
                      .flatMap(x -> x.getMetricsList().stream())
                      .map(x -> x.getName())
                      .collect(Collectors.toSet());
              if (!exported.isEmpty() && receivedMetrics.containsAll(presentMetrics)) {
                if (exactMatch) {
                  return current.size() == exported.size();
                } else {
                  return true;
                }
              }
              return false;
            });

    return exportedMetrics.stream()
        .flatMap(req -> req.getResourceMetricsList().stream())
        .flatMap(rm -> rm.getScopeMetricsList().stream().map(x -> new Pair<>(rm, x)))
        .flatMap(
            sm ->
                sm.getSecond().getMetricsList().stream()
                    .map(x -> new ResourceScopeMetric(sm.getFirst(), sm.getSecond(), x)))
        .collect(toImmutableList());
  }

  private <T> List<T> waitForContent(String url, TypeReference<List<T>> t) {
    // Verify that there is no more data to be received
    return this.waitForContent(
        url, t, (current, exported) -> (!exported.isEmpty() && current.size() == exported.size()));
  }

  private <T> List<T> waitForContent(
      String url, TypeReference<List<T>> t, BiFunction<List<T>, List<T>, Boolean> waitCondition) {
    var deadline = Instant.now().plus(TIMEOUT_DELAY);
    List<T> exported = ImmutableList.of();

    while (deadline.compareTo(Instant.now()) > 0) {
      try (var content =
          client.get(url).aggregateWithPooledObjects(ByteBufAllocator.DEFAULT).join().content()) {

        List<T> currentExported = OBJECT_MAPPER.readValue(content.toInputStream(), t);
        if (waitCondition.apply(exported, currentExported)) {
          return currentExported;
        }
        exported = currentExported;
      } catch (IOException e) {
        logger.error("Error while reading content", e);
      }
      Uninterruptibles.sleepUninterruptibly(WAIT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    throw new RuntimeException("Timeout waiting for content");
  }
}
