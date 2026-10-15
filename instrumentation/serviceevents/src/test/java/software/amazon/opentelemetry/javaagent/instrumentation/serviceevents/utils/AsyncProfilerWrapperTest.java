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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link AsyncProfilerWrapper#buildStartCommand} — the async-profiler {@code start}
 * command differs by mode, so assert both {@code wall} and {@code cpu} produce the expected string
 * (no live profiler / native lib needed; the command is pure string assembly from config).
 */
class AsyncProfilerWrapperTest {

  private static AsyncProfilerWrapper wrapper(int mode, boolean memory) {
    // cpu interval 10ms, wall interval 50ms, loop 60s, alloc 512 KiB — profilerDataDir "" so
    // buildStartCommand's arg is used verbatim (constructor args:
    // cpuIntervalMs, wallIntervalMs, jfrBasePath, profilerDataDir, memoryEnabled,
    // allocIntervalBytes, loopSeconds, mode).
    return new AsyncProfilerWrapper(10, 50, "profiler-jfr", "", memory, 524288L, 60, mode);
  }

  @Test
  void buildStartCommand_wall_usesWallEventSignalAndInterval() {
    String cmd = wrapper(AsyncProfilerWrapper.MODE_WALL, false).buildStartCommand("profiler-jfr");
    assertEquals(
        "start,event=wall,signal=26,interval=50000000,threads,ann,jfr,"
            + "file=profiler-jfr-%t.jfr,loop=60s",
        cmd);
  }

  @Test
  void buildStartCommand_cpu_usesCpuEventNoSignalAndCpuInterval() {
    String cmd = wrapper(AsyncProfilerWrapper.MODE_CPU, false).buildStartCommand("profiler-jfr");
    // cpu: event=cpu, NO signal=26, cpu interval (10ms).
    assertEquals(
        "start,event=cpu,interval=10000000,threads,ann,jfr,file=profiler-jfr-%t.jfr,loop=60s", cmd);
  }

  @Test
  void buildStartCommand_memoryEnabled_appendsAllocClauseAfterInterval() {
    String cmd = wrapper(AsyncProfilerWrapper.MODE_WALL, true).buildStartCommand("profiler-jfr");
    assertEquals(
        "start,event=wall,signal=26,interval=50000000,alloc=524288,threads,ann,jfr,"
            + "file=profiler-jfr-%t.jfr,loop=60s",
        cmd);
  }

  // ---- config accessors ----

  @Test
  void accessors_reflectConstructorArgs() {
    AsyncProfilerWrapper wall = wrapper(AsyncProfilerWrapper.MODE_WALL, true);
    assertEquals(AsyncProfilerWrapper.MODE_WALL, wall.getMode());
    assertEquals(50, wall.getWallIntervalMs());
    assertEquals(50, wall.getPrimaryIntervalMs(), "wall mode -> wall interval");
    assertTrue(wall.isMemoryEnabled());
    assertEquals(524288L, wall.getAllocIntervalBytes());

    AsyncProfilerWrapper cpu = wrapper(AsyncProfilerWrapper.MODE_CPU, false);
    assertEquals(10, cpu.getPrimaryIntervalMs(), "cpu mode -> cpu interval");
    assertFalse(cpu.isMemoryEnabled());
  }

  @Test
  void getJfrBasePath_usesDataDirWhenSet_elseBasePath() {
    // profilerDataDir empty -> jfrBasePath verbatim
    assertEquals("profiler-jfr", wrapper(AsyncProfilerWrapper.MODE_WALL, false).getJfrBasePath());
    // profilerDataDir set -> <dataDir>/profiler-jfr
    AsyncProfilerWrapper w =
        new AsyncProfilerWrapper(10, 50, "ignored", "/tmp/adot-x", false, 524288L, 60);
    assertEquals(new File("/tmp/adot-x", "profiler-jfr").getPath(), w.getJfrBasePath());
  }

  @Test
  void fourArgConvenienceConstructor_defaultsToWallNoMemory() {
    AsyncProfilerWrapper w = new AsyncProfilerWrapper(10, 50, "profiler-jfr", "");
    assertEquals(AsyncProfilerWrapper.MODE_WALL, w.getMode());
    assertFalse(w.isMemoryEnabled());
  }

  @Test
  void shutdown_whenNotRunning_isNoOp() {
    // Native profiler is not started in unit tests, so running == false; shutdown must not throw.
    wrapper(AsyncProfilerWrapper.MODE_WALL, false).shutdown();
    assertFalse(wrapper(AsyncProfilerWrapper.MODE_WALL, false).isRunning());
  }

  // ---- JFR file retention (no native lib; pure filesystem) ----

  private static AsyncProfilerWrapper wrapperInDir(Path dataDir, int loopSeconds) {
    return new AsyncProfilerWrapper(
        10, 50, "profiler-jfr", dataDir.toString(), false, 524288L, loopSeconds, 0);
  }

  private static File touchJfr(Path dir, String name, long lastModified) throws Exception {
    File f = dir.resolve(name).toFile();
    assertTrue(f.createNewFile() || f.exists());
    assertTrue(f.setLastModified(lastModified));
    return f;
  }

  @Test
  void cleanupOldJfrFiles_deletesFilesOlderThanRetention_keepsRecent(@TempDir Path dir)
      throws Exception {
    // retention window = loopSeconds * RETENTION_WINDOWS(5). loop=1s -> 5s cutoff.
    AsyncProfilerWrapper w = wrapperInDir(dir, 1);
    long now = System.currentTimeMillis();
    File old = touchJfr(dir, "profiler-jfr-old.jfr", now - 60_000L); // well past cutoff
    File recent = touchJfr(dir, "profiler-jfr-new.jfr", now); // within cutoff
    File unrelated = touchJfr(dir, "keep-me.txt", now - 60_000L); // wrong suffix/prefix

    w.cleanupOldJfrFiles();

    assertFalse(old.exists(), "aged JFR should be evicted");
    assertTrue(recent.exists(), "recent JFR must be kept");
    assertTrue(unrelated.exists(), "non-JFR files are never touched");
  }

  @Test
  void startProfiling_thenShutdown_managesSessionAndDoesNotThrow(@TempDir Path dir) {
    // Exercises the start + stop lifecycle. When the native lib is present a real session runs
    // (into the temp dir); when absent, startProfiling no-ops on !available. Either way shutdown
    // leaves running == false and nothing throws.
    AsyncProfilerWrapper w =
        new AsyncProfilerWrapper(10, 10, "profiler-jfr", dir.toString(), false, 524288L, 60, 0);
    try {
      w.startProfiling();
    } finally {
      w.shutdown();
    }
    assertFalse(w.isRunning(), "shutdown must stop any running session");
  }

  @Test
  void deleteAllJfrFiles_removesEveryMatchingJfr_leavesOthers(@TempDir Path dir) throws Exception {
    AsyncProfilerWrapper w = wrapperInDir(dir, 60);
    long now = System.currentTimeMillis();
    File a = touchJfr(dir, "profiler-jfr-1.jfr", now);
    File b = touchJfr(dir, "profiler-jfr-2.jfr", now);
    File other = touchJfr(dir, "unrelated.log", now);

    w.deleteAllJfrFiles();

    assertFalse(a.exists());
    assertFalse(b.exists());
    assertTrue(other.exists(), "only profiler-jfr*.jfr files are deleted");
  }
}
