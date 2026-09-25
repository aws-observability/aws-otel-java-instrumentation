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

import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.sdk.resources.Resource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import one.jfr.ClassRef;
import one.jfr.JfrReader;
import one.jfr.MethodRef;
import one.jfr.StackTrace;
import one.jfr.event.AllocationSample;
import one.jfr.event.Event;
import one.jfr.event.ExecutionSample;
import one.jfr.event.SpanEvent;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ProfilesExporter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsOtlpEmitter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.AsyncProfilerWrapper;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.FrameInfo;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.OtlpProfileBuilder;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.ProfilerDataDir;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.ProfilerSpanTag;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.SpanMetadata;

/**
 * Processes profiler data at JFR file rotation boundaries.
 *
 * <p>Waits for JFR file rotation (once per window), then reads the fully-finalized rotated file in
 * a single pass.
 *
 * <p>Produces two types of output from a single JFR scan:
 *
 * <ul>
 *   <li><b>Global aggregate profile</b>: All samples merged into one call tree
 *   <li><b>Per-operation aggregate profiles</b>: Samples attributed to specific HTTP operations via
 *       {@code profiler.Span} correlation
 * </ul>
 *
 * <p><b>Correlation source.</b> The per-thread request-interval index is built from {@code
 * profiler.Span} JFR events — the async-profiler 4.5 markers written by {@link
 * software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.ServiceEventsSpanProcessor}
 * via {@code one.profiler.Span.start()/end(tag)}. Each event supplies the request thread, its
 * {@code [startNs, startNs+timeSpan]} interval, and a {@link ProfilerSpanTag} (operation + optional
 * trace/span ids). Samples are then attributed with the SAME per-thread {@code floorEntry} + strict
 * {@code startNs < ts < endNs} containment join used for both wall and alloc.
 *
 * <p>The JFR is read with async-profiler's own pure-Java parser {@link one.jfr.JfrReader} (Java-8
 * bytecode, no {@code jdk.jfr.consumer} dependency), so the profiler runs on Java 8+. Uses stack
 * trace deduplication (caching formatted frames by JFR stack-trace id, cleared per chunk since ids
 * are only meaningful within their chunk) to avoid redundant frame formatting of repeated stacks.
 */
public class RotationBoundaryProcessor extends BaseCollector {

  private static final Logger logger = Logger.getLogger(RotationBoundaryProcessor.class.getName());

  private final AsyncProfilerWrapper asyncProfilerWrapper;
  private final long windowMs;
  private final ProfilesExporter profilesExporter;
  // When true, function_table.filename emits the fully-qualified class path
  // (e.g. "com/example/Foo.java") instead of the simple file name ("Foo.java").
  // Internal: hardcoded default false, no env override.
  private final boolean profilerFullPaths;
  private final int aggregationMode;

  /**
   * Operational-safety: total-bytes backstop for the per-PID data dir. Complements the hardcoded
   * time-based JFR retention (see {@code AsyncProfilerWrapper.RETENTION_WINDOWS}); when the dir's
   * {@code *.jfr} exceed this, the oldest are evicted (never the active newest) in {@link
   * #cleanupOldFiles()}. This is a footprint backstop, not a customer tuning dial, so it is a
   * hardcoded constant with no env override — like the time-retention it complements. Sized well
   * above the healthy working set, so it never evicts a file still inside the retention window in
   * normal operation; it only engages under pathological pile-up.
   */
  private static final long DATA_DIR_MAX_BYTES = 128L * 1024L * 1024L; // 128 MiB

  /**
   * Max enclosing-span lookback when correlating a sample to its request span. Request-boundary
   * (SERVER / local-root) spans nest shallowly per thread, so a small cap finds any realistic
   * enclosing span while keeping a gap sample (one inside no span) from scanning the whole
   * per-thread {@code TreeMap} — the O(N)-per-sample worst case at high request volume.
   */
  private static final int MAX_SPAN_WALK = 16;

  /** Tracks known JFR files to detect rotation. */
  private final Set<String> knownJfrFiles = new HashSet<>();

  /**
   * Initialize the rotation boundary processor.
   *
   * @param checkIntervalMs How often to check for new rotated JFR files (milliseconds)
   * @param asyncProfilerWrapper The async-profiler wrapper managing JFR files
   * @param windowSeconds JFR loop interval in seconds
   * @param otlpEmitter Optional OTLP emitter supplying the resolved Resource for the export
   * @param profilesExporter native OTLP profiles exporter (HTTP or gRPC) for the aggregate profile
   * @param profilerFullPaths when true, emit fully-qualified source paths in the function table
   * @param aggregationMode OTLP sample-aggregation mode (0 NONE, 1 FULL, 2 SUM); merges
   *     same-identity samples to shrink the payload, correlation preserved
   */
  public RotationBoundaryProcessor(
      int checkIntervalMs,
      AsyncProfilerWrapper asyncProfilerWrapper,
      int windowSeconds,
      ServiceEventsOtlpEmitter otlpEmitter,
      ProfilesExporter profilesExporter,
      boolean profilerFullPaths,
      int aggregationMode) {
    super(checkIntervalMs, "RotationBoundaryProcessor", otlpEmitter);
    this.profilesExporter = profilesExporter;
    this.profilerFullPaths = profilerFullPaths;
    this.aggregationMode = aggregationMode;
    this.asyncProfilerWrapper = asyncProfilerWrapper;
    this.windowMs = windowSeconds * 1000L;
  }

  @Override
  protected void collect() {
    // Always clean up old profiler data files
    cleanupOldFiles();

    if (asyncProfilerWrapper == null
        || !asyncProfilerWrapper.isAvailable()
        || !asyncProfilerWrapper.isRunning()) {
      return;
    }

    // Detect newly rotated JFR files
    List<File> currentFiles = findJfrFiles();
    List<File> newFiles = new ArrayList<>();

    for (File f : currentFiles) {
      if (!knownJfrFiles.contains(f.getAbsolutePath())) {
        newFiles.add(f);
        knownJfrFiles.add(f.getAbsolutePath());
      }
    }

    // Prune knownJfrFiles if it grows too large (should never exceed ~10 in normal operation)
    if (knownJfrFiles.size() > 20) {
      Set<String> currentPaths = new HashSet<>();
      for (File f : currentFiles) {
        currentPaths.add(f.getAbsolutePath());
      }
      knownJfrFiles.retainAll(currentPaths);
    }

    if (newFiles.isEmpty()) {
      return; // No rotation detected
    }

    // When a NEW file appears, it means async-profiler just started writing to it.
    // The PREVIOUS file is the one that just got completed/finalized.
    // We should process the previous file (safe to read), not the new active one.
    for (File newFile : newFiles) {
      File completedFile = findPreviousJfrFile(newFile);
      if (completedFile != null) {
        try {
          processRotatedFile(completedFile);
        } catch (Exception e) {
          logger.log(
              Level.SEVERE, "Error processing completed JFR file: " + completedFile.getName(), e);
        }
      }
    }
  }

  /**
   * Process a newly rotated (completed) JFR file — scanned exactly once.
   *
   * <p>The completed file is scanned a single time for its samples, correlated against the {@code
   * profiler.Span} markers <em>in that same file</em>. A request whose Span marker (written at
   * {@code end()}) landed in this file — including one that started in an earlier file and
   * straddled into it — attributes this file's samples. A request that ends in a <em>later</em>
   * file stays uncorrelated here; crucially, this file is <b>not</b> re-scanned on the next
   * rotation, so a given sample is emitted in exactly one exported profile.
   */
  private void processRotatedFile(File rotatedFile) {
    long processingStartMs = System.currentTimeMillis();

    // 1. Window bounds for the builder's absolute time_nanos/duration: the completed file's OWN
    // recording window [start, start + windowMs], from its %t (file-start) filename timestamp. A
    // just-rotated file's samples were recorded during this window, so their timestamps fall within
    // it.
    long rotatedFileTs = parseJfrFilenameTimestamp(rotatedFile);
    long jfrStartMs = rotatedFileTs;
    long jfrEndMs = rotatedFileTs + windowMs;

    // 2. Build the per-thread request-interval index from THIS file's profiler.Span JFR events
    // (samples and markers share a single clock).
    Map<String, TreeMap<Long, SpanMetadata>> spanIndex = new HashMap<>();
    int spanCount = 0;
    try {
      spanCount += indexSpansFromJfr(rotatedFile.toPath(), spanIndex);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error indexing spans in JFR file: " + rotatedFile.getName(), e);
    }

    // 3. Single-pass JFR scan building the OTLP profile. The primary Profile's period + sample_type
    // follow the profiling mode: cpu interval + {cpu, nanoseconds} in cpu mode, wall interval +
    // {wall, nanoseconds} otherwise (the scan itself is mode-agnostic — cpu and wall samples both
    // surface as ExecutionSample).
    long periodNs = asyncProfilerWrapper.getPrimaryIntervalMs() * 1_000_000L;
    int primaryType =
        asyncProfilerWrapper.getMode() == AsyncProfilerWrapper.MODE_CPU
            ? OtlpProfileBuilder.PRIMARY_CPU
            : OtlpProfileBuilder.PRIMARY_WALL;
    OtlpProfileBuilder otlpProfileBuilder =
        new OtlpProfileBuilder(
            jfrStartMs * 1_000_000L,
            (jfrEndMs - jfrStartMs) * 1_000_000L,
            periodNs,
            asyncProfilerWrapper.getAllocIntervalBytes(),
            aggregationMode,
            primaryType);

    Map<Integer, List<FrameInfo>> stackTraceCache = new HashMap<>();

    int totalSamples = 0;
    try {
      totalSamples +=
          scanJfrFileSinglePass(
              rotatedFile.toPath(), spanIndex, otlpProfileBuilder, stackTraceCache);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error scanning JFR file: " + rotatedFile.getName(), e);
    }

    // 4. Emit the primary (wall or cpu) + alloc profiles as one ExportProfilesServiceRequest. The
    // builder hoists its interned tables into the request-level ProfilesDictionary and emits the
    // Profiles; the configured exporter (OTLP/HTTP or OTLP/gRPC) sends it to the resolved
    // profiles endpoint.
    if ((otlpProfileBuilder.getSampleCount() > 0 || otlpProfileBuilder.getAllocSampleCount() > 0)
        && profilesExporter != null) {
      Resource resource = otlpEmitter != null ? otlpEmitter.getResource() : Resource.getDefault();
      ExportProfilesServiceRequest request = otlpProfileBuilder.toExportRequest(resource);
      profilesExporter.export(request.toByteArray());
    }

    long processingTimeMs = System.currentTimeMillis() - processingStartMs;
    logger.info(
        "Processed rotated JFR file "
            + rotatedFile.getName()
            + ": "
            + totalSamples
            + " samples, "
            + spanCount
            + " profiler.Span events, "
            + otlpProfileBuilder.getUniqueStackCount()
            + " unique stacks, "
            + processingTimeMs
            + "ms");
  }

  /**
   * Read {@code profiler.Span} events from a JFR file and populate the per-thread request-interval
   * index. Each event contributes one {@link SpanMetadata} keyed by thread name → {@code
   * TreeMap<startNs, SpanMetadata>}, exactly the shape the {@code floorEntry} + strict-containment
   * join in {@link #scanJfrFileSinglePass} consumes.
   *
   * <p>Field mapping (async-profiler 4.5 {@code profiler.Span}, read via {@link
   * one.jfr.event.SpanEvent}): {@code startTime} ticks → {@code JfrReader.eventTimeToNanos(time)}
   * (absolute epoch nanos, anchored per chunk); {@code duration} → {@code eventTimeToNanos(time +
   * duration)} yields the interval end (= start + duration); {@code eventThread} → {@code
   * JfrReader.threads.get(tid)} (java thread name, osName fallback); {@code tag} → {@code
   * SpanEvent.tag}, decoded via {@link ProfilerSpanTag} into operation / traceId / spanId.
   *
   * @return the number of {@code profiler.Span} events indexed
   */
  int indexSpansFromJfr(Path jfrPath, Map<String, TreeMap<Long, SpanMetadata>> spanIndex) {
    int indexed = 0;
    try (JfrReader jfr = new JfrReader(jfrPath.toString())) {
      // readEvent(SpanEvent.class) skips every non-span event and auto-advances across chunks, so
      // one loop reads all profiler.Span markers in the file.
      for (SpanEvent event; (event = jfr.readEvent(SpanEvent.class)) != null; ) {
        String threadName = jfr.threads.get(event.tid);
        if (threadName == null || threadName.isEmpty()) {
          continue;
        }

        ProfilerSpanTag.Decoded decoded = ProfilerSpanTag.decode(event.tag);
        if (decoded == null || decoded.operation == null) {
          continue;
        }

        // eventTimeToNanos anchors ticks to the chunk's absolute epoch-nanos start, so span and
        // sample timestamps are comparable across files even with separate JfrReader instances.
        long startNs = jfr.eventTimeToNanos(event.time);
        long endNs = jfr.eventTimeToNanos(event.time + event.duration);

        SpanMetadata meta =
            new SpanMetadata(
                threadName, decoded.operation, startNs, endNs, decoded.traceId, decoded.spanId);
        // Keyed by startNs. On the (negligible) chance two spans on one thread share an identical
        // startNs, keep the one with the larger endNs (the enclosing/longer span) rather than let a
        // later put() arbitrarily drop the other's operation/trace metadata.
        spanIndex
            .computeIfAbsent(threadName, k -> new TreeMap<>())
            .merge(
                startNs,
                meta,
                (existing, added) -> added.endNs >= existing.endNs ? added : existing);
        indexed++;
      }
    } catch (IOException e) {
      logger.log(
          Level.WARNING, "Failed to open JFR file for span indexing: " + jfrPath.getFileName(), e);
    }
    return indexed;
  }

  /**
   * Single-pass JFR scan that populates an OtlpProfileBuilder with individual samples.
   *
   * <p>Handles both the wall events (async-profiler's {@code profiler.WallClockSample}, plus {@code
   * jdk.ExecutionSample} — all surface as {@link one.jfr.event.ExecutionSample}) and, when memory
   * profiling is enabled, the allocation events async-profiler 4.5 writes ({@code
   * jdk.ObjectAllocationInNewTLAB}; {@code OutsideTLAB} / {@code ObjectAllocationSample} handled
   * defensively — all surface as {@link one.jfr.event.AllocationSample}). Allocation samples go
   * through the SAME per-thread {@code floorEntry} + strict {@code startNs < ts < endNs}
   * correlation join as wall (thread-name key), and each preserves async-profiler's per-event byte
   * weight (see {@link #allocEventBytes}). Package-private so the fixture-backed scanner test can
   * drive it directly.
   *
   * @return Number of samples processed
   */
  int scanJfrFileSinglePass(
      Path jfrPath,
      Map<String, TreeMap<Long, SpanMetadata>> spanIndex,
      OtlpProfileBuilder otlpProfileBuilder,
      Map<Integer, List<FrameInfo>> stackTraceCache) {

    int samplesProcessed = 0;

    // Wall profiles tag each sample with its thread state (on-CPU vs off-CPU) as a thread.state
    // attribute; cpu profiles do not (every cpu-engine sample is STATE_DEFAULT — a useless
    // constant). Gate on the builder's primary type (set from the profiling mode) rather than the
    // wrapper, so it also holds when the wrapper isn't wired (tests).
    boolean wallMode = otlpProfileBuilder.isPrimaryWall();

    try (JfrReader jfr = new JfrReader(jfrPath.toString())) {
      // Chunk-by-chunk so the frame cache can be dropped at each chunk boundary: JFR stack-trace
      // ids are only meaningful within their chunk, so a cached FrameInfo list for a reused id
      // would otherwise be stale.
      jfr.stopAtNewChunk = true;
      while (jfr.hasMoreChunks()) {
        stackTraceCache.clear();
        // Resolve the JFR thread-state enum once per chunk (wall mode only) rather than per sample
        // — the scan processes hundreds of thousands of samples per window. Pre-strip the STATE_
        // prefix here too so the per-sample path is an allocation-free map lookup (the values are
        // stable objects internString can dedupe), instead of a substring per sample.
        Map<Integer, String> threadStates =
            wallMode ? strippedThreadStates(jfr.enums.get("jdk.types.ThreadState")) : null;

        for (Event event; (event = jfr.readEvent()) != null; ) {
          // ExecutionSample = wall/execution samples; AllocationSample = the alloc events. Every
          // other event type (spans, settings, CPULoad, ...) is skipped.
          boolean isWall = event instanceof ExecutionSample;
          boolean isAlloc = event instanceof AllocationSample;
          if (!isWall && !isAlloc) {
            continue;
          }

          long eventTimeNs = jfr.eventTimeToNanos(event.time);

          String threadName = jfr.threads.get(event.tid);
          if (threadName == null || threadName.isEmpty()) {
            continue;
          }

          int stackTraceId = event.stackTraceId;
          if (stackTraceId == 0) {
            continue;
          }

          List<FrameInfo> frames =
              stackTraceCache.computeIfAbsent(
                  stackTraceId,
                  k -> {
                    List<FrameInfo> parsed = formatFrameListStructured(jfr, k);
                    return (parsed != null && !parsed.isEmpty()) ? parsed : null;
                  });

          if (frames == null) {
            continue;
          }

          samplesProcessed++;

          // Resolve operation, traceId, and spanId from the profiler.Span index using the
          // per-thread floorEntry + strict startNs < ts < endNs containment join (same for wall
          // and alloc).
          String operation = null;
          String traceId = null;
          String spanId = null;

          TreeMap<Long, SpanMetadata> threadSpans = spanIndex.get(threadName);
          if (threadSpans != null) {
            // Walk from the greatest startNs <= ts downward: the innermost span is checked first,
            // then enclosing (earlier-start, later-end) spans. This way a sample that falls inside
            // an OUTER span but not the earlier-started inner one still correlates, instead of
            // being dropped by a floorEntry-only check. Bounded by MAX_SPAN_WALK so a gap sample
            // (inside no span) can't scan the whole per-thread map at high request volume.
            Map.Entry<Long, SpanMetadata> entry = threadSpans.floorEntry(eventTimeNs);
            for (int walk = 0; entry != null && walk < MAX_SPAN_WALK; walk++) {
              SpanMetadata span = entry.getValue();
              if (eventTimeNs > span.startNs && eventTimeNs < span.endNs) {
                operation = span.operation;
                traceId = span.traceId;
                spanId = span.spanId;
                break;
              }
              entry = threadSpans.lowerEntry(entry.getKey());
            }
          }

          if (isWall) {
            // Carry async-profiler's ExecutionSample.samples coalescing count so folded samples
            // are weighted by count × period, not undercounted as a single period. In wall mode
            // also tag the sample with its thread state so the backend can slice on-CPU (RUNNABLE)
            // vs off-CPU (SLEEPING — async-profiler collapses all off-CPU into one state); in cpu
            // mode threadStates is null (all samples are STATE_DEFAULT) so no attribute is added.
            ExecutionSample es = (ExecutionSample) event;
            // threadStates is pre-stripped and null in cpu mode → allocation-free lookup; a null
            // value omits the attribute.
            String threadState = threadStates == null ? null : threadStates.get(es.threadState);
            otlpProfileBuilder.addSample(
                frames,
                eventTimeNs,
                threadName,
                operation,
                traceId,
                spanId,
                es.samples,
                threadState);
          } else {
            long bytes = allocEventBytes((AllocationSample) event);
            otlpProfileBuilder.addAllocSample(
                frames, eventTimeNs, threadName, operation, traceId, spanId, bytes);
          }
        }
      }
    } catch (IOException e) {
      logger.log(Level.WARNING, "Failed to open JFR file: " + jfrPath.getFileName(), e);
    }

    return samplesProcessed;
  }

  /**
   * Build the per-chunk {@code threadState id → thread.state label} map from the JFR {@code
   * jdk.types.ThreadState} enum, stripping the {@code STATE_} prefix once ({@code STATE_RUNNABLE} →
   * {@code RUNNABLE}) — matching async-profiler's {@code --state} convention and splunk-otel-java's
   * {@code thread.state} values. Doing the strip here (once per chunk, over the small enum) rather
   * than per sample keeps the hot scan path an allocation-free {@link Map#get} and gives {@code
   * internString} stable value objects to dedupe. async-profiler currently emits only {@code
   * RUNNABLE} (on-CPU) and {@code SLEEPING} (all off-CPU collapsed) for wall samples — but this is
   * pass-through, so any name the reader supplies is used verbatim. Returns null when the reader
   * has no such enum (attribute then omitted for the chunk).
   */
  private static Map<Integer, String> strippedThreadStates(Map<Integer, String> raw) {
    if (raw == null) {
      return null;
    }
    Map<Integer, String> stripped = new HashMap<>(raw.size() * 2);
    for (Map.Entry<Integer, String> e : raw.entrySet()) {
      String name = e.getValue();
      if (name != null) {
        stripped.put(
            e.getKey(), name.startsWith("STATE_") ? name.substring("STATE_".length()) : name);
      }
    }
    return stripped;
  }

  /**
   * Per-event allocation byte weight.
   *
   * <p>async-profiler 4.5 {@code alloc=} writes only {@code jdk.ObjectAllocationInNewTLAB} events;
   * validated against async-profiler's own {@code jfrconv -o collapsed --alloc --total}, the byte
   * total equals the sum of the {@code tlabSize} field exactly. {@link
   * one.jfr.event.AllocationSample#value()} returns exactly that weight — {@code tlabSize} when set
   * (InNewTLAB), else {@code allocationSize} — which is the same field jfrconv sums. {@code
   * jdk.ObjectAllocationOutsideTLAB} ({@code allocationSize}) and the sampled {@code
   * jdk.ObjectAllocationSample} ({@code weight}, read positionally into {@code allocationSize}) are
   * covered by the same {@code value()} for other JFR producers/settings.
   */
  private static long allocEventBytes(AllocationSample event) {
    return event.value();
  }

  /** Format a JFR stack trace (by id) as a list of structured FrameInfo (root → leaf). */
  private List<FrameInfo> formatFrameListStructured(JfrReader jfr, int stackTraceId) {
    StackTrace stackTrace = jfr.stackTraces.get(stackTraceId);
    if (stackTrace == null || stackTrace.methods == null || stackTrace.methods.length == 0) {
      return null;
    }

    long[] methodIds = stackTrace.methods;
    int[] locations = stackTrace.locations;

    List<FrameInfo> result = new ArrayList<>();
    // JFR stores frames leaf → root (index 0 is the topmost frame); iterate in reverse to emit
    // root → leaf, matching the order OtlpProfileBuilder expects.
    for (int i = methodIds.length - 1; i >= 0; i--) {
      MethodRef method = jfr.methods.get(methodIds[i]);
      if (method == null) continue;

      String typeName = resolveTypeName(jfr, method);
      String methodName = resolveMethodName(jfr, method);
      // locations pack (line << 16 | bci); the high 16 bits are the source line number.
      int lineNumber = locations[i] >>> 16;

      // Build the simple ClassName.java first (always needed). If full-paths is
      // enabled, prepend the package path so consumers get a JVM-style source
      // file identifier like "com/example/Foo.java".
      int lastDot = typeName.lastIndexOf('.');
      String simpleClassName = lastDot >= 0 ? typeName.substring(lastDot + 1) : typeName;
      int dollarSign = simpleClassName.indexOf('$');
      String simpleFileName =
          (dollarSign >= 0 ? simpleClassName.substring(0, dollarSign) : simpleClassName) + ".java";
      String fileName;
      if (profilerFullPaths && lastDot >= 0) {
        fileName = typeName.substring(0, lastDot).replace('.', '/') + "/" + simpleFileName;
      } else {
        fileName = simpleFileName;
      }

      result.add(new FrameInfo(typeName, methodName, fileName, lineNumber));
    }

    return result.isEmpty() ? null : result;
  }

  /**
   * Fully-qualified (dotted) declaring-class name for a JFR method, e.g. {@code com.example.Foo}.
   * JFR class symbols are JVM-internal names ({@code com/example/Foo}); converting {@code '/'} →
   * {@code '.'} yields the dotted form. Native/synthetic frames with no declaring class resolve to
   * an empty string.
   */
  private static String resolveTypeName(JfrReader jfr, MethodRef method) {
    ClassRef cls = jfr.classes.get(method.cls);
    byte[] classSymbol = cls != null ? jfr.symbols.get(cls.name) : null;
    if (classSymbol == null || classSymbol.length == 0) {
      return "";
    }
    return new String(classSymbol, java.nio.charset.StandardCharsets.UTF_8).replace('/', '.');
  }

  /** Method name for a JFR method. */
  private static String resolveMethodName(JfrReader jfr, MethodRef method) {
    byte[] methodSymbol = jfr.symbols.get(method.name);
    return methodSymbol != null
        ? new String(methodSymbol, java.nio.charset.StandardCharsets.UTF_8)
        : "";
  }

  /** Find all JFR files sorted by name. */
  private List<File> findJfrFiles() {
    String jfrBasePath = asyncProfilerWrapper.getJfrBasePath();
    File baseFile = new File(jfrBasePath);
    File dir = baseFile.getParentFile();
    if (dir == null) dir = new File(".");
    String prefix = baseFile.getName();

    if (!dir.isDirectory()) return new ArrayList<>();

    File[] files = dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".jfr"));
    if (files == null) return new ArrayList<>();

    Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
    return new ArrayList<>(Arrays.asList(files));
  }

  /** Find the JFR file that was rotated just before the given file. */
  private File findPreviousJfrFile(File currentFile) {
    List<File> allFiles = findJfrFiles();
    for (int i = 1; i < allFiles.size(); i++) {
      if (allFiles.get(i).getAbsolutePath().equals(currentFile.getAbsolutePath())) {
        return allFiles.get(i - 1);
      }
    }
    return null;
  }

  /**
   * Parse the timestamp embedded in a JFR filename.
   *
   * <p>JFR files are named like {@code profiler-jfr-20260309-172942.jfr}. This extracts the
   * timestamp portion (YYYYMMDD-HHmmss) and converts it to epoch milliseconds using the JVM-default
   * timezone — matching async-profiler's {@code %t}, which formats the filename in local time.
   *
   * @param jfrFile The JFR file
   * @return Epoch milliseconds from the filename timestamp, or the file's lastModified as fallback
   */
  private long parseJfrFilenameTimestamp(File jfrFile) {
    String name = jfrFile.getName();
    // Expected format: profiler-jfr-YYYYMMDD-HHmmss.jfr Find the timestamp by looking for the
    // pattern after the base prefix
    int dotJfr = name.lastIndexOf(".jfr");
    if (dotJfr < 0) {
      return jfrFile.lastModified();
    }

    // The timestamp is the last 15 characters before .jfr: YYYYMMDD-HHmmss
    String beforeExt = name.substring(0, dotJfr);
    if (beforeExt.length() < 15) {
      return jfrFile.lastModified();
    }

    String tsStr = beforeExt.substring(beforeExt.length() - 15); // "YYYYMMDD-HHmmss"
    long parsed = parseTimestamp(tsStr);
    return parsed >= 0 ? parsed : jfrFile.lastModified();
  }

  /**
   * Parse a {@code YYYYMMDD-HHmmss} timestamp string (async-profiler's {@code %t} filename format,
   * JVM-default timezone) back to epoch milliseconds, or {@code -1} on failure.
   */
  private static long parseTimestamp(String timestamp) {
    try {
      SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss");
      return sdf.parse(timestamp).getTime();
    } catch (Exception e) {
      return -1;
    }
  }

  /**
   * Clean up old profiler data files. Runs first in every collect() cycle:
   *
   * <ol>
   *   <li>time-based JFR retention (async-profiler wrapper);
   *   <li>byte-budget eviction of the per-PID data dir — oldest {@code *.jfr} first, never the
   *       active newest, so the local footprint is bounded if windows pile up.
   * </ol>
   */
  private void cleanupOldFiles() {
    if (asyncProfilerWrapper == null) {
      return;
    }
    asyncProfilerWrapper.cleanupOldJfrFiles();

    File dataDir = new File(asyncProfilerWrapper.getJfrBasePath()).getParentFile();
    if (dataDir == null) {
      return;
    }
    // Byte budget — evict our own *.jfr files only (never the extracted native lib that shares the
    // dir).
    ProfilerDataDir.enforceByteBudget(
        dataDir, (d, name) -> name.endsWith(".jfr"), DATA_DIR_MAX_BYTES);
  }
}
