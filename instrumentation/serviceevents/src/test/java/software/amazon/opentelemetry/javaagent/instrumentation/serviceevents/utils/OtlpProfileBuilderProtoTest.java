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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.profiles.v1development.KeyValueAndUnit;
import io.opentelemetry.proto.profiles.v1development.Link;
import io.opentelemetry.proto.profiles.v1development.Profile;
import io.opentelemetry.proto.profiles.v1development.ProfilesDictionary;
import io.opentelemetry.proto.profiles.v1development.ResourceProfiles;
import io.opentelemetry.proto.profiles.v1development.Sample;
import io.opentelemetry.proto.profiles.v1development.ScopeProfiles;
import io.opentelemetry.proto.profiles.v1development.ValueType;
import io.opentelemetry.sdk.resources.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link OtlpProfileBuilder#toExportRequest} emits a native OTLP profiles protobuf that
 * round-trips through {@code ExportProfilesServiceRequest.parseFrom} with no unknown fields, builds
 * a single wall Profile, and carries per-sample operation attributes + trace Links.
 */
class OtlpProfileBuilderProtoTest {

  private static final long PERIOD_NS = 10_000_000L; // 10ms wall interval
  private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c"; // 32 hex = 16 bytes
  private static final String SPAN_ID = "b7ad6b7169203331"; // 16 hex = 8 bytes

  private static List<FrameInfo> stack() {
    return Arrays.asList(
        new FrameInfo("com.example.Service", "handle", "Service.java", 42),
        new FrameInfo("com.example.Service", "doWork", "Service.java", 88));
  }

  private static AnyValue attrByKey(ExportProfilesServiceRequest req, String key) {
    for (KeyValue kv : req.getResourceProfiles(0).getResource().getAttributesList()) {
      if (key.equals(kv.getKey())) {
        return kv.getValue();
      }
    }
    return null;
  }

  @Test
  void toExportRequest_mapsResourceAttributesToTypedAnyValues() {
    // Exercises attributeToAnyValue for each supported type + the stringified fallback (array).
    Resource resource =
        Resource.create(
            Attributes.builder()
                .put(AttributeKey.stringKey("k.str"), "svc")
                .put(AttributeKey.booleanKey("k.bool"), true)
                .put(AttributeKey.longKey("k.long"), 7L)
                .put(AttributeKey.doubleKey("k.double"), 1.5)
                .put(AttributeKey.stringArrayKey("k.arr"), Arrays.asList("a", "b"))
                .build());

    ExportProfilesServiceRequest req = builderWithSamples().toExportRequest(resource);

    assertEquals("svc", attrByKey(req, "k.str").getStringValue());
    assertTrue(attrByKey(req, "k.bool").getBoolValue());
    assertEquals(7L, attrByKey(req, "k.long").getIntValue());
    assertEquals(1.5, attrByKey(req, "k.double").getDoubleValue());
    // arrays/other types fall through to a stringified AnyValue
    assertTrue(attrByKey(req, "k.arr").getStringValue().contains("a"));
  }

  @Test
  void addSample_growsBackingArraysBeyondInitialCapacity() {
    // INITIAL_SAMPLE_CAPACITY is 65536; add more than that to drive ensureCapacity's grow branch
    // (and the alloc equivalent), then confirm every sample survived the array copies.
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(1_700_000_000_000_000_000L, 60_000_000_000L, PERIOD_NS, 524288L);
    int n = 65_536 + 10;
    for (int i = 0; i < n; i++) {
      builder.addSample(stack(), 1_700_000_000_000_000_000L + i, "t", "op", null, null);
      builder.addAllocSample(stack(), 1_700_000_000_000_000_000L + i, "t", "op", null, null, 64L);
    }
    assertEquals(n, builder.getSampleCount());
    assertEquals(n, builder.getAllocSampleCount());
    // still serializes cleanly after the regrows
    assertNotNull(builder.toExportRequest(Resource.getDefault()));
  }

  @Test
  void classlessFrame_exportsBareNameAndSystemName_andNoFileName() {
    // A frame with no declaring class (native/JVM frame or async-profiler marker such as
    // break_deopt) must export name == system_name == the bare method name and an unset file name
    // (string index 0 == ""), not ".java" / ".break_deopt".
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(1_700_000_000_000_000_000L, 60_000_000_000L, PERIOD_NS);
    builder.addSample(
        Arrays.asList(
            new FrameInfo("com.example.Foo", "bar", "Foo.java", 3),
            new FrameInfo("", "break_deopt", "", 0)),
        1_700_000_000_001_000_000L,
        "t",
        null,
        null,
        null);
    ProfilesDictionary dict = builder.toExportRequest(Resource.getDefault()).getDictionary();

    io.opentelemetry.proto.profiles.v1development.Function marker = null;
    io.opentelemetry.proto.profiles.v1development.Function classFrame = null;
    for (io.opentelemetry.proto.profiles.v1development.Function f : dict.getFunctionTableList()) {
      String name = dict.getStringTable(f.getNameStrindex());
      if ("break_deopt".equals(name)) {
        marker = f;
      } else if ("com.example.Foo.bar".equals(name)) {
        classFrame = f;
      }
    }
    assertNotNull(marker, "classless frame must be exported with its bare method name");
    assertEquals(0, marker.getSystemNameStrindex(), "system_name is left unset");
    assertEquals(0, marker.getFilenameStrindex(), "classless frame has no file name");

    assertNotNull(classFrame);
    assertEquals(0, classFrame.getSystemNameStrindex(), "system_name is left unset");
    // A file name the caller supplies is passed through as-is (the JFR scan never supplies one).
    assertEquals("Foo.java", dict.getStringTable(classFrame.getFilenameStrindex()));
  }

  @Test
  void nativeFrame_bareSymbolWithLibraryAsMapping_sameSymbolInTwoLibrariesStaysDistinct() {
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(1_700_000_000_000_000_000L, 60_000_000_000L, PERIOD_NS);
    builder.addSample(
        Arrays.asList(
            new FrameInfo("", "start_thread", "", 0, "libc.so.6"),
            new FrameInfo("", "memcpy", "", 0, "libc.so.6"),
            new FrameInfo("", "memcpy", "", 0, "libfoo.so")),
        1_700_000_000_001_000_000L,
        "t",
        null,
        null,
        null);
    ProfilesDictionary dict = builder.toExportRequest(Resource.getDefault()).getDictionary();

    assertEquals(3, dict.getMappingTableCount(), "sentinel + libc.so.6 + libfoo.so");
    assertEquals(0, dict.getMappingTable(0).getFilenameStrindex(), "mapping 0 is the sentinel");
    java.util.List<String> libsOfMemcpy = new ArrayList<>();
    for (io.opentelemetry.proto.profiles.v1development.Location loc :
        dict.getLocationTableList().subList(1, dict.getLocationTableCount())) {
      io.opentelemetry.proto.profiles.v1development.Function fn =
          dict.getFunctionTable(loc.getLines(0).getFunctionIndex());
      String name = dict.getStringTable(fn.getNameStrindex());
      String lib =
          dict.getStringTable(dict.getMappingTable(loc.getMappingIndex()).getFilenameStrindex());
      if ("start_thread".equals(name)) {
        assertEquals("libc.so.6", lib);
      }
      if ("memcpy".equals(name)) {
        libsOfMemcpy.add(lib);
      }
      assertEquals(0, fn.getFilenameStrindex(), "native frames have no source file");
    }
    java.util.Collections.sort(libsOfMemcpy);
    assertEquals(
        Arrays.asList("libc.so.6", "libfoo.so"),
        libsOfMemcpy,
        "same symbol in two libraries is two locations, each with its own mapping");
  }

  @Test
  void addSample_nullOrEmptyFrames_areIgnored() {
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(1_700_000_000_000_000_000L, 60_000_000_000L, PERIOD_NS, 524288L);
    builder.addSample(null, 1L, "t", "op", null, null);
    builder.addSample(new ArrayList<>(), 1L, "t", "op", null, null);
    builder.addAllocSample(null, 1L, "t", "op", null, null, 64L);
    builder.addAllocSample(new ArrayList<>(), 1L, "t", "op", null, null, 64L);
    assertEquals(0, builder.getSampleCount());
    assertEquals(0, builder.getAllocSampleCount());
    assertFalse(builder.toExportRequest(Resource.getDefault()).getResourceProfilesList().isEmpty());
  }

  /**
   * The OTLP profiles schema requires index 0 of every dictionary table to be present and the zero
   * value ("string_table[0] must always be \"\"", "location_table[0] must always be zero value
   * (Location{})", and so on), both with and without data.
   */
  @Test
  void dictionary_index0_isZeroValueInEveryTable() {
    for (OtlpProfileBuilder builder :
        new OtlpProfileBuilder[] {
          builderWithSamples(),
          new OtlpProfileBuilder(1_700_000_000_000_000_000L, 60_000_000_000L, PERIOD_NS)
        }) {
      ProfilesDictionary d = builder.toExportRequest(Resource.getDefault()).getDictionary();
      assertEquals("", d.getStringTable(0));
      assertEquals(
          io.opentelemetry.proto.profiles.v1development.Mapping.getDefaultInstance(),
          d.getMappingTable(0));
      assertEquals(
          io.opentelemetry.proto.profiles.v1development.Location.getDefaultInstance(),
          d.getLocationTable(0));
      assertEquals(
          io.opentelemetry.proto.profiles.v1development.Function.getDefaultInstance(),
          d.getFunctionTable(0));
      assertEquals(Link.getDefaultInstance(), d.getLinkTable(0));
      assertEquals(KeyValueAndUnit.getDefaultInstance(), d.getAttributeTable(0));
      assertEquals(
          io.opentelemetry.proto.profiles.v1development.Stack.getDefaultInstance(),
          d.getStackTable(0));
    }
  }

  /**
   * Samples recorded slightly after the nominal window (the real JFR rotation runs late) still fall
   * inside the exported [time_unix_nano, time_unix_nano + duration_nano) range; with all samples
   * inside the window the range is the nominal one.
   */
  @Test
  void timeRange_coversSamplesOutsideNominalWindow() {
    long start = 1_700_000_000_000_000_000L;
    long window = 10_000_000_000L;
    OtlpProfileBuilder inside = new OtlpProfileBuilder(start, window, PERIOD_NS);
    inside.addSample(stack(), start + 5, "t", null, null, null);
    Profile p =
        inside
            .toExportRequest(Resource.getDefault())
            .getResourceProfiles(0)
            .getScopeProfiles(0)
            .getProfiles(0);
    assertEquals(start, p.getTimeUnixNano());
    assertEquals(window, p.getDurationNano());

    OtlpProfileBuilder late = new OtlpProfileBuilder(start, window, PERIOD_NS, 524288L);
    long lateTs = start + window + 270_000_000L;
    late.addSample(stack(), start + 5, "t", null, null, null);
    late.addAllocSample(stack(), lateTs, "t", null, null, null, 64L);
    for (Profile q :
        late.toExportRequest(Resource.getDefault())
            .getResourceProfiles(0)
            .getScopeProfiles(0)
            .getProfilesList()) {
      assertEquals(start, q.getTimeUnixNano());
      assertTrue(lateTs < q.getTimeUnixNano() + q.getDurationNano(), "late sample inside range");
    }
  }

  /**
   * Reusing one frame-list instance for many samples (as the JFR scan does) yields exactly the same
   * dictionary and samples as passing a fresh, equal list each time.
   */
  @Test
  void reusedFrameList_producesSameOutputAsFreshEqualLists() {
    long start = 1_700_000_000_000_000_000L;
    OtlpProfileBuilder reused = new OtlpProfileBuilder(start, 60_000_000_000L, PERIOD_NS);
    OtlpProfileBuilder fresh = new OtlpProfileBuilder(start, 60_000_000_000L, PERIOD_NS);
    List<FrameInfo> shared = stack();
    for (int i = 0; i < 5; i++) {
      reused.addSample(shared, start + i, "t", "GET /a", null, null);
      fresh.addSample(stack(), start + i, "t", "GET /a", null, null);
    }
    ExportProfilesServiceRequest a = reused.toExportRequest(Resource.getDefault());
    ExportProfilesServiceRequest b = fresh.toExportRequest(Resource.getDefault());
    assertEquals(b.getDictionary(), a.getDictionary());
    assertEquals(
        b.getResourceProfiles(0).getScopeProfiles(0).getProfiles(0).getSamplesList(),
        a.getResourceProfiles(0).getScopeProfiles(0).getProfiles(0).getSamplesList());
    assertEquals(fresh.getUniqueStackCount(), reused.getUniqueStackCount());
  }

  private static ExportProfilesServiceRequest withoutProfileIds(ExportProfilesServiceRequest r) {
    ExportProfilesServiceRequest.Builder b = r.toBuilder();
    for (int i = 0; i < b.getResourceProfilesCount(); i++) {
      for (int j = 0; j < b.getResourceProfiles(i).getScopeProfilesCount(); j++) {
        for (int k = 0; k < b.getResourceProfiles(i).getScopeProfiles(j).getProfilesCount(); k++) {
          b.getResourceProfilesBuilder(i)
              .getScopeProfilesBuilder(j)
              .getProfilesBuilder(k)
              .clearProfileId();
        }
      }
    }
    return b.build();
  }

  /**
   * The streamed serialization parses to exactly the request {@link
   * OtlpProfileBuilder#toExportRequest} builds (apart from the random profile ids), in every
   * aggregation mode and with allocation samples.
   */
  @Test
  void toExportRequestBytes_parsesToSameRequestAsToExportRequest() throws Exception {
    Resource resource =
        Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "svc"));
    for (int mode :
        new int[] {
          OtlpProfileBuilder.AGG_NONE, OtlpProfileBuilder.AGG_SUM, OtlpProfileBuilder.AGG_FULL
        }) {
      long start = 1_700_000_000_000_000_000L;
      OtlpProfileBuilder b =
          new OtlpProfileBuilder(start, 60_000_000_000L, PERIOD_NS, 524288L, mode);
      List<FrameInfo> s1 = stack();
      for (int i = 0; i < 50; i++) {
        b.addSample(
            s1,
            start + i,
            "exec-" + (i % 3),
            i % 2 == 0 ? "GET /a" : null,
            i % 4 == 0 ? TRACE_ID : null,
            i % 4 == 0 ? SPAN_ID : null);
        b.addAllocSample(stack(), start + i, "exec-1", "GET /a", null, null, 64L * (i + 1));
      }
      ExportProfilesServiceRequest expected = withoutProfileIds(b.toExportRequest(resource));
      ExportProfilesServiceRequest streamed =
          withoutProfileIds(
              ExportProfilesServiceRequest.parseFrom(b.toExportRequestBytes(resource)));
      assertEquals(expected, streamed, "aggregation mode " + mode);
    }
  }

  private OtlpProfileBuilder builderWithSamples() {
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(1_700_000_000_000_000_000L, 60_000_000_000L, PERIOD_NS);
    // Correlated sample: operation + trace link.
    builder.addSample(
        stack(),
        1_700_000_000_001_000_000L,
        "http-nio-8080-exec-1",
        "GET /orders",
        TRACE_ID,
        SPAN_ID);
    // Operation-only sample (no trace link).
    builder.addSample(
        stack(), 1_700_000_000_002_000_000L, "http-nio-8080-exec-2", "GET /health", null, null);
    // Uncorrelated background sample (aggregate profile still carries it).
    builder.addSample(stack(), 1_700_000_000_003_000_000L, "background-worker", null, null, null);
    return builder;
  }

  @Test
  void toExportRequest_roundTripsWithNoUnknownFields() throws Exception {
    OtlpProfileBuilder builder = builderWithSamples();
    Resource resource =
        Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "orders-svc"));

    ExportProfilesServiceRequest request = builder.toExportRequest(resource);
    byte[] bytes = request.toByteArray();

    ExportProfilesServiceRequest parsed = ExportProfilesServiceRequest.parseFrom(bytes);
    assertTrue(parsed.getUnknownFields().asMap().isEmpty(), "no unknown fields expected");
    assertEquals(request, parsed, "serialize -> parseFrom must round-trip identically");

    // Exactly one ResourceProfiles / ScopeProfiles / Profile.
    assertEquals(1, parsed.getResourceProfilesCount());
    ResourceProfiles rp = parsed.getResourceProfiles(0);
    assertEquals(1, rp.getScopeProfilesCount());
    assertEquals(1, rp.getScopeProfiles(0).getProfilesCount());

    // Resource attributes converted.
    assertTrue(rp.hasResource());
    boolean hasServiceName =
        rp.getResource().getAttributesList().stream()
            .anyMatch(
                kv ->
                    kv.getKey().equals("service.name")
                        && kv.getValue().getStringValue().equals("orders-svc"));
    assertTrue(hasServiceName, "resource must carry service.name");

    // Scope name is set.
    assertEquals("serviceevents", rp.getScopeProfiles(0).getScope().getName());
  }

  @Test
  void wallProfile_hasWallNanosecondsSampleTypeAndPeriod() {
    ExportProfilesServiceRequest request =
        builderWithSamples().toExportRequest(Resource.getDefault());
    Profile profile = request.getResourceProfiles(0).getScopeProfiles(0).getProfiles(0);
    ProfilesDictionary dict = request.getDictionary();

    ValueType sampleType = profile.getSampleType();
    assertEquals("wall", dict.getStringTable(sampleType.getTypeStrindex()));
    assertEquals("nanoseconds", dict.getStringTable(sampleType.getUnitStrindex()));

    // period_type mirrors sample_type; period is the wall interval.
    assertEquals("wall", dict.getStringTable(profile.getPeriodType().getTypeStrindex()));
    assertEquals("nanoseconds", dict.getStringTable(profile.getPeriodType().getUnitStrindex()));
    assertEquals(PERIOD_NS, profile.getPeriod());

    assertEquals(1_700_000_000_000_000_000L, profile.getTimeUnixNano());
    assertEquals(60_000_000_000L, profile.getDurationNano());
    // profile_id is 16 bytes.
    assertEquals(16, profile.getProfileId().size());

    assertEquals(3, profile.getSamplesCount());
    for (Sample sample : profile.getSamplesList()) {
      // Each sample carries one wall interval (ns) and its timestamp.
      assertEquals(1, sample.getValuesCount());
      assertEquals(PERIOD_NS, sample.getValues(0));
      assertEquals(1, sample.getTimestampsUnixNanoCount());
    }
  }

  @Test
  void cpuMode_primaryProfileHasCpuNanosecondsSampleTypeAndPeriod() {
    // In cpu mode (PRIMARY_CPU) the primary Profile is emitted as {cpu, nanoseconds} instead of
    // {wall, nanoseconds}; everything else (value = interval ns) is unchanged.
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(
            1_700_000_000_000_000_000L,
            60_000_000_000L,
            PERIOD_NS,
            524288L,
            OtlpProfileBuilder.AGG_NONE,
            OtlpProfileBuilder.PRIMARY_CPU);
    builder.addSample(stack(), 1_700_000_000_001_000_000L, "worker-1", "GET /a", null, null);
    builder.addSample(stack(), 1_700_000_000_002_000_000L, "worker-1", "GET /a", null, null);

    ExportProfilesServiceRequest request = builder.toExportRequest(Resource.getDefault());
    Profile profile = request.getResourceProfiles(0).getScopeProfiles(0).getProfiles(0);
    ProfilesDictionary dict = request.getDictionary();

    ValueType sampleType = profile.getSampleType();
    assertEquals("cpu", dict.getStringTable(sampleType.getTypeStrindex()));
    assertEquals("nanoseconds", dict.getStringTable(sampleType.getUnitStrindex()));
    // period_type mirrors sample_type.
    assertEquals("cpu", dict.getStringTable(profile.getPeriodType().getTypeStrindex()));
    assertEquals("nanoseconds", dict.getStringTable(profile.getPeriodType().getUnitStrindex()));
    assertEquals(PERIOD_NS, profile.getPeriod());
    assertEquals(2, profile.getSamplesCount());
  }

  @Test
  void samples_carryOperationAttributeAndTraceLink() {
    ExportProfilesServiceRequest request =
        builderWithSamples().toExportRequest(Resource.getDefault());
    Profile profile = request.getResourceProfiles(0).getScopeProfiles(0).getProfiles(0);
    ProfilesDictionary dict = request.getDictionary();

    // Index 0 sentinels preserved in every table.
    assertEquals("", dict.getStringTable(0));
    assertEquals(Link.getDefaultInstance(), dict.getLinkTable(0));
    assertTrue(dict.getLinkTable(0).getTraceId().isEmpty());

    // Find the correlated sample (has a non-zero link_index).
    Sample linked =
        profile.getSamplesList().stream()
            .filter(s -> s.getLinkIndex() != 0)
            .findFirst()
            .orElse(null);
    assertNotNull(linked, "expected a sample with a trace link");

    Link link = dict.getLinkTable(linked.getLinkIndex());
    assertEquals(16, link.getTraceId().size(), "trace_id must be 16 bytes");
    assertEquals(8, link.getSpanId().size(), "span_id must be 8 bytes");
    assertEquals(TRACE_ID, toHex(link.getTraceId().toByteArray()));
    assertEquals(SPAN_ID, toHex(link.getSpanId().toByteArray()));

    // The linked sample also carries the operation attribute.
    assertTrue(
        hasOperationAttribute(linked, dict, "GET /orders"),
        "linked sample must carry operation=GET /orders");

    // At least one sample carries operation but NO link (health check).
    boolean healthOperationOnly =
        profile.getSamplesList().stream()
            .anyMatch(s -> s.getLinkIndex() == 0 && hasOperationAttribute(s, dict, "GET /health"));
    assertTrue(healthOperationOnly, "expected an operation-only (unlinked) sample");
  }

  @Test
  void stack_locationIndicesAreLeafFirstPerOtlpSpec() {
    ExportProfilesServiceRequest request =
        builderWithSamples().toExportRequest(Resource.getDefault());
    Profile profile = request.getResourceProfiles(0).getScopeProfiles(0).getProfiles(0);
    ProfilesDictionary dict = request.getDictionary();

    // stack() is [handle (root/caller), doWork (leaf/callee)]. OTLP mandates location_indices be
    // leaf-first ("The first location is the leaf frame."), so the emitted order must be reversed
    // relative to the input: doWork (leaf) first, handle (root) last. Emitting root-first renders
    // the flame graph inverted (thread entry treated as the leaf, self-time mis-attributed to it).
    List<String> functions = stackFunctionNames(profile.getSamples(0), dict);
    assertEquals(
        Arrays.asList("com.example.Service.doWork", "com.example.Service.handle"),
        functions,
        "location_indices must be leaf-first per the OTLP profiles spec");
  }

  @Test
  void allocProfiles_haveExpectedSampleTypesPeriodsAndValues() {
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(1_700_000_000_000_000_000L, 60_000_000_000L, PERIOD_NS, 524288L);
    // Two wall samples + two alloc samples with known byte weights.
    builder.addSample(stack(), 1_700_000_000_001_000_000L, "worker-1", null, null, null);
    builder.addSample(stack(), 1_700_000_000_002_000_000L, "worker-1", null, null, null);
    builder.addAllocSample(
        stack(), 1_700_000_000_003_000_000L, "worker-1", null, null, null, 524_288L);
    builder.addAllocSample(
        stack(), 1_700_000_000_004_000_000L, "worker-1", null, null, null, 1_048_576L);

    ExportProfilesServiceRequest request = builder.toExportRequest(Resource.getDefault());
    ProfilesDictionary dict = request.getDictionary();

    // Three profiles: wall + alloc_space + alloc_objects, all under one ScopeProfiles.
    assertEquals(1, request.getResourceProfilesCount());
    assertEquals(1, request.getResourceProfiles(0).getScopeProfilesCount());
    assertEquals(3, request.getResourceProfiles(0).getScopeProfiles(0).getProfilesCount());

    Profile allocSpace = profileByType(request, "alloc_space");
    assertNotNull(allocSpace, "alloc_space Profile expected");
    assertEquals("bytes", dict.getStringTable(allocSpace.getSampleType().getUnitStrindex()));
    // period_type mirrors sample_type; period is the alloc sampling interval in bytes.
    assertEquals("alloc_space", dict.getStringTable(allocSpace.getPeriodType().getTypeStrindex()));
    assertEquals("bytes", dict.getStringTable(allocSpace.getPeriodType().getUnitStrindex()));
    assertEquals(524288L, allocSpace.getPeriod());
    assertEquals(2, allocSpace.getSamplesCount());
    // Each alloc sample's value is its byte weight; the total is the sum of the weights.
    long spaceTotal = 0;
    for (Sample s : allocSpace.getSamplesList()) {
      assertEquals(1, s.getValuesCount());
      spaceTotal += s.getValues(0);
    }
    assertEquals(524_288L + 1_048_576L, spaceTotal);

    Profile allocObjects = profileByType(request, "alloc_objects");
    assertNotNull(allocObjects, "alloc_objects Profile expected");
    assertEquals("count", dict.getStringTable(allocObjects.getSampleType().getUnitStrindex()));
    assertEquals(
        "alloc_objects", dict.getStringTable(allocObjects.getPeriodType().getTypeStrindex()));
    assertEquals("count", dict.getStringTable(allocObjects.getPeriodType().getUnitStrindex()));
    assertEquals(1L, allocObjects.getPeriod());
    assertEquals(2, allocObjects.getSamplesCount());
    long objectsTotal = 0;
    for (Sample s : allocObjects.getSamplesList()) {
      assertEquals(1, s.getValuesCount());
      objectsTotal += s.getValues(0);
    }
    assertEquals(2L, objectsTotal, "one count unit per allocation event");

    // profile_ids are 16 bytes and distinct across the profiles.
    assertEquals(16, allocSpace.getProfileId().size());
    assertEquals(16, allocObjects.getProfileId().size());
  }

  @Test
  void allocSample_carriesOperationAttributeAndTraceLink() {
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(1_700_000_000_000_000_000L, 60_000_000_000L, PERIOD_NS, 524288L);
    builder.addAllocSample(
        stack(),
        1_700_000_000_005_000_000L,
        "http-nio-8080-exec-9",
        "POST /alloc",
        TRACE_ID,
        SPAN_ID,
        262_144L);

    ExportProfilesServiceRequest request = builder.toExportRequest(Resource.getDefault());
    ProfilesDictionary dict = request.getDictionary();
    Profile allocSpace = profileByType(request, "alloc_space");
    assertNotNull(allocSpace);
    assertEquals(1, allocSpace.getSamplesCount());

    Sample sample = allocSpace.getSamples(0);
    assertEquals(262_144L, sample.getValues(0), "value is the allocation byte weight");
    // Trace Link mirrors wall correlation.
    Link link = dict.getLinkTable(sample.getLinkIndex());
    assertEquals(16, link.getTraceId().size());
    assertEquals(8, link.getSpanId().size());
    assertEquals(TRACE_ID, toHex(link.getTraceId().toByteArray()));
    assertEquals(SPAN_ID, toHex(link.getSpanId().toByteArray()));
    // Operation attribute mirrors wall correlation.
    assertTrue(
        hasOperationAttribute(sample, dict, "POST /alloc"),
        "alloc sample must carry operation=POST /alloc");
  }

  private static Profile profileByType(ExportProfilesServiceRequest request, String type) {
    ProfilesDictionary dict = request.getDictionary();
    for (Profile p : request.getResourceProfiles(0).getScopeProfiles(0).getProfilesList()) {
      if (dict.getStringTable(p.getSampleType().getTypeStrindex()).equals(type)) {
        return p;
      }
    }
    return null;
  }

  /** Resolve a sample's stack to its qualified function names, in emitted (leaf → root) order. */
  private static List<String> stackFunctionNames(Sample sample, ProfilesDictionary dict) {
    List<String> names = new ArrayList<>();
    for (int locIdx : dict.getStackTable(sample.getStackIndex()).getLocationIndicesList()) {
      int fnIdx = dict.getLocationTable(locIdx).getLines(0).getFunctionIndex();
      names.add(dict.getStringTable(dict.getFunctionTable(fnIdx).getNameStrindex()));
    }
    return names;
  }

  private static boolean hasOperationAttribute(
      Sample sample, ProfilesDictionary dict, String expectedValue) {
    for (int idx : sample.getAttributeIndicesList()) {
      KeyValueAndUnit attr = dict.getAttributeTable(idx);
      String key = dict.getStringTable(attr.getKeyStrindex());
      if (key.equals("operation")) {
        // The value is carried inline as AnyValue.string_value (some OTLP profiles backends reject
        // the string_value_strindex interned variant — see OtlpProfileBuilder attribute_table
        // encoding).
        String value = attr.getValue().getStringValue();
        if (value.equals(expectedValue)) {
          return true;
        }
      }
    }
    return false;
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

  @Test
  void wallSample_carriesThreadStateAttributeOnlyWhenProvided() {
    // Wall mode tags each sample with its thread state; cpu mode (and callers with no state) pass
    // null. thread.state matches splunk-otel-java's per-sample label so the backend can slice
    // on-CPU (RUNNABLE) vs off-CPU (SLEEPING).
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(1_700_000_000_000_000_000L, 60_000_000_000L, PERIOD_NS);
    builder.addSample(
        stack(), 1_700_000_000_001_000_000L, "worker-1", "GET /a", null, null, 1, "RUNNABLE");
    builder.addSample(
        stack(), 1_700_000_000_002_000_000L, "worker-2", "GET /a", null, null, 1, (String) null);

    ExportProfilesServiceRequest request = builder.toExportRequest(Resource.getDefault());
    Profile wall = request.getResourceProfiles(0).getScopeProfiles(0).getProfiles(0);
    ProfilesDictionary dict = request.getDictionary();

    assertEquals(2, wall.getSamplesCount());
    long withState =
        wall.getSamplesList().stream()
            .filter(s -> "RUNNABLE".equals(attributeValue(s, dict, "thread.state")))
            .count();
    long withoutState =
        wall.getSamplesList().stream()
            .filter(s -> attributeValue(s, dict, "thread.state") == null)
            .count();
    assertEquals(1, withState, "exactly one sample carries thread.state=RUNNABLE");
    assertEquals(1, withoutState, "the null-state (cpu-mode style) sample carries no thread.state");
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  @Test
  void wallSample_weightedByCoalescingCount() {
    // async-profiler folds repeated identical samples into one ExecutionSample with samples=N; the
    // emitted wall value must be N × period, not a single period.
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(1_700_000_000_000_000_000L, 60_000_000_000L, PERIOD_NS);
    builder.addSample(stack(), 1_700_000_000_001_000_000L, "w", null, null, null, 3);
    Profile wall =
        builder
            .toExportRequest(Resource.getDefault())
            .getResourceProfiles(0)
            .getScopeProfiles(0)
            .getProfiles(0);
    assertEquals(1, wall.getSamplesCount());
    assertEquals(
        3L * PERIOD_NS,
        wall.getSamples(0).getValues(0),
        "wall value must be the coalescing count (3) × period");
  }

  // ---- sample aggregation (OtlpProfileBuilder modes 0=NONE, 1=FULL, 2=SUM; the env knob
  // OTEL_AWS_PROFILER_AGGREGATION_MODE is name-valued none|full|sum and maps to these) ----

  private OtlpProfileBuilder builderForAggregation(int mode) {
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(
            1_700_000_000_000_000_000L, 60_000_000_000L, PERIOD_NS, 524288L, mode);
    // Three wall samples with identical identity (same stack, thread, operation; no trace link).
    builder.addSample(stack(), 1_700_000_000_001_000_000L, "worker-1", "GET /a", null, null);
    builder.addSample(stack(), 1_700_000_000_002_000_000L, "worker-1", "GET /a", null, null);
    builder.addSample(stack(), 1_700_000_000_003_000_000L, "worker-1", "GET /a", null, null);
    // A fourth sample with a different identity (different operation) — must stay separate.
    builder.addSample(stack(), 1_700_000_000_004_000_000L, "worker-1", "GET /b", null, null);
    return builder;
  }

  private static Profile wallProfile(OtlpProfileBuilder b) {
    return b.toExportRequest(Resource.getDefault())
        .getResourceProfiles(0)
        .getScopeProfiles(0)
        .getProfiles(0);
  }

  @Test
  void aggregationNone_emitsOneSamplePerObservation() {
    Profile profile = wallProfile(builderForAggregation(OtlpProfileBuilder.AGG_NONE));
    assertEquals(4, profile.getSamplesCount(), "NONE: one Sample per collected sample");
    for (Sample s : profile.getSamplesList()) {
      assertEquals(1, s.getValuesCount());
      assertEquals(PERIOD_NS, s.getValues(0));
      assertEquals(1, s.getTimestampsUnixNanoCount());
    }
  }

  @Test
  void aggregationFull_mergesIdentityKeepingAllValuesAndTimestamps() {
    ExportProfilesServiceRequest request =
        builderForAggregation(OtlpProfileBuilder.AGG_FULL).toExportRequest(Resource.getDefault());
    Profile profile = request.getResourceProfiles(0).getScopeProfiles(0).getProfiles(0);
    ProfilesDictionary dict = request.getDictionary();

    assertEquals(2, profile.getSamplesCount(), "FULL merges same-identity samples");
    Sample merged = null;
    Sample single = null;
    for (Sample s : profile.getSamplesList()) {
      // Parallel arrays: len(values) == len(timestamps).
      assertEquals(
          s.getTimestampsUnixNanoCount(), s.getValuesCount(), "values parallel to timestamps");
      if (s.getValuesCount() == 3) {
        merged = s;
      } else if (s.getValuesCount() == 1) {
        single = s;
      }
    }
    assertNotNull(merged, "expected the merged sample (3 observations)");
    assertNotNull(single, "expected the distinct sample (1 observation)");
    assertEquals(
        3 * PERIOD_NS,
        merged.getValuesList().stream().mapToLong(Long::longValue).sum(),
        "kept values sum to the total wall time");
    assertEquals(
        Arrays.asList(
            1_700_000_000_001_000_000L, 1_700_000_000_002_000_000L, 1_700_000_000_003_000_000L),
        merged.getTimestampsUnixNanoList(),
        "all 3 timestamps preserved");

    // Correlation preserved: merged sample retains its operation attribute (part of identity).
    boolean hasOperationA =
        merged.getAttributeIndicesList().stream()
            .map(dict::getAttributeTable)
            .anyMatch(
                kv ->
                    "operation".equals(dict.getStringTable(kv.getKeyStrindex()))
                        && "GET /a".equals(kv.getValue().getStringValue()));
    assertTrue(hasOperationA, "merged sample must retain its operation attribute");
  }

  @Test
  void aggregationSum_mergesIntoSummedValueWithNoTimestamps() {
    Profile profile = wallProfile(builderForAggregation(OtlpProfileBuilder.AGG_SUM));
    assertEquals(2, profile.getSamplesCount(), "SUM merges same-identity samples");
    Sample merged = null;
    Sample single = null;
    for (Sample s : profile.getSamplesList()) {
      assertEquals(1, s.getValuesCount(), "SUM: one summed value");
      assertEquals(0, s.getTimestampsUnixNanoCount(), "SUM: no per-observation timestamps");
      if (s.getValues(0) == 3 * PERIOD_NS) {
        merged = s;
      } else if (s.getValues(0) == PERIOD_NS) {
        single = s;
      }
    }
    assertNotNull(merged, "expected the merged sample (3 intervals summed)");
    assertNotNull(single, "expected the distinct sample (1 interval)");
  }

  @Test
  void aggregationFull_allocObjectsUsesEmptyValues_wallAndSpaceKeepWeights() {
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(
            0L, 60_000_000_000L, PERIOD_NS, 524288L, OtlpProfileBuilder.AGG_FULL);
    // one wall sample (value = interval, not 1) + three alloc samples, same identity.
    builder.addSample(stack(), 1_700_000_000_001_000_000L, "worker-1", "GET /a", null, null);
    builder.addAllocSample(
        stack(), 1_700_000_000_002_000_000L, "worker-1", "GET /a", null, null, 100L);
    builder.addAllocSample(
        stack(), 1_700_000_000_003_000_000L, "worker-1", "GET /a", null, null, 200L);
    builder.addAllocSample(
        stack(), 1_700_000_000_004_000_000L, "worker-1", "GET /a", null, null, 300L);
    ScopeProfiles scope =
        builder.toExportRequest(Resource.getDefault()).getResourceProfiles(0).getScopeProfiles(0);
    // Profiles order: [0]=wall, [1]=alloc_space, [2]=alloc_objects.
    Sample wall = scope.getProfiles(0).getSamples(0);
    Sample space = scope.getProfiles(1).getSamples(0);
    Sample objects = scope.getProfiles(2).getSamples(0);

    // wall value != 1 -> FULL keeps explicit parallel values (weight kept).
    assertEquals(1, wall.getValuesCount());
    assertEquals(PERIOD_NS, wall.getValues(0));

    // alloc_space value = variable bytes -> FULL keeps explicit per-sample bytes.
    assertEquals(3, space.getValuesCount(), "space keeps per-sample byte weights");
    assertEquals(3, space.getTimestampsUnixNanoCount());
    assertEquals(600L, space.getValuesList().stream().mapToLong(Long::longValue).sum());

    // alloc_objects value == 1 -> FULL empty-values shape: values=[], timestamps=[3].
    assertEquals(0, objects.getValuesCount(), "count profile uses empty values (1 per timestamp)");
    assertEquals(3, objects.getTimestampsUnixNanoCount(), "one timestamp per counted object");
  }

  @Test
  void aggregation_keepsDistinctTraceLinksSeparate() {
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(
            1_700_000_000_000_000_000L,
            60_000_000_000L,
            PERIOD_NS,
            524288L,
            OtlpProfileBuilder.AGG_SUM);
    // Same stack/thread/operation but two DIFFERENT traces -> must not merge (link is identity).
    builder.addSample(stack(), 1_700_000_000_001_000_000L, "worker-1", "GET /a", TRACE_ID, SPAN_ID);
    builder.addSample(
        stack(),
        1_700_000_000_002_000_000L,
        "worker-1",
        "GET /a",
        "11112222333344445555666677778888",
        "1111222233334444");
    assertEquals(
        2, wallProfile(builder).getSamplesCount(), "different trace links must stay separate");
  }
}
