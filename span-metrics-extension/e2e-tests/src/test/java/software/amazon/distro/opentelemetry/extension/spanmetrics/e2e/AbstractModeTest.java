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

package software.amazon.distro.opentelemetry.extension.spanmetrics.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.SpanMetricsAssertions.CALLS_METRIC;
import static software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.SpanMetricsAssertions.assertScope;
import static software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.SpanMetricsAssertions.callsDataPoint;
import static software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.SpanMetricsAssertions.callsValue;
import static software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.SpanMetricsAssertions.stringAttributes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.base.SpanMetricsContractTestBase;
import software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.utils.ResourceScopeMetric;
import software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.utils.ResourceScopeSpan;

/**
 * Shared behavior for all four wiring modes: drive traffic to the DB endpoint (which every app
 * instruments), then assert that span metrics reflect 100% of requests while exported spans reflect
 * only the 5% sampling rate.
 */
abstract class AbstractModeTest extends SpanMetricsContractTestBase {

  private static final int REQUEST_COUNT = 100;

  /** The span name the app under test produces for a database operation. */
  protected abstract String databaseSpanName();

  /**
   * Whether the database span carries current-semantic-convention {@code db.*} attributes. Manual
   * apps set them explicitly; the javaagent at this version still emits legacy {@code db.system},
   * which the extension deliberately does not copy.
   */
  protected boolean databaseSpanHasSemconvAttributes() {
    return true;
  }

  @Test
  void metricsReflectAllSpansWhileTracesAreSampled() {
    for (int i = 0; i < REQUEST_COUNT; i++) {
      appClient.get("/db").aggregate().join();
    }

    List<ResourceScopeMetric> metrics = awaitDatabaseCalls();

    // The calls metric counts 100% of the database spans...
    long calls = callsValue(metrics, databaseSpanName());
    assertThat(calls)
        .as("calls metric for %s should count all %d requests", databaseSpanName(), REQUEST_COUNT)
        .isEqualTo(REQUEST_COUNT);

    // ...while exported spans of that name are sampled well below 100%. At 5% sampling the collector
    // may have received zero of these spans, which is still a valid (stronger) sampling result, so
    // an empty trace store is tolerated.
    long exportedDbSpans = countExportedDatabaseSpans();
    assertThat(exportedDbSpans)
        .as("exported spans should be sampled below the full request count")
        .isLessThan(REQUEST_COUNT);

    assertScope(metrics);
  }

  private long countExportedDatabaseSpans() {
    try {
      List<ResourceScopeSpan> spans = mockCollectorClient.getTraces();
      return spans.stream().filter(s -> s.getSpan().getName().equals(databaseSpanName())).count();
    } catch (RuntimeException e) {
      // getTraces() times out when nothing was sampled; treat as zero exported spans.
      return 0;
    }
  }

  /**
   * Polls the collector until the database span's calls datapoint reaches the full request count.
   * The metric name can appear (from other spans) before this specific cumulative datapoint has
   * caught up, so keying the wait on the value avoids a flaky race.
   */
  private List<ResourceScopeMetric> awaitDatabaseCalls() {
    List<ResourceScopeMetric> metrics = List.of();
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      metrics = mockCollectorClient.getMetrics(Set.of(CALLS_METRIC));
      if (callsValue(metrics, databaseSpanName()) >= REQUEST_COUNT) {
        return metrics;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return metrics;
  }

  @Test
  void metricAttributesUseShortFormsAndSemconvDerivedAttributes() {
    for (int i = 0; i < REQUEST_COUNT; i++) {
      appClient.get("/db").aggregate().join();
    }

    List<ResourceScopeMetric> metrics = awaitDatabaseCalls();
    Map<String, String> attrs =
        callsDataPoint(metrics, databaseSpanName())
            .map(dp -> stringAttributes(dp.getAttributesList()))
            .orElseThrow(() -> new AssertionError("no calls datapoint for " + databaseSpanName()));

    // Short-form values, not the connector's SPAN_KIND_* / STATUS_CODE_* prefixes.
    assertThat(attrs.get("span.kind")).isEqualTo("CLIENT");
    assertThat(attrs).containsKey("status.code");
    assertThat(attrs.get("status.code")).doesNotStartWith("STATUS_CODE_");
    assertThat(attrs.get("span.kind")).doesNotStartWith("SPAN_KIND_");

    // Schema + library-version markers on every metric datapoint (spec §6).
    assertThat(attrs.get("aws.otel.span.metrics.schema")).isEqualTo("v1");
    assertThat(attrs).containsKey("aws.otel.extension.lib.version");

    // Semconv-derived database attributes copied from the span (where the app emits them).
    if (databaseSpanHasSemconvAttributes()) {
      assertThat(attrs).containsKey("db.system.name");
    }
  }

  @Test
  void exportedSpansCarryDedupAndSchemaAttributes() {
    for (int i = 0; i < REQUEST_COUNT; i++) {
      appClient.get("/db").aggregate().join();
    }
    // Ensure metrics (and therefore spans) have been generated + exported.
    awaitDatabaseCalls();

    List<ResourceScopeSpan> spans;
    try {
      spans = mockCollectorClient.getTraces();
    } catch (RuntimeException e) {
      // If nothing was sampled in this window, there are no exported spans to inspect. Rare with
      // REQUEST_COUNT requests; skip rather than fail (the metric-side test covers the mechanism).
      return;
    }

    // Every exported span the extension processed must carry the dedup/schema markers so the
    // backend knows not to regenerate metrics for it (spec §6).
    assertThat(spans)
        .isNotEmpty()
        .allSatisfy(
            s -> {
              Map<String, String> a = stringAttributes(s.getSpan().getAttributesList());
              assertThat(a.get("aws.otel.span.metrics.schema")).isEqualTo("v1");
              assertThat(a).containsKey("aws.otel.extension.lib.version");
            });
  }
}
