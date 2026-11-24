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
import static software.amazon.opentelemetry.cloudwatch.spanmetrics.e2e.SpanMetricsAssertions.intAttribute;

import io.opentelemetry.proto.common.v1.KeyValue;
import java.util.List;
import java.util.Map;
import software.amazon.opentelemetry.cloudwatch.spanmetrics.e2e.SpanMetricsAssertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/** HTTP-family derived attributes copied from an agent-instrumented HTTP server span. */
@Testcontainers(disabledWithoutDocker = true)
class HttpFamilyTest extends FamilyTestBase {

  @Test
  void httpDerivedAttributesCopied() {
    List<KeyValue> raw = rawAttributesFor("/ping", "GET /ping");
    Map<String, String> attrs = SpanMetricsAssertions.stringAttributes(raw);
    assertThat(attrs.get("http.request.method")).isEqualTo("GET");
    assertThat(attrs.get("http.route")).isEqualTo("/ping");
    // status_code is an int per semconv (not a string dimension).
    assertThat(intAttribute(raw, "http.response.status_code")).isEqualTo(200L);
  }
}
