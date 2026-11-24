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

import io.opentelemetry.proto.metrics.v1.Metric;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.base.ContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.AppSignalsConstants;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeMetric;

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
                  AppSignalsConstants.JVM_GC_DURATION,
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

      testResourceAttributes(metrics);
      testApplicationSignalsConsoleMetrics(metrics);
      for (String metricName : List.of(AppSignalsConstants.JVM_POOL_USED)) {
        testGaugeMetrics(metrics, metricName, "name");
      }
      for (String metricName :
          List.of(
              AppSignalsConstants.JVM_HEAP_USED,
              AppSignalsConstants.JVM_NON_HEAP_USED,
              AppSignalsConstants.JVM_AFTER_GC,
              AppSignalsConstants.JVM_THREAD_COUNT,
              AppSignalsConstants.JVM_CLASS_LOADED,
              AppSignalsConstants.JVM_CPU_UTILIZATION)) {
        testGaugeMetrics(metrics, metricName, "");
      }
      for (String metricName :
          List.of(AppSignalsConstants.JVM_GC_DURATION, AppSignalsConstants.JVM_GC_COUNT)) {
        testCounterMetrics(metrics, metricName, "name");
      }
      for (String metricName : List.of(AppSignalsConstants.JVM_CPU_TIME)) {
        testCounterMetrics(metrics, metricName, "");
      }
    }

    private void testGaugeMetrics(
        List<ResourceScopeMetric> resourceScopeMetrics, String metricName, String attributeKey) {
      for (ResourceScopeMetric rsm : resourceScopeMetrics) {
        Metric metric = rsm.getMetric();
        if (metricName.equals(metric.getName())) {
          assertThat(metric.getGauge().getDataPointsList())
              .as(metricName + " is not empty")
              .isNotEmpty();
          assertThat(metric.getGauge().getDataPointsList())
              .as(metricName + " is valid")
              .allMatch(
                  dp -> {
                    boolean valid = true;
                    if (!attributeKey.isEmpty()) {
                      valid =
                          dp.getAttributesList().stream()
                              .anyMatch(attribute -> attribute.getKey().equals(attributeKey));
                    }
                    return valid && dp.getAsInt() >= 0;
                  });
        }
      }
    }

    private void testCounterMetrics(
        List<ResourceScopeMetric> resourceScopeMetrics, String metricName, String attributeKey) {
      for (ResourceScopeMetric rsm : resourceScopeMetrics) {
        Metric metric = rsm.getMetric();
        if (metricName.equals(metric.getName())) {
          assertThat(metric.getSum().getDataPointsList())
              .as(metricName + " is not empty")
              .isNotEmpty();
          assertThat(metric.getSum().getDataPointsList())
              .as(metricName + " is valid")
              .allMatch(
                  dp -> {
                    boolean valid = true;
                    if (!attributeKey.isEmpty()) {
                      valid =
                          dp.getAttributesList().stream()
                              .anyMatch(attribute -> attribute.getKey().equals(attributeKey));
                    }
                    return valid && dp.getAsInt() >= 0;
                  });
        }
      }
    }

    // The CloudWatch agent turns these metrics into the Application Signals runtime metrics shown
    // in
    // the console (JVMGCDuration, JVMMemoryOldGenUsed, ...), keeping their units and matching on
    // their "name" attribute. Other metrics may be added, but these must not change.
    private void testApplicationSignalsConsoleMetrics(
        List<ResourceScopeMetric> resourceScopeMetrics) {
      Map<String, String> expectedUnits =
          Map.of(
              AppSignalsConstants.JVM_GC_DURATION, "ms",
              AppSignalsConstants.JVM_GC_COUNT, "1",
              AppSignalsConstants.JVM_HEAP_USED, "By",
              AppSignalsConstants.JVM_NON_HEAP_USED, "By",
              AppSignalsConstants.JVM_AFTER_GC, "By",
              AppSignalsConstants.JVM_POOL_USED, "By",
              AppSignalsConstants.JVM_THREAD_COUNT, "1",
              AppSignalsConstants.JVM_CLASS_LOADED, "1",
              AppSignalsConstants.JVM_CPU_TIME, "ns",
              AppSignalsConstants.JVM_CPU_UTILIZATION, "1");
      for (ResourceScopeMetric rsm : resourceScopeMetrics) {
        Metric metric = rsm.getMetric();
        if (expectedUnits.containsKey(metric.getName())) {
          assertThat(metric.getUnit())
              .as(metric.getName() + " unit")
              .isEqualTo(expectedUnits.get(metric.getName()));
        }
      }

      // JVMMemoryOldGenUsed, JVMMemoryEdenSpaceUsed, and JVMMemorySurvivorSpaceUsed
      assertThat(nameAttributeValues(resourceScopeMetrics, AppSignalsConstants.JVM_POOL_USED))
          .anyMatch(name -> name.matches(".*Old\\sGen$"))
          .anyMatch(name -> name.matches(".*Eden\\sSpace$"))
          .anyMatch(name -> name.matches(".*Survivor\\sSpace$"));
      // JVMGCOldGen* and JVMGCYoungGen*, available only in G1
      for (String metricName :
          List.of(AppSignalsConstants.JVM_GC_DURATION, AppSignalsConstants.JVM_GC_COUNT)) {
        Set<String> gcNames = nameAttributeValues(resourceScopeMetrics, metricName);
        if (gcNames.stream().anyMatch(name -> name.startsWith("G1 "))) {
          assertThat(gcNames)
              .as(metricName + " G1 collectors")
              .contains("G1 Young Generation", "G1 Old Generation");
        }
      }
    }

    private Set<String> nameAttributeValues(
        List<ResourceScopeMetric> resourceScopeMetrics, String metricName) {
      return resourceScopeMetrics.stream()
          .map(ResourceScopeMetric::getMetric)
          .filter(metric -> metric.getName().equals(metricName))
          .flatMap(
              metric ->
                  metric.hasSum()
                      ? metric.getSum().getDataPointsList().stream()
                          .flatMap(dp -> dp.getAttributesList().stream())
                      : metric.getGauge().getDataPointsList().stream()
                          .flatMap(dp -> dp.getAttributesList().stream()))
          .filter(attribute -> attribute.getKey().equals("name"))
          .map(attribute -> attribute.getValue().getStringValue())
          .collect(Collectors.toSet());
    }

    private void testResourceAttributes(List<ResourceScopeMetric> resourceScopeMetrics) {
      for (ResourceScopeMetric rsm : resourceScopeMetrics) {
        assertThat(rsm.getResource().getResource().getAttributesList())
            .anyMatch(
                attr ->
                    attr.getKey().equals(AppSignalsConstants.AWS_LOCAL_SERVICE)
                        && attr.getValue()
                            .getStringValue()
                            .equals(getApplicationOtelServiceName()));
      }
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
