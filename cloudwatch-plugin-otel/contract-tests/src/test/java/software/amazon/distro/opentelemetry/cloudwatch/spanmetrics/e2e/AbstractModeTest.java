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

package software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e.SpanMetricsAssertions.DURATION_METRIC;
import static software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e.SpanMetricsAssertions.assertScope;
import static software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e.SpanMetricsAssertions.attributeEquals;
import static software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e.SpanMetricsAssertions.callsDataPoint;
import static software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e.SpanMetricsAssertions.callsValue;
import static software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e.SpanMetricsAssertions.stringAttributes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e.base.SpanMetricsContractTestBase;
import software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e.utils.ResourceScopeMetric;
import software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e.utils.ResourceScopeSpan;

/**
 * Mode tests verify only what is mode-dependent: that the extension is wired into this integration
 * mode and generating metrics. Metric shape and per-family attribute derivation are mode-independent
 * and are covered once (javaagent) by the family tests, not re-verified per mode.
 *
 * <p>Each mode drives a database call and asserts: 100% of spans are counted while trace export
 * stays at the sampling rate, both metrics are emitted, and the schema/lib markers are present
 * (proof the extension is active in this mode).
 */
abstract class AbstractModeTest extends SpanMetricsContractTestBase {

  /** A span name this mode's app reliably produces, used to key the wiring assertions. */
  protected abstract String databaseSpanName();

  @Test
  void metricsReflectAllSpansWhileTracesAreSampled() {
    drive("/db");
    List<ResourceScopeMetric> metrics = awaitCalls(databaseSpanName());

    assertThat(callsValue(metrics, databaseSpanName()))
        .as("calls metric for %s should count all %d requests", databaseSpanName(), REQUEST_COUNT)
        .isEqualTo(REQUEST_COUNT);

    // Exported spans of that name are sampled well below 100%. At 5% sampling the collector may
    // have received zero, which is a valid (stronger) result, so an empty trace store is tolerated.
    assertThat(countExportedSpans(databaseSpanName()))
        .as("exported spans should be sampled below the full request count")
        .isLessThan(REQUEST_COUNT);

    assertScope(metrics);
  }

  @Test
  void bothMetricsAreEmittedWithSchemaMarker() {
    drive("/db");
    List<ResourceScopeMetric> metrics = awaitCalls(databaseSpanName());

    // calls carries the schema marker (proves the extension, not something else, produced it).
    Map<String, String> callsAttrs =
        callsDataPoint(metrics, databaseSpanName())
            .map(dp -> stringAttributes(dp.getAttributesList()))
            .orElseThrow(() -> new AssertionError("no calls datapoint for " + databaseSpanName()));
    assertThat(callsAttrs.get("aws.otel.span.metrics.schema")).isEqualTo("v1");

    // duration is emitted (in seconds) for the same span.
    assertThat(awaitDurationDatapoint(databaseSpanName())).isTrue();
  }

  private long countExportedSpans(String spanName) {
    try {
      List<ResourceScopeSpan> spans = mockCollectorClient.getTraces();
      return spans.stream().filter(s -> s.getSpan().getName().equals(spanName)).count();
    } catch (RuntimeException e) {
      return 0; // getTraces() times out when nothing was sampled; treat as zero exported spans.
    }
  }

  private boolean awaitDurationDatapoint(String spanName) {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      boolean found =
          mockCollectorClient.getMetrics(Set.of(DURATION_METRIC)).stream()
              .filter(m -> m.getMetric().getName().equals(DURATION_METRIC))
              .filter(m -> m.getMetric().getUnit().equals("s"))
              .flatMap(m -> m.getMetric().getHistogram().getDataPointsList().stream())
              .anyMatch(dp -> attributeEquals(dp.getAttributesList(), "span.name", spanName));
      if (found) {
        return true;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return false;
  }
}
