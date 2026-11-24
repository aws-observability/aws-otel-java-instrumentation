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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Single-process size-bounded rolling file writer used by {@link ServiceEventsFileWriter}.
 *
 * <p>Writes UTF-8 text to {@code path}, rotating the file once {@link #maxBytes} bytes have been
 * written. Rotation moves {@code path} to {@code path.1}, shifting older backups by one slot up to
 * {@link #backupCount}; the oldest backup is dropped. A fresh writer is then opened at {@code
 * path}.
 *
 * <p>This class is <strong>not</strong> thread-safe. The caller is responsible for serializing
 * calls; {@link ServiceEventsFileWriter} does so under its own {@code ReentrantLock}.
 */
final class RollingFileChannel implements Closeable {

  static final long MAX_BYTES = 50L * 1024 * 1024;
  static final int BACKUP_COUNT = 5;

  private final Path path;
  private final long maxBytes;
  private final int backupCount;

  private BufferedWriter writer;
  private long bytesWritten;

  RollingFileChannel(Path path) throws IOException {
    this(path, MAX_BYTES, BACKUP_COUNT);
  }

  /** Test-only constructor exposing the rotation thresholds. */
  RollingFileChannel(Path path, long maxBytes, int backupCount) throws IOException {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be > 0");
    }
    if (backupCount < 0) {
      throw new IllegalArgumentException("backupCount must be >= 0");
    }
    this.path = path;
    this.maxBytes = maxBytes;
    this.backupCount = backupCount;
    this.writer = openWriter(path);
    this.bytesWritten = Files.exists(path) ? Files.size(path) : 0L;
  }

  void writeLines(List<String> lines) throws IOException {
    for (String line : lines) {
      writer.write(line);
      writer.write('\n');
      // Each character in the line plus the newline becomes one or more UTF-8 bytes.
      // Counting characters here under-counts for non-ASCII content, so use the encoded length
      // to keep the threshold honest when the workload contains multi-byte code points.
      bytesWritten += utf8Length(line) + 1L;
      if (bytesWritten >= maxBytes) {
        rollover();
      }
    }
  }

  void flush() throws IOException {
    writer.flush();
  }

  @Override
  public void close() throws IOException {
    try {
      writer.flush();
    } finally {
      writer.close();
    }
  }

  /**
   * Rotates the current file. Caller must hold the serializing lock and must not interleave writes
   * during rotation.
   */
  void rollover() throws IOException {
    // 1. Flush + close the current writer so the file handle is released before any move.
    try {
      writer.flush();
    } catch (IOException ignored) {
      // Continue with close; we still want to release the handle.
    }
    try {
      writer.close();
    } catch (IOException ignored) {
      // Best-effort close: a failed close still released the OS file descriptor on most platforms.
    }

    try {
      if (backupCount > 0) {
        // 2. Drop the oldest backup if it exists.
        Files.deleteIfExists(backupPath(backupCount));

        // 3. Shift backups up by one slot: file.<i> -> file.<i+1>
        for (int i = backupCount - 1; i >= 1; i--) {
          Path src = backupPath(i);
          if (Files.exists(src)) {
            Files.move(src, backupPath(i + 1), StandardCopyOption.REPLACE_EXISTING);
          }
        }

        // 4. Move the current file to file.1.
        if (Files.exists(path)) {
          Files.move(path, backupPath(1), StandardCopyOption.REPLACE_EXISTING);
        }
      } else {
        // No backups retained: just drop the current file's content on the floor.
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      // A move/delete failure leaves the on-disk state mid-rotation, but we still need a usable
      // writer so subsequent writes don't go through a closed handle. Reopen unconditionally;
      // bytesWritten is reset to the actual file size below.
      this.writer = openWriter(path);
      this.bytesWritten = Files.exists(path) ? Files.size(path) : 0L;
      throw e;
    }

    // 5. Reopen at the original path with CREATE + APPEND. The file no longer exists at this
    //    point so this is effectively a fresh CREATE.
    this.writer = openWriter(path);
    this.bytesWritten = 0L;
  }

  private Path backupPath(int index) {
    return path.resolveSibling(path.getFileName().toString() + "." + index);
  }

  private static BufferedWriter openWriter(Path path) throws IOException {
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    return Files.newBufferedWriter(path, UTF_8, CREATE, APPEND);
  }

  private static long utf8Length(String s) {
    // Compute the UTF-8 encoded length without allocating a byte[] for every line.
    long len = 0L;
    int i = 0;
    int n = s.length();
    while (i < n) {
      int cp = s.codePointAt(i);
      if (cp < 0x80) {
        len += 1;
      } else if (cp < 0x800) {
        len += 2;
      } else if (cp < 0x10000) {
        len += 3;
      } else {
        len += 4;
      }
      i += Character.charCount(cp);
    }
    return len;
  }
}
