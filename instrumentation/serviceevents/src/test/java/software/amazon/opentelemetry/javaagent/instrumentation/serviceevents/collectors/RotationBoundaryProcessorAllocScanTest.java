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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.profiles.v1development.KeyValueAndUnit;
import io.opentelemetry.proto.profiles.v1development.Link;
import io.opentelemetry.proto.profiles.v1development.Profile;
import io.opentelemetry.proto.profiles.v1development.ProfilesDictionary;
import io.opentelemetry.proto.profiles.v1development.Sample;
import io.opentelemetry.sdk.resources.Resource;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.FrameInfo;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.OtlpProfileBuilder;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.SpanMetadata;

/**
 * Fixture-backed scanner test for allocation profiling.
 *
 * <p>The fixture {@code /wall-alloc-sample.jfr} is a real async-profiler 4.5 recording produced
 * with {@code start,event=wall,interval=10ms,alloc=524288,jfr} (wall + alloc in one JFR session),
 * checked in under {@code src/test/resources}. It contains 169 {@code profiler.WallClockSample} and
 * 1028 {@code jdk.ObjectAllocationInNewTLAB} events on the {@code main} thread.
 *
 * <p><b>Exact-value check.</b> {@link #EXPECTED_ALLOC_BYTES} and {@link #EXPECTED_ALLOC_COUNT} are
 * async-profiler's own {@code jfr-converter.jar} numbers for this exact fixture:
 *
 * <pre>
 *   java -jar jfr-converter.jar -o collapsed --alloc --total wall-alloc-sample.jfr => sum = 538968064
 *   java -jar jfr-converter.jar -o collapsed --alloc         wall-alloc-sample.jfr => sum = 1028
 * </pre>
 *
 * jfrconv's byte total is the sum of the {@code jdk.ObjectAllocationInNewTLAB} {@code tlabSize}
 * field, so the scanner reads {@code tlabSize} as the per-event weight and the emitted {@code
 * alloc_space} total equals jfrconv's number <b>exactly</b> (no tolerance).
 */
class RotationBoundaryProcessorAllocScanTest {

  // async-profiler jfrconv reference numbers for /wall-alloc-sample.jfr (see class javadoc).
  private static final long EXPECTED_ALLOC_BYTES =
      538_968_064L; // jfrconv -o collapsed --alloc --total
  private static final long EXPECTED_ALLOC_COUNT = 1028L; // jfrconv -o collapsed --alloc

  private static final long WALL_PERIOD_NS = 10_000_000L; // 10ms
  private static final long ALLOC_PERIOD_BYTES = 524_288L; // alloc= interval

  // Σ ExecutionSample.samples over /wall-alloc-sample.jfr = 609 across 169 wall events (via
  // one.jfr.JfrReader). async-profiler coalesces repeated samples, so the correct weighted wall
  // total is 609 × WALL_PERIOD_NS — not 169 × period.
  private static final long EXPECTED_WALL_SAMPLES = 609L;

  // Fixture alloc events are all on the "main" thread; this covers their full timestamp span.
  private static final String ALLOC_THREAD = "main";
  private static final long SPAN_START_NS = 1_786_707_790_000_000_000L;
  private static final long SPAN_END_NS = 1_786_707_791_000_000_000L;

  private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  private static final String SPAN_ID = "b7ad6b7169203331";

  private static Path fixture() throws Exception {
    return Path.of(
        RotationBoundaryProcessorAllocScanTest.class.getResource("/wall-alloc-sample.jfr").toURI());
  }

  /** RotationBoundaryProcessor whose scan method we drive directly (no wrapper/exporter needed). */
  private static RotationBoundaryProcessor newProcessor() {
    return new RotationBoundaryProcessor(10000, null, 60, null, null, false, 0);
  }

  private static OtlpProfileBuilder newBuilder() {
    return new OtlpProfileBuilder(0L, 60_000_000_000L, WALL_PERIOD_NS, ALLOC_PERIOD_BYTES);
  }

  private static Profile profileByType(ExportProfilesServiceRequest req, String type) {
    ProfilesDictionary dict = req.getDictionary();
    for (Profile p : req.getResourceProfiles(0).getScopeProfiles(0).getProfilesList()) {
      if (dict.getStringTable(p.getSampleType().getTypeStrindex()).equals(type)) {
        return p;
      }
    }
    return null;
  }

  private static long sumValues(Profile p) {
    long sum = 0;
    for (Sample s : p.getSamplesList()) {
      sum += s.getValues(0);
    }
    return sum;
  }

  @Test
  void scan_emitsWallAndAllocProfiles_withPeriodsAndExactJfrconvByteTotal() throws Exception {
    RotationBoundaryProcessor proc = newProcessor();
    OtlpProfileBuilder builder = newBuilder();

    Map<String, TreeMap<Long, SpanMetadata>> spanIndex = new HashMap<>();
    Map<Integer, List<FrameInfo>> stackCache = new HashMap<>();

    proc.scanJfrFileSinglePass(fixture(), spanIndex, builder, stackCache);

    // Alloc bytes/count must match jfrconv exactly.
    assertEquals(
        EXPECTED_ALLOC_COUNT,
        builder.getAllocSampleCount(),
        "scanner must record every jdk.ObjectAllocationInNewTLAB event (== jfrconv count)");
    assertTrue(builder.getSampleCount() > 0, "fixture also has wall samples");

    ExportProfilesServiceRequest request = builder.toExportRequest(Resource.getDefault());
    ProfilesDictionary dict = request.getDictionary();

    // Round-trips with no unknown fields.
    ExportProfilesServiceRequest parsed =
        ExportProfilesServiceRequest.parseFrom(request.toByteArray());
    assertTrue(parsed.getUnknownFields().asMap().isEmpty(), "no unknown fields");
    assertEquals(request, parsed, "serialize -> parseFrom must round-trip identically");

    // All profiles ride under one ScopeProfiles (one shared ProfilesDictionary).
    assertEquals(1, request.getResourceProfilesCount());
    assertEquals(1, request.getResourceProfiles(0).getScopeProfilesCount());
    assertEquals(3, request.getResourceProfiles(0).getScopeProfiles(0).getProfilesCount());

    // Wall Profile: {wall, nanoseconds} + period_type/period.
    Profile wall = profileByType(request, "wall");
    assertNotNull(wall, "wall Profile must be present");
    assertEquals("nanoseconds", dict.getStringTable(wall.getSampleType().getUnitStrindex()));
    assertEquals("wall", dict.getStringTable(wall.getPeriodType().getTypeStrindex()));
    assertEquals("nanoseconds", dict.getStringTable(wall.getPeriodType().getUnitStrindex()));
    assertEquals(WALL_PERIOD_NS, wall.getPeriod());
    assertTrue(wall.getSamplesCount() > 0, "wall Profile must carry samples");
    assertEquals(
        EXPECTED_WALL_SAMPLES * WALL_PERIOD_NS,
        sumValues(wall),
        "wall total must weight each event by its ExecutionSample.samples coalescing count × period"
            + " (609 × 10ms), not one period per event");

    // alloc_space Profile: {alloc_space, bytes} + period_type/period; exact byte total.
    Profile allocSpace = profileByType(request, "alloc_space");
    assertNotNull(allocSpace, "alloc_space Profile must be present");
    assertEquals("bytes", dict.getStringTable(allocSpace.getSampleType().getUnitStrindex()));
    assertEquals("alloc_space", dict.getStringTable(allocSpace.getPeriodType().getTypeStrindex()));
    assertEquals("bytes", dict.getStringTable(allocSpace.getPeriodType().getUnitStrindex()));
    assertEquals(ALLOC_PERIOD_BYTES, allocSpace.getPeriod());
    assertEquals((int) EXPECTED_ALLOC_COUNT, allocSpace.getSamplesCount());
    assertEquals(
        EXPECTED_ALLOC_BYTES,
        sumValues(allocSpace),
        "alloc_space total must equal async-profiler jfrconv --alloc --total for this JFR");

    // alloc_objects Profile: {alloc_objects, count}; total == event count (== jfrconv
    // count).
    Profile allocObjects = profileByType(request, "alloc_objects");
    assertNotNull(allocObjects, "alloc_objects Profile must be present");
    assertEquals("count", dict.getStringTable(allocObjects.getSampleType().getUnitStrindex()));
    assertEquals(
        "alloc_objects", dict.getStringTable(allocObjects.getPeriodType().getTypeStrindex()));
    assertEquals("count", dict.getStringTable(allocObjects.getPeriodType().getUnitStrindex()));
    assertEquals(1L, allocObjects.getPeriod());
    assertEquals(EXPECTED_ALLOC_COUNT, sumValues(allocObjects));
  }

  @Test
  void scan_allocSamples_correlateOperationAndTraceLinkLikeWall() throws Exception {
    RotationBoundaryProcessor proc = newProcessor();
    OtlpProfileBuilder builder = newBuilder();

    // A single span on the alloc thread covering the fixture's whole alloc time span, exactly the
    // per-thread floorEntry + strict startNs<ts<endNs join wall uses.
    TreeMap<Long, SpanMetadata> threadSpans = new TreeMap<>();
    threadSpans.put(
        SPAN_START_NS,
        new SpanMetadata(
            ALLOC_THREAD, "POST /alloc", SPAN_START_NS, SPAN_END_NS, TRACE_ID, SPAN_ID));
    Map<String, TreeMap<Long, SpanMetadata>> spanIndex = new HashMap<>();
    spanIndex.put(ALLOC_THREAD, threadSpans);

    Map<Integer, List<FrameInfo>> stackCache = new HashMap<>();

    proc.scanJfrFileSinglePass(fixture(), spanIndex, builder, stackCache);

    ExportProfilesServiceRequest request = builder.toExportRequest(Resource.getDefault());
    ProfilesDictionary dict = request.getDictionary();
    Profile allocSpace = profileByType(request, "alloc_space");
    assertNotNull(allocSpace);

    long linked = 0;
    long withOperation = 0;
    for (Sample s : allocSpace.getSamplesList()) {
      if (s.getLinkIndex() != 0) {
        Link link = dict.getLinkTable(s.getLinkIndex());
        assertEquals(16, link.getTraceId().size(), "trace_id must be 16 bytes");
        assertEquals(8, link.getSpanId().size(), "span_id must be 8 bytes");
        assertEquals(TRACE_ID, toHex(link.getTraceId().toByteArray()));
        assertEquals(SPAN_ID, toHex(link.getSpanId().toByteArray()));
        linked++;
      }
      if (hasOperation(s, dict, "POST /alloc")) {
        withOperation++;
      }
    }

    // Every in-range alloc sample must get the operation attribute AND the trace Link, exactly like
    // wall correlation.
    assertEquals(
        EXPECTED_ALLOC_COUNT, linked, "every in-range alloc sample must carry the trace Link");
    assertEquals(
        EXPECTED_ALLOC_COUNT,
        withOperation,
        "every in-range alloc sample must carry the operation attribute");
  }

  private static boolean hasOperation(Sample s, ProfilesDictionary dict, String expected) {
    for (int idx : s.getAttributeIndicesList()) {
      KeyValueAndUnit attr = dict.getAttributeTable(idx);
      if ("operation".equals(dict.getStringTable(attr.getKeyStrindex()))
          && expected.equals(attr.getValue().getStringValue())) {
        return true;
      }
    }
    return false;
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
