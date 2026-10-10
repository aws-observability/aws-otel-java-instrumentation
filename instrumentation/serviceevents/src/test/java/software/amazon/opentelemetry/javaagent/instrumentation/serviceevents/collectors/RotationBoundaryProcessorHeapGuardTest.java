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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ProfilesExporter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.AsyncProfilerWrapper;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.HeapGuard;

/**
 * The profiler must never push the application's heap into an OOM: a window that does not fit is
 * skipped, one that runs into heap pressure is abandoned, and with room nothing changes.
 */
class RotationBoundaryProcessorHeapGuardTest {

  private static final long MB = 1L << 20;

  private static HeapGuard heap(long maxMb, long liveMb) {
    return new HeapGuard(
        new HeapGuard.HeapStats() {
          @Override
          public long maxBytes() {
            return maxMb * MB;
          }

          @Override
          public long liveBytes() {
            return liveMb * MB;
          }
        });
  }

  private static final class Capture implements ProfilesExporter {
    final List<byte[]> payloads = new ArrayList<>();

    @Override
    public boolean export(byte[] payload) {
      payloads.add(payload);
      return true;
    }

    @Override
    public void shutdown() {}

    @Override
    public String getEndpoint() {
      return "test://capture";
    }
  }

  private static RotationBoundaryProcessor processorOverFixture(Path dir, Capture exporter)
      throws Exception {
    Files.createDirectories(dir);
    Path fixture =
        Path.of(
            RotationBoundaryProcessorHeapGuardTest.class
                .getResource("/wall-alloc-sample.jfr")
                .toURI());
    Files.copy(fixture, dir.resolve("profiler-jfr-20260101-000000.jfr"));
    AsyncProfilerWrapper stopped =
        new AsyncProfilerWrapper(10, 10, "profiler-jfr", dir.toString(), true, 524288L, 60, 0) {
          @Override
          public boolean isAvailable() {
            return true;
          }

          @Override
          public boolean isRunning() {
            return false;
          }
        };
    return new RotationBoundaryProcessor(10_000, stopped, 60, null, exporter, 0);
  }

  @Test
  void heapGuard_startsOnlyWhenWindowFits_andReportsPressureAboveThreshold() {
    // 80% of 1000 MB = 800 MB: 300 live + 500 needed fits, 300 + 501 does not.
    assertTrue(heap(1000, 300).canStart(500 * MB));
    assertFalse(heap(1000, 300).canStart(501 * MB));
    // Pressure above 90% live.
    assertFalse(heap(1000, 900).underPressure());
    assertTrue(heap(1000, 901).underPressure());
    // Unknown or unbounded heap: never blocks.
    assertTrue(heap(0, 0).canStart(Long.MAX_VALUE / 2));
    assertFalse(heap(0, 0).underPressure());
  }

  @Test
  void windowThatDoesNotFit_isSkipped(@TempDir Path dir) throws Exception {
    Capture exporter = new Capture();
    RotationBoundaryProcessor proc = processorOverFixture(dir, exporter);
    proc.setHeapGuard(heap(64, 60)); // almost no room left
    proc.flushRemainingWindows();
    assertTrue(exporter.payloads.isEmpty(), "window skipped, nothing exported");
    proc.flushRemainingWindows();
    assertTrue(exporter.payloads.isEmpty(), "a skipped window is not retried");
  }

  /**
   * Heap figures where the live heap jumps to 98% once more than {@code okChecks} reads happened.
   */
  private static HeapGuard fillsUpAfter(int okChecks, AtomicInteger reads) {
    return new HeapGuard(
        new HeapGuard.HeapStats() {
          @Override
          public long maxBytes() {
            return 4096 * MB;
          }

          @Override
          public long liveBytes() {
            return reads.incrementAndGet() > okChecks ? 4000 * MB : 0;
          }
        });
  }

  @Test
  void heapPressureDuringScan_abandonsWindowWithoutThrowing(@TempDir Path dir) throws Exception {
    Capture exporter = new Capture();
    RotationBoundaryProcessor proc = processorOverFixture(dir, exporter);
    proc.setHeapCheckInterval(1);
    AtomicInteger reads = new AtomicInteger();
    // Room to start (read 1) and for the first 9 per-event checks, then the heap fills up.
    proc.setHeapGuard(fillsUpAfter(10, reads));
    proc.flushRemainingWindows(); // must not throw
    // 1 start check + 10 per-event checks (the 10th sees pressure) + 1 read for the warning text.
    assertEquals(12, reads.get(), "abandoned at the first check that saw pressure");
    assertTrue(exporter.payloads.isEmpty(), "abandoned window is not exported");
  }

  @Test
  void heapPressureBeforeSerializing_abandonsWindow(@TempDir Path dir) throws Exception {
    Capture exporter = new Capture();
    RotationBoundaryProcessor proc = processorOverFixture(dir, exporter);
    proc.setHeapCheckInterval(Integer.MAX_VALUE); // no per-event checks
    AtomicInteger reads = new AtomicInteger();
    proc.setHeapGuard(fillsUpAfter(1, reads)); // room to start, pressure at the next check
    proc.flushRemainingWindows();
    // Start check, pre-serialization check (sees pressure), 1 read for the warning text.
    assertEquals(3, reads.get(), "start check, then the pre-serialization check");
    assertTrue(exporter.payloads.isEmpty());
  }

  @Test
  void withRoom_windowIsExportedExactlyAsWithoutGuard(@TempDir Path dir) throws Exception {
    Capture guarded = new Capture();
    RotationBoundaryProcessor proc = processorOverFixture(dir.resolve("a"), guarded);
    proc.setHeapGuard(heap(16_384, 100));
    proc.flushRemainingWindows();

    Capture unguarded = new Capture();
    RotationBoundaryProcessor plain = processorOverFixture(dir.resolve("b"), unguarded);
    plain.setHeapGuard(heap(0, 0)); // unbounded: guard never intervenes
    plain.flushRemainingWindows();

    assertEquals(1, guarded.payloads.size());
    assertEquals(1, unguarded.payloads.size());
    // Same content apart from the random profile ids.
    assertEquals(
        strip(unguarded.payloads.get(0)), strip(guarded.payloads.get(0)), "identical export");
  }

  private static io.opentelemetry.proto.collector.profiles.v1development
          .ExportProfilesServiceRequest
      strip(byte[] payload) throws Exception {
    io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest.Builder b =
        io
            .opentelemetry
            .proto
            .collector
            .profiles
            .v1development
            .ExportProfilesServiceRequest
            .parseFrom(payload)
            .toBuilder();
    for (int k = 0; k < b.getResourceProfiles(0).getScopeProfiles(0).getProfilesCount(); k++) {
      b.getResourceProfilesBuilder(0)
          .getScopeProfilesBuilder(0)
          .getProfilesBuilder(k)
          .clearProfileId();
    }
    return b.build();
  }
}
