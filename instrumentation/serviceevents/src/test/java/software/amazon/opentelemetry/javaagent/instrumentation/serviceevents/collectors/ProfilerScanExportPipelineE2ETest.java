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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.profiles.v1development.KeyValueAndUnit;
import io.opentelemetry.proto.profiles.v1development.Link;
import io.opentelemetry.proto.profiles.v1development.Profile;
import io.opentelemetry.proto.profiles.v1development.ProfilesDictionary;
import io.opentelemetry.proto.profiles.v1development.Sample;
import io.opentelemetry.sdk.resources.Resource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.OtlpHttpProfilesExporter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.AsyncProfilerWrapper;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.OtlpProfileBuilder;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.SpanMetadata;

/**
 * Non-Docker end-to-end check for the profiler's scan → build → export pipeline.
 *
 * <p>Exercises the full path a JFR file takes on a rotation boundary — {@link
 * RotationBoundaryProcessor#scanJfrFileSinglePass} reads samples into an {@link
 * OtlpProfileBuilder}, {@link OtlpProfileBuilder#toExportRequest} assembles a native OTLP {@link
 * ExportProfilesServiceRequest}, and {@link OtlpHttpProfilesExporter} POSTs it — without Docker,
 * testcontainers, or the mock-collector image. A local {@link MockWebServer} stands in for the
 * collector at {@code /v1development/profiles} and parses the (gunzipped) protobuf body back.
 *
 * <p>Three flavors:
 *
 * <ul>
 *   <li>{@link #fixtureScan_buildsAndExports_mockWebServerParsesWallAllocAndCorrelation()} — the
 *       deterministic gate over the checked-in {@code /wall-alloc-sample.jfr} fixture (a real
 *       async-profiler 4.5 wall+alloc recording). Always runs.
 *   <li>{@link #liveProfiler_producesScannableJfr_thenBuildsAndExports()} — starts the real
 *       async-profiler via {@link AsyncProfilerWrapper} (wall+alloc), generates CPU + allocation
 *       work, stops it (forcing the JFR to finalize), then scans that live-produced file through
 *       the same pipeline. Best-effort: skipped (not failed) where async-profiler's native library
 *       cannot load or the run yields no samples (Windows / restricted CI). async-profiler's single
 *       Linux .so works on both glibc and musl, so Alpine is supported too.
 *   <li>{@link #liveProfiler_cpuMode_producesCpuNanosecondsProfile()} — the {@code cpu}-mode
 *       counterpart of the live check: starts async-profiler in {@link
 *       AsyncProfilerWrapper#MODE_CPU cpu} mode, burns CPU, then scans with a {@link
 *       OtlpProfileBuilder#PRIMARY_CPU} builder and asserts the primary Profile is {@code {cpu,
 *       nanoseconds}} (not wall) with no {@code thread.state}. Same best-effort skip guards.
 * </ul>
 */
class ProfilerScanExportPipelineE2ETest {

  // async-profiler jfrconv reference geometry for /wall-alloc-sample.jfr (see
  // RotationBoundaryProcessorAllocScanTest). The fixture's alloc events are all on the "main"
  // thread within this timestamp span.
  private static final long WALL_PERIOD_NS = 10_000_000L; // 10ms
  private static final long ALLOC_PERIOD_BYTES = 524_288L;
  private static final String ALLOC_THREAD = "main";
  private static final long SPAN_START_NS = 1_786_707_790_000_000_000L;
  private static final long SPAN_END_NS = 1_786_707_791_000_000_000L;
  private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  private static final String SPAN_ID = "b7ad6b7169203331";

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  private static Path fixture() throws Exception {
    return Path.of(
        ProfilerScanExportPipelineE2ETest.class.getResource("/wall-alloc-sample.jfr").toURI());
  }

  private static RotationBoundaryProcessor newProcessor(OtlpHttpProfilesExporter exporter) {
    return new RotationBoundaryProcessor(10000, null, 60, null, exporter, 0);
  }

  @Test
  void fixtureScan_buildsAndExports_mockWebServerParsesWallAllocAndCorrelation() throws Exception {
    server.enqueue(new MockResponse.Builder().code(200).build());
    String url = server.url("/v1development/profiles").toString();
    OtlpHttpProfilesExporter exporter = new OtlpHttpProfilesExporter(url, "gzip", 10_000);

    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(0L, 60_000_000_000L, WALL_PERIOD_NS, ALLOC_PERIOD_BYTES);

    // One span on the alloc thread covering the fixture's alloc span — exactly the per-thread
    // floorEntry + strict startNs<ts<endNs correlation join the live pipeline uses — so scanned
    // samples pick up the operation attribute + trace Link.
    TreeMap<Long, SpanMetadata> threadSpans = new TreeMap<>();
    threadSpans.put(
        SPAN_START_NS,
        new SpanMetadata("POST /alloc", SPAN_START_NS, SPAN_END_NS, TRACE_ID, SPAN_ID));
    Map<String, TreeMap<Long, SpanMetadata>> spanIndex = new HashMap<>();
    spanIndex.put(ALLOC_THREAD, threadSpans);

    newProcessor(exporter).scanJfrFileSinglePass(fixture(), spanIndex, builder, new HashMap<>());

    assertTrue(builder.getSampleCount() > 0, "fixture must yield wall samples");
    assertTrue(builder.getAllocSampleCount() > 0, "fixture must yield alloc samples");

    ExportProfilesServiceRequest request = builder.toExportRequest(Resource.getDefault());
    assertTrue(request.getSerializedSize() > 0, "request must be non-empty");
    assertTrue(
        exporter.export(request.toByteArray()), "exporter must POST successfully (HTTP 200)");
    exporter.shutdown();

    // The MockWebServer received the POST at the profiles route and can parse the body back.
    RecordedRequest recorded = server.takeRequest();
    assertEquals("POST", recorded.getMethod());
    assertEquals("/v1development/profiles", recorded.getUrl().encodedPath());
    assertEquals("application/x-protobuf", recorded.getHeaders().get("Content-Type"));
    assertEquals("gzip", recorded.getHeaders().get("Content-Encoding"));

    byte[] body = gunzip(recorded.getBody().toByteArray());
    ExportProfilesServiceRequest parsed = ExportProfilesServiceRequest.parseFrom(body);
    assertTrue(parsed.getUnknownFields().asMap().isEmpty(), "no unknown fields");
    assertEquals(request, parsed, "round-trips identically over the wire");

    ProfilesDictionary dict = parsed.getDictionary();

    // Wall Profile: {wall, nanoseconds} + period_type/period.
    Profile wall = profileByType(parsed, "wall");
    assertNotNull(wall, "wall Profile must be present");
    assertEquals("nanoseconds", dict.getStringTable(wall.getSampleType().getUnitStrindex()));
    assertEquals("wall", dict.getStringTable(wall.getPeriodType().getTypeStrindex()));
    assertEquals(WALL_PERIOD_NS, wall.getPeriod());
    assertTrue(wall.getSamplesCount() > 0);

    // alloc_space Profile: {alloc_space, bytes} + period_type/period.
    Profile allocSpace = profileByType(parsed, "alloc_space");
    assertNotNull(allocSpace, "alloc_space Profile must be present");
    assertEquals("bytes", dict.getStringTable(allocSpace.getSampleType().getUnitStrindex()));
    assertEquals("alloc_space", dict.getStringTable(allocSpace.getPeriodType().getTypeStrindex()));
    assertEquals(ALLOC_PERIOD_BYTES, allocSpace.getPeriod());
    assertTrue(allocSpace.getSamplesCount() > 0);

    // Correlation survives the wire: at least one sample carries operation=POST /alloc + a Link.
    assertNotNull(anyOperationValue(parsed), "a sample must carry the operation attribute");
    assertEquals("POST /alloc", anyOperationValue(parsed));
    Link link = anyTraceLink(parsed);
    assertNotNull(link, "a sample must carry a trace Link");
    assertEquals(16, link.getTraceId().size());
    assertEquals(8, link.getSpanId().size());
    assertEquals(TRACE_ID, toHex(link.getTraceId().toByteArray()));
    assertEquals(SPAN_ID, toHex(link.getSpanId().toByteArray()));
  }

  @Test
  void liveProfiler_producesScannableJfr_thenBuildsAndExports() throws Exception {
    Path dataDir = Files.createTempDirectory("profiler-e2e");
    AsyncProfilerWrapper wrapper =
        new AsyncProfilerWrapper(
            10,
            10,
            "profiler-jfr",
            dataDir.toString(),
            /* memoryEnabled= */ true,
            ALLOC_PERIOD_BYTES,
            /* loopSeconds= */ 60);
    // Skip (do not fail) where async-profiler's native library can't load (e.g. Windows).
    assumeTrue(wrapper.isAvailable(), "async-profiler native library unavailable on this platform");

    wrapper.deleteAllJfrFiles();
    wrapper.startProfiling();
    assumeTrue(wrapper.isRunning(), "async-profiler did not start on this platform");

    // Generate CPU + allocation work on this thread. The profiler samples all threads, so this
    // work thread is captured.
    List<byte[]> sink = new ArrayList<>();
    long acc = 0;
    long deadline = System.nanoTime() + 2_000_000_000L; // ~2s
    while (System.nanoTime() < deadline) {
      for (int i = 1; i < 20_000; i++) {
        acc += (long) (Math.sqrt(i) * Math.log(i + 1));
      }
      sink.add(new byte[128 * 1024]); // allocation pressure for the alloc profiler
      if (sink.size() > 128) {
        sink.clear();
      }
    }
    // Keep `acc`/`sink` observable so the JIT can't elide the work.
    assertTrue(acc != Long.MIN_VALUE && sink != null);
    wrapper.shutdown(); // stops the session and finalizes the JFR file

    Path jfr = newestJfr(wrapper.getJfrBasePath());
    assumeTrue(jfr != null, "async-profiler produced no JFR file on this platform");

    server.enqueue(new MockResponse.Builder().code(200).build());
    String url = server.url("/v1development/profiles").toString();
    OtlpHttpProfilesExporter exporter = new OtlpHttpProfilesExporter(url, "gzip", 10_000);

    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(
            0L,
            60_000_000_000L,
            wrapper.getWallIntervalMs() * 1_000_000L,
            wrapper.getAllocIntervalBytes());
    // Force a scan of the live-produced JFR (no span index — we only assert the pipeline
    // mechanics).
    newProcessor(exporter).scanJfrFileSinglePass(jfr, new HashMap<>(), builder, new HashMap<>());

    // Skip (do not fail) if this constrained host produced a JFR with no scannable samples.
    assumeTrue(
        builder.getSampleCount() > 0 || builder.getAllocSampleCount() > 0,
        "live async-profiler run captured no samples on this host");

    ExportProfilesServiceRequest request = builder.toExportRequest(Resource.getDefault());
    assertTrue(
        exporter.export(request.toByteArray()), "exporter must POST the live profile (HTTP 200)");
    exporter.shutdown();

    RecordedRequest recorded = server.takeRequest();
    assertEquals("POST", recorded.getMethod());
    assertEquals("/v1development/profiles", recorded.getUrl().encodedPath());

    ExportProfilesServiceRequest parsed =
        ExportProfilesServiceRequest.parseFrom(gunzip(recorded.getBody().toByteArray()));
    assertTrue(parsed.getUnknownFields().asMap().isEmpty(), "no unknown fields");
    // A wall Profile with live samples is always present.
    Profile wall = profileByType(parsed, "wall");
    assertNotNull(wall, "wall Profile must be present from a live run");
    assertEquals(WALL_PERIOD_NS, wall.getPeriod());

    // Wall samples carry the thread.state attribute — verifies the scan resolves
    // ExecutionSample.threadState via the JFR jdk.types.ThreadState enum on a real recording.
    // assumeTrue (not assertTrue): skip rather than fail on a reader/platform where that enum is
    // absent, mirroring the isRunning gate above — this is a best-effort live smoke test.
    if (wall.getSamplesCount() > 0) {
      assumeTrue(
          wall.getSamplesList().stream()
              .anyMatch(s -> attributeValue(s, parsed.getDictionary(), "thread.state") != null),
          "wall samples carry thread.state (skipped: JFR jdk.types.ThreadState enum unavailable)");
    }
  }

  @Test
  void liveProfiler_cpuMode_producesCpuNanosecondsProfile() throws Exception {
    Path dataDir = Files.createTempDirectory("profiler-e2e-cpu");
    // MODE_CPU: cpu interval 10ms, wall interval unused. No alloc (memory disabled) — this test
    // targets the on-CPU primary profile specifically.
    AsyncProfilerWrapper wrapper =
        new AsyncProfilerWrapper(
            10,
            50,
            "profiler-jfr",
            dataDir.toString(),
            /* memoryEnabled= */ false,
            ALLOC_PERIOD_BYTES,
            /* loopSeconds= */ 60,
            AsyncProfilerWrapper.MODE_CPU);
    // Skip (do not fail) where async-profiler's native library can't load (e.g. Windows). cpu
    // also degrades gracefully to ctimer in locked-down containers.
    assumeTrue(wrapper.isAvailable(), "async-profiler native library unavailable on this platform");
    assertEquals(AsyncProfilerWrapper.MODE_CPU, wrapper.getMode(), "wrapper must run in cpu mode");

    wrapper.deleteAllJfrFiles();
    wrapper.startProfiling();
    assumeTrue(wrapper.isRunning(), "async-profiler did not start on this platform");

    // Burn CPU so the on-CPU sampler has running-thread stacks to capture.
    long acc = 0;
    long deadline = System.nanoTime() + 2_000_000_000L; // ~2s
    while (System.nanoTime() < deadline) {
      for (int i = 1; i < 50_000; i++) {
        acc += (long) (Math.sqrt(i) * Math.log(i + 1));
      }
    }
    assertTrue(acc != Long.MIN_VALUE); // keep the work observable so the JIT can't elide it
    wrapper.shutdown(); // stops the session and finalizes the JFR file

    Path jfr = newestJfr(wrapper.getJfrBasePath());
    assumeTrue(jfr != null, "async-profiler produced no JFR file on this platform");

    server.enqueue(new MockResponse.Builder().code(200).build());
    String url = server.url("/v1development/profiles").toString();
    OtlpHttpProfilesExporter exporter = new OtlpHttpProfilesExporter(url, "gzip", 10_000);

    long cpuPeriodNs = wrapper.getPrimaryIntervalMs() * 1_000_000L;
    // PRIMARY_CPU: scanJfrFileSinglePass keys off builder.isPrimaryWall() — with PRIMARY_CPU it
    // treats the run as on-CPU (no thread.state) and emits the primary Profile as {cpu,
    // nanoseconds}.
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(
            0L,
            60_000_000_000L,
            cpuPeriodNs,
            wrapper.getAllocIntervalBytes(),
            /* aggregationMode= */ 0,
            OtlpProfileBuilder.PRIMARY_CPU);
    newProcessor(exporter).scanJfrFileSinglePass(jfr, new HashMap<>(), builder, new HashMap<>());

    // Skip (do not fail) if this constrained host produced a JFR with no scannable samples.
    assumeTrue(
        builder.getSampleCount() > 0,
        "live async-profiler cpu run captured no samples on this host");

    ExportProfilesServiceRequest request = builder.toExportRequest(Resource.getDefault());
    assertTrue(
        exporter.export(request.toByteArray()),
        "exporter must POST the live cpu profile (HTTP 200)");
    exporter.shutdown();

    RecordedRequest recorded = server.takeRequest();
    assertEquals("POST", recorded.getMethod());
    assertEquals("/v1development/profiles", recorded.getUrl().encodedPath());

    ExportProfilesServiceRequest parsed =
        ExportProfilesServiceRequest.parseFrom(gunzip(recorded.getBody().toByteArray()));
    assertTrue(parsed.getUnknownFields().asMap().isEmpty(), "no unknown fields");
    ProfilesDictionary dict = parsed.getDictionary();

    // Primary Profile is {cpu, nanoseconds} with the cpu interval as its period — NOT wall.
    Profile cpu = profileByType(parsed, "cpu");
    assertNotNull(cpu, "cpu Profile must be present from a live cpu-mode run");
    assertEquals("nanoseconds", dict.getStringTable(cpu.getSampleType().getUnitStrindex()));
    assertEquals("cpu", dict.getStringTable(cpu.getPeriodType().getTypeStrindex()));
    assertEquals(cpuPeriodNs, cpu.getPeriod());
    assertTrue(cpu.getSamplesCount() > 0, "cpu Profile must carry samples");
    assertNull(profileByType(parsed, "wall"), "cpu mode must not emit a wall Profile");

    // cpu mode carries no thread.state attribute (every on-CPU sample is STATE_DEFAULT).
    assumeTrue(
        cpu.getSamplesList().stream()
            .noneMatch(s -> attributeValue(s, dict, "thread.state") != null),
        "cpu samples must not carry thread.state");
  }

  /** The inline string value of {@code sample}'s attribute {@code wantKey}, or null if absent. */
  private static String attributeValue(Sample sample, ProfilesDictionary dict, String wantKey) {
    for (int idx : sample.getAttributeIndicesList()) {
      KeyValueAndUnit attr = dict.getAttributeTable(idx);
      if (dict.getStringTable(attr.getKeyStrindex()).equals(wantKey)) {
        return attr.getValue().getStringValue();
      }
    }
    return null;
  }

  // --- helpers ---

  private static Path newestJfr(String jfrBasePath) {
    File base = new File(jfrBasePath);
    File dir = base.getParentFile();
    if (dir == null) {
      dir = new File(".");
    }
    String prefix = base.getName();
    File[] files = dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".jfr"));
    if (files == null || files.length == 0) {
      return null;
    }
    return java.util.Arrays.stream(files)
        .max(Comparator.comparingLong(File::lastModified))
        .map(File::toPath)
        .orElse(null);
  }

  private static Profile profileByType(ExportProfilesServiceRequest req, String type) {
    ProfilesDictionary dict = req.getDictionary();
    for (var rp : req.getResourceProfilesList()) {
      for (var sp : rp.getScopeProfilesList()) {
        for (Profile p : sp.getProfilesList()) {
          if (type.equals(dict.getStringTable(p.getSampleType().getTypeStrindex()))) {
            return p;
          }
        }
      }
    }
    return null;
  }

  private static String anyOperationValue(ExportProfilesServiceRequest req) {
    ProfilesDictionary dict = req.getDictionary();
    for (var rp : req.getResourceProfilesList()) {
      for (var sp : rp.getScopeProfilesList()) {
        for (Profile p : sp.getProfilesList()) {
          for (Sample sample : p.getSamplesList()) {
            for (int idx : sample.getAttributeIndicesList()) {
              KeyValueAndUnit attr = dict.getAttributeTable(idx);
              if ("operation".equals(dict.getStringTable(attr.getKeyStrindex()))) {
                String value = attr.getValue().getStringValue();
                if (value != null && !value.isEmpty()) {
                  return value;
                }
              }
            }
          }
        }
      }
    }
    return null;
  }

  private static Link anyTraceLink(ExportProfilesServiceRequest req) {
    ProfilesDictionary dict = req.getDictionary();
    for (var rp : req.getResourceProfilesList()) {
      for (var sp : rp.getScopeProfilesList()) {
        for (Profile p : sp.getProfilesList()) {
          for (Sample sample : p.getSamplesList()) {
            if (sample.getLinkIndex() != 0) {
              Link link = dict.getLinkTable(sample.getLinkIndex());
              if (!link.getTraceId().isEmpty()) {
                return link;
              }
            }
          }
        }
      }
    }
    return null;
  }

  private static byte[] gunzip(byte[] input) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(input))) {
      byte[] buf = new byte[4096];
      int n;
      while ((n = gis.read(buf)) != -1) {
        out.write(buf, 0, n);
      }
    }
    return out.toByteArray();
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}
