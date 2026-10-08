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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * fixture JFRs, exercising the full rotation path (rotation detection, {@code findPreviousJfrFile},
 * {@code parseJfrFilenameTimestamp}, {@code processRotatedFile}, the scan, and export) without a
 * live async-profiler session — a fake wrapper reports available/running and points at the temp
 * dir.
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
    // Two rotated files; when the newer appears the OLDER (completed) one is processed exactly
    // once.
    Files.copy(fixture(), dir.resolve("profiler-jfr-20260101-000000.jfr"));
    Files.copy(fixture(), dir.resolve("profiler-jfr-20260101-000100.jfr"));

    CapturingExporter exporter = new CapturingExporter();
    RotationBoundaryProcessor proc =
        new RotationBoundaryProcessor(10_000, new FakeWrapper(dir), 60, null, exporter, 0);

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
        new RotationBoundaryProcessor(10_000, notRunning, 60, null, exporter, 0);

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
            10_000, new FakeWrapper(dir, AsyncProfilerWrapper.MODE_CPU), 60, null, exporter, 0);

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
        new RotationBoundaryProcessor(10_000, null, 60, null, exporter, 0);
    proc.collect(); // must not throw; cleanup + guards short-circuit on a null wrapper
    assertTrue(exporter.payloads.isEmpty());
  }

  @Test
  void collect_singleFile_noPreviousToProcess(@TempDir Path dir) throws Exception {
    // Only one file present -> no completed predecessor -> nothing processed yet.
    Files.copy(fixture(), dir.resolve("profiler-jfr-20260101-000000.jfr"));
    CapturingExporter exporter = new CapturingExporter();
    RotationBoundaryProcessor proc =
        new RotationBoundaryProcessor(10_000, new FakeWrapper(dir), 60, null, exporter, 0);
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
        new RotationBoundaryProcessor(10_000, new FakeWrapper(dir), 60, null, exporter, 0);

    proc.collect(); // must not throw despite unparseable files

    assertTrue(exporter.payloads.isEmpty(), "no valid JFRs -> nothing exported");
  }

  @Test
  void scanAndIndex_onGarbageJfr_areCaught_returnZero(@TempDir Path dir) throws Exception {
    // A file that isn't a valid JFR -> JfrReader ctor throws IOException -> caught, returns 0.
    Path garbage = dir.resolve("profiler-jfr-bad.jfr");
    Files.write(garbage, new byte[] {1, 2, 3, 4, 5});
    RotationBoundaryProcessor proc = new RotationBoundaryProcessor(10_000, null, 60, null, null, 0);

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
  void isNonJavaFrame_matchesAsyncProfilerClassification() {
    // async-profiler frame types: 0 interpreted, 1 JIT, 2 inlined, 3 native, 4 C++, 5 kernel,
    // 6 C1-compiled. In an async-profiler recording type 3 is a C function...
    boolean asyncProfiler = true;
    for (byte javaType : new byte[] {0, 1, 2, 6}) {
      assertFalse(RotationBoundaryProcessor.isNonJavaFrame(javaType, asyncProfiler), "" + javaType);
    }
    assertTrue(RotationBoundaryProcessor.isNonJavaFrame((byte) 3, asyncProfiler));
    assertTrue(RotationBoundaryProcessor.isNonJavaFrame((byte) 4, asyncProfiler));
    assertTrue(RotationBoundaryProcessor.isNonJavaFrame((byte) 5, asyncProfiler));
    // ...but in a JDK Flight Recorder recording type 3 is a Java native method (e.g. Object.wait0),
    // which must keep its class.
    assertFalse(RotationBoundaryProcessor.isNonJavaFrame((byte) 3, false));
    assertTrue(RotationBoundaryProcessor.isNonJavaFrame((byte) 4, false));
  }

  @Test
  void normalizeHiddenClassName_stripsPerRunSuffixLikeAsyncProfilerNorm() {
    // Lambda and hidden-class names as recorded by different JDKs (from a live capture).
    assertEquals(
        "Foo$$Lambda$344",
        RotationBoundaryProcessor.normalizeHiddenClassName("Foo$$Lambda$344/7064297"));
    assertEquals(
        "Foo$$Lambda$345",
        RotationBoundaryProcessor.normalizeHiddenClassName("Foo$$Lambda$345/0x00007f5e78000c10"));
    assertEquals(
        "com/example/Foo$$Lambda",
        RotationBoundaryProcessor.normalizeHiddenClassName(
            "com/example/Foo$$Lambda/0x00007f740c373a08"));
    assertEquals(
        "java/lang/invoke/LambdaForm$MH",
        RotationBoundaryProcessor.normalizeHiddenClassName(
            "java/lang/invoke/LambdaForm$MH/0x00007f740c0a1c00"));
    // The JDK recorder's form: "+0x<16 hex digits>" before the per-run number is also dropped.
    assertEquals(
        "com/example/Foo$$Lambda",
        RotationBoundaryProcessor.normalizeHiddenClassName(
            "com/example/Foo$$Lambda+0x00007f8177090218/543846639"));
    // Ordinary class names are untouched, including digits elsewhere in the name.
    for (String unchanged :
        new String[] {
          "com/example/Foo", "Foo$1", "com/v2/Foo", "com/example/Foo$Inner2", "", "F"
        }) {
      assertEquals(unchanged, RotationBoundaryProcessor.normalizeHiddenClassName(unchanged));
    }
  }

  @Test
  void javaTypeName_isDottedAndNormalized() {
    assertEquals("com.example.Foo", RotationBoundaryProcessor.javaTypeName("com/example/Foo"));
    assertEquals(
        "com.example.Foo$$Lambda",
        RotationBoundaryProcessor.javaTypeName("com/example/Foo$$Lambda/0x00007f740c373a08"));
    assertEquals("", RotationBoundaryProcessor.javaTypeName(""));
  }

  @Test
  void sourceFileName_isOuterClassDotJava_orEmpty() {
    assertEquals("Foo.java", RotationBoundaryProcessor.sourceFileName("com.example.Foo"));
    assertEquals("Foo.java", RotationBoundaryProcessor.sourceFileName("com.example.Foo$Inner"));
    assertEquals("Foo.java", RotationBoundaryProcessor.sourceFileName("com.example.Foo$$Lambda"));
    assertEquals("Foo.java", RotationBoundaryProcessor.sourceFileName("Foo"));
    assertEquals("FooKt.java", RotationBoundaryProcessor.sourceFileName("com.example.FooKt"));
    // No class, or no outer class name (e.g. a proxy class "$Proxy12"): no file name.
    assertEquals("", RotationBoundaryProcessor.sourceFileName(""));
    assertEquals("", RotationBoundaryProcessor.sourceFileName("jdk.proxy1.$Proxy12"));
  }

  /**
   * Source of truth: async-profiler's own converter. Scanning the fixture JFR must produce exactly
   * the function names async-profiler's JFR→OTLP converter produces for the same recording (with
   * --dot, the dotted Java form we emit, and --norm, which strips hidden-class suffixes), every
   * native/C++ frame must carry its library as a Mapping, Java frames carry the outer class's
   * {@code .java} file name, native frames carry none, and no function carries a system name.
   */
  @Test
  void scan_namesFramesExactlyLikeAsyncProfiler_withLibrariesAsMappings(@TempDir Path dir)
      throws Exception {
    RotationBoundaryProcessor proc = new RotationBoundaryProcessor(10_000, null, 60, null, null, 0);
    OtlpProfileBuilder builder = new OtlpProfileBuilder(0L, 60_000_000_000L, 10_000_000L, 524_288L);
    proc.scanJfrFileSinglePass(
        fixture(), new HashMap<String, TreeMap<Long, SpanMetadata>>(), builder, new HashMap<>());
    io.opentelemetry.proto.profiles.v1development.ProfilesDictionary ours =
        builder
            .toExportRequest(io.opentelemetry.sdk.resources.Resource.getDefault())
            .getDictionary();

    // async-profiler reference: wall (execution) samples, and allocation samples, dotted names.
    java.util.Set<String> expected = new java.util.TreeSet<>();
    for (String[] extra : new String[][] {{}, {"--alloc"}}) {
      Path out = dir.resolve("ref" + extra.length + ".otlp");
      List<String> argv = new ArrayList<>(java.util.Arrays.asList("-o", "otlp", "--dot", "--norm"));
      argv.addAll(java.util.Arrays.asList(extra));
      argv.add(fixture().toString());
      argv.add(out.toString());
      one.convert.Main.main(argv.toArray(new String[0]));
      io.opentelemetry.proto.profiles.v1development.ProfilesDictionary ref =
          io.opentelemetry.proto.profiles.v1development.ProfilesData.parseFrom(
                  Files.readAllBytes(out))
              .getDictionary();
      for (io.opentelemetry.proto.profiles.v1development.Function f : ref.getFunctionTableList()) {
        expected.add(ref.getStringTable(f.getNameStrindex()));
      }
    }

    java.util.Set<String> actual = new java.util.TreeSet<>();
    Map<String, String> fileByName = new HashMap<>();
    for (io.opentelemetry.proto.profiles.v1development.Function f : ours.getFunctionTableList()) {
      String name = ours.getStringTable(f.getNameStrindex());
      actual.add(name);
      fileByName.put(name, ours.getStringTable(f.getFilenameStrindex()));
      assertEquals(0, f.getSystemNameStrindex(), "no system name");
    }
    assertEquals(expected, actual, "function names must match async-profiler's converter");

    // Java frames: file name of the outer class, including for nested classes.
    assertEquals("Thread.java", fileByName.get("java.lang.Thread.run"));
    assertEquals("Reference.java", fileByName.get("java.lang.ref.Reference$ReferenceHandler.run"));
    assertEquals(
        "AbstractQueuedSynchronizer.java",
        fileByName.get(
            "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await"));

    // Native frames: bare symbol + library as a Mapping (e.g. start_thread in libc.so.6).
    boolean sawStartThread = false;
    for (io.opentelemetry.proto.profiles.v1development.Location loc : ours.getLocationTableList()) {
      String fn =
          ours.getStringTable(
              ours.getFunctionTable(loc.getLines(0).getFunctionIndex()).getNameStrindex());
      String lib =
          ours.getStringTable(ours.getMappingTable(loc.getMappingIndex()).getFilenameStrindex());
      if ("start_thread".equals(fn)) {
        sawStartThread = true;
        assertEquals("libc.so.6", lib, "native frame keeps its library as a Mapping");
      }
      if (fn.startsWith("java.") || fn.startsWith("jdk.")) {
        assertEquals("", lib, "Java frames have no mapping");
      }
      if (!lib.isEmpty()) {
        assertEquals("", fileByName.get(fn), "native frame has no file name: " + fn);
      }
      // Library-qualified names like "libc.so.6.start_thread" must not appear (the library is the
      // Mapping). An unresolved native frame named after the library path itself (e.g.
      // "/usr/lib64/libz.so.1") is async-profiler's own naming and is covered by the equivalence
      // check above.
      assertFalse(fn.startsWith(lib + ".") && !lib.isEmpty(), "library baked into name: " + fn);
    }
    assertTrue(sawStartThread, "fixture contains libc's start_thread");
  }

  /**
   * Correlation must respect the END of a request, not just its start: a sample taken after the
   * request finished (thread idle or doing other work) is still exported, but must NOT carry the
   * request's operation or trace link. Without this, idle time after every request would be
   * attributed to the previous request.
   *
   * <p>The window is derived from the fixture itself: a span on the {@code main} thread covers only
   * the first half of that thread's sample timestamps, so samples exist both inside and after it.
   */
  @Test
  void correlation_samplesAfterSpanEnd_keepNoOperationOrTraceLink() throws Exception {
    final String thread = "main";
    List<Long> sampleTimes = new ArrayList<>();
    try (one.jfr.JfrReader r = new one.jfr.JfrReader(fixture().toString())) {
      for (one.jfr.event.Event e; (e = r.readEvent()) != null; ) {
        boolean isSample =
            e instanceof one.jfr.event.ExecutionSample
                || e instanceof one.jfr.event.AllocationSample;
        if (isSample && e.stackTraceId != 0 && thread.equals(r.threads.get(e.tid))) {
          sampleTimes.add(r.eventTimeToNanos(e.time));
        }
      }
    }
    java.util.Collections.sort(sampleTimes);
    assertTrue(sampleTimes.size() >= 10, "fixture needs main-thread samples to split");
    long spanStart = sampleTimes.get(0) - 1;
    long spanEnd = sampleTimes.get(sampleTimes.size() / 2);

    TreeMap<Long, SpanMetadata> spans = new TreeMap<>();
    spans.put(
        spanStart,
        new SpanMetadata(
            "GET /first-half",
            spanStart,
            spanEnd,
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331"));
    Map<String, TreeMap<Long, SpanMetadata>> spanIndex = new HashMap<>();
    spanIndex.put(thread, spans);

    RotationBoundaryProcessor proc = new RotationBoundaryProcessor(10_000, null, 60, null, null, 0);
    // Default aggregation (none): one OTLP sample per observation, each with its own timestamp.
    OtlpProfileBuilder builder = new OtlpProfileBuilder(0L, 60_000_000_000L, 10_000_000L, 524_288L);
    proc.scanJfrFileSinglePass(fixture(), spanIndex, builder, new HashMap<>());
    ExportProfilesServiceRequest request =
        builder.toExportRequest(io.opentelemetry.sdk.resources.Resource.getDefault());
    io.opentelemetry.proto.profiles.v1development.ProfilesDictionary dict = request.getDictionary();

    int inside = 0;
    int after = 0;
    for (io.opentelemetry.proto.profiles.v1development.ResourceProfiles rp :
        request.getResourceProfilesList()) {
      for (io.opentelemetry.proto.profiles.v1development.ScopeProfiles sp :
          rp.getScopeProfilesList()) {
        for (io.opentelemetry.proto.profiles.v1development.Profile p : sp.getProfilesList()) {
          for (io.opentelemetry.proto.profiles.v1development.Sample s : p.getSamplesList()) {
            if (!thread.equals(attribute(s, dict, "thread.name"))) {
              continue;
            }
            long t = s.getTimestampsUnixNano(0);
            String operation = attribute(s, dict, "operation");
            if (t > spanStart && t < spanEnd) {
              inside++;
              assertEquals("GET /first-half", operation, "sample inside the request window");
              assertTrue(s.getLinkIndex() != 0, "sample inside the request window has a link");
            } else if (t >= spanEnd) {
              after++;
              assertEquals(null, operation, "sample at/after the request ended has no operation");
              assertEquals(0, s.getLinkIndex(), "sample at/after the request ended has no link");
            }
          }
        }
      }
    }
    assertTrue(inside > 0, "expected samples inside the request window");
    assertTrue(after > 0, "expected samples after the request ended (still exported)");
  }

  /** Value of the sample attribute {@code key}, or null when the sample does not carry it. */
  private static String attribute(
      io.opentelemetry.proto.profiles.v1development.Sample sample,
      io.opentelemetry.proto.profiles.v1development.ProfilesDictionary dict,
      String key) {
    for (int idx : sample.getAttributeIndicesList()) {
      io.opentelemetry.proto.profiles.v1development.KeyValueAndUnit kv =
          dict.getAttributeTable(idx);
      if (key.equals(dict.getStringTable(kv.getKeyStrindex()))) {
        return kv.getValue().getStringValue();
      }
    }
    return null;
  }

  @Test
  void parseTimestamp_validAndInvalid() {
    assertTrue(RotationBoundaryProcessor.parseTimestamp("20260309-172942") > 0, "valid %t parses");
    assertEquals(-1L, RotationBoundaryProcessor.parseTimestamp("not-a-timestamp"), "bad -> -1");
  }

  @Test
  void parseJfrFilenameTimestamp_parsesValidName_elseFallsBackToLastModified(@TempDir Path dir)
      throws Exception {
    RotationBoundaryProcessor proc = new RotationBoundaryProcessor(10_000, null, 60, null, null, 0);

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
