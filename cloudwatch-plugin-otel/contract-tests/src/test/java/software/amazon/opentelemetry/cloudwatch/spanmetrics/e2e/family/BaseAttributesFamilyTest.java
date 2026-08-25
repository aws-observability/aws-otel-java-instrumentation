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

package software.amazon.opentelemetry.cloudwatch.spanmetrics.e2e.family;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.cloudwatch.spanmetrics.e2e.utils.ResourceScopeMetric;

/** Base attributes present on every datapoint, plus the error-status path. */
@Testcontainers(disabledWithoutDocker = true)
class BaseAttributesFamilyTest extends FamilyTestBase {

  @Test
  void baseAttributesUseShortForms() {
    Map<String, String> attrs = metricAttributesFor("/ping", "GET /ping");
    // service.name lives on the metric RESOURCE, not the datapoint (asserted separately below).
    assertThat(attrs).doesNotContainKey("service.name");
    assertThat(attrs).containsKey("span.name");
    assertThat(attrs.get("span.kind")).isEqualTo("SERVER").doesNotStartWith("SPAN_KIND_");
    assertThat(attrs.get("status.code")).isEqualTo("UNSET").doesNotStartWith("STATUS_CODE_");
    // Schema + lib-version markers on every metric datapoint.
    assertThat(attrs.get("aws.otel.span.metrics.schema")).isEqualTo("v1");
    assertThat(attrs).containsKey("aws.otel.extension.lib.version");
  }

  @Test
  void serviceNameOnResourceAndCallsUnitIsCallAnnotation() {
    ResourceScopeMetric calls = callsMetricFor("/ping", "GET /ping");
    // service.name is carried by the metric resource with the app's CONFIGURED value. Asserting the
    // value (not just key presence) matters: the SDK unconditionally defaults service.name to
    // unknown_service:java, so a key-presence check could never fail.
    String resourceServiceName =
        calls.getResource().getResource().getAttributesList().stream()
            .filter(kv -> kv.getKey().equals("service.name"))
            .findFirst()
            .map(kv -> kv.getValue().getStringValue())
            .orElse(null);
    assertThat(resourceServiceName).isEqualTo(getApplicationOtelServiceName());
    // calls unit is the {call} UCUM annotation.
    assertThat(calls.getMetric().getUnit()).isEqualTo("{call}");
  }

  @Test
  void errorPathSetsErrorStatus() {
    Map<String, String> attrs = metricAttributesFor("/error", "GET /error");
    assertThat(attrs.get("status.code")).isEqualTo("ERROR");
  }
}
