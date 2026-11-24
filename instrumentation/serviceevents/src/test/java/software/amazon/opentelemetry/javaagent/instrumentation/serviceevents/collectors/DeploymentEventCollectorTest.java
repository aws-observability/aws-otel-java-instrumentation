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
import org.junit.jupiter.api.Test;

/** Tests for DeploymentEventCollector. */
class DeploymentEventCollectorTest {

  @Test
  void testCollectEmitsToConsoleWhenNoOtlpEmitter() throws InterruptedException {
    CountDownLatch collectLatch = new CountDownLatch(1);

    // Create a subclass that signals when collect() runs
    DeploymentEventCollector collector =
        new DeploymentEventCollector(1000, "dev", "svc", "d1", "", "", "", "", null) {
          @Override
          protected void collect() {
            super.collect();
            collectLatch.countDown();
          }
        };

    collector.start();
    try {
      assertTrue(collectLatch.await(5, TimeUnit.SECONDS), "collect() should be called");
    } finally {
      collector.stop();
    }
    // No exception means console output succeeded
  }

  @Test
  void testFlushIntervalConstant() {
    assertEquals(86_400_000, DeploymentEventCollector.FLUSH_INTERVAL_MS);
  }
}
