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

import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.base.ContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.AppSignalsConstants;

/**
 * Tests in this class are supposed to validate that the agent is able to get the resource
 * attributes through the environment variables OTEL_RESOURCE_ATTRIBUTES and OTEL_SERVICE_NAME
 *
 * <p>These tests are structured with nested classes since it is only possible to change the
 * resource attributes during the initialiation of the OpenTelemetry SDK.
 */
public class ResourceAttributesTest {
  private static String toResourceAttributesEnvVar(Map<String, String> keyValues) {
    return keyValues.entrySet().stream()
        .map(x -> String.format("%s=%s", x.getKey(), x.getValue()))
        .collect(Collectors.joining(","));
  }

  private abstract static class ResourceAttributesContractTestsBase extends ContractTestBase {
    @Override
    protected String getApplicationImageName() {
      return "aws-appsignals-tests-http-server-spring-mvc";
    }

    protected String getApplicationWaitPattern() {
      return ".*Started Application.*";
    }

    protected Map<String, String> getK8sAttributes() {
      return Map.of(
          "k8s.namespace.name",
          "namespace-name",
          "k8s.pod.name",
          "pod-name",
          "k8s.deployment.name",
          "deployment-name");
    }

    protected abstract Pattern getExpectedOtelServiceNamePattern();

    protected void doTestResourceAttributes() {
      var response = appClient.get("/success").aggregate().join();

      assertThat(response.status().isSuccess()).isTrue();
      assertResourceAttributes();
    }

    private void assertK8sAttributes(Resource resource) {
      var attributes =
          resource.getAttributesList().stream()
              .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
      getK8sAttributes()
          .forEach(
              (key, value) -> assertThat(attributes.get(key).getStringValue()).isEqualTo(value));
    }

    protected void assertServiceName(Resource resource) {
      var attributes = resource.getAttributesList();
      assertThat(attributes)
          .filteredOn(x -> x.getKey().equals("service.name"))
          .singleElement()
          .satisfies(
              x ->
                  assertThat(x.getValue().getStringValue())
                      .matches(getExpectedOtelServiceNamePattern()));
    }

    protected void assertResourceAttributes() {

      var resourceScopeSpans = mockCollectorClient.getTraces();
      var metrics =
          mockCollectorClient.getMetrics(
              Set.of(
                  AppSignalsConstants.LATENCY_METRIC,
                  AppSignalsConstants.ERROR_METRIC,
                  AppSignalsConstants.FAULT_METRIC));

      Assertions.assertThat(resourceScopeSpans)
          .filteredOn(x -> x.getSpan().getName().equals("GET /success"))
          .singleElement()
          .satisfies(
              x -> {
                assertK8sAttributes(x.getResource().getResource());
                assertServiceName(x.getResource().getResource());
              });
      Assertions.assertThat(metrics)
          .filteredOn(x -> x.getMetric().getName().equals(AppSignalsConstants.LATENCY_METRIC))
          .singleElement()
          .satisfies(
              x -> {
                assertK8sAttributes(x.getResource().getResource());
                assertServiceName(x.getResource().getResource());
              });
      Assertions.assertThat(metrics)
          .filteredOn(x -> x.getMetric().getName().equals(AppSignalsConstants.ERROR_METRIC))
          .singleElement()
          .satisfies(
              x -> {
                assertK8sAttributes(x.getResource().getResource());
                assertServiceName(x.getResource().getResource());
              });
      Assertions.assertThat(metrics)
          .filteredOn(x -> x.getMetric().getName().equals(AppSignalsConstants.FAULT_METRIC))
          .singleElement()
          .satisfies(
              x -> {
                assertK8sAttributes(x.getResource().getResource());
                assertServiceName(x.getResource().getResource());
              });
    }
  }

  @Testcontainers(disabledWithoutDocker = true)
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @Nested
  class ServiceNameInResourceAttributes extends ResourceAttributesContractTestsBase {

    protected Pattern getExpectedOtelServiceNamePattern() {
      return Pattern.compile("^service-name$");
    }

    @Override
    protected String getApplicationOtelResourceAttributes() {
      var resourceAttributes = new HashMap<>(getK8sAttributes());
      resourceAttributes.put("service.name", "service-name");
      return toResourceAttributesEnvVar(resourceAttributes);
    }

    //    @Test
    //    void testServiceNameInResourceAttributes() {
    //      doTestResourceAttributes();
    //    }
  }

  @Testcontainers(disabledWithoutDocker = true)
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @Nested
  class ServiceNameInEvVar extends ResourceAttributesContractTestsBase {

    @Override
    protected Pattern getExpectedOtelServiceNamePattern() {
      return Pattern.compile("^service-name$");
    }

    @Override
    protected Map<String, String> getApplicationExtraEnvironmentVariables() {
      return Map.of("OTEL_SERVICE_NAME", "service-name");
    }

    @Override
    protected String getApplicationOtelResourceAttributes() {
      return toResourceAttributesEnvVar(getK8sAttributes());
    }

    //    @Test
    //    void tesServiceNameInEnvVar() {
    //      doTestResourceAttributes();
    //    }
  }

  @Testcontainers(disabledWithoutDocker = true)
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @Nested
  class UnknownServicename extends ResourceAttributesContractTestsBase {
    protected Pattern getExpectedOtelServiceNamePattern() {
      return Pattern.compile("^unknown_service:.*$");
    }

    @Override
    protected String getApplicationOtelResourceAttributes() {
      return toResourceAttributesEnvVar(getK8sAttributes());
    }

    //    @Test
    //    void testUnknownServiceName() {
    //      doTestResourceAttributes();
    //    }
  }
}
