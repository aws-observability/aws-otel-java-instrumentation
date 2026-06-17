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

import io.opentelemetry.sdk.common.CompletableResultCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reference-counted append-mode file writer keyed on absolute path.
 *
 * <p>Shared between {@link ServiceEventsCloudWatchLogFileExporter} and {@link
 * ServiceEventsCloudWatchMetricFileExporter} so log + metric lines addressed to the same path land
 * in one file without interleaving (a {@link ReentrantLock} guards each line write).
 */
final class ServiceEventsFileWriter {

  private static final Logger logger = Logger.getLogger(ServiceEventsFileWriter.class.getName());

  private static final Map<String, ServiceEventsFileWriter> WRITERS = new HashMap<>();
  private static final Object REGISTRY_LOCK = new Object();

  private final String absolutePath;
  private final RollingFileChannel writer;
  private final ReentrantLock writeLock = new ReentrantLock();
  private int refCount;

  private ServiceEventsFileWriter(String absolutePath, RollingFileChannel writer) {
    this.absolutePath = absolutePath;
    this.writer = writer;
    this.refCount = 1;
  }

  /**
   * Open or share the writer for {@code outputFilePath}. Returns {@code null} if the path could not
   * be opened (permission denied, disk full, etc.); callers must tolerate a null writer and avoid
   * propagating the failure into the customer application.
   */
  static ServiceEventsFileWriter acquire(String outputFilePath) {
    Path abs;
    String key;
    try {
      abs = Paths.get(outputFilePath).toAbsolutePath().normalize();
      key = abs.toString();
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "ServiceEvents: invalid output file path " + outputFilePath, e);
      return null;
    }
    synchronized (REGISTRY_LOCK) {
      ServiceEventsFileWriter existing = WRITERS.get(key);
      if (existing != null) {
        existing.refCount += 1;
        return existing;
      }
      try {
        Path parent = abs.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        RollingFileChannel bw = new RollingFileChannel(abs);
        ServiceEventsFileWriter created = new ServiceEventsFileWriter(key, bw);
        WRITERS.put(key, created);
        return created;
      } catch (IOException | RuntimeException e) {
        logger.log(Level.WARNING, "ServiceEvents: failed to open output file " + outputFilePath, e);
        return null;
      }
    }
  }

  void writeLines(List<String> lines) throws IOException {
    writeLock.lock();
    try {
      writer.writeLines(lines);
    } finally {
      writeLock.unlock();
    }
  }

  CompletableResultCode flush() {
    writeLock.lock();
    try {
      writer.flush();
      return CompletableResultCode.ofSuccess();
    } catch (IOException e) {
      logger.log(Level.WARNING, "ServiceEvents: failed to flush output file " + absolutePath, e);
      return CompletableResultCode.ofFailure();
    } finally {
      writeLock.unlock();
    }
  }

  CompletableResultCode release() {
    synchronized (REGISTRY_LOCK) {
      refCount -= 1;
      if (refCount > 0) {
        return CompletableResultCode.ofSuccess();
      }
      WRITERS.remove(absolutePath);
    }
    writeLock.lock();
    try {
      writer.flush();
      writer.close();
      return CompletableResultCode.ofSuccess();
    } catch (IOException e) {
      logger.log(Level.WARNING, "ServiceEvents: failed to close output file " + absolutePath, e);
      return CompletableResultCode.ofFailure();
    } finally {
      writeLock.unlock();
    }
  }

  /** Test-only: drop all cached writers and close their files. */
  static void resetForTests() {
    synchronized (REGISTRY_LOCK) {
      for (ServiceEventsFileWriter w : WRITERS.values()) {
        try {
          w.writer.close();
        } catch (IOException ignored) {
          // best-effort close during test teardown
        }
      }
      WRITERS.clear();
    }
  }
}
