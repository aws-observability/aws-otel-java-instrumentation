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

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;
import one.profiler.AsyncProfiler;

/**
 * Wrapper for a single async-profiler session used for wall-clock or on-CPU sampling and, when
 * enabled, allocation profiling with JFR output.
 *
 * <p>Runs one session in the configured {@linkplain #MODE_WALL wall} or {@linkplain #MODE_CPU cpu}
 * mode with {@code threads,ann,jfr} and {@code loop} rotation. In {@code wall} mode (the default,
 * captures both CPU-bound and I/O-bound activity) all threads are sampled. In {@code cpu} mode only
 * running threads are sampled (perf/ctimer). Span↔sample correlation is driven by {@code
 * profiler.Span} markers the {@code ServiceEventsSpanProcessor} writes into this JFR session,
 * regardless of mode.
 */
public class AsyncProfilerWrapper {

  private static final Logger logger = Logger.getLogger(AsyncProfilerWrapper.class.getName());

  /** Wall-clock sampling ({@code event=wall}); captures on- and off-CPU activity. */
  public static final int MODE_WALL = 0;

  /** On-CPU sampling ({@code event=cpu}, perf_events → ctimer fallback); running threads only. */
  public static final int MODE_CPU = 1;

  /**
   * Default JFR rotation window (seconds) when {@code OTEL_AWS_PROFILER_WINDOW_SECONDS} is unset.
   */
  public static final int DEFAULT_WINDOW_SECONDS = 60;

  /**
   * JFR file retention = window × this many rotations. Kept &gt; 1 so retention cleanup never
   * deletes a rotated file before it is scanned.
   */
  private static final int RETENTION_WINDOWS = 5;

  /**
   * JFR file rotation interval in seconds — async-profiler's native {@code loop=<loopSeconds>s}.
   * This IS the export window and MUST equal {@link
   * software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.RotationBoundaryProcessor}'s
   * {@code windowSeconds}; both are threaded from {@code OTEL_AWS_PROFILER_WINDOW_SECONDS} via
   * ServiceEventsInstrumentation. A shorter window makes each per-window OTLP request smaller
   * (fewer samples and distinct identities) — a lever for fitting under a backend's per-request
   * cap.
   */
  private final int loopSeconds;

  private final int cpuIntervalMs;
  private final int wallIntervalMs;
  private final String jfrBasePath;
  private final String profilerDataDir;

  /** Sampling event to run: {@link #MODE_WALL} or {@link #MODE_CPU}. */
  private final int mode;

  /**
   * Memory/allocation profiling. When {@code memoryEnabled} is true, {@code
   * ,alloc=<allocIntervalBytes>} is appended to the {@code start} command so async-profiler records
   * allocation events ({@code jdk.ObjectAllocationInNewTLAB}) in the SAME single JFR session.
   * {@code allocIntervalBytes} is async-profiler's {@code alloc=} sampling interval (bytes; ~one
   * sample per this many bytes allocated).
   */
  private final boolean memoryEnabled;

  private final long allocIntervalBytes;

  /** Process-wide async-profiler singleton (one session: wall or cpu, JFR output). */
  private AsyncProfiler profiler;

  private volatile boolean available;
  private volatile boolean running;

  /**
   * Convenience constructor: wall mode, memory profiling disabled.
   *
   * @param cpuIntervalMs CPU sampling interval in milliseconds
   * @param wallIntervalMs Wall-clock sampling interval in milliseconds
   * @param jfrBasePath Base path for JFR files (e.g., "profiler-jfr")
   * @param profilerDataDir Directory for profiler data (JFR files + extracted native lib)
   */
  public AsyncProfilerWrapper(
      int cpuIntervalMs, int wallIntervalMs, String jfrBasePath, String profilerDataDir) {
    this(
        cpuIntervalMs,
        wallIntervalMs,
        jfrBasePath,
        profilerDataDir,
        false,
        524288L,
        DEFAULT_WINDOW_SECONDS);
  }

  /**
   * Initialize the async-profiler wrapper.
   *
   * @param cpuIntervalMs CPU sampling interval in milliseconds
   * @param wallIntervalMs Wall-clock sampling interval in milliseconds
   * @param jfrBasePath Base path for JFR files (e.g., "profiler-jfr")
   * @param profilerDataDir Directory for profiler data (JFR files + extracted native lib)
   * @param memoryEnabled when true, record allocation events ({@code alloc=}) in the same JFR
   *     session
   * @param allocIntervalBytes async-profiler {@code alloc=} sampling interval in bytes
   * @param loopSeconds JFR rotation window in seconds (async-profiler {@code loop=}); must match
   *     the RotationBoundaryProcessor {@code windowSeconds}
   */
  public AsyncProfilerWrapper(
      int cpuIntervalMs,
      int wallIntervalMs,
      String jfrBasePath,
      String profilerDataDir,
      boolean memoryEnabled,
      long allocIntervalBytes,
      int loopSeconds) {
    this(
        cpuIntervalMs,
        wallIntervalMs,
        jfrBasePath,
        profilerDataDir,
        memoryEnabled,
        allocIntervalBytes,
        loopSeconds,
        MODE_WALL);
  }

  /**
   * Initialize the async-profiler wrapper with an explicit profiling mode.
   *
   * @param mode {@link #MODE_WALL} (wall-clock) or {@link #MODE_CPU} (on-CPU)
   */
  public AsyncProfilerWrapper(
      int cpuIntervalMs,
      int wallIntervalMs,
      String jfrBasePath,
      String profilerDataDir,
      boolean memoryEnabled,
      long allocIntervalBytes,
      int loopSeconds,
      int mode) {
    this.cpuIntervalMs = cpuIntervalMs;
    this.wallIntervalMs = wallIntervalMs;
    this.jfrBasePath = jfrBasePath;
    this.profilerDataDir = profilerDataDir;
    this.memoryEnabled = memoryEnabled;
    this.allocIntervalBytes = allocIntervalBytes;
    this.loopSeconds = loopSeconds;
    this.mode = (mode == MODE_CPU) ? MODE_CPU : MODE_WALL;
    this.available = false;
    this.running = false;

    try {
      // Explicit native-lib extraction: point async-profiler at our controlled, writable data dir
      // (via its one.profiler.extractPath hook) instead of java.io.tmpdir, so the embedded .so
      // extraction survives a noexec /tmp. Respects an operator-set extractPath/libraryPath.
      if (profilerDataDir != null
          && !profilerDataDir.isEmpty()
          && System.getProperty("one.profiler.extractPath") == null
          && System.getProperty("one.profiler.libraryPath") == null) {
        File extractDir = new File(profilerDataDir);
        if (extractDir.exists() || extractDir.mkdirs()) {
          System.setProperty("one.profiler.extractPath", extractDir.getAbsolutePath());
        }
      }
      // getInstance() loads the native library. Process-wide singleton: one session at a time.
      profiler = AsyncProfiler.getInstance();
      available = true;
      logger.info("async-profiler loaded successfully (version: " + profiler.getVersion() + ")");
    } catch (Throwable e) {
      logger.log(
          Level.WARNING,
          "async-profiler native library not available, profiling disabled: "
              + e.getMessage()
              + ". The library is extracted into the profiler data dir ("
              + profilerDataDir
              + "); if that is on a noexec filesystem (\"failed to map segment\"), set"
              + " OTEL_AWS_PROFILER_DATA_DIR to a directory that allows executing files.");
      available = false;
    }
  }

  /** Check if async-profiler is available. */
  public boolean isAvailable() {
    return available;
  }

  /**
   * Start the profiler.
   *
   * <p>Runs a single async-profiler session in the configured mode: {@code event=wall} (captures
   * both CPU-bound and I/O-bound activity, all thread states) or {@code event=cpu} (on-CPU only).
   * JFR output with {@code loop} enables automatic file rotation, which {@code
   * RotationBoundaryProcessor} scans at each boundary.
   *
   * <p>Note: async-profiler is a process-wide singleton, so only one session runs at a time — which
   * is why wall and cpu are selectable modes here rather than run together.
   */
  public void startProfiling() {
    if (!available) {
      return;
    }
    try {
      // Resolve JFR file path: use profilerDataDir if set, otherwise use jfrBasePath directly
      String effectiveJfrBasePath;
      if (profilerDataDir != null && !profilerDataDir.isEmpty()) {
        File dataDir = new File(profilerDataDir);
        if (!dataDir.exists() && !dataDir.mkdirs() && !dataDir.isDirectory()) {
          logger.warning("Could not create profiler data dir: " + dataDir.getAbsolutePath());
        }
        effectiveJfrBasePath = new File(dataDir, "profiler-jfr").getPath();
      } else {
        effectiveJfrBasePath = jfrBasePath;
        File jfrDir = new File(jfrBasePath).getParentFile();
        if (jfrDir != null && !jfrDir.exists() && !jfrDir.mkdirs() && !jfrDir.isDirectory()) {
          logger.warning("Could not create JFR output dir: " + jfrDir.getAbsolutePath());
        }
      }

      // Single async-profiler session in the configured mode; buildStartCommand assembles the
      // event/signal/interval/alloc/jfr/loop command string (see it for the wall-vs-cpu diffs).
      String command = buildStartCommand(effectiveJfrBasePath);
      profiler.execute(command);
      running = true;
      logger.info(
          "Started async-profiler (event="
              + (mode == MODE_CPU ? "cpu" : "wall")
              + ", interval="
              + (mode == MODE_CPU ? cpuIntervalMs : wallIntervalMs)
              + "ms, memory="
              + memoryEnabled
              + (memoryEnabled ? ", alloc=" + allocIntervalBytes + "B" : "")
              + ", jfr="
              + effectiveJfrBasePath
              + "-%t.jfr, loop="
              + loopSeconds
              + "s, dataDir="
              + profilerDataDir
              + ")");
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Failed to start async-profiler", e);
      running = false;
    }
  }

  /**
   * Build the async-profiler {@code start} command for the configured mode. cpu and wall differ
   * only in the event name, the wall-only {@code signal=26}, and the interval; the rest ({@code
   * ,threads,ann,jfr,file=…-%t.jfr,loop=…s}, plus {@code ,alloc=} when memory profiling is on) is
   * shared. Package-private for unit testing.
   */
  String buildStartCommand(String effectiveJfrBasePath) {
    // ",alloc=<bytes>" records jdk.ObjectAllocationInNewTLAB in the SAME JFR (one session, JFR-only
    // mode, which async-profiler 4.5 requires). cpu: perf_events → ctimer fallback, running threads
    // only. wall (signal=26): all threads, on- and off-CPU.
    String allocClause = memoryEnabled ? (",alloc=" + allocIntervalBytes) : "";
    String eventName = (mode == MODE_CPU) ? "cpu" : "wall";
    String signalClause = (mode == MODE_CPU) ? "" : ",signal=26";
    int effectiveIntervalMs = (mode == MODE_CPU) ? cpuIntervalMs : wallIntervalMs;
    return "start,event="
        + eventName
        + signalClause
        + ",interval="
        + (effectiveIntervalMs * 1_000_000L)
        + allocClause
        + ",threads"
        + ",ann"
        + ",jfr"
        + ",file="
        + effectiveJfrBasePath
        + "-%t.jfr"
        + ",loop="
        + loopSeconds
        + "s";
  }

  /**
   * Get the base path for JFR files.
   *
   * @return The JFR base path (without timestamp suffix)
   */
  public String getJfrBasePath() {
    if (profilerDataDir != null && !profilerDataDir.isEmpty()) {
      return new File(profilerDataDir, "profiler-jfr").getPath();
    }
    return jfrBasePath;
  }

  public int getWallIntervalMs() {
    return wallIntervalMs;
  }

  /** Profiling mode: {@link #MODE_WALL} or {@link #MODE_CPU}. */
  public int getMode() {
    return mode;
  }

  /**
   * Sampling interval (ms) of the primary event actually running — the cpu interval in {@link
   * #MODE_CPU}, otherwise the wall interval. This is the {@code period} of the emitted primary
   * Profile, so the per-sample value weight (period &times; coalescing count) matches the mode.
   */
  public int getPrimaryIntervalMs() {
    return mode == MODE_CPU ? cpuIntervalMs : wallIntervalMs;
  }

  /** Whether allocation profiling ({@code alloc=}) is enabled for this session. */
  public boolean isMemoryEnabled() {
    return memoryEnabled;
  }

  /**
   * async-profiler {@code alloc=} sampling interval in bytes. Also the {@code period} of the
   * emitted {@code alloc_space} Profile.
   */
  public long getAllocIntervalBytes() {
    return allocIntervalBytes;
  }

  /**
   * Clean up old JFR files that exceed the retention period.
   *
   * <p>Called periodically during JFR rotation to prevent unbounded file growth.
   */
  public void cleanupOldJfrFiles() {
    try {
      File baseFile = new File(getJfrBasePath());
      File dir = baseFile.getParentFile();
      if (dir == null) {
        dir = new File(".");
      }
      String prefix = baseFile.getName();

      if (!dir.isDirectory()) {
        return;
      }

      File[] jfrFiles =
          dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".jfr"));

      if (jfrFiles == null || jfrFiles.length == 0) {
        return;
      }

      long cutoffMs = System.currentTimeMillis() - ((long) loopSeconds * RETENTION_WINDOWS * 1000L);
      int deleted = 0;

      for (File f : jfrFiles) {
        if (f.lastModified() < cutoffMs) {
          if (f.delete()) {
            deleted++;
            logger.fine("Deleted old JFR file: " + f.getName());
          }
        }
      }

      if (deleted > 0) {
        logger.info("Cleaned up " + deleted + " old JFR file(s)");
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error cleaning up old JFR files", e);
    }
  }

  /**
   * Delete ALL matching JFR files unconditionally (no age cutoff).
   *
   * <p>Called on startup to ensure a clean start by removing stale files from previous runs.
   */
  public void deleteAllJfrFiles() {
    try {
      File baseFile = new File(getJfrBasePath());
      File dir = baseFile.getParentFile();
      if (dir == null) {
        dir = new File(".");
      }
      String prefix = baseFile.getName();

      if (!dir.isDirectory()) {
        return;
      }

      File[] jfrFiles =
          dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".jfr"));

      if (jfrFiles == null || jfrFiles.length == 0) {
        return;
      }

      int deleted = 0;
      for (File f : jfrFiles) {
        if (f.delete()) {
          deleted++;
        }
      }

      if (deleted > 0) {
        logger.info("Deleted " + deleted + " JFR file(s) from " + dir.getPath());
      }
    } catch (Throwable e) {
      logger.log(Level.WARNING, "Error deleting all JFR files on startup", e);
    }
  }

  /** Shut down the profiler and release resources. */
  public void shutdown() {
    if (!available) {
      return;
    }
    try {
      if (running) {
        profiler.stop();
        running = false;
        logger.info("Stopped async-profiler");
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error stopping async-profiler", e);
    }
  }

  /** Check if profiler is currently running. */
  public boolean isRunning() {
    return running;
  }
}
