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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.BaseCollector;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.RotationBoundaryProcessor;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ProfilesExporter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.AsyncProfilerWrapper;

/**
 * When profiler initialization fails after async-profiler has started (e.g. the exporter or the
 * collector thread cannot be created), nothing may be left running: otherwise async-profiler keeps
 * writing JFR files that nothing deletes.
 */
class ServiceEventsProfilerFailedStartTest {

  private static final class RecordingWrapper extends AsyncProfilerWrapper {
    boolean started;
    boolean stopped;
    boolean filesDeleted;

    RecordingWrapper(Path dir) {
      super(10, 10, "profiler-jfr", dir.toString(), false, 524288L, 60, MODE_WALL);
    }

    @Override
    public void startProfiling() {
      started = true;
    }

    @Override
    public void shutdown() {
      stopped = true;
    }

    @Override
    public void deleteAllJfrFiles() {
      filesDeleted = true;
    }
  }

  private static final class RecordingExporter implements ProfilesExporter {
    boolean shutdown;

    @Override
    public boolean export(byte[] payload) {
      return true;
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public String getEndpoint() {
      return "test://recording";
    }
  }

  @Test
  void cleanupFailedProfilerStart_stopsEverythingThatWasStarted(@TempDir Path dir) {
    RecordingWrapper wrapper = new RecordingWrapper(dir);
    RecordingExporter exporter = new RecordingExporter();
    RotationBoundaryProcessor processor =
        new RotationBoundaryProcessor(10_000, wrapper, 60, null, exporter, 0);
    List<BaseCollector> collectors = new ArrayList<>();
    collectors.add(processor);
    processor.start();
    assertTrue(processor.isRunning());

    ServiceEventsInstrumentation.cleanupFailedProfilerStart(
        collectors, processor, wrapper, exporter);

    assertFalse(processor.isRunning(), "collector thread stopped");
    assertTrue(collectors.isEmpty(), "collector removed");
    assertTrue(wrapper.stopped, "async-profiler stopped");
    assertTrue(wrapper.filesDeleted, "its JFR files deleted");
    assertTrue(exporter.shutdown, "exporter closed");
  }

  /** The exporter cannot be created after async-profiler started: the profiler is stopped. */
  @Test
  void startProfilerPipeline_exporterFails_stopsProfiler(@TempDir Path dir) {
    RecordingWrapper wrapper = new RecordingWrapper(dir);
    List<BaseCollector> collectors = new ArrayList<>();
    RuntimeException failure = new RuntimeException("no exporter");

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                ServiceEventsInstrumentation.startProfilerPipeline(
                    wrapper,
                    () -> {
                      throw failure;
                    },
                    exporter -> {
                      throw new AssertionError("processor must not be created");
                    },
                    collectors));

    assertSame(failure, thrown);
    assertTrue(wrapper.started);
    assertTrue(wrapper.stopped, "async-profiler stopped");
    assertTrue(wrapper.filesDeleted, "its JFR files deleted");
    assertTrue(collectors.isEmpty());
  }

  /** The processor cannot be created: profiler stopped and the exporter closed. */
  @Test
  void startProfilerPipeline_processorFails_stopsProfilerAndClosesExporter(@TempDir Path dir) {
    RecordingWrapper wrapper = new RecordingWrapper(dir);
    RecordingExporter exporter = new RecordingExporter();
    List<BaseCollector> collectors = new ArrayList<>();

    assertThrows(
        OutOfMemoryError.class,
        () ->
            ServiceEventsInstrumentation.startProfilerPipeline(
                wrapper,
                () -> exporter,
                e -> {
                  throw new OutOfMemoryError("unable to create native thread");
                },
                collectors));

    assertTrue(wrapper.stopped);
    assertTrue(exporter.shutdown, "exporter closed");
    assertTrue(collectors.isEmpty());
  }

  /** On success nothing is undone and the processor is running and registered. */
  @Test
  void startProfilerPipeline_success_leavesEverythingRunning(@TempDir Path dir) {
    RecordingWrapper wrapper = new RecordingWrapper(dir);
    RecordingExporter exporter = new RecordingExporter();
    List<BaseCollector> collectors = new ArrayList<>();

    ServiceEventsInstrumentation.ProfilerPipeline pipeline =
        ServiceEventsInstrumentation.startProfilerPipeline(
            wrapper,
            () -> exporter,
            e -> new RotationBoundaryProcessor(10_000, wrapper, 60, null, e, 0),
            collectors);
    try {
      assertSame(exporter, pipeline.exporter);
      assertTrue(pipeline.processor.isRunning());
      assertEquals(1, collectors.size());
      assertFalse(wrapper.stopped);
      assertFalse(exporter.shutdown);
    } finally {
      pipeline.processor.stop();
    }
  }

  /** Failure before the processor or exporter existed: only the profiler is stopped. */
  @Test
  void cleanupFailedProfilerStart_toleratesPartialStart(@TempDir Path dir) {
    RecordingWrapper wrapper = new RecordingWrapper(dir);
    List<BaseCollector> collectors = new ArrayList<>();

    ServiceEventsInstrumentation.cleanupFailedProfilerStart(collectors, null, wrapper, null);
    ServiceEventsInstrumentation.cleanupFailedProfilerStart(collectors, null, null, null);

    assertTrue(wrapper.stopped);
    assertEquals(0, collectors.size());
  }
}
