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

/** Messaging-family attributes from agent-instrumented Kafka produce/consume against a broker. */
@Testcontainers(disabledWithoutDocker = true)
class MessagingFamilyTest extends FamilyTestBase {

  @Test
  void messagingDerivedAttributesCopied() {
    // The /kafka endpoint produces AND consumes, so two datapoints carry messaging.system=kafka;
    // pin the PRODUCER one explicitly.
    Map<String, String> attrs =
        metricAttributesMatching("/kafka", "messaging.system", "kafka", "span.kind", "PRODUCER");
    assertThat(attrs.get("messaging.destination.name")).isEqualTo("span-metrics-topic");
    // Note: the agent at this version emits older messaging semconv without
    // messaging.operation.name (the operation is encoded in the span name), so we assert only the
    // attributes actually produced. High-cardinality/payload attributes must not leak.
    assertThat(attrs).doesNotContainKey("messaging.message.body");
  }
}
