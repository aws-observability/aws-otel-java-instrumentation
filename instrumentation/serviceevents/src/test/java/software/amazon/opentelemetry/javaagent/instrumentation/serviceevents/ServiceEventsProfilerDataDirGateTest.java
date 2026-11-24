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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.config.ServiceEventsConfig;

/**
 * Wiring test for the data-dir writability gate in {@link ServiceEventsInstrumentation}: an
 * unwritable profiler data dir must make the profiler a clean no-op (WARN + return) — it must
 * <b>not</b> start async-profiler or the {@code RotationBoundaryProcessor}.
 *
 * <p>Drives the real {@link ServiceEventsInstrumentation#initialize()} in <b>profiler-only</b> mode
 * (ServiceEvents disabled, profiler enabled) so the ServiceEvents signal setup is skipped, and
 * asserts on the captured logs. The unwritable path returns from {@code initializeProfiler()}
 * before async-profiler is ever touched, so this never loads the native library.
 */
class ServiceEventsProfilerDataDirGateTest {

  private static final Logger INSTR_LOGGER =
      Logger.getLogger(ServiceEventsInstrumentation.class.getName());

  @TempDir File tmp;

  private final List<LogRecord> records = new CopyOnWriteArrayList<>();
  private Handler handler;
  private Level savedLevel;
  private boolean savedUseParent;

  @BeforeEach
  void setUp() {
    ServiceEventsInstrumentation.reset();
    records.clear();
    handler =
        new Handler() {
          @Override
          public void publish(LogRecord r) {
            records.add(r);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    handler.setLevel(Level.ALL);
    savedLevel = INSTR_LOGGER.getLevel();
    savedUseParent = INSTR_LOGGER.getUseParentHandlers();
    INSTR_LOGGER.setLevel(Level.ALL);
    INSTR_LOGGER.setUseParentHandlers(false); // keep INFO chatter off the test console
    INSTR_LOGGER.addHandler(handler);
  }

  @AfterEach
  void tearDown() {
    INSTR_LOGGER.removeHandler(handler);
    INSTR_LOGGER.setLevel(savedLevel);
    INSTR_LOGGER.setUseParentHandlers(savedUseParent);
    ServiceEventsInstrumentation.reset();
  }

  private boolean logged(String needle) {
    for (LogRecord r : records) {
      String m = r.getMessage();
      if (m != null && m.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  @Test
  void unwritableDataDir_disablesProfilerCleanly_withoutStarting() throws IOException {
    // Make the resolved per-PID dir unwritable portably (root-safe, unlike setWritable(false)):
    // put a regular file where a parent directory would need to be, so mkdirs()/the probe fail.
    File blocker = new File(tmp, "blocker");
    Files.write(blocker.toPath(), new byte[] {1});
    String unwritableDataDir = new File(blocker, "profiler").getPath(); // sits under a regular file

    ServiceEventsConfig config =
        new ServiceEventsConfig.Builder()
            .enabled(false) // profiler-only mode: skip ServiceEvents signal setup
            .asyncProfilerEnabled(true)
            .profilerDataDir(unwritableDataDir)
            .build();

    ServiceEventsInstrumentation.getInstance(config).initialize();

    assertTrue(
        logged("not writable") && logged("Profiler disabled"),
        "profiler must log the writability WARN and disable itself");
    // The gate returns before starting anything — neither the profiler session nor the collector.
    assertFalse(
        logged("Started RotationBoundaryProcessor"),
        "RotationBoundaryProcessor must not start when the data dir is unwritable");
    assertFalse(
        logged("Started async-profiler"),
        "async-profiler must not start when the data dir is unwritable");
  }
}
