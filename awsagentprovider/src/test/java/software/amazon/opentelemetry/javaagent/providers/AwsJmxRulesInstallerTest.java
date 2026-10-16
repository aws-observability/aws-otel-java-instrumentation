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
import static org.assertj.core.api.Assertions.entry;
import static software.amazon.opentelemetry.javaagent.providers.AwsJmxRulesInstaller.AWS_JMX_TARGET_SYSTEM_CONFIG;
import static software.amazon.opentelemetry.javaagent.providers.AwsJmxRulesInstaller.OTEL_JMX_TARGET_SYSTEM_CONFIG;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.jmx.JmxTelemetry;
import io.opentelemetry.instrumentation.jmx.JmxTelemetryBuilder;
import io.opentelemetry.instrumentation.jmx.internal.InternalMetricsDefinitions;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AwsJmxRulesInstallerTest {

  @Test
  void selectsOnlyAdotTargetSystems() {
    assertThat(
            AwsJmxRulesInstaller.adotTargetSystems(
                Arrays.asList("jvm", "kafka", "activemq", " tomcat", "kafka-producer", "kafka")))
        .containsExactly("jvm", "kafka", "tomcat", "kafka-producer");
    assertThat(AwsJmxRulesInstaller.adotTargetSystems(Arrays.asList("activemq", "jetty")))
        .isEmpty();
    assertThat(AwsJmxRulesInstaller.adotTargetSystems(Collections.emptyList())).isEmpty();
  }

  @Test
  void splitsAdotTargetSystemsFromUpstreamOnes() {
    assertThat(
            AwsJmxRulesInstaller.splitTargetSystems(
                Arrays.asList("activemq", "jvm", "kafka", "jetty")))
        .containsOnly(
            entry(OTEL_JMX_TARGET_SYSTEM_CONFIG, "activemq,jetty"),
            entry(AWS_JMX_TARGET_SYSTEM_CONFIG, "jvm,kafka"));
    assertThat(AwsJmxRulesInstaller.splitTargetSystems(Collections.singletonList("jvm")))
        .containsOnly(
            entry(OTEL_JMX_TARGET_SYSTEM_CONFIG, ""), entry(AWS_JMX_TARGET_SYSTEM_CONFIG, "jvm"));
    assertThat(AwsJmxRulesInstaller.splitTargetSystems(Arrays.asList("activemq", "jetty")))
        .isEmpty();
    assertThat(AwsJmxRulesInstaller.splitTargetSystems(Collections.emptyList())).isEmpty();
  }

  @Test
  void usesSameDiscoveryDelayAsUpstream() {
    assertThat(
            AwsJmxRulesInstaller.discoveryDelay(
                DefaultConfigProperties.createFromMap(
                    Map.of(
                        "otel.jmx.discovery.delay", "5s", "otel.metric.export.interval", "100"))))
        .isEqualTo(Duration.ofSeconds(5));
    assertThat(
            AwsJmxRulesInstaller.discoveryDelay(
                DefaultConfigProperties.createFromMap(Map.of("otel.jmx.discovery.delay", "2000"))))
        .isEqualTo(Duration.ofSeconds(2));
    assertThat(
            AwsJmxRulesInstaller.discoveryDelay(
                DefaultConfigProperties.createFromMap(
                    Map.of("otel.metric.export.interval", "100"))))
        .isEqualTo(Duration.ofMillis(100));
    assertThat(
            AwsJmxRulesInstaller.discoveryDelay(
                DefaultConfigProperties.createFromMap(Collections.emptyMap())))
        .isEqualTo(Duration.ofMinutes(1));
  }

  @ParameterizedTest
  @ValueSource(strings = {"jvm", "tomcat", "kafka", "kafka-consumer", "kafka-producer"})
  void loadsAdotRulesFromClasspath(String target) throws IOException {
    assertThat(AwsJmxRulesInstaller.ADOT_TARGET_SYSTEMS).contains(target);
    ClassLoader classLoader = JmxTelemetryBuilder.class.getClassLoader();
    try (InputStream input =
        classLoader.getResourceAsStream(AwsJmxRulesInstaller.RULES_PATH + target + ".yaml")) {
      assertThat(input).isNotNull();
      // throws IllegalArgumentException when the rules can't be parsed
      JmxTelemetry.builder(OpenTelemetry.noop()).addRules(input);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"tomcat", "kafka", "kafka-consumer", "kafka-producer"})
  void adotRulesAreNotOnUpstreamRulesPath(String target) {
    // The upstream installer loads jmx/rules/<system>.yaml as built-in stable rules, and would pick
    // up ADOT rules found there, even with no target system configured.
    InternalMetricsDefinitions upstream =
        new InternalMetricsDefinitions(JmxTelemetryBuilder.class.getClassLoader());
    assertThat(upstream.getRulesPath(target, true)).isNull();
  }
}
