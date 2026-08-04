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

package software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.family;

import static software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.SpanMetricsAssertions.CALLS_METRIC;
import static software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.SpanMetricsAssertions.callsDataPoint;
import static software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.SpanMetricsAssertions.stringAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.base.SpanMetricsContractTestBase;
import software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.utils.ResourceScopeMetric;

/**
 * Base for per-family attribute tests. These run against a single mode (javaagent), because
 * attribute derivation is mode-independent — the mode tests cover wiring separately. The javaagent
 * app is used because real instrumentation produces authentic HTTP/DB/RPC/messaging spans.
 *
 * <p>Provides the javaagent + extension jar mount and a helper to fetch a span's metric attribute
 * map. The Kafka broker (needed only by the messaging family) is a heavyweight container, so it is
 * shared as a single static sidecar across all family classes rather than one-per-class — this
 * removes redundant broker bring-ups that starved the Docker daemon during the full suite. Because
 * the broker is long-lived, the family classes also share one static Docker {@link Network}; each
 * class's collector + app containers join it, and no class closes it (Ryuk reaps both at JVM exit).
 */
abstract class FamilyTestBase extends SpanMetricsContractTestBase {

  private static final String KAFKA_ALIAS = "kafkaBroker";

  /** Shared network for the whole family suite; created once, reaped by Ryuk at JVM exit. */
  private static final Network SHARED_NETWORK = Network.newNetwork();

  /**
   * Single Kafka broker shared across every family class. Started once when this class is loaded
   * (static initializer) and never explicitly stopped, so the ~6 family classes reuse one broker
   * instead of launching one each.
   */
  private static final KafkaContainer SHARED_KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
          .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
          .withNetwork(SHARED_NETWORK)
          .withNetworkAliases(KAFKA_ALIAS);

  static {
    SHARED_KAFKA.start();
  }

  @Override
  protected Network createNetwork() {
    return SHARED_NETWORK;
  }

  @Override
  protected boolean ownsNetwork() {
    return false; // shared across classes — must outlive any single class's @AfterAll
  }

  @Override
  protected String getApplicationImageName() {
    return "aws-otel-span-metrics-javaagent-app";
  }

  @Override
  protected void configureContainer(GenericContainer<?> container) {
    container
        .withCopyFileToContainer(MountableFile.forHostPath(JAVAAGENT_JAR_PATH), AGENT_MOUNT_PATH)
        .withCopyFileToContainer(
            MountableFile.forHostPath(EXTENSION_JAR_PATH), EXTENSION_MOUNT_PATH);
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    Map<String, String> env = new HashMap<>();
    env.put("JAVA_TOOL_OPTIONS", "-javaagent:" + AGENT_MOUNT_PATH);
    env.put("OTEL_JAVAAGENT_EXTENSIONS", EXTENSION_MOUNT_PATH);
    // In-network broker address for the /kafka endpoint.
    env.put("KAFKA_BOOTSTRAP_SERVERS", KAFKA_ALIAS + ":9092");
    return env;
  }

  /** Drives {@code path} then returns the string attribute map of the named span's calls datapoint. */
  protected Map<String, String> metricAttributesFor(String path, String spanName) {
    return stringAttributes(rawAttributesFor(path, spanName));
  }

  /** Drives {@code path} then returns the raw KeyValue attributes (incl. non-string) of the datapoint. */
  protected java.util.List<io.opentelemetry.proto.common.v1.KeyValue> rawAttributesFor(
      String path, String spanName) {
    drive(path);
    List<ResourceScopeMetric> metrics = awaitCalls(spanName);
    return callsDataPoint(metrics, spanName)
        .map(dp -> dp.getAttributesList())
        .orElseThrow(() -> new AssertionError("no calls datapoint for " + spanName));
  }

  /**
   * Drives {@code path} then returns the string attributes of the first calls datapoint matching all
   * of the given {@code key=value} predicates. For families whose span name isn't fixed (gRPC,
   * Kafka), match on stable family attributes instead of the span name. Multiple predicates are
   * required when a single endpoint emits several same-family datapoints (e.g. the Kafka endpoint
   * emits both a PRODUCER and a CONSUMER span, both with {@code messaging.system=kafka}), so the test
   * must pin down the exact one.
   */
  protected Map<String, String> metricAttributesMatching(String path, String... keyValuePairs) {
    if (keyValuePairs.length == 0 || keyValuePairs.length % 2 != 0) {
      throw new IllegalArgumentException("keyValuePairs must be non-empty key,value pairs");
    }
    drive(path);
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      var match =
          mockCollectorClient.getMetrics(java.util.Set.of(CALLS_METRIC)).stream()
              .filter(m -> m.getMetric().getName().equals(CALLS_METRIC))
              .flatMap(m -> m.getMetric().getSum().getDataPointsList().stream())
              .map(dp -> stringAttributes(dp.getAttributesList()))
              .filter(a -> matchesAll(a, keyValuePairs))
              .findFirst();
      if (match.isPresent()) {
        return match.get();
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError(
        "no calls datapoint matching " + java.util.Arrays.toString(keyValuePairs));
  }

  private static boolean matchesAll(Map<String, String> attrs, String[] keyValuePairs) {
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      if (!keyValuePairs[i + 1].equals(attrs.get(keyValuePairs[i]))) {
        return false;
      }
    }
    return true;
  }
}
