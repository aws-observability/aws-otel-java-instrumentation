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
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link ProfilerDataDir} — the local data-dir hardening helpers. */
class ProfilerDataDirTest {

  @TempDir File tmp;

  private static final FilenameFilter JFR = (d, name) -> name.endsWith(".jfr");

  private File writeFile(File dir, String name, int bytes, long lastModified) throws IOException {
    File f = new File(dir, name);
    Files.write(f.toPath(), new byte[bytes]);
    assertTrue(f.setLastModified(lastModified), "should be able to set mtime in test");
    return f;
  }

  // --- probeWritable ---

  @Test
  void probeWritable_writableDir_returnsTrue_andLeavesNoProbeFile() {
    assertTrue(ProfilerDataDir.probeWritable(tmp));
    File[] leftovers = tmp.listFiles();
    assertTrue(leftovers != null && leftovers.length == 0, "probe file must be cleaned up");
  }

  @Test
  void probeWritable_createsMissingDir() {
    File missing = new File(tmp, "a/b/c");
    assertFalse(missing.exists());
    assertTrue(ProfilerDataDir.probeWritable(missing));
    assertTrue(missing.isDirectory());
  }

  @Test
  void probeWritable_pathIsARegularFile_returnsFalse() throws IOException {
    File asFile = new File(tmp, "not-a-dir");
    Files.write(asFile.toPath(), new byte[] {1});
    assertFalse(ProfilerDataDir.probeWritable(asFile));
  }

  @Test
  void probeWritable_null_returnsFalse() {
    assertFalse(ProfilerDataDir.probeWritable(null));
  }

  // --- enforceByteBudget ---

  @Test
  void enforceByteBudget_evictsOldestUntilUnderBudget_keepsNewest() throws IOException {
    File f1 = writeFile(tmp, "profiler-jfr-1.jfr", 100, 1_000L);
    File f2 = writeFile(tmp, "profiler-jfr-2.jfr", 100, 2_000L);
    File f3 = writeFile(tmp, "profiler-jfr-3.jfr", 100, 3_000L);
    File f4 = writeFile(tmp, "profiler-jfr-4.jfr", 100, 4_000L);

    long freed = ProfilerDataDir.enforceByteBudget(tmp, JFR, 250);

    assertEquals(200, freed, "should evict the two oldest (100+100) to get 400 -> 200 <= 250");
    assertFalse(f1.exists());
    assertFalse(f2.exists());
    assertTrue(f3.exists());
    assertTrue(f4.exists(), "newest is always kept");
  }

  @Test
  void enforceByteBudget_underBudget_isNoop() throws IOException {
    File f1 = writeFile(tmp, "profiler-jfr-1.jfr", 100, 1_000L);
    File f2 = writeFile(tmp, "profiler-jfr-2.jfr", 100, 2_000L);
    assertEquals(0, ProfilerDataDir.enforceByteBudget(tmp, JFR, 10_000));
    assertTrue(f1.exists() && f2.exists());
  }

  @Test
  void enforceByteBudget_zeroBudget_disabled() throws IOException {
    File f1 = writeFile(tmp, "profiler-jfr-1.jfr", 100, 1_000L);
    assertEquals(0, ProfilerDataDir.enforceByteBudget(tmp, JFR, 0));
    assertTrue(f1.exists());
  }

  @Test
  void enforceByteBudget_neverDeletesNonMatchingFiles() throws IOException {
    // The extracted native library shares the dir — the .jfr filter must never touch it.
    File so = writeFile(tmp, "libasyncProfiler.so", 10_000, 500L);
    writeFile(tmp, "profiler-jfr-1.jfr", 100, 1_000L);
    File jfr2 = writeFile(tmp, "profiler-jfr-2.jfr", 100, 2_000L);

    ProfilerDataDir.enforceByteBudget(tmp, JFR, 1); // aggressively small budget

    assertTrue(so.exists(), ".so must never be evicted");
    assertTrue(jfr2.exists(), "newest .jfr is protected");
  }

  @Test
  void enforceByteBudget_singleMatch_neverDeleted() throws IOException {
    File only = writeFile(tmp, "profiler-jfr-1.jfr", 10_000, 1_000L);
    assertEquals(0, ProfilerDataDir.enforceByteBudget(tmp, JFR, 1));
    assertTrue(only.exists(), "the single (active) file must never be evicted");
  }

  // --- restrictToOwner ---

  @Test
  void restrictToOwner_setsOwnerOnlyPosixPerms() throws IOException {
    File dir = new File(tmp, "pid-123");
    assertTrue(dir.mkdirs());
    ProfilerDataDir.restrictToOwner(dir);
    java.nio.file.attribute.PosixFileAttributeView view =
        java.nio.file.Files.getFileAttributeView(
            dir.toPath(), java.nio.file.attribute.PosixFileAttributeView.class);
    // Linux/macOS (the only platforms the profiler runs on) are POSIX; skip elsewhere.
    org.junit.jupiter.api.Assumptions.assumeTrue(view != null, "non-POSIX filesystem");
    assertEquals(
        "rwx------",
        java.nio.file.attribute.PosixFilePermissions.toString(view.readAttributes().permissions()),
        "per-PID dir must be owner-only (0700) so other local users can't read recordings");
  }
}
