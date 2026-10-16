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

import static java.util.Collections.emptyList;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.incubator.config.DeclarativeConfigProperties;
import io.opentelemetry.instrumentation.api.incubator.config.internal.DeclarativeConfigUtil;
import io.opentelemetry.instrumentation.jmx.JmxTelemetry;
import io.opentelemetry.instrumentation.jmx.JmxTelemetryBuilder;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads the JMX rules bundled with ADOT (instrumentation/jmx-metrics) for the {@code
 * otel.jmx.target.system} entries that the upstream JMX Metric Insight installer does not support.
 *
 * <p>Up to OpenTelemetry Java instrumentation 2.31.x, the upstream installer resolved every target
 * system to a {@code jmx/rules/<target>.yaml} classpath resource, which is how the ADOT kafka,
 * kafka-consumer and kafka-producer rules were picked up. Since 2.32.0 it only loads its own
 * built-in systems, so this listener restores classpath loading for the ADOT-only targets.
 */
public class AwsJmxRulesInstaller implements AgentListener {

  private static final Logger logger = Logger.getLogger(AwsJmxRulesInstaller.class.getName());

  // Target systems whose rules ship only with ADOT. The other ADOT rule files (jvm, tomcat) share
  // their name with an upstream-supported system and are still loaded by the upstream installer.
  static final Set<String> ADOT_TARGET_SYSTEMS =
      Collections.unmodifiableSet(
          new HashSet<>(Arrays.asList("kafka", "kafka-consumer", "kafka-producer")));

  @Override
  public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
    // Read the same configuration as the upstream JmxMetricInsightInstaller.
    DeclarativeConfigProperties config =
        DeclarativeConfigUtil.getInstrumentationConfig(GlobalOpenTelemetry.get(), "jmx");
    if (!config.getBoolean("enabled", true)) {
      return;
    }

    List<String> targets =
        adotTargetSystems(config.get("target").getScalarList("system", String.class, emptyList()));
    if (targets.isEmpty()) {
      return;
    }

    JmxTelemetryBuilder jmx =
        JmxTelemetry.builder(GlobalOpenTelemetry.get())
            .beanDiscoveryDelay(
                Duration.ofMillis(
                    config.get("discovery").getLong("delay", Duration.ofMinutes(1).toMillis())));
    ClassLoader classLoader = JmxTelemetryBuilder.class.getClassLoader();
    targets.forEach(target -> addClasspathRules(target, classLoader, jmx));
    jmx.build().start();
    logger.log(Level.INFO, "Loaded ADOT JMX rules for target systems {0}", targets);
  }

  static List<String> adotTargetSystems(List<String> targetSystems) {
    List<String> result = new ArrayList<>();
    for (String target : targetSystems) {
      if (ADOT_TARGET_SYSTEMS.contains(target) && !result.contains(target)) {
        result.add(target);
      }
    }
    return result;
  }

  static void addClasspathRules(
      String target, ClassLoader classLoader, JmxTelemetryBuilder builder) {
    String resource = "jmx/rules/" + target + ".yaml";
    try (InputStream input = classLoader.getResourceAsStream(resource)) {
      if (input == null) {
        logger.log(Level.SEVERE, "JMX configuration not found on classpath {0}", resource);
        return;
      }
      builder.addRules(input);
    } catch (IOException | RuntimeException e) {
      // only log JMX configuration errors, as they must not prevent agent startup
      logger.log(
          Level.SEVERE, "Error while loading JMX configuration from classpath " + resource, e);
    }
  }
}
