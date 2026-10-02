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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ProfilesExporter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.AsyncProfilerWrapper;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.FrameInfo;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.OtlpProfileBuilder;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.SpanMetadata;

/**
 * Drives {@link RotationBoundaryProcessor#collect()} against a temp data dir seeded with two
 * fixture JFRs, exercising the full rotation path (rotation detection, {@code
 * findPreviousJfrFile}, {@code parseJfrFilenameTimestamp}, {@code processRotatedFile}, the scan,
 * and export) without a live async-profiler session — a fake wrapper reports available/running and
 * points at the temp dir.
 */
class RotationBoundaryProcessorCollectTest {

  /** Captures exported OTLP payloads instead of sending them. */
  private static final class CapturingExporter implements ProfilesExporter {
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

  /** Wrapper that reports available+running and serves JFRs from the temp data dir. */
  private static final class FakeWrapper extends AsyncProfilerWrapper {
    FakeWrapper(Path dataDir) {
      this(dataDir, MODE_WALL);
    }

    FakeWrapper(Path dataDir, int mode) {
      super(10, 10, "profiler-jfr", dataDir.toString(), false, 524288L, 60, mode);
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isRunning() {
      return true;
    }
  }

  private static Path fixture() throws Exception {
    return Path.of(
        RotationBoundaryProcessorCollectTest.class.getResource("/wall-alloc-sample.jfr").toURI());
  }

  @Test
  void collect_onRotation_processesCompletedFile_andExports(@TempDir Path dir) throws Exception {
    // Two rotated files; when the newer appears the OLDER (completed) one is processed exactly once.
    Files.copy(fixture(), dir.resolve("profiler-jfr-20260101-000000.jfr"));
    Files.copy(fixture(), dir.resolve("profiler-jfr-20260101-000100.jfr"));

    CapturingExporter exporter = new CapturingExporter();
    RotationBoundaryProcessor proc =
        new RotationBoundaryProcessor(10_000, new FakeWrapper(dir), 60, null, exporter, false, 0);

    proc.collect();

    assertEquals(1, exporter.payloads.size(), "exactly the one completed file is exported");
    ExportProfilesServiceRequest req =
        ExportProfilesServiceRequest.parseFrom(exporter.payloads.get(0));
    assertTrue(req.getResourceProfilesCount() > 0, "export carries profiles");
    assertTrue(
        req.getResourceProfiles(0).getScopeProfiles(0).getProfilesCount() > 0,
        "at least the primary wall profile is present");

    // A second collect() with no new file must not re-process (no duplicate export).
    proc.collect();
    assertEquals(1, exporter.payloads.size(), "already-known files are not re-processed");
  }

  @Test
  void collect_whenProfilerNotRunning_isNoOp(@TempDir Path dir) throws Exception {
    Files.copy(fixture(), dir.resolve("profiler-jfr-20260101-000000.jfr"));
    Files.copy(fixture(), dir.resolve("profiler-jfr-20260101-000100.jfr"));

    CapturingExporter exporter = new CapturingExporter();
    // A real wrapper that was never started reports isRunning()==false -> collect() short-circuits
    // after cleanup, so nothing is scanned or exported.
    AsyncProfilerWrapper notRunning =
        new AsyncProfilerWrapper(10, 10, "profiler-jfr", dir.toString(), false, 524288L, 60, 0);
    RotationBoundaryProcessor proc =
        new RotationBoundaryProcessor(10_000, notRunning, 60, null, exporter, false, 0);

    proc.collect();

    assertTrue(exporter.payloads.isEmpty(), "no processing while the profiler is not running");
  }

  @Test
  void collect_cpuMode_emitsCpuPrimaryProfile(@TempDir Path dir) throws Exception {
    Files.copy(fixture(), dir.resolve("profiler-jfr-20260101-000000.jfr"));
    Files.copy(fixture(), dir.resolve("profiler-jfr-20260101-000100.jfr"));

    CapturingExporter exporter = new CapturingExporter();
    RotationBoundaryProcessor proc =
        new RotationBoundaryProcessor(
            10_000,
            new FakeWrapper(dir, AsyncProfilerWrapper.MODE_CPU),
            60,
            null,
            exporter,
            false,
            0);

    proc.collect();

    assertEquals(1, exporter.payloads.size());
    ExportProfilesServiceRequest req =
        ExportProfilesServiceRequest.parseFrom(exporter.payloads.get(0));
    // In cpu mode the scan drives the cpu primary-type path (no per-sample thread.state attribute).
    assertTrue(req.getResourceProfilesCount() > 0);
  }

  @Test
  void collect_nullWrapper_isNoOp() {
    CapturingExporter exporter = new CapturingExporter();
    RotationBoundaryProcessor proc =
        new RotationBoundaryProcessor(10_000, null, 60, null, exporter, false, 0);
    proc.collect(); // must not throw; cleanup + guards short-circuit on a null wrapper
    assertTrue(exporter.payloads.isEmpty());
  }

  @Test
  void collect_singleFile_noPreviousToProcess(@TempDir Path dir) throws Exception {
    // Only one file present -> no completed predecessor -> nothing processed yet.
    Files.copy(fixture(), dir.resolve("profiler-jfr-20260101-000000.jfr"));
    CapturingExporter exporter = new CapturingExporter();
    RotationBoundaryProcessor proc =
        new RotationBoundaryProcessor(10_000, new FakeWrapper(dir), 60, null, exporter, false, 0);
    proc.collect();
    assertTrue(exporter.payloads.isEmpty(), "first file has no predecessor to export");
  }

  @Test
  void collect_manyFiles_prunesKnownSet_andSurvivesUnparseableFiles(@TempDir Path dir)
      throws Exception {
    // >20 rotated files drives the knownJfrFiles prune branch; the files are empty so each
    // predecessor scan fails to parse (JfrReader throws IOException) and is caught — collect() must
    // not throw and must export nothing.
    for (int i = 0; i < 22; i++) {
      Files.write(dir.resolve(String.format("profiler-jfr-seq-%02d.jfr", i)), new byte[] {0});
    }
    CapturingExporter exporter = new CapturingExporter();
    RotationBoundaryProcessor proc =
        new RotationBoundaryProcessor(10_000, new FakeWrapper(dir), 60, null, exporter, false, 0);

    proc.collect(); // must not throw despite unparseable files

    assertTrue(exporter.payloads.isEmpty(), "no valid JFRs -> nothing exported");
  }

  @Test
  void scanAndIndex_onGarbageJfr_areCaught_returnZero(@TempDir Path dir) throws Exception {
    // A file that isn't a valid JFR -> JfrReader ctor throws IOException -> caught, returns 0.
    Path garbage = dir.resolve("profiler-jfr-bad.jfr");
    Files.write(garbage, new byte[] {1, 2, 3, 4, 5});
    RotationBoundaryProcessor proc =
        new RotationBoundaryProcessor(10_000, null, 60, null, null, false, 0);

    OtlpProfileBuilder builder = new OtlpProfileBuilder(0L, 60_000_000_000L, 10_000_000L);
    Map<String, TreeMap<Long, SpanMetadata>> spanIndex = new HashMap<>();

    assertEquals(0, proc.indexSpansFromJfr(garbage, spanIndex), "no spans from a garbage file");
    int scanned =
        proc.scanJfrFileSinglePass(
            garbage, spanIndex, builder, new HashMap<Integer, List<FrameInfo>>());
    assertEquals(0, scanned, "no samples from a garbage file");
    assertEquals(0, builder.getSampleCount());
  }

  @Test
  void parseTimestamp_validAndInvalid() {
    assertTrue(RotationBoundaryProcessor.parseTimestamp("20260309-172942") > 0, "valid %t parses");
    assertEquals(-1L, RotationBoundaryProcessor.parseTimestamp("not-a-timestamp"), "bad -> -1");
  }

  @Test
  void parseJfrFilenameTimestamp_parsesValidName_elseFallsBackToLastModified(@TempDir Path dir)
      throws Exception {
    RotationBoundaryProcessor proc =
        new RotationBoundaryProcessor(10_000, null, 60, null, null, false, 0);

    // Valid %t name -> parsed epoch (not the file mtime).
    File good = dir.resolve("profiler-jfr-20260309-172942.jfr").toFile();
    Files.createFile(good.toPath());
    long parsed = proc.parseJfrFilenameTimestamp(good);
    assertEquals(RotationBoundaryProcessor.parseTimestamp("20260309-172942"), parsed);

    // No ".jfr" -> lastModified fallback.
    File noExt = dir.resolve("profiler-jfr-20260309-172942").toFile();
    Files.createFile(noExt.toPath());
    assertTrue(noExt.setLastModified(1_234_000L));
    assertEquals(1_234_000L, proc.parseJfrFilenameTimestamp(noExt));

    // Too-short name before ".jfr" -> lastModified fallback.
    File shortName = dir.resolve("x.jfr").toFile();
    Files.createFile(shortName.toPath());
    assertTrue(shortName.setLastModified(5_678_000L));
    assertEquals(5_678_000L, proc.parseJfrFilenameTimestamp(shortName));

    // Right length but unparseable timestamp -> lastModified fallback.
    File badTs = dir.resolve("profiler-jfr-NOTADATE-XXXXXX.jfr").toFile();
    Files.createFile(badTs.toPath());
    assertTrue(badTs.setLastModified(9_999_000L));
    assertEquals(9_999_000L, proc.parseJfrFilenameTimestamp(badTs));
  }
}
