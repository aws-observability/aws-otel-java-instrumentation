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

package software.amazon.opentelemetry.appsignals.test.misc;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import java.util.List;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.base.ContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeMetric;

/**
 * Tests in this class are supposed to validate that the SDK was configured in the correct way: * It
 * uses the X-Ray ID format. * Metrics are deltaPreferred. * Type of the metrics are
 * exponentialHistogram
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConfigurationTest extends ContractTestBase {

  @Override
  protected String getApplicationImageName() {
    return "aws-appsignals-tests-http-server-spring-mvc";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*Started Application.*";
  }

  private void assertMetricConfiguration(List<ResourceScopeMetric> metrics, String metricName) {
    assertThat(metrics)
        .filteredOn(x -> x.getMetric().getName().equals(metricName))
        .singleElement()
        .satisfies(
            metric -> {
              assertThat(metric.getMetric().hasExponentialHistogram()).isTrue();
              assertThat(metric.getMetric().getExponentialHistogram().getAggregationTemporality())
                  .isEqualTo(AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA);
            });
  }

  //  @Test
  //  void testConfigurationMetrics() {
  //    var response = appClient.get("/success").aggregate().join();
  //    assertThat(response.status().isSuccess()).isTrue();
  //
  //    var metrics =
  //        mockCollectorClient.getMetrics(
  //            Set.of(
  //                AppSignalsConstants.LATENCY_METRIC,
  //                AppSignalsConstants.ERROR_METRIC,
  //                AppSignalsConstants.FAULT_METRIC));
  //
  //    assertMetricConfiguration(metrics, AppSignalsConstants.LATENCY_METRIC);
  //    assertMetricConfiguration(metrics, AppSignalsConstants.ERROR_METRIC);
  //    assertMetricConfiguration(metrics, AppSignalsConstants.FAULT_METRIC);
  //  }

  //  @Test
  //  void xrayIdFormat() {
  //    // We are testing here that the X-Ray id format is always used by inspecting the traceid
  // that
  //    // was in the span received by the collector, which should be consistent across multiple
  // spans.
  //    // We are testing the following properties:
  //    // 1. Traceid is random
  //    // 2. First 32 bits of traceid is a timestamp
  //    // It is important to remember that the X-Ray traceId format had to be adapted to fit into
  // the
  //    // definition of the OpenTelemetry traceid:
  //    // https://opentelemetry.io/docs/specs/otel/trace/api/#retrieving-the-traceid-and-spanid
  //    // Specifically for an X-Ray traceid to be a valid Otel traceId, the version digit had to be
  //    // dropped. Reference:
  //    //
  // https://github.com/open-telemetry/opentelemetry-java-contrib/blob/main/aws-xray/src/main/java/io/opentelemetry/contrib/awsxray/AwsXrayIdGenerator.java#L45
  //    var seen = new HashSet<String>();
  //
  //    for (int i = 0; i < 100; i++) {
  //      var response = appClient.get("/success").aggregate().join();
  //      assertThat(response.status().isSuccess()).isTrue();
  //
  //      var traces = mockCollectorClient.getTraces();
  //
  //      Assertions.assertThat(traces)
  //          .filteredOn(x -> x.getSpan().getName().equals("GET /success"))
  //          .singleElement()
  //          .satisfies(
  //              trace -> {
  //                // Get the binary representation of the traceid
  //                var traceId = ByteBuffer.wrap(trace.getSpan().getTraceId().toByteArray());
  //                // Get the first 8 bytes containing the timestamp.
  //                var high = traceId.getLong();
  //                // Covert to the hex representation
  //                var traceIdHex = TraceId.fromBytes(traceId.array());
  //
  //                assertThat(traceIdHex).isNotIn(seen);
  //                seen.add(traceIdHex);
  //
  //                var traceTimestamp = high >>> 32;
  //                // Since we just made the request, the time in epoch registered in the traceid
  //                // should be
  //                // approximate equal to the current time in the test, since both run on the same
  //                // host.
  //                var currentTimeSecs =
  // TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
  //
  //                assertThat(traceTimestamp)
  //                    // Give 2 minutes time range of tolerance for the trace timestamp.
  //                    .isGreaterThanOrEqualTo(currentTimeSecs - 60)
  //                    .isLessThanOrEqualTo(currentTimeSecs + 60);
  //              });
  //
  //      mockCollectorClient.clearSignals();
  //    }
  //  }
}
