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

package software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e.family;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Database-family attributes. The javaagent 2.29.0 JDBC instrumentation emits legacy DB semconv
 * ({@code db.system}), so the extension passes those legacy keys through unchanged (spec §4) — it
 * does not up-convert to {@code db.system.name}.
 */
@Testcontainers(disabledWithoutDocker = true)
class DbFamilyTest extends FamilyTestBase {

  @Test
  void legacyDbAttributesPassThrough() {
    Map<String, String> attrs = metricAttributesFor("/db", "SELECT spanmetrics.test_items");
    assertThat(attrs.get("span.kind")).isEqualTo("CLIENT");
    assertThat(attrs.get("db.system")).isEqualTo("h2");
    // Not up-converted to the current key.
    assertThat(attrs).doesNotContainKey("db.system.name");
    // High-cardinality statement is never copied.
    assertThat(attrs).doesNotContainKey("db.statement");
  }
}
