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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ServiceAttributes;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.config.DynamicInstrumentationConfig;

class ConfigurationPollerTest {

  private DynamicInstrumentationConfig config;
  private DynamicInstrumentationClient client;

  @BeforeEach
  void setUp() {
    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    client = new DynamicInstrumentationClient(config);
  }

  @Test
  void testConstruction() {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    assertThat(poller).isNotNull();
    assertThat(poller.isRunning()).isFalse();
  }

  @Test
  void testStart() throws InterruptedException {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    assertThat(poller.isRunning()).isFalse();

    poller.start();

    // Give threads a moment to start
    Thread.sleep(100);

    assertThat(poller.isRunning()).isTrue();

    poller.stop();
  }

  @Test
  void testStop() throws InterruptedException {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    poller.start();
    Thread.sleep(100);

    assertThat(poller.isRunning()).isTrue();

    poller.stop();

    assertThat(poller.isRunning()).isFalse();
  }

  @Test
  void testStartWhenAlreadyRunning() throws InterruptedException {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    poller.start();
    Thread.sleep(100);

    // Try to start again - should be a no-op
    poller.start();

    assertThat(poller.isRunning()).isTrue();

    poller.stop();
  }

  @Test
  void testStopWhenNotRunning() {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    // Try to stop when not running - should be a no-op
    poller.stop();

    assertThat(poller.isRunning()).isFalse();
  }

  @Test
  void concurrentStartCreatesPollerThreadsOnlyOnce() throws Exception {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    // Wait out any poller threads leaked by other tests in this class. A leftover thread that
    // dies between the baseline count below and the assertion would offset the +1 from this
    // poller's new thread and make the delta read 0 (the CI flake this guards against).
    awaitNoThreadsNamed("ProbePoller");
    awaitNoThreadsNamed("BreakpointPoller");

    // Count the poller threads created by this poller's concurrent start storm via a before/after
    // delta. With an atomic check-and-set only one start() wins, so exactly one thread of each
    // name (ProbePoller + BreakpointPoller) is added; a non-atomic check-then-act could let
    // multiple callers through and spawn extras.
    int beforeProbe = countThreadsNamed("ProbePoller");
    int beforeBreakpoint = countThreadsNamed("BreakpointPoller");

    int callers = 8;
    CyclicBarrier barrier = new CyclicBarrier(callers);
    Thread[] threads = new Thread[callers];
    for (int i = 0; i < callers; i++) {
      threads[i] =
          new Thread(
              () -> {
                try {
                  barrier.await();
                  poller.start();
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      threads[i].start();
    }
    for (Thread t : threads) {
      t.join();
    }

    try {
      assertThat(poller.isRunning()).isTrue();
      assertThat(countThreadsNamed("ProbePoller") - beforeProbe).isEqualTo(1);
      assertThat(countThreadsNamed("BreakpointPoller") - beforeBreakpoint).isEqualTo(1);
    } finally {
      poller.stop();
    }
  }

  /** Count live threads with the exact given name. */
  private static int countThreadsNamed(String name) {
    int count = 0;
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (name.equals(t.getName()) && t.isAlive()) {
        count++;
      }
    }
    return count;
  }

  /** Wait until no live thread with the exact given name remains, failing after a timeout. */
  private static void awaitNoThreadsNamed(String name) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15_000;
    while (countThreadsNamed(name) > 0) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("Timed out waiting for leftover " + name + " threads to exit");
      }
      Thread.sleep(50);
    }
  }

  @Test
  void testStartAndStopMultipleTimes() throws InterruptedException {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    // First cycle
    poller.start();
    Thread.sleep(100);
    assertThat(poller.isRunning()).isTrue();
    poller.stop();
    assertThat(poller.isRunning()).isFalse();

    // Wait a bit
    Thread.sleep(100);

    // Second cycle
    poller.start();
    Thread.sleep(100);
    assertThat(poller.isRunning()).isTrue();
    poller.stop();
    assertThat(poller.isRunning()).isFalse();
  }
}
