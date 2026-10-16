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

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.jmx.JmxTelemetry;
import io.opentelemetry.instrumentation.jmx.JmxTelemetryBuilder;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.internal.AutoConfigureUtil;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads the JMX rules bundled with ADOT (instrumentation/jmx-metrics, {@code jmx/aws/rules}) for
 * the JMX target systems ADOT ships, keeping their JMX metrics the same as before OpenTelemetry
 * Java instrumentation 2.32.0.
 *
 * <p>Up to 2.31.x, the upstream JMX Metric Insight installer resolved every {@code
 * otel.jmx.target.system} entry to a {@code jmx/rules/<target>.yaml} classpath resource, which
 * picked up the ADOT rules. Since 2.32.0 it only loads its built-in rules, and also enables their
 * unstable metrics. So {@link #splitTargetSystems} moves the ADOT target systems from {@code
 * otel.jmx.target.system} to {@code otel.aws.jmx.target.system}, and this listener loads their
 * rules. The other target systems are left to the upstream installer.
 */
public class AwsJmxRulesInstaller implements AgentListener {

  private static final Logger logger = Logger.getLogger(AwsJmxRulesInstaller.class.getName());

  static final String OTEL_JMX_TARGET_SYSTEM_CONFIG = "otel.jmx.target.system";
  // Internal: set by splitTargetSystems, read by this listener.
  static final String AWS_JMX_TARGET_SYSTEM_CONFIG = "otel.aws.jmx.target.system";
  private static final String OTEL_JMX_ENABLED_CONFIG = "otel.jmx.enabled";
  private static final String OTEL_JMX_DISCOVERY_DELAY_CONFIG = "otel.jmx.discovery.delay";
  private static final String OTEL_METRIC_EXPORT_INTERVAL_CONFIG = "otel.metric.export.interval";
  static final String RULES_PATH = "jmx/aws/rules/";

  // Target systems with rules in RULES_PATH.
  static final Set<String> ADOT_TARGET_SYSTEMS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList("jvm", "tomcat", "kafka", "kafka-consumer", "kafka-producer")));

  @Override
  public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
    ConfigProperties config = AutoConfigureUtil.getConfig(autoConfiguredSdk);
    if (config == null || !config.getBoolean(OTEL_JMX_ENABLED_CONFIG, true)) {
      return;
    }

    List<String> targets = adotTargetSystems(config.getList(AWS_JMX_TARGET_SYSTEM_CONFIG));
    if (targets.isEmpty()) {
      return;
    }

    JmxTelemetryBuilder jmx =
        JmxTelemetry.builder(GlobalOpenTelemetry.get()).beanDiscoveryDelay(discoveryDelay(config));
    ClassLoader classLoader = JmxTelemetryBuilder.class.getClassLoader();
    targets.forEach(target -> addClasspathRules(target, classLoader, jmx));
    jmx.build().start();
    logger.log(Level.INFO, "Loaded ADOT JMX rules for target systems {0}", targets);
  }

  /**
   * Returns the property overrides that move the ADOT target systems out of {@code
   * otel.jmx.target.system} into {@code otel.aws.jmx.target.system}, or an empty map when there is
   * none.
   */
  static Map<String, String> splitTargetSystems(List<String> targetSystems) {
    List<String> adotTargets = adotTargetSystems(targetSystems);
    if (adotTargets.isEmpty()) {
      return Collections.emptyMap();
    }
    List<String> upstreamTargets = new ArrayList<>();
    for (String target : targetSystems) {
      String trimmed = target.trim();
      if (!trimmed.isEmpty()
          && !adotTargets.contains(trimmed)
          && !upstreamTargets.contains(trimmed)) {
        upstreamTargets.add(trimmed);
      }
    }
    Map<String, String> overrides = new HashMap<>();
    overrides.put(OTEL_JMX_TARGET_SYSTEM_CONFIG, String.join(",", upstreamTargets));
    overrides.put(AWS_JMX_TARGET_SYSTEM_CONFIG, String.join(",", adotTargets));
    return overrides;
  }

  /**
   * Same discovery delay as the upstream installer: {@code otel.jmx.discovery.delay}, falling back
   * to {@code otel.metric.export.interval}, then to one minute. The platform MBean server is only
   * initialized for discovery once this delay has expired.
   */
  static Duration discoveryDelay(ConfigProperties config) {
    Duration delay = config.getDuration(OTEL_JMX_DISCOVERY_DELAY_CONFIG);
    if (delay == null) {
      delay = config.getDuration(OTEL_METRIC_EXPORT_INTERVAL_CONFIG);
    }
    return delay != null ? delay : Duration.ofMinutes(1);
  }

  static List<String> adotTargetSystems(List<String> targetSystems) {
    List<String> result = new ArrayList<>();
    for (String target : targetSystems) {
      String trimmed = target.trim();
      if (ADOT_TARGET_SYSTEMS.contains(trimmed) && !result.contains(trimmed)) {
        result.add(trimmed);
      }
    }
    return result;
  }

  static void addClasspathRules(
      String target, ClassLoader classLoader, JmxTelemetryBuilder builder) {
    String resource = RULES_PATH + target + ".yaml";
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
