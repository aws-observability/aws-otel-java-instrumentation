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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.RotationBoundaryProcessor;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ProfilesExporter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.AsyncProfilerWrapper;

/** The final export at JVM shutdown must finish when it can, and never hold up the exit. */
class ServiceEventsProfilerShutdownTest {

  /** Profiler already stopped, serving the fixture JFR from the temp dir. */
  private static final class StoppedWrapper extends AsyncProfilerWrapper {
    StoppedWrapper(Path dir) {
      super(10, 10, "profiler-jfr", dir.toString(), false, 524288L, 60, MODE_WALL);
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isRunning() {
      return false;
    }
  }

  private static final class Exporter implements ProfilesExporter {
    final AtomicInteger exports = new AtomicInteger();
    final CountDownLatch release;

    Exporter(CountDownLatch release) {
      this.release = release;
    }

    @Override
    public boolean export(byte[] payload) {
      try {
        if (release != null) {
          release.await();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      exports.incrementAndGet();
      return true;
    }

    @Override
    public void shutdown() {}

    @Override
    public String getEndpoint() {
      return "test://exporter";
    }
  }

  private static Path withFixture(Path dir) throws Exception {
    Path fixture =
        Path.of(
            ServiceEventsProfilerShutdownTest.class.getResource("/wall-alloc-sample.jfr").toURI());
    Files.copy(fixture, dir.resolve("profiler-jfr-20260101-000000.jfr"));
    return dir;
  }

  @Test
  void exportFinalWindows_exportsLastWindow(@TempDir Path dir) throws Exception {
    Exporter exporter = new Exporter(null);
    RotationBoundaryProcessor processor =
        new RotationBoundaryProcessor(
            10_000, new StoppedWrapper(withFixture(dir)), 60, null, exporter, 0);

    assertTrue(ServiceEventsInstrumentation.exportFinalWindows(processor, 30_000));
    assertEquals(1, exporter.exports.get());
  }

  /** An export that hangs (unreachable endpoint) is abandoned after the timeout. */
  @Test
  @org.junit.jupiter.api.Timeout(10)
  void exportFinalWindows_hangingExport_returnsAfterTimeout(@TempDir Path dir) throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    Exporter exporter = new Exporter(release);
    RotationBoundaryProcessor processor =
        new RotationBoundaryProcessor(
            10_000, new StoppedWrapper(withFixture(dir)), 60, null, exporter, 0);
    try {
      long start = System.nanoTime();
      assertFalse(ServiceEventsInstrumentation.exportFinalWindows(processor, 300));
      long tookMs = (System.nanoTime() - start) / 1_000_000;
      assertTrue(tookMs < 5_000, "returned after " + tookMs + "ms");
    } finally {
      release.countDown();
    }
  }
}
