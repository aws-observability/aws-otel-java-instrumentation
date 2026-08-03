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

import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.utils.ResourceScopeMetric;

/** Shared helpers for asserting on span-metrics datapoints received by the mock collector. */
final class SpanMetricsAssertions {

  static final String CALLS_METRIC = "traces.span.metrics.calls";
  static final String DURATION_METRIC = "traces.span.metrics.duration";
  static final String SCOPE_NAME = "io.opentelemetry.contrib.spanmetrics";
  static final String SCHEMA_VERSION = "v1";

  /**
   * Returns the highest-count calls datapoint for the given span name across all received export
   * batches. With cumulative temporality the collector keeps every snapshot, so the latest (largest)
   * datapoint holds the running total.
   */
  static Optional<NumberDataPoint> callsDataPoint(
      List<ResourceScopeMetric> metrics, String spanName) {
    return metrics.stream()
        .filter(m -> m.getMetric().getName().equals(CALLS_METRIC))
        .flatMap(m -> m.getMetric().getSum().getDataPointsList().stream())
        .filter(dp -> attributeEquals(dp.getAttributesList(), "span.name", spanName))
        .max(java.util.Comparator.comparingLong(SpanMetricsAssertions::pointValue));
  }

  static long callsValue(List<ResourceScopeMetric> metrics, String spanName) {
    return callsDataPoint(metrics, spanName).map(SpanMetricsAssertions::pointValue).orElse(0L);
  }

  // A NumberDataPoint carries either an int or a double value depending on encoding; read whichever
  // is set so the count is correct regardless.
  private static long pointValue(NumberDataPoint dp) {
    return dp.hasAsDouble() ? (long) dp.getAsDouble() : dp.getAsInt();
  }

  static Map<String, String> stringAttributes(List<KeyValue> attributes) {
    return attributes.stream()
        .filter(kv -> kv.getValue().hasStringValue())
        .collect(Collectors.toMap(KeyValue::getKey, kv -> kv.getValue().getStringValue()));
  }

  static boolean attributeEquals(List<KeyValue> attributes, String key, String value) {
    return attributes.stream()
        .anyMatch(kv -> kv.getKey().equals(key) && kv.getValue().getStringValue().equals(value));
  }

  /** Asserts the calls metric is emitted under our instrumentation scope. */
  static void assertScope(List<ResourceScopeMetric> metrics) {
    assertThat(metrics)
        .filteredOn(m -> m.getMetric().getName().equals(CALLS_METRIC))
        .anySatisfy(m -> assertThat(m.getScope().getScope().getName()).isEqualTo(SCOPE_NAME));
  }

  private SpanMetricsAssertions() {}
}
