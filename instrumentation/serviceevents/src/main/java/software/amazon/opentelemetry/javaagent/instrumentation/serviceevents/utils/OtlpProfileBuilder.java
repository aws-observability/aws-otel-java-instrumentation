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

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.profiles.v1development.Function;
import io.opentelemetry.proto.profiles.v1development.KeyValueAndUnit;
import io.opentelemetry.proto.profiles.v1development.Line;
import io.opentelemetry.proto.profiles.v1development.Link;
import io.opentelemetry.proto.profiles.v1development.Location;
import io.opentelemetry.proto.profiles.v1development.Mapping;
import io.opentelemetry.proto.profiles.v1development.Profile;
import io.opentelemetry.proto.profiles.v1development.ProfilesDictionary;
import io.opentelemetry.proto.profiles.v1development.ResourceProfiles;
import io.opentelemetry.proto.profiles.v1development.Sample;
import io.opentelemetry.proto.profiles.v1development.ScopeProfiles;
import io.opentelemetry.proto.profiles.v1development.Stack;
import io.opentelemetry.proto.profiles.v1development.ValueType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds an OTLP profile data model from individual JFR samples using dictionary-based
 * deduplication.
 *
 * <p>Wall samples are added via {@link #addSample} and allocation samples via {@link
 * #addAllocSample}. Structural data (strings, functions, locations, stacks) is deduplicated into
 * shared dictionary tables. {@link #toExportRequest} then hoists those tables into the
 * request-level {@link ProfilesDictionary} and emits separate {@link Profile}s under one {@link
 * ScopeProfiles}: the primary Profile ({@code {wall, nanoseconds}} or {@code {cpu, nanoseconds}})
 * and — when allocation samples exist — an {@code {alloc_space, bytes}} and an {@code
 * {alloc_objects, count}} Profile. Separate profiles are required because {@code sample_type} is
 * singular in the OTLP profiles v1development schema; every Profile sets {@code period_type} /
 * {@code period}.
 */
public final class OtlpProfileBuilder {

  private static final String INSTRUMENTATION_SCOPE = "serviceevents";
  private static final int TRACE_ID_BYTES = 16;
  private static final int SPAN_ID_BYTES = 8;
  private static final int PROFILE_ID_BYTES = 16;

  private static final int INITIAL_SAMPLE_CAPACITY = 65536;

  // Dictionary tables
  private final List<String> stringTable = new ArrayList<>();
  private final Map<String, Integer> stringIndex = new HashMap<>();

  private final List<int[]> functionTable = new ArrayList<>();
  private final Map<Long, Integer> functionIndex = new HashMap<>();

  // [functionIndex, line, mappingIndex]
  private final List<int[]> locationTable = new ArrayList<>();
  // One (function, line) -> location index map per mapping index, so the same symbol in two
  // different libraries stays two distinct locations.
  private final List<Map<Long, Integer>> locationIndexByMapping = new ArrayList<>();

  // Mapping (shared library) table: filename string index per mapping; index 0 = sentinel.
  private final List<Integer> mappingTable = new ArrayList<>();
  private final Map<String, Integer> mappingIndex = new HashMap<>();

  private final List<List<Integer>> stackTable = new ArrayList<>();
  private final Map<List<Integer>, Integer> stackIndex = new HashMap<>();

  private final List<String[]> linkTable = new ArrayList<>(); // [traceId, spanId]
  private final Map<String, Integer> linkIndex = new HashMap<>();

  private final List<int[]> attributeTable = new ArrayList<>();
  private final Map<Long, Integer> attributeIndex = new HashMap<>();

  // Wall sample data stored as growable arrays
  private int sampleCount;
  private int[] sampleStackIndices;
  private long[] sampleTimestamps;
  private int[] sampleLinkIndices;
  private int[][] sampleAttributeIndices;
  // Per-wall-sample value in nanoseconds = the async-profiler ExecutionSample.samples coalescing
  // count × the wall period. async-profiler folds N consecutive identical samples into one JFR
  // ExecutionSample with samples=N, so a fixed period-per-event would undercount wall time N-fold.
  private long[] sampleWallValues;

  // Allocation sample data, stored in parallel growable arrays mirroring the wall arrays
  // plus a per-sample byte weight. Each entry is one async-profiler allocation event
  // (jdk.ObjectAllocationInNewTLAB); allocSampleBytes[i] carries that event's tlabSize (the weight
  // async-profiler's own jfrconv --total sums), so summing it reproduces jfrconv's byte total.
  private int allocSampleCount;
  private int[] allocSampleStackIndices;
  private long[] allocSampleTimestamps;
  private int[] allocSampleLinkIndices;
  private int[][] allocSampleAttributeIndices;
  private long[] allocSampleBytes;

  // Profile metadata
  private final long timeUnixNano;
  private final long durationNano;
  private final long periodNano;
  // period of the alloc_space Profile: async-profiler's alloc= sampling interval in bytes.
  private final long allocPeriodBytes;
  // Sample-aggregation mode (see addSamplesToProfile): 0 NONE, 1 FULL, 2 SUM.
  private final int aggregationMode;
  // Primary profile sample/period type: PRIMARY_WALL -> {wall, nanoseconds}; PRIMARY_CPU ->
  // {cpu, nanoseconds}. Chosen by the profiling mode (wall vs on-CPU); the samples themselves are
  // added the same way (period x coalescing count), only the emitted type/unit label differs.
  private final int primaryType;

  /** No aggregation — one OTLP Sample per collected sample. */
  static final int AGG_NONE = 0;

  /**
   * Merge by identity; keep all per-observation values + timestamps (full fidelity). Uses the most
   * compact lossless OTLP shape per profile: a profile whose every value is 1 in its declared unit
   * ({@code alloc_objects}) emits empty {@code values} + all timestamps (the "1 per timestamp"
   * shape); profiles with real per-sample weights ({@code wall}/{@code cpu} ns, {@code alloc_space}
   * bytes) emit parallel {@code values}∥{@code timestamps}.
   */
  static final int AGG_FULL = 1;

  /** Merge by identity into a summed value with no timestamps (most compact). */
  static final int AGG_SUM = 2;

  /** Primary sample type WALL — the primary Profile emits {@code {wall, nanoseconds}}. */
  public static final int PRIMARY_WALL = 0;

  /**
   * Primary sample type CPU — the primary Profile emits {@code {cpu, nanoseconds}} (on-CPU mode).
   */
  public static final int PRIMARY_CPU = 1;

  // Pre-registered well-known strings
  private final int strWall;
  private final int strCpu;
  private final int strNanoseconds;
  private final int strThreadName;
  private final int strOperation;
  // Per-sample thread state (wall only): "thread.state" with the async-profiler JFR state name,
  // STATE_ stripped — currently RUNNABLE (on-CPU) or SLEEPING (async-profiler collapses all off-CPU
  // into SLEEPING). Key matches splunk-otel-java's per-sample pprof label and the OTel thread.*
  // namespace. Lets the backend slice wall into on-CPU vs off-CPU.
  private final int strThreadState;
  private final int strAllocSpace;
  private final int strAllocObjects;
  private final int strBytes;
  private final int strCount;

  /** Wall-mode convenience constructor (allocation period defaults to 512 KiB). */
  public OtlpProfileBuilder(long timeUnixNano, long durationNano, long periodNano) {
    this(timeUnixNano, durationNano, periodNano, 524288L);
  }

  public OtlpProfileBuilder(
      long timeUnixNano, long durationNano, long periodNano, long allocPeriodBytes) {
    this(timeUnixNano, durationNano, periodNano, allocPeriodBytes, AGG_NONE);
  }

  /**
   * @param aggregationMode 0 NONE (one Sample per sample), 1 FULL (merge by identity, keep all
   *     per-observation values+timestamps; all-1-in-unit profiles use the empty-values shape), 2
   *     SUM (merge by identity into a summed value, no timestamps). All merging modes preserve
   *     correlation (link + attributes are in the identity). See {@link #addSamplesToProfile}.
   */
  public OtlpProfileBuilder(
      long timeUnixNano,
      long durationNano,
      long periodNano,
      long allocPeriodBytes,
      int aggregationMode) {
    this(timeUnixNano, durationNano, periodNano, allocPeriodBytes, aggregationMode, PRIMARY_WALL);
  }

  /**
   * @param primaryType {@link #PRIMARY_WALL} — emit the primary Profile as {@code {wall,
   *     nanoseconds}}; {@link #PRIMARY_CPU} — emit {@code {cpu, nanoseconds}} (on-CPU mode). Only
   *     the primary Profile's sample_type/period_type label changes; sample values are unchanged.
   */
  public OtlpProfileBuilder(
      long timeUnixNano,
      long durationNano,
      long periodNano,
      long allocPeriodBytes,
      int aggregationMode,
      int primaryType) {
    this.timeUnixNano = timeUnixNano;
    this.durationNano = durationNano;
    this.periodNano = periodNano;
    this.allocPeriodBytes = allocPeriodBytes;
    this.aggregationMode = aggregationMode;
    this.primaryType = (primaryType == PRIMARY_CPU) ? PRIMARY_CPU : PRIMARY_WALL;

    this.sampleCount = 0;
    this.sampleStackIndices = new int[INITIAL_SAMPLE_CAPACITY];
    this.sampleTimestamps = new long[INITIAL_SAMPLE_CAPACITY];
    this.sampleLinkIndices = new int[INITIAL_SAMPLE_CAPACITY];
    this.sampleAttributeIndices = new int[INITIAL_SAMPLE_CAPACITY][];
    this.sampleWallValues = new long[INITIAL_SAMPLE_CAPACITY];

    this.allocSampleCount = 0;
    this.allocSampleStackIndices = new int[INITIAL_SAMPLE_CAPACITY];
    this.allocSampleTimestamps = new long[INITIAL_SAMPLE_CAPACITY];
    this.allocSampleLinkIndices = new int[INITIAL_SAMPLE_CAPACITY];
    this.allocSampleAttributeIndices = new int[INITIAL_SAMPLE_CAPACITY][];
    this.allocSampleBytes = new long[INITIAL_SAMPLE_CAPACITY];

    // Index 0 in string_table must be empty string
    internString("");

    // Pre-register well-known strings
    this.strWall = internString("wall");
    this.strCpu = internString("cpu");
    this.strNanoseconds = internString("nanoseconds");
    this.strThreadName = internString("thread.name");
    this.strOperation = internString("operation");
    this.strThreadState = internString("thread.state");
    this.strAllocSpace = internString("alloc_space");
    this.strAllocObjects = internString("alloc_objects");
    this.strBytes = internString("bytes");
    this.strCount = internString("count");

    // Index 0 in all other tables is the zero/sentinel entry
    functionTable.add(new int[] {0, 0, 0, 0});
    functionIndex.put(functionKey(0, 0), 0);

    mappingTable.add(0);
    mappingIndex.put("", 0);
    locationIndexByMapping.add(new HashMap<>());

    locationTable.add(new int[] {0, 0, 0});
    locationIndexByMapping.get(0).put(locationKey(0, 0), 0);

    // Collections.singletonList (not List.of, which is Java 9+) so the profiler module compiles at
    // --release 8; List equality is element-wise, so this [0] sentinel still matches the ArrayList
    // keys internStack builds.
    stackTable.add(Collections.singletonList(0));
    stackIndex.put(Collections.singletonList(0), 0);

    linkTable.add(new String[] {"", ""});
    linkIndex.put("", 0);

    attributeTable.add(new int[] {0, 0});
    attributeIndex.put(attributeKey(0, 0), 0);
  }

  /**
   * Add a single JFR sample to the profile.
   *
   * @param frames Stack trace frames ordered root → leaf
   * @param timestampNs Sample timestamp in epoch nanoseconds
   * @param threadName Thread that produced this sample
   * @param operation HTTP operation if the thread was serving a request, or null
   * @param traceId Trace ID for correlation (sampled traces only), or null
   */
  public void addSample(
      List<FrameInfo> frames,
      long timestampNs,
      String threadName,
      String operation,
      String traceId,
      String spanId) {
    addSample(frames, timestampNs, threadName, operation, traceId, spanId, 1);
  }

  /**
   * Add a wall sample carrying async-profiler's {@code ExecutionSample.samples} coalescing count.
   * The emitted wall value is {@code max(1, wallSampleCount) × period} ns, so folded samples
   * (samples &gt; 1) are weighted correctly rather than counted as a single period.
   *
   * @param wallSampleCount the JFR {@code ExecutionSample.samples} multiplier for this event
   */
  public void addSample(
      List<FrameInfo> frames,
      long timestampNs,
      String threadName,
      String operation,
      String traceId,
      String spanId,
      int wallSampleCount) {
    addSample(frames, timestampNs, threadName, operation, traceId, spanId, wallSampleCount, null);
  }

  /**
   * Add a wall sample that also carries the sampling thread's state as a {@code thread.state}
   * attribute (wall mode only — in cpu mode every sample is on-CPU, so callers pass {@code null}).
   *
   * @param threadState async-profiler JFR thread-state name with the {@code STATE_} prefix stripped
   *     ({@code RUNNABLE}/{@code SLEEPING}/{@code PARKED}/...), or null to omit
   */
  public void addSample(
      List<FrameInfo> frames,
      long timestampNs,
      String threadName,
      String operation,
      String traceId,
      String spanId,
      int wallSampleCount,
      String threadState) {

    if (frames == null || frames.isEmpty()) {
      return;
    }

    ensureCapacity();

    int idx = sampleCount++;
    sampleStackIndices[idx] = internStack(frames);
    sampleTimestamps[idx] = timestampNs;
    sampleLinkIndices[idx] = linkIndexFor(traceId, spanId);
    sampleAttributeIndices[idx] = attributeIndicesFor(threadName, operation, threadState);
    sampleWallValues[idx] = (long) Math.max(1, wallSampleCount) * periodNano;
  }

  /**
   * Add a single allocation sample. Mirrors {@link #addSample} — same stack interning, thread.name
   * / operation attributes and trace {@link Link} correlation — plus the per-event byte weight
   * ({@code bytes}, async-profiler's {@code tlabSize} from a {@code jdk.ObjectAllocationInNewTLAB}
   * event). Alloc samples are stored separately and emitted as their own {@code alloc_space} /
   * {@code alloc_objects} Profile in {@link #toExportRequest}, since {@code sample_type} is
   * singular in the OTLP profiles schema.
   *
   * @param frames Stack trace frames ordered root → leaf
   * @param timestampNs Sample timestamp in epoch nanoseconds
   * @param threadName Thread that produced this allocation
   * @param operation HTTP operation if the thread was serving a request, or null
   * @param traceId Trace ID for correlation (sampled traces only), or null
   * @param spanId Span ID for correlation, or null
   * @param bytes async-profiler per-event allocation weight in bytes (tlabSize)
   */
  public void addAllocSample(
      List<FrameInfo> frames,
      long timestampNs,
      String threadName,
      String operation,
      String traceId,
      String spanId,
      long bytes) {

    if (frames == null || frames.isEmpty()) {
      return;
    }

    ensureAllocCapacity();

    int idx = allocSampleCount++;
    allocSampleStackIndices[idx] = internStack(frames);
    allocSampleTimestamps[idx] = timestampNs;
    allocSampleLinkIndices[idx] = linkIndexFor(traceId, spanId);
    allocSampleAttributeIndices[idx] = attributeIndicesFor(threadName, operation);
    allocSampleBytes[idx] = bytes;
  }

  /** Interned link index for a trace/span pair, or 0 (sentinel) when there is no trace. */
  private int linkIndexFor(String traceId, String spanId) {
    return (traceId != null && !traceId.isEmpty()) ? internLink(traceId, spanId) : 0;
  }

  /** Interned per-sample attribute indices (thread.name, operation), or null when none apply. */
  private int[] attributeIndicesFor(String threadName, String operation) {
    return attributeIndicesFor(threadName, operation, null);
  }

  /**
   * Interned per-sample attribute indices (thread.name, operation, and — wall only — thread.state),
   * or null when none apply.
   */
  private int[] attributeIndicesFor(String threadName, String operation, String threadState) {
    List<Integer> attrIndices = new ArrayList<>(3);
    if (threadName != null && !threadName.isEmpty()) {
      attrIndices.add(internAttribute(strThreadName, internString(threadName)));
    }
    if (operation != null && !operation.isEmpty()) {
      attrIndices.add(internAttribute(strOperation, internString(operation)));
    }
    if (threadState != null && !threadState.isEmpty()) {
      attrIndices.add(internAttribute(strThreadState, internString(threadState)));
    }
    return attrIndices.isEmpty()
        ? null
        : attrIndices.stream().mapToInt(Integer::intValue).toArray();
  }

  /**
   * Whether the primary profile is {@code {wall, nanoseconds}} (vs {@code {cpu, nanoseconds}}).
   * Callers gate the wall-only {@code thread.state} attribute on this — in cpu mode every sample is
   * on-CPU (STATE_DEFAULT), so the attribute would be a useless constant.
   */
  public boolean isPrimaryWall() {
    return primaryType == PRIMARY_WALL;
  }

  public int getSampleCount() {
    return sampleCount;
  }

  public int getAllocSampleCount() {
    return allocSampleCount;
  }

  public int getUniqueStackCount() {
    return stackTable.size();
  }

  /**
   * Convert this builder's interned tables + samples into a native OTLP {@link
   * ExportProfilesServiceRequest} protobuf.
   *
   * <p>The interned dictionary tables are hoisted verbatim into the request-level {@link
   * ProfilesDictionary} (shared across all profiles per the v1development schema), preserving the
   * index-0 sentinels. A primary {@link Profile} is always emitted ({@code sample_type} {@code
   * {wall, nanoseconds}} or {@code {cpu, nanoseconds}}, matching {@code period_type}, the sampling
   * interval as {@code period}); when allocation samples exist, an {@code {alloc_space, bytes}}
   * Profile (per-sample value = allocated bytes, {@code period} = the alloc sampling interval) and
   * an {@code {alloc_objects, count}} Profile (per-sample value = 1, {@code period} = 1) are
   * emitted alongside it under the same {@link ScopeProfiles}. Each accumulated sample becomes one
   * {@link Sample}: its {@code stack_index}, its {@code attribute_indices} (operation / thread.name
   * / thread.state), its {@code link_index} (0 when no trace), and its values and timestamps per
   * the aggregation mode. Interned link entries are decoded from hex into the 16-byte trace-id /
   * 8-byte span-id {@link Link} bytes.
   *
   * @param resource the resolved SDK resource, converted into the proto {@link
   *     io.opentelemetry.proto.resource.v1.Resource} attached to the {@link ResourceProfiles}
   * @return the fully-populated export request; {@code req.toByteArray()} round-trips via {@code
   *     ExportProfilesServiceRequest.parseFrom} with no unknown fields
   */
  public ExportProfilesServiceRequest toExportRequest(
      io.opentelemetry.sdk.resources.Resource resource) {
    ProfilesDictionary.Builder dictionary = ProfilesDictionary.newBuilder();

    // string_table (index 0 == "")
    dictionary.addAllStringTable(stringTable);

    // mapping_table: index 0 is the empty sentinel ("mapping unknown or not applicable", used by
    // Java
    // frames); one entry per shared library that native/C++ frames came from, with filename = the
    // library as recorded by async-profiler (e.g. "libc.so.6"). Addresses/build ids are not in the
    // JFR, so only the filename is set.
    for (int filenameStrindex : mappingTable) {
      dictionary.addMappingTable(
          Mapping.newBuilder().setFilenameStrindex(filenameStrindex).build());
    }

    // function_table (index 0 == all-zero sentinel)
    for (int[] f : functionTable) {
      dictionary.addFunctionTable(
          Function.newBuilder()
              .setNameStrindex(f[0])
              .setSystemNameStrindex(f[1])
              .setFilenameStrindex(f[2])
              .setStartLine(f[3])
              .build());
    }

    // location_table (index 0 == {0,0,0} sentinel). Each location carries exactly one line; JFR
    // does
    // not expose a column, so only function_index + line are set, plus the mapping (library) for
    // native/C++ frames.
    for (int[] loc : locationTable) {
      dictionary.addLocationTable(
          Location.newBuilder()
              .setMappingIndex(loc[2])
              .addLines(Line.newBuilder().setFunctionIndex(loc[0]).setLine(loc[1]).build())
              .build());
    }

    // link_table (index 0 == empty sentinel). Hex trace/span ids decode to the fixed-width bytes.
    for (String[] link : linkTable) {
      dictionary.addLinkTable(
          Link.newBuilder()
              .setTraceId(hexToByteString(link[0], TRACE_ID_BYTES))
              .setSpanId(hexToByteString(link[1], SPAN_ID_BYTES))
              .build());
    }

    // attribute_table (index 0 == {0,0} sentinel). The value is emitted as an inline
    // AnyValue.string_value for backend compatibility (some backends reject the
    // string_value_strindex interned variant). attr[1] is the value's string-table index, so
    // resolve it back to the literal string. Keys remain interned via key_strindex, and
    // attribute_table entries are still deduped upstream.
    for (int[] attr : attributeTable) {
      dictionary.addAttributeTable(
          KeyValueAndUnit.newBuilder()
              .setKeyStrindex(attr[0])
              .setValue(AnyValue.newBuilder().setStringValue(stringTable.get(attr[1])).build())
              .build());
    }

    // stack_table (index 0 == [0] sentinel)
    for (List<Integer> locIndices : stackTable) {
      dictionary.addStackTable(Stack.newBuilder().addAllLocationIndices(locIndices).build());
    }

    // sample_type is singular in the OTLP profiles schema, so each value type is a separate
    // Profile. All profiles share the one request-level ProfilesDictionary above and ride under one
    // ScopeProfiles. Every Profile sets period_type + period (Pyroscope requires it).
    ScopeProfiles.Builder scopeProfilesBuilder =
        ScopeProfiles.newBuilder()
            .setScope(InstrumentationScope.newBuilder().setName(INSTRUMENTATION_SCOPE).build());

    // Primary Profile: sample_type/period_type = {wall, nanoseconds} (wall mode) or {cpu,
    // nanoseconds} (on-CPU mode); each sample's value is its async-profiler coalescing count × the
    // sampling period (ns) — see sampleWallValues — so the values sum to the total attributed time
    // even when async-profiler folds repeated samples.
    int primaryTypeStr = (primaryType == PRIMARY_CPU) ? strCpu : strWall;
    ValueType primaryNanos =
        ValueType.newBuilder()
            .setTypeStrindex(primaryTypeStr)
            .setUnitStrindex(strNanoseconds)
            .build();
    scopeProfilesBuilder.addProfiles(
        buildProfile(
            primaryNanos,
            primaryNanos,
            periodNano,
            sampleCount,
            sampleStackIndices,
            sampleTimestamps,
            sampleLinkIndices,
            sampleAttributeIndices,
            sampleWallValues,
            0L));

    // Allocation Profiles, only when allocation samples were recorded.
    if (allocSampleCount > 0) {
      // alloc_space: sample_type/period_type = {alloc_space, bytes}; per-sample value is the
      // event's byte weight (tlabSize), so the total matches async-profiler jfrconv's
      // --alloc --total byte figure. period = async-profiler's alloc= sampling interval (bytes).
      ValueType allocSpaceBytes =
          ValueType.newBuilder().setTypeStrindex(strAllocSpace).setUnitStrindex(strBytes).build();
      scopeProfilesBuilder.addProfiles(
          buildProfile(
              allocSpaceBytes,
              allocSpaceBytes,
              allocPeriodBytes,
              allocSampleCount,
              allocSampleStackIndices,
              allocSampleTimestamps,
              allocSampleLinkIndices,
              allocSampleAttributeIndices,
              allocSampleBytes,
              0L));

      // alloc_objects: sample_type/period_type = {alloc_objects, count}; per-sample value is 1
      // (one sampled allocation event = one counted object), so the total matches jfrconv's
      // default --alloc count. period = 1 (the natural count unit).
      ValueType allocObjectsCount =
          ValueType.newBuilder().setTypeStrindex(strAllocObjects).setUnitStrindex(strCount).build();
      scopeProfilesBuilder.addProfiles(
          buildProfile(
              allocObjectsCount,
              allocObjectsCount,
              1L,
              allocSampleCount,
              allocSampleStackIndices,
              allocSampleTimestamps,
              allocSampleLinkIndices,
              allocSampleAttributeIndices,
              null,
              1L));
    }

    ScopeProfiles scopeProfiles = scopeProfilesBuilder.build();

    ResourceProfiles resourceProfiles =
        ResourceProfiles.newBuilder()
            .setResource(toProtoResource(resource))
            .addScopeProfiles(scopeProfiles)
            .build();

    return ExportProfilesServiceRequest.newBuilder()
        .addResourceProfiles(resourceProfiles)
        .setDictionary(dictionary.build())
        .build();
  }

  /**
   * Build one {@link Profile} from a parallel array of samples. All profiles emitted by this
   * builder share the same interned dictionary tables and the same {@code time_unix_nano} / {@code
   * duration_nano} window; they differ only in {@code sample_type} / {@code period_type} / {@code
   * period} and per-sample value. Each profile gets a fresh 16-byte {@code profile_id}.
   *
   * @param perSampleValue when non-null, {@code perSampleValue[i]} is sample {@code i}'s value;
   *     otherwise {@code fixedValue} is used for every sample
   */
  private Profile buildProfile(
      ValueType sampleType,
      ValueType periodType,
      long period,
      int count,
      int[] stackIndices,
      long[] timestamps,
      int[] linkIndices,
      int[][] attributeIndices,
      long[] perSampleValue,
      long fixedValue) {
    Profile.Builder profile =
        Profile.newBuilder()
            .setSampleType(sampleType)
            .setTimeUnixNano(timeUnixNano)
            .setDurationNano(durationNano)
            .setPeriodType(periodType)
            .setPeriod(period)
            .setProfileId(
                hexToByteString(UUID.randomUUID().toString().replace("-", ""), PROFILE_ID_BYTES));

    addSamplesToProfile(
        profile,
        count,
        stackIndices,
        timestamps,
        linkIndices,
        attributeIndices,
        perSampleValue,
        fixedValue);
    return profile.build();
  }

  /**
   * Emit the window's samples into {@code profile} per {@link #aggregationMode}:
   *
   * <ul>
   *   <li>{@code AGG_NONE} — one {@link Sample} per collected sample (values=[v], timestamps=[ts]).
   *   <li>{@code AGG_FULL} — merge by identity {@code (stack, link, attributes)}; keep all
   *       per-observation values + timestamps (full fidelity; dedups the repeated per-sample
   *       identity fields), using the most compact lossless shape per profile: a profile whose
   *       per-sample values are all 1 in its declared unit ({@code alloc_objects}) emits {@code
   *       values=[]} with all timestamps (the OTLP "assume 1 per timestamp" shape), while profiles
   *       with real weights — {@code wall}/{@code cpu} (count × interval ns) and {@code
   *       alloc_space} (variable bytes) — emit parallel {@code values}∥{@code timestamps} so those
   *       weights are never lost.
   *   <li>{@code AGG_SUM} — merge by identity into {@code values=[total], timestamps_unix_nano=[]}
   *       (the compact "aggregated value" shape). The only mode whose size scales with the distinct
   *       identity count rather than the raw sample count, so it lets a fine sample interval fit
   *       under a backend's per-request cap.
   * </ul>
   *
   * <p>All merging modes preserve trace/operation correlation because the trace {@code link} and
   * attributes are part of the identity. SUM's trade-off is losing per-observation timestamps (no
   * in-window time-series); the window is still bounded by {@code time_unix_nano} + {@code
   * duration_nano}. The OTLP schema requires {@code len(values) == len(timestamps)} when both are
   * populated, which every shape above satisfies.
   */
  private void addSamplesToProfile(
      Profile.Builder profile,
      int count,
      int[] stackIndices,
      long[] timestamps,
      int[] linkIndices,
      int[][] attributeIndices,
      long[] perSampleValue,
      long fixedValue) {
    if (aggregationMode == AGG_NONE) {
      for (int i = 0; i < count; i++) {
        long value = perSampleValue != null ? perSampleValue[i] : fixedValue;
        Sample.Builder sample =
            Sample.newBuilder()
                .setStackIndex(stackIndices[i])
                .addValues(value)
                .addTimestampsUnixNano(timestamps[i]);
        if (linkIndices[i] != 0) {
          sample.setLinkIndex(linkIndices[i]);
        }
        int[] attrs = attributeIndices[i];
        if (attrs != null) {
          for (int a : attrs) {
            sample.addAttributeIndices(a);
          }
        }
        profile.addSamples(sample.build());
      }
      return;
    }

    // FULL uses the empty-values "1 per timestamp" shape ONLY when every value is 1 in the
    // profile's declared unit (alloc_objects: fixedValue == 1 with no per-sample values).
    // Profiles with real weights fall back to explicit parallel values: the wall/cpu profile
    // (count × sampling interval ns, not 1) and the allocation *size* profile (alloc_space,
    // per-sample byte weights) — otherwise those weights would be lost.
    boolean useEmptyValuesShape =
        aggregationMode == AGG_FULL && perSampleValue == null && fixedValue == 1L;

    // Merge by identity. LinkedHashMap preserves first-seen order for deterministic output.
    LinkedHashMap<String, AggregatedSample> groups = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      int[] attrs = attributeIndices[i];
      String key = stackIndices[i] + "|" + linkIndices[i] + "|" + Arrays.toString(attrs);
      AggregatedSample agg = groups.get(key);
      if (agg == null) {
        agg = new AggregatedSample(stackIndices[i], linkIndices[i], attrs);
        groups.put(key, agg);
      }
      agg.values.add(perSampleValue != null ? perSampleValue[i] : fixedValue);
      agg.timestamps.add(timestamps[i]);
    }
    for (AggregatedSample agg : groups.values()) {
      Sample.Builder sample = Sample.newBuilder().setStackIndex(agg.stackIndex);
      if (aggregationMode == AGG_SUM) {
        long sum = 0L;
        for (long v : agg.values) {
          sum += v;
        }
        sample.addValues(sum); // values=[total], timestamps=[]
      } else if (useEmptyValuesShape) {
        for (long ts : agg.timestamps) { // values=[], timestamps=[all] (count = 1/ts)
          sample.addTimestampsUnixNano(ts);
        }
      } else { // FULL, non-all-1 profiles: parallel values + timestamps
        for (long v : agg.values) {
          sample.addValues(v);
        }
        for (long ts : agg.timestamps) {
          sample.addTimestampsUnixNano(ts);
        }
      }
      if (agg.linkIndex != 0) {
        sample.setLinkIndex(agg.linkIndex);
      }
      if (agg.attrs != null) {
        for (int a : agg.attrs) {
          sample.addAttributeIndices(a);
        }
      }
      profile.addSamples(sample.build());
    }
  }

  /** Accumulator for one merged (stack + link + attributes) identity. */
  private static final class AggregatedSample {
    final int stackIndex;
    final int linkIndex;
    final int[] attrs;
    // Parallel per-observation arrays (values[i] measured at timestamps[i]).
    final List<Long> values = new ArrayList<>();
    final List<Long> timestamps = new ArrayList<>();

    AggregatedSample(int stackIndex, int linkIndex, int[] attrs) {
      this.stackIndex = stackIndex;
      this.linkIndex = linkIndex;
      this.attrs = attrs;
    }
  }

  /** Convert an SDK {@link io.opentelemetry.sdk.resources.Resource} to the proto Resource. */
  private static io.opentelemetry.proto.resource.v1.Resource toProtoResource(
      io.opentelemetry.sdk.resources.Resource resource) {
    io.opentelemetry.proto.resource.v1.Resource.Builder builder =
        io.opentelemetry.proto.resource.v1.Resource.newBuilder();
    if (resource != null) {
      resource
          .getAttributes()
          .forEach(
              (key, value) ->
                  builder.addAttributes(
                      KeyValue.newBuilder()
                          .setKey(key.getKey())
                          .setValue(attributeToAnyValue(value))
                          .build()));
    }
    return builder.build();
  }

  /** Map a resource attribute value to a proto AnyValue. Arrays/other types are stringified. */
  private static AnyValue attributeToAnyValue(Object value) {
    if (value instanceof String) {
      return AnyValue.newBuilder().setStringValue((String) value).build();
    }
    if (value instanceof Boolean) {
      return AnyValue.newBuilder().setBoolValue((Boolean) value).build();
    }
    if (value instanceof Long || value instanceof Integer) {
      return AnyValue.newBuilder().setIntValue(((Number) value).longValue()).build();
    }
    if (value instanceof Double || value instanceof Float) {
      return AnyValue.newBuilder().setDoubleValue(((Number) value).doubleValue()).build();
    }
    return AnyValue.newBuilder().setStringValue(String.valueOf(value)).build();
  }

  /**
   * Decode a hex string into a {@link ByteString} of exactly {@code expectedBytes} length. Empty /
   * null input (the sentinel entries) yields {@link ByteString#EMPTY}. Odd-length or malformed
   * input is truncated at the last decodable byte; over-length input is truncated to {@code
   * expectedBytes}.
   */
  private static ByteString hexToByteString(String hex, int expectedBytes) {
    if (hex == null || hex.isEmpty()) {
      return ByteString.EMPTY;
    }
    int usableChars = Math.min(hex.length() - (hex.length() % 2), expectedBytes * 2);
    byte[] out = new byte[usableChars / 2];
    for (int i = 0; i < out.length; i++) {
      int hi = Character.digit(hex.charAt(i * 2), 16);
      int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
      if (hi < 0 || lo < 0) {
        return ByteString.copyFrom(Arrays.copyOf(out, i));
      }
      out[i] = (byte) ((hi << 4) | lo);
    }
    return ByteString.copyFrom(out);
  }

  // --- Dictionary interning methods ---

  private int internString(String s) {
    if (s == null) {
      return 0;
    }
    Integer idx = stringIndex.get(s);
    if (idx != null) {
      return idx;
    }
    int newIdx = stringTable.size();
    stringTable.add(s);
    stringIndex.put(s, newIdx);
    return newIdx;
  }

  private int internFunction(FrameInfo frame) {
    // Function.name is what pprof-style UIs (Pyroscope) render. A Java frame is the fully-qualified
    // "com.example.Foo.bar" so the flame graph disambiguates methods by class (async-profiler's
    // converter with --dot). A frame with no declaring class — native/C++/kernel functions,
    // whose library goes in the Mapping instead, and JVM stubs / async-profiler markers — is the
    // bare symbol (e.g. "start_thread", "break_deopt"). A Java frame with no method name is just
    // its class, as in async-profiler.
    String name =
        frame.typeName.isEmpty()
            ? frame.methodName
            : frame.methodName.isEmpty() ? frame.typeName : frame.typeName + "." + frame.methodName;
    int nameStr = internString(name);
    // OTLP: filename is "Source file containing the function. Empty string if not available." The
    // JFR scan sets it for Java frames only; it is empty for native, C++ and kernel frames.
    int fileNameStr = internString(frame.fileName);
    // system_name ("function name, as identified by the system", e.g. a C++ mangled name) is left
    // unset: the JFR has no such distinct name for these frames.
    long key = functionKey(nameStr, fileNameStr);
    Integer idx = functionIndex.get(key);
    if (idx != null) {
      return idx;
    }
    int newIdx = functionTable.size();
    // OTLP Function.start_line spec: "Line number in source file. 0 means unset."
    // This is the function's DECLARATION line (where the function starts in source),
    // distinct from the per-sample call-site line which lives on Location.lines[].line.
    // JFR frames don't expose method declaration lines (only the line where execution was
    // when sampled), so 0 ("unset") is emitted rather than a wrong value.
    functionTable.add(new int[] {nameStr, 0, fileNameStr, 0});
    functionIndex.put(key, newIdx);
    return newIdx;
  }

  /** Interned mapping (shared library) index for a native frame's library; 0 when none. */
  private int internMapping(String library) {
    if (library == null || library.isEmpty()) {
      return 0;
    }
    Integer idx = mappingIndex.get(library);
    if (idx != null) {
      return idx;
    }
    int newIdx = mappingTable.size();
    mappingTable.add(internString(library));
    mappingIndex.put(library, newIdx);
    locationIndexByMapping.add(new HashMap<>());
    return newIdx;
  }

  private int internLocation(FrameInfo frame) {
    int funcIdx = internFunction(frame);
    int mappingIdx = internMapping(frame.libraryName);
    Map<Long, Integer> index = locationIndexByMapping.get(mappingIdx);
    long key = locationKey(funcIdx, frame.lineNumber);
    Integer idx = index.get(key);
    if (idx != null) {
      return idx;
    }
    int newIdx = locationTable.size();
    locationTable.add(new int[] {funcIdx, frame.lineNumber, mappingIdx});
    index.put(key, newIdx);
    return newIdx;
  }

  private int internStack(List<FrameInfo> frames) {
    // OTLP requires Stack.location_indices to be LEAF-FIRST — "The first location is the leaf
    // frame." (opentelemetry profiles.proto), matching the pprof convention that Pyroscope and
    // other profiles backends consume. addSample/addAllocSample receive frames root → leaf, so
    // emit them reversed here (leaf → root). Emitting root-first renders the flame graph inverted:
    // the thread entry (e.g. Thread.run) is treated as the leaf and self-time is mis-attributed to
    // it, while real leaves appear as roots.
    List<Integer> locIndices = new ArrayList<>(frames.size());
    for (int i = frames.size() - 1; i >= 0; i--) {
      locIndices.add(internLocation(frames.get(i)));
    }

    Integer idx = stackIndex.get(locIndices);
    if (idx != null) {
      return idx;
    }
    int newIdx = stackTable.size();
    stackTable.add(locIndices);
    stackIndex.put(locIndices, newIdx);
    return newIdx;
  }

  private int internLink(String traceId, String spanId) {
    String key = traceId + ":" + (spanId != null ? spanId : "");
    Integer idx = linkIndex.get(key);
    if (idx != null) {
      return idx;
    }
    int newIdx = linkTable.size();
    linkTable.add(new String[] {traceId, spanId != null ? spanId : ""});
    linkIndex.put(key, newIdx);
    return newIdx;
  }

  private int internAttribute(int keyStrindex, int valueStrindex) {
    long key = attributeKey(keyStrindex, valueStrindex);
    Integer idx = attributeIndex.get(key);
    if (idx != null) {
      return idx;
    }
    int newIdx = attributeTable.size();
    attributeTable.add(new int[] {keyStrindex, valueStrindex});
    attributeIndex.put(key, newIdx);
    return newIdx;
  }

  // --- Key generation helpers (pack two ints into a long for fast HashMap lookup) ---

  private static long functionKey(int nameStr, int fileNameStr) {
    // Exact: two non-negative 32-bit string indices, so distinct functions can never collide.
    return ((long) nameStr << 32) | (fileNameStr & 0xFFFFFFFFL);
  }

  private static long locationKey(int funcIdx, int line) {
    return ((long) funcIdx << 32) | (line & 0xFFFFFFFFL);
  }

  private static long attributeKey(int keyStrindex, int valueStrindex) {
    return ((long) keyStrindex << 32) | (valueStrindex & 0xFFFFFFFFL);
  }

  private void ensureCapacity() {
    if (sampleCount >= sampleStackIndices.length) {
      int newCapacity = sampleStackIndices.length * 2;
      sampleStackIndices = Arrays.copyOf(sampleStackIndices, newCapacity);
      sampleTimestamps = Arrays.copyOf(sampleTimestamps, newCapacity);
      sampleLinkIndices = Arrays.copyOf(sampleLinkIndices, newCapacity);
      sampleAttributeIndices = Arrays.copyOf(sampleAttributeIndices, newCapacity);
      sampleWallValues = Arrays.copyOf(sampleWallValues, newCapacity);
    }
  }

  private void ensureAllocCapacity() {
    if (allocSampleCount >= allocSampleStackIndices.length) {
      int newCapacity = allocSampleStackIndices.length * 2;
      allocSampleStackIndices = Arrays.copyOf(allocSampleStackIndices, newCapacity);
      allocSampleTimestamps = Arrays.copyOf(allocSampleTimestamps, newCapacity);
      allocSampleLinkIndices = Arrays.copyOf(allocSampleLinkIndices, newCapacity);
      allocSampleAttributeIndices = Arrays.copyOf(allocSampleAttributeIndices, newCapacity);
      allocSampleBytes = Arrays.copyOf(allocSampleBytes, newCapacity);
    }
  }
}
