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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RollingFileChannelTest {

  @TempDir Path tmpDir;

  @Test
  void belowThreshold_noRollover() throws Exception {
    Path file = tmpDir.resolve("svc.log");
    long maxBytes = 1024L; // 1 KiB
    int backupCount = 5;

    String line = repeat('a', 100); // 100 + 1 newline = 101 bytes per line
    try (RollingFileChannel ch = new RollingFileChannel(file, maxBytes, backupCount)) {
      // 5 lines = 505 bytes < 1024
      for (int i = 0; i < 5; i++) {
        ch.writeLines(Collections.singletonList(line));
      }
      ch.flush();
    }

    assertTrue(Files.exists(file));
    assertFalse(Files.exists(backup(file, 1)), "no rollover should have occurred");
    assertEquals(5L * (line.length() + 1), Files.size(file));
  }

  @Test
  void atThreshold_singleRollover() throws Exception {
    Path file = tmpDir.resolve("svc.log");
    long maxBytes = 200L;
    int backupCount = 5;

    String preLine = repeat('p', 99); // 99 + 1 = 100 bytes
    String postLine = repeat('q', 19); // 19 + 1 = 20 bytes
    try (RollingFileChannel ch = new RollingFileChannel(file, maxBytes, backupCount)) {
      // Two preLines = 200 bytes, equal to threshold -> rollover after the second.
      ch.writeLines(Arrays.asList(preLine, preLine));
      // The next write happens against the freshly opened file.
      ch.writeLines(Collections.singletonList(postLine));
      ch.flush();
    }

    assertTrue(Files.exists(file));
    assertTrue(Files.exists(backup(file, 1)));
    assertFalse(Files.exists(backup(file, 2)));

    List<String> mainLines = Files.readAllLines(file, StandardCharsets.UTF_8);
    assertEquals(Collections.singletonList(postLine), mainLines);

    List<String> backupLines = Files.readAllLines(backup(file, 1), StandardCharsets.UTF_8);
    assertEquals(Arrays.asList(preLine, preLine), backupLines);
  }

  @Test
  void backupCap_dropsOldest() throws Exception {
    Path file = tmpDir.resolve("svc.log");
    long maxBytes = 100L;
    int backupCount = 5;

    // Drive 6 rotations, tagging each batch so we can verify which backups are retained.
    try (RollingFileChannel ch = new RollingFileChannel(file, maxBytes, backupCount)) {
      for (int i = 1; i <= 6; i++) {
        // Single line big enough to cross the threshold immediately.
        ch.writeLines(Collections.singletonList("batch=" + i + "-" + repeat('x', 100)));
      }
      // Add a small line so the post-final-rotation main file is non-empty for assertions.
      ch.writeLines(Collections.singletonList("tail"));
      ch.flush();
    }

    assertTrue(Files.exists(file));
    for (int i = 1; i <= backupCount; i++) {
      assertTrue(Files.exists(backup(file, i)), "expected " + backup(file, i) + " to exist");
    }
    assertFalse(Files.exists(backup(file, backupCount + 1)), "backup beyond cap must not exist");

    // After 6 rotations:
    //   batch=1 was rotated to file.1, then shifted -> file.2, ... -> file.5, then dropped.
    //   batch=2 ended up in file.5 after the final rotation.
    //   batch=6 (most recent rotation) ended up in file.1.
    assertTrue(readFirstLine(backup(file, 1)).startsWith("batch=6-"));
    assertTrue(readFirstLine(backup(file, 5)).startsWith("batch=2-"));
    // batch=1 must be gone everywhere.
    for (int i = 1; i <= backupCount; i++) {
      String firstLine = readFirstLine(backup(file, i));
      assertFalse(
          firstLine.startsWith("batch=1-"), "batch=1 should have been dropped: " + firstLine);
    }
  }

  @Test
  void concurrentWritesDuringRollover() throws Exception {
    Path file = tmpDir.resolve("svc.log");
    long maxBytes = 4 * 1024L; // 4 KiB triggers rotations quickly
    int backupCount = 5;
    int threadCount = 4;
    int linesPerThread = 200;

    // Mirror the production caller: ServiceEventsFileWriter serializes via a ReentrantLock.
    ReentrantLock lock = new ReentrantLock();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threadCount);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    try (RollingFileChannel ch = new RollingFileChannel(file, maxBytes, backupCount)) {
      List<Thread> threads = new ArrayList<>();
      for (int t = 0; t < threadCount; t++) {
        final int threadId = t;
        Thread thread =
            new Thread(
                () -> {
                  try {
                    start.await();
                    for (int i = 0; i < linesPerThread; i++) {
                      String marker = "T" + threadId + "-" + i + "-" + repeat('z', 40);
                      lock.lock();
                      try {
                        ch.writeLines(Collections.singletonList(marker));
                      } finally {
                        lock.unlock();
                      }
                    }
                  } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                  } finally {
                    done.countDown();
                  }
                },
                "rolling-file-test-" + t);
        thread.start();
        threads.add(thread);
      }

      start.countDown();
      assertTrue(done.await(30, TimeUnit.SECONDS), "writer threads timed out");

      lock.lock();
      try {
        ch.flush();
      } finally {
        lock.unlock();
      }

      for (Thread thread : threads) {
        thread.join();
      }
    }

    assertNull(failure.get(), "no IOException should be observed during concurrent writes");

    // Collect every line across the main file and all backups, in any order.
    List<Path> sources = new ArrayList<>();
    sources.add(file);
    for (int i = 1; i <= backupCount; i++) {
      Path b = backup(file, i);
      if (Files.exists(b)) {
        sources.add(b);
      }
    }

    Pattern shape = Pattern.compile("^T(\\d+)-(\\d+)-z+$");
    Set<String> seen = new HashSet<>();
    int totalLines = 0;
    for (Path src : sources) {
      List<String> lines = Files.readAllLines(src, StandardCharsets.UTF_8);
      for (String line : lines) {
        Matcher m = shape.matcher(line);
        // A torn line would fail to match the strict pattern.
        assertTrue(m.matches(), "torn or malformed line: '" + line + "' from " + src);
        seen.add(line);
        totalLines++;
      }
    }

    // Each unique marker should appear at most once: duplicates would imply a write was replayed.
    assertEquals(totalLines, seen.size(), "duplicate lines detected across rotations");

    // The most recent backup retention bucket can drop earlier rotations, so we don't require
    // every marker to be present. We do require that the surviving lines are well-formed and that
    // we observed at least one rollover (multiple files in `sources`).
    assertTrue(sources.size() > 1, "expected at least one rollover to have occurred");
    assertTrue(totalLines > 0, "expected at least some lines to survive");
  }

  private static Path backup(Path file, int index) {
    return file.resolveSibling(file.getFileName().toString() + "." + index);
  }

  private static String repeat(char c, int n) {
    char[] buf = new char[n];
    Arrays.fill(buf, c);
    return new String(buf);
  }

  private static String readFirstLine(Path p) throws IOException {
    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
    return lines.isEmpty() ? "" : lines.get(0);
  }
}
