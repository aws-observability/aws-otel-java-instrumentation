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

package software.amazon.opentelemetry.javaagent.providers;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.jmx.JmxTelemetry;
import io.opentelemetry.instrumentation.jmx.JmxTelemetryBuilder;
import io.opentelemetry.instrumentation.jmx.internal.InternalMetricsDefinitions;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AwsJmxRulesInstallerTest {

  @Test
  void selectsOnlyAdotTargetSystems() {
    assertThat(
            AwsJmxRulesInstaller.adotTargetSystems(
                Arrays.asList("jvm", "kafka", "tomcat", "kafka-producer", "kafka")))
        .containsExactly("kafka", "kafka-producer");
    assertThat(AwsJmxRulesInstaller.adotTargetSystems(Arrays.asList("jvm", "tomcat"))).isEmpty();
    assertThat(AwsJmxRulesInstaller.adotTargetSystems(Collections.emptyList())).isEmpty();
  }

  @Test
  void adotTargetSystemsAreNotSupportedUpstream() {
    // If upstream starts supporting one of these names, the rules would be loaded twice.
    InternalMetricsDefinitions upstream =
        new InternalMetricsDefinitions(JmxTelemetryBuilder.class.getClassLoader());
    assertThat(upstream.getSupportedSystems())
        .doesNotContainAnyElementsOf(AwsJmxRulesInstaller.ADOT_TARGET_SYSTEMS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"kafka", "kafka-consumer", "kafka-producer"})
  void loadsAdotRulesFromClasspath(String target) throws IOException {
    assertThat(AwsJmxRulesInstaller.ADOT_TARGET_SYSTEMS).contains(target);
    ClassLoader classLoader = JmxTelemetryBuilder.class.getClassLoader();
    try (InputStream input = classLoader.getResourceAsStream("jmx/rules/" + target + ".yaml")) {
      assertThat(input).isNotNull();
      // throws IllegalArgumentException when the rules can't be parsed
      JmxTelemetry.builder(OpenTelemetry.noop()).addRules(input);
    }
  }
}
