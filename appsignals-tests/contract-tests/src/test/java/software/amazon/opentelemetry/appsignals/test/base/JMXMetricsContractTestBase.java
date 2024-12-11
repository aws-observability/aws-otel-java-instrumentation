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

package software.amazon.opentelemetry.appsignals.test.base;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import software.amazon.opentelemetry.appsignals.test.utils.JMXMetricsConstants;

public abstract class JMXMetricsContractTestBase extends ContractTestBase {

  @Override
  protected Map<String, String> getApplicationEnvironmentVariables() {
    return Map.of(
        "JAVA_TOOL_OPTIONS", "-javaagent:" + MOUNT_PATH,
        "OTEL_METRIC_EXPORT_INTERVAL", "100", // 100 ms
        "OTEL_METRICS_EXPORTER", "none",
        "OTEL_LOGS_EXPORTER", "none",
        "OTEL_TRACES_EXPORTER", "none",
        "OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf",
        "OTEL_JMX_ENABLED", "true",
        "OTEL_AWS_JMX_EXPORTER_METRICS_ENDPOINT", COLLECTOR_HTTP_ENDPOINT + "/v1/metrics");
  }

  protected void doTestMetrics() {
    var response = appClient.get("/success").aggregate().join();

    assertThat(response.status().isSuccess()).isTrue();
    assertMetrics();
  }

  protected void assertMetrics() {
    var metrics = mockCollectorClient.getRuntimeMetrics(getExpectedMetrics());
    metrics.forEach(
        metric -> {
          var dataPoints = metric.getMetric().getGauge().getDataPointsList();
          assertGreaterThanOrEqual(dataPoints, getThreshold(metric.getMetric().getName()));
        });
  }

  protected abstract Set<String> getExpectedMetrics();

  protected long getThreshold(String metricName) {
    long threshold = 0;
    switch (metricName) {
        // If maximum memory size is undefined, then value is -1
        // https://docs.oracle.com/en/java/javase/17/docs/api/java.management/java/lang/management/MemoryUsage.html#getMax()
      case JMXMetricsConstants.JVM_HEAP_MAX:
      case JMXMetricsConstants.JVM_NON_HEAP_MAX:
      case JMXMetricsConstants.JVM_POOL_MAX:
        threshold = -1;
      default:
    }
    return threshold;
  }

  private void assertGreaterThanOrEqual(List<NumberDataPoint> dps, long threshold) {
    assertDataPoints(dps, (value) -> assertThat(value).isGreaterThanOrEqualTo(threshold));
  }

  private void assertDataPoints(List<NumberDataPoint> dps, Consumer<Long> consumer) {
    dps.forEach(datapoint -> consumer.accept(datapoint.getAsInt()));
  }
}
