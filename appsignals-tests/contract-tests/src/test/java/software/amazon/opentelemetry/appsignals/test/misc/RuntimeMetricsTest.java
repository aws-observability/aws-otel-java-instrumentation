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

import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.base.ContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.AppSignalsConstants;

public class RuntimeMetricsTest {
  private abstract static class RuntimeMetricsContractTestBase extends ContractTestBase {
    @Override
    protected String getApplicationImageName() {
      return "aws-appsignals-tests-http-server-spring-mvc";
    }

    @Override
    protected String isRuntimeEnabled() {
      return "true";
    }

    protected String getApplicationWaitPattern() {
      return ".*Started Application.*";
    }

    protected void doTestRuntimeMetrics() {
      var response = appClient.get("/success").aggregate().join();

      assertThat(response.status().isSuccess()).isTrue();
      assertRuntimeMetrics();
    }

    protected void assertRuntimeMetrics() {
      var metrics =
          mockCollectorClient.getRuntimeMetrics(
              Set.of(
                  AppSignalsConstants.JVM_GC_METRIC,
                  AppSignalsConstants.JVM_GC_COUNT,
                  AppSignalsConstants.JVM_HEAP_USED,
                  AppSignalsConstants.JVM_NON_HEAP_USED,
                  AppSignalsConstants.JVM_AFTER_GC,
                  AppSignalsConstants.JVM_POOL_USED,
                  AppSignalsConstants.JVM_THREAD_COUNT,
                  AppSignalsConstants.JVM_CLASS_LOADED,
                  AppSignalsConstants.JVM_CPU_TIME,
                  AppSignalsConstants.JVM_CPU_UTILIZATION,
                  AppSignalsConstants.LATENCY_METRIC,
                  AppSignalsConstants.ERROR_METRIC,
                  AppSignalsConstants.FAULT_METRIC));
      metrics.forEach(
          metric -> {
            var dataPoints = metric.getMetric().getGauge().getDataPointsList();
            assertNonNegativeValue(dataPoints);
          });
    }

    private void assertNonNegativeValue(List<NumberDataPoint> dps) {
      dps.forEach(
          datapoint -> {
            Assert.assertTrue(datapoint.getAsInt() >= 0);
          });
    }
  }

  @Testcontainers(disabledWithoutDocker = true)
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @Nested
  class ValidateRuntimeMetricsTest extends RuntimeMetricsContractTestBase {
    @Test
    void testRuntimeMetrics() {
      doTestRuntimeMetrics();
    }
  }
}
