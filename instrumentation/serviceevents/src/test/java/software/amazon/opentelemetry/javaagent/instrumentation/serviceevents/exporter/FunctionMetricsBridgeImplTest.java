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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Collection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the per-call metric contract: {@code service.function.duration} (Exponential Histogram)
 * is recorded for sampled calls only with the documented attribute set.
 */
class FunctionMetricsBridgeImplTest {

  private InMemoryMetricReader metricReader;
  private SdkMeterProvider meterProvider;
  private FunctionMetricsBridgeImpl bridge;

  @BeforeEach
  void setUp() {
    metricReader = InMemoryMetricReader.create();
    meterProvider =
        SdkMeterProvider.builder()
            .setResource(Resource.empty())
            .registerMetricReader(metricReader)
            // Match production: register an exponential histogram view for the duration metric.
            .registerView(
                InstrumentSelector.builder().setName("service.function.duration").build(),
                View.builder()
                    .setAggregation(Aggregation.base2ExponentialBucketHistogram())
                    .build())
            .build();

    Meter meter = meterProvider.get("serviceevents");

    // Per-data-point base attribute set is intentionally minimal — process-constants
    // ride on the MeterProvider's Resource in production.
    Attributes baseAttrs = Attributes.builder().put("Telemetry.Source", "ServiceEvents").build();

    bridge =
        new FunctionMetricsBridgeImpl(
            meter
                .histogramBuilder("service.function.duration")
                .setUnit("Microseconds")
                .setDescription("Function call duration")
                .build(),
            baseAttrs);
  }

  @AfterEach
  void tearDown() {
    meterProvider.shutdown();
  }

  @Test
  void successCallEmitsHistogramWithStatusSuccess() {
    bridge.recordFunctionCall(
        "com.example.Service.process",
        "GET /api/users",
        /* caller= */ "com.example.Controller.handle",
        /* durationNs= */ 25_000L,
        /* exceptionType= */ null);

    Collection<MetricData> metrics = metricReader.collectAllMetrics();

    MetricData duration = metricByName(metrics, "service.function.duration");
    ExponentialHistogramData hist = duration.getExponentialHistogramData();
    assertEquals(1, hist.getPoints().size());
    assertEquals(1L, hist.getPoints().iterator().next().getCount());
    assertEquals(25.0, hist.getPoints().iterator().next().getSum(), 0.0001);

    Attributes attrs = hist.getPoints().iterator().next().getAttributes();
    assertEquals("success", attrs.get(AttributeKey.stringKey("status")));
    assertEquals("com.example.Service.process", attrs.get(AttributeKey.stringKey("function.name")));
    assertEquals(
        "com.example.Controller.handle",
        attrs.get(AttributeKey.stringKey("aws.service_events.caller")));
    assertEquals("ServiceEvents", attrs.get(AttributeKey.stringKey("Telemetry.Source")));
    // Process-constants must NOT live on per-data-point attributes — they belong on the Resource.
    assertFalse(
        attrs.asMap().containsKey(AttributeKey.stringKey("service.name")),
        "service.name belongs on the Resource, not per-data-point attrs");
    assertFalse(
        attrs.asMap().containsKey(AttributeKey.stringKey("environment")),
        "environment belongs on the Resource, not per-data-point attrs");
    assertFalse(
        attrs.asMap().containsKey(AttributeKey.stringKey("aws.service_events.version")),
        "aws.service_events.version belongs on the Resource");
    assertFalse(
        attrs.asMap().containsKey(AttributeKey.stringKey("aws.service_events.deployment.id")),
        "aws.service_events.deployment.id belongs on the Resource");
    assertFalse(
        attrs.asMap().containsKey(AttributeKey.stringKey("vcs.ref.head.revision")),
        "vcs.ref.head.revision belongs on the Resource");
    assertFalse(
        attrs.asMap().containsKey(AttributeKey.stringKey("vcs.repository.url.full")),
        "vcs.repository.url.full belongs on the Resource");
  }

  @Test
  void errorCallSetsStatusErrorButOmitsExceptionType() {
    bridge.recordFunctionCall(
        "com.example.Service.process",
        "GET /api/users",
        /* caller= */ null,
        /* durationNs= */ 12_000L,
        /* exceptionType= */ "RuntimeException");

    Collection<MetricData> metrics = metricReader.collectAllMetrics();
    Attributes attrs =
        metricByName(metrics, "service.function.duration")
            .getExponentialHistogramData()
            .getPoints()
            .iterator()
            .next()
            .getAttributes();
    assertEquals("error", attrs.get(AttributeKey.stringKey("status")));
    // Exception class is intentionally NOT a histogram attribute — it would
    // unbound success-path cardinality. The failure_type breakdown lives on
    // EndpointSummary instead.
    assertFalse(
        attrs.asMap().containsKey(AttributeKey.stringKey("exception.type")),
        "exception.type must not appear on the histogram (cardinality control)");
    // No caller -> attribute should be absent (sparse, matches Python contract).
    assertFalse(
        attrs.asMap().containsKey(AttributeKey.stringKey("aws.service_events.caller")),
        "caller attribute should be absent when caller is null");
  }

  @Test
  void successAndErrorEmitSeparateDataPointsViaStatusAttribute() {
    // Same function, different outcomes -> two distinct data points (status='success' vs 'error').
    bridge.recordFunctionCall("com.example.Svc.run", "POST /x", null, 50L, null);
    bridge.recordFunctionCall("com.example.Svc.run", "POST /x", null, 50L, "TypeError");

    Collection<MetricData> metrics = metricReader.collectAllMetrics();
    MetricData duration = metricByName(metrics, "service.function.duration");
    assertEquals(2, duration.getExponentialHistogramData().getPoints().size());
  }

  @Test
  void differentExceptionClassesCollapseIntoSingleErrorDataPoint() {
    // Cardinality guard: same function + status="error" but two distinct exception classes
    // must NOT split into separate data points (exception.type isn't a histogram attribute).
    bridge.recordFunctionCall("com.example.Svc.run", "POST /x", null, 100L, "RuntimeException");
    bridge.recordFunctionCall(
        "com.example.Svc.run", "POST /x", null, 200L, "IllegalStateException");
    bridge.recordFunctionCall("com.example.Svc.run", "POST /x", null, 300L, "NullPointerException");

    Collection<MetricData> metrics = metricReader.collectAllMetrics();
    MetricData duration = metricByName(metrics, "service.function.duration");
    assertEquals(
        1,
        duration.getExponentialHistogramData().getPoints().size(),
        "Different exception classes must collapse into a single status='error' data point");
    var point = duration.getExponentialHistogramData().getPoints().iterator().next();
    assertEquals(3L, point.getCount());
    // 100 + 200 + 300 ns = 0.6 microseconds
    assertEquals(0.6, point.getSum(), 0.0001);
    assertEquals("error", point.getAttributes().get(AttributeKey.stringKey("status")));
  }

  @Test
  void multipleSampledCallsAccumulateInSingleDataPoint() {
    // Same function + status -> all sampled durations roll into one histogram data point.
    bridge.recordFunctionCall("com.example.Svc.run", "POST /x", null, 100L, null);
    bridge.recordFunctionCall("com.example.Svc.run", "POST /x", null, 200L, null);
    bridge.recordFunctionCall("com.example.Svc.run", "POST /x", null, 300L, null);

    Collection<MetricData> metrics = metricReader.collectAllMetrics();
    MetricData duration = metricByName(metrics, "service.function.duration");
    assertEquals(1, duration.getExponentialHistogramData().getPoints().size());
    var point = duration.getExponentialHistogramData().getPoints().iterator().next();
    assertEquals(3L, point.getCount());
    // 100ns + 200ns + 300ns = 600ns = 0.6 microseconds
    assertEquals(0.6, point.getSum(), 0.0001);
  }

  @Test
  void onlyDurationMetricIsEmittedNoCounterArtifacts() {
    bridge.recordFunctionCall("com.example.Svc.run", "POST /x", null, 100L, null);

    Collection<MetricData> metrics = metricReader.collectAllMetrics();
    assertEquals(1, metrics.size(), "Bridge must emit only service.function.duration");
    assertTrue(
        metrics.stream().anyMatch(m -> "service.function.duration".equals(m.getName())),
        "Expected service.function.duration to be emitted");
  }

  private static MetricData metricByName(Collection<MetricData> metrics, String name) {
    return metrics.stream()
        .filter(m -> name.equals(m.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("metric '" + name + "' not emitted: " + metrics));
  }
}
