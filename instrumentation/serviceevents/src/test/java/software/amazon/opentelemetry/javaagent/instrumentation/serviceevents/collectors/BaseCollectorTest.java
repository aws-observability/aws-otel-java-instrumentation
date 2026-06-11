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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BaseCollectorTest {

  /**
   * Verifies that runCollectionLoop survives a Throwable (e.g., Error) thrown from collect() and
   * continues looping. Before the fix, an Error would kill the daemon thread permanently.
   */
  @Test
  void collectionLoopSurvivesThrowable() throws InterruptedException {
    AtomicInteger collectCallCount = new AtomicInteger(0);
    CountDownLatch secondCallLatch = new CountDownLatch(1);

    BaseCollector collector =
        new BaseCollector(100, "TestCollector", null) {
          @Override
          protected void collect() {
            int call = collectCallCount.incrementAndGet();
            if (call == 1) {
              // Throw an Error (not Exception) on first call
              throw new OutOfMemoryError("simulated error");
            }
            // Second call means the loop survived the Error
            secondCallLatch.countDown();
          }
        };

    collector.start();
    try {
      // Wait for the second collect() call, proving the loop survived the Error
      assertTrue(
          secondCallLatch.await(5, TimeUnit.SECONDS),
          "collect() should be called again after throwing an Error");
      assertTrue(collectCallCount.get() >= 2, "collect() should have been called at least twice");
      assertTrue(collector.isRunning(), "Collector should still be running");
    } finally {
      collector.stop();
    }
  }

  @Test
  void collectionLoopStopsCleanlyOnStop() throws InterruptedException {
    AtomicInteger collectCallCount = new AtomicInteger(0);

    BaseCollector collector =
        new BaseCollector(50, "TestCollector", null) {
          @Override
          protected void collect() {
            collectCallCount.incrementAndGet();
          }
        };

    collector.start();
    assertTrue(collector.isRunning());

    // Let it run a few cycles
    Thread.sleep(200);

    collector.stop();
    assertFalse(collector.isRunning());

    // collect() should have been called multiple times (including the final collection)
    assertTrue(collectCallCount.get() >= 2);
  }
}
