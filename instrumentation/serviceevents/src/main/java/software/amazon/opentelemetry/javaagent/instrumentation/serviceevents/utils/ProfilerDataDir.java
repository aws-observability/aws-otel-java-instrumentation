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
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Operational-safety helpers for the profiler's local data directory (disk &amp; permissions).
 *
 * <p>Pure, side-effect-scoped static utilities so they can be unit-tested against temp dirs. They
 * back the profiler's host-safety guarantees:
 *
 * <ul>
 *   <li>{@link #probeWritable(File)} — a startup writability pre-check (create → write → delete a
 *       probe file) so an unwritable data dir becomes a clean "disabled" decision, not a downstream
 *       {@code SEVERE} on every rotation.
 *   <li>{@link #enforceByteBudget(File, FilenameFilter, long)} — a total-bytes cap on the per-PID
 *       dir on top of time-based retention, evicting oldest matches first (never the newest match,
 *       which may still be written).
 * </ul>
 *
 * <p>Both operate only on the profiler's <em>own</em> per-PID data dir — they never touch another
 * process's files. Everything here works on Java 8+ (no {@code ProcessHandle}, no NIO-2 features
 * unavailable there) since the profiler supports Java 8+.
 */
public final class ProfilerDataDir {

  private static final Logger logger = Logger.getLogger(ProfilerDataDir.class.getName());

  private ProfilerDataDir() {}

  /**
   * Pre-flight writability check for {@code dir}: create the directory if needed, then create,
   * write to, and delete a probe file inside it. Returns {@code true} only if all steps succeed.
   *
   * <p>Never throws — any failure returns {@code false} so the caller can no-op the profiler with a
   * clear log instead of failing on every write later.
   */
  public static boolean probeWritable(File dir) {
    if (dir == null) {
      return false;
    }
    try {
      if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
        // mkdirs() returns false if another thread/JVM created it concurrently — isDirectory()
        // re-check disambiguates that benign race from a real failure.
        return false;
      }
      if (!dir.isDirectory()) {
        return false;
      }
      File probe = File.createTempFile("aws-profiler-probe", ".tmp", dir);
      try {
        Files.write(probe.toPath(), new byte[] {0});
      } finally {
        // Best-effort delete; leftover probe files are tiny and swept by the byte budget anyway.
        if (!probe.delete()) {
          probe.deleteOnExit();
        }
      }
      return true;
    } catch (IOException | RuntimeException e) {
      logger.log(Level.FINE, "Data-dir writability probe failed for " + dir, e);
      return false;
    }
  }

  /**
   * Restrict {@code dir} to owner-only access (POSIX 0700). The profiler's JFR recordings (written
   * 0644 by async-profiler) carry stack traces, operation names, thread names, and trace/span ids;
   * a 0700 per-PID dir means other local users can't traverse into it to read them. Best-effort:
   * POSIX {@code chmod 700} where supported, else the {@link File} permission API; never throws.
   */
  public static void restrictToOwner(File dir) {
    if (dir == null || !dir.isDirectory()) {
      return;
    }
    try {
      Files.setPosixFilePermissions(
          dir.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
      return;
    } catch (UnsupportedOperationException e) {
      // Non-POSIX filesystem — fall through to the File API.
    } catch (IOException | RuntimeException e) {
      logger.log(Level.FINE, "Could not chmod 700 " + dir + "; trying File permission API", e);
    }
    // Java-8-safe fallback: drop group/other, then re-grant owner rwx.
    dir.setReadable(false, false);
    dir.setWritable(false, false);
    dir.setExecutable(false, false);
    dir.setReadable(true, true);
    dir.setWritable(true, true);
    dir.setExecutable(true, true);
  }

  /**
   * Evict oldest files (by last-modified) matching {@code filter} in {@code dir} until the total
   * size of matching files is at most {@code maxBytes}. The single newest matching file is never
   * deleted (it may be the actively-written one). A {@code maxBytes <= 0} disables the budget
   * (no-op). Returns the number of bytes reclaimed.
   *
   * <p>The filter must match only the profiler's own rotating data files (e.g. {@code *.jfr}) so
   * this never reclaims the extracted native library that shares the dir.
   */
  public static long enforceByteBudget(File dir, FilenameFilter filter, long maxBytes) {
    if (dir == null || maxBytes <= 0 || !dir.isDirectory()) {
      return 0L;
    }
    File[] matches = dir.listFiles(filter);
    if (matches == null || matches.length <= 1) {
      return 0L; // nothing to do, or only the (protected) newest file
    }
    long total = 0L;
    for (File f : matches) {
      total += f.length();
    }
    if (total <= maxBytes) {
      return 0L;
    }
    // Oldest first; keep the newest match untouched.
    List<File> ordered = new ArrayList<>(Arrays.asList(matches));
    ordered.sort(Comparator.comparingLong(File::lastModified));
    long freed = 0L;
    for (int i = 0; i < ordered.size() - 1 && total - freed > maxBytes; i++) {
      File f = ordered.get(i);
      long len = f.length();
      if (f.delete()) {
        freed += len;
        logger.fine("Byte-budget eviction: deleted " + f.getName() + " (" + len + " bytes)");
      }
    }
    if (freed > 0) {
      logger.info(
          "Profiler data dir exceeded "
              + maxBytes
              + " bytes; reclaimed "
              + freed
              + " bytes (oldest files first)");
    }
    return freed;
  }
}
