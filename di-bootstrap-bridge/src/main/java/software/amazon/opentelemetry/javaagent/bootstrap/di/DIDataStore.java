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

package software.amazon.opentelemetry.javaagent.bootstrap.di;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class DIDataStore {

  private static volatile DISerializer serializer;
  private static final ConcurrentLinkedQueue<PendingCapture> queue =
      new ConcurrentLinkedQueue<PendingCapture>();

  /**
   * Holds pending method entry data per thread, keyed by method key. Each key maps to a LIFO stack
   * of frames so that recursion and re-entrancy are handled correctly: a deeper frame pushes its
   * own entry data without overwriting the enclosing frame's, and each exit pops the frame that
   * matches it (innermost first).
   */
  private static final ThreadLocal<Map<String, Deque<PendingEntryData>>> pendingEntries =
      new ThreadLocal<Map<String, Deque<PendingEntryData>>>() {
        @Override
        protected Map<String, Deque<PendingEntryData>> initialValue() {
          return new HashMap<String, Deque<PendingEntryData>>();
        }
      };

  // ─── Runtime configuration (written by agent, read by Advice) ───────────────
  // These maps live on the bootstrap classpath, accessible from any classloader.

  private static final ConcurrentHashMap<String, int[]> limits = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, String[]> captureArguments =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, String[]> captureLocals =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Boolean> captureReturnFlags =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, String[]> parameterNames =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, HitState> hitStates = new ConcurrentHashMap<>();

  private DIDataStore() {}

  public static void setSerializer(DISerializer s) {
    serializer = s;
  }

  /**
   * Called by LineCaptureAdvice on app thread. Serializes locals and enqueues as a LINE capture.
   */
  public static void captureLocals(
      String key,
      Map<String, Object> locals,
      int lineNumber,
      int maxDepth,
      int maxFields,
      int maxCollWidth,
      int maxCollDepth,
      int maxStrLen,
      long timestamp,
      String traceId,
      String spanId,
      long threadId,
      String threadName) {
    DISerializer s = serializer;
    if (s == null) return;
    try {
      Map<String, SerializedValue> serialized =
          s.serialize(locals, maxDepth, maxFields, maxCollWidth, maxCollDepth, maxStrLen, 100L);
      StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
      queue.offer(
          new PendingCapture(
              PendingCapture.CaptureType.LINE,
              key,
              lineNumber,
              serialized,
              null,
              null,
              null,
              timestamp,
              0L,
              traceId,
              spanId,
              threadId,
              threadName,
              stackTrace));
    } catch (Exception e) {
      // Never break the application
    }
  }

  /**
   * Called by MethodCaptureAdvice on app thread for method entry. Serializes arguments and holds
   * them in a ThreadLocal until the corresponding method exit.
   */
  public static void captureMethodEntry(
      String key,
      Map<String, Object> arguments,
      int maxDepth,
      int maxFields,
      int maxCollWidth,
      int maxCollDepth,
      int maxStrLen,
      long timestamp,
      String traceId,
      String spanId,
      long threadId,
      String threadName) {
    DISerializer s = serializer;
    if (s == null) return;
    try {
      Map<String, SerializedValue> serialized =
          s.serialize(arguments, maxDepth, maxFields, maxCollWidth, maxCollDepth, maxStrLen, 100L);
      StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
      // Push onto this method key's per-thread frame stack so recursive/re-entrant calls each
      // retain their own entry data (popped by the matching exit, innermost first).
      Map<String, Deque<PendingEntryData>> frames = pendingEntries.get();
      Deque<PendingEntryData> stack = frames.get(key);
      if (stack == null) {
        stack = new ArrayDeque<PendingEntryData>();
        frames.put(key, stack);
      }
      stack.push(
          new PendingEntryData(
              serialized, timestamp, traceId, spanId, threadId, threadName, stackTrace));
    } catch (Exception e) {
      // Never break the application
    }
  }

  /**
   * Called by MethodCaptureAdvice on app thread for method exit. Retrieves the pending entry data,
   * merges with exit data, and enqueues a single METHOD capture.
   */
  public static void captureMethodExit(
      String key,
      Object returnVal,
      Object thrown,
      int maxDepth,
      int maxFields,
      int maxCollWidth,
      int maxCollDepth,
      int maxStrLen,
      long durationNanos,
      long timestamp,
      String traceId,
      String spanId,
      long threadId,
      String threadName) {
    DISerializer s = serializer;
    if (s == null) return;
    try {
      // Pop the innermost pending entry for this method key (matches this exit in LIFO order).
      // May be null if entry capture was skipped for this frame (e.g. no arguments to capture);
      // in that case we fall back to exit-only data below.
      Map<String, Deque<PendingEntryData>> frames = pendingEntries.get();
      Deque<PendingEntryData> stack = frames.get(key);
      PendingEntryData entry = stack != null ? stack.poll() : null;
      if (stack != null && stack.isEmpty()) {
        frames.remove(key); // avoid leaking empty stacks on the ThreadLocal
      }

      // Serialize return value
      SerializedValue rv = null;
      if (returnVal != null) {
        Map<String, Object> single = new HashMap<String, Object>();
        single.put("@return", returnVal);
        Map<String, SerializedValue> serialized =
            s.serialize(single, maxDepth, maxFields, maxCollWidth, maxCollDepth, maxStrLen, 100L);
        rv = serialized.get("@return");
      }

      // Extract throwable data directly via public API
      ThrowableData th = null;
      if (thrown instanceof Throwable) {
        Throwable t = (Throwable) thrown;
        th = new ThrowableData(t.getClass().getName(), t.getMessage(), t.getStackTrace());
      }

      // Use entry data if available, otherwise use exit-only data
      Map<String, SerializedValue> arguments = entry != null ? entry.arguments : null;
      long entryTimestamp = entry != null ? entry.timestamp : timestamp;
      String entryTraceId = entry != null ? entry.traceId : traceId;
      String entrySpanId = entry != null ? entry.spanId : spanId;
      long entryThreadId = entry != null ? entry.threadId : threadId;
      String entryThreadName = entry != null ? entry.threadName : threadName;
      StackTraceElement[] entryStack =
          entry != null ? entry.stackTrace : Thread.currentThread().getStackTrace();

      queue.offer(
          new PendingCapture(
              PendingCapture.CaptureType.METHOD,
              key,
              0,
              null,
              arguments,
              rv,
              th,
              entryTimestamp,
              durationNanos,
              entryTraceId,
              entrySpanId,
              entryThreadId,
              entryThreadName,
              entryStack));
    } catch (Exception e) {
      // Never break the application
    }
  }

  /** Called by DISnapshotCollector on background thread. */
  public static List<PendingCapture> drain() {
    if (queue.isEmpty()) return null;
    List<PendingCapture> result = new ArrayList<PendingCapture>();
    PendingCapture item;
    while ((item = queue.poll()) != null) {
      result.add(item);
    }
    return result.isEmpty() ? null : result;
  }

  // ─── Runtime config registration (called by agent) ───────────────────────────

  /**
   * Register instrumentation configuration for Advice runtime reads.
   *
   * <p>Note: writes to individual maps are not atomic as a group. A concurrent Advice read during
   * re-registration may observe a mix of old and new values for one capture cycle. This is
   * acceptable — re-registration only occurs when configs change, which also triggers a class
   * retransformation that briefly pauses instrumentation.
   *
   * @param key Method key or instrumentation key
   * @param configLimits int[5]: {maxObjectDepth, maxFieldsPerObject, maxCollectionWidth,
   *     maxStringLength, maxCollectionDepth}
   * @param captureArgs Argument filter (null=don't capture, empty=all, non-empty=named only)
   * @param capLocals Locals filter (null=don't capture, empty=all, non-empty=named only)
   * @param capReturn Whether to capture return value
   * @param maxHits Max hits before disable (Integer.MAX_VALUE = unlimited)
   * @param expiresAtMillis Expiry timestamp in millis (0 = no expiry)
   */
  public static void registerConfig(
      String key,
      int[] configLimits,
      String[] captureArgs,
      String[] capLocals,
      boolean capReturn,
      int maxHits,
      long expiresAtMillis) {
    limits.put(key, configLimits);
    if (captureArgs != null) {
      captureArguments.put(key, captureArgs);
    } else {
      captureArguments.remove(key);
    }
    if (capLocals != null) {
      captureLocals.put(key, capLocals);
    } else {
      captureLocals.remove(key);
    }
    captureReturnFlags.put(key, capReturn);
    HitState existing = hitStates.get(key);
    if (existing == null
        || existing.maxHits != maxHits
        || existing.expiresAtMillis != expiresAtMillis) {
      hitStates.put(key, new HitState(maxHits, expiresAtMillis));
    }
  }

  public static void registerParameterNames(String key, String[] names) {
    parameterNames.put(key, names);
  }

  public static void removeConfig(String key) {
    limits.remove(key);
    captureArguments.remove(key);
    captureLocals.remove(key);
    captureReturnFlags.remove(key);
    parameterNames.remove(key);
    hitStates.remove(key);
  }

  // ─── Runtime config reads (called by Advice code) ───────────────────────────

  public static boolean recordHit(String key) {
    HitState state = hitStates.get(key);
    if (state == null) {
      return true;
    }
    return state.tryHit();
  }

  /**
   * Take a point-in-time snapshot of every instrumentation's runtime hit state, for status
   * reporting. Reads from the bootstrap-classloader {@link HitState} — the single source of truth
   * for hit activity (incremented by the Advice path via {@link #recordHit}).
   *
   * <p>When {@code resetPeriodFlag} is true, the per-period "hit since last report" flag is
   * read-and-cleared in the same pass (used by the periodic reporting cycle that emits ACTIVE).
   * When false, the flag is read without clearing (used by out-of-band/initial reports, which must
   * not consume the flag — otherwise the next periodic report would miss a genuine ACTIVE signal).
   * The clear uses {@link java.util.concurrent.atomic.AtomicBoolean#getAndSet} so concurrent report
   * cycles (the scheduled reporter thread and the out-of-band caller) cannot both observe the same
   * hit as new.
   *
   * @param resetPeriodFlag whether to clear each config's per-period hit flag after reading it
   * @return snapshots keyed by instrumentation key (empty if no configs registered)
   */
  public static Map<String, HitSnapshot> snapshotAll(boolean resetPeriodFlag) {
    Map<String, HitSnapshot> result = new HashMap<String, HitSnapshot>();
    for (Map.Entry<String, HitState> entry : hitStates.entrySet()) {
      result.put(entry.getKey(), entry.getValue().snapshot(resetPeriodFlag));
    }
    return result;
  }

  public static int[] getLimits(String key) {
    int[] l = limits.get(key);
    return l != null ? l : new int[] {3, 20, 20, 255, 3};
  }

  public static boolean shouldCaptureReturn(String key) {
    Boolean val = captureReturnFlags.get(key);
    return val != null && val;
  }

  public static String[] getCaptureArguments(String key) {
    return captureArguments.get(key);
  }

  public static String[] getCaptureLocals(String key) {
    return captureLocals.get(key);
  }

  public static String[] getParameterNames(String key) {
    return parameterNames.get(key);
  }

  // ─── Inner classes ──────────────────────────────────────────────────────────

  /** Holds serialized method entry data until the corresponding exit. */
  public static final class PendingEntryData {
    public final Map<String, SerializedValue> arguments;
    public final long timestamp;
    public final String traceId;
    public final String spanId;
    public final long threadId;
    public final String threadName;
    public final StackTraceElement[] stackTrace;

    public PendingEntryData(
        Map<String, SerializedValue> arguments,
        long timestamp,
        String traceId,
        String spanId,
        long threadId,
        String threadName,
        StackTraceElement[] stackTrace) {
      this.arguments = arguments;
      this.timestamp = timestamp;
      this.traceId = traceId;
      this.spanId = spanId;
      this.threadId = threadId;
      this.threadName = threadName;
      this.stackTrace = stackTrace;
    }
  }

  /**
   * Lightweight hit state for rate limiting and maxHits enforcement. Lives on bootstrap classpath
   * with no dependencies on agent model classes.
   */
  public static final class HitState {
    private static final int DEFAULT_MAX_PER_SECOND = 5;

    private final AtomicInteger hitCount = new AtomicInteger(0);
    final int maxHits;
    final long expiresAtMillis;
    private volatile boolean disabled = false;

    // Set true on every hit (including gated/disabled hits); read-and-reset each report period by
    // the status reporter to emit ACTIVE. Tracks "was this location touched at all this period".
    // AtomicBoolean (not a plain volatile) so the read-and-clear in snapshot() is atomic: the
    // scheduled reporter thread and an out-of-band reportNow() caller may both snapshot, and
    // getAndSet ensures at most one of them sees a given hit as new.
    private final AtomicBoolean hitInLastPeriod = new AtomicBoolean(false);

    // Rate limiter: 5 captures per 1-second window (CAS-based rollover, monotonic clock)
    private final AtomicLong windowStartNanos;
    private final AtomicInteger windowCount = new AtomicInteger(0);

    public HitState(int maxHits, long expiresAtMillis) {
      this.maxHits = maxHits;
      this.expiresAtMillis = expiresAtMillis;
      this.windowStartNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Record a hit and check if capture is allowed.
     *
     * @return true if capture is allowed, false if disabled/expired/rate-limited
     */
    public boolean tryHit() {
      // Mark traffic for ACTIVE reporting before any gating — a breakpoint hitting its maxHits cap
      // or being rate-limited is still receiving traffic.
      hitInLastPeriod.set(true);

      if (disabled) {
        return false;
      }

      int count = hitCount.incrementAndGet();

      // Check maxHits (use > to allow exactly maxHits captures)
      if (maxHits > 0 && maxHits != Integer.MAX_VALUE && count > maxHits) {
        disabled = true;
        return false;
      }

      // Check expiry
      if (expiresAtMillis > 0 && System.currentTimeMillis() > expiresAtMillis) {
        disabled = true;
        return false;
      }

      // Rate limit: 5 captures per 1-second window (monotonic clock)
      long now = System.nanoTime();
      long window = windowStartNanos.get();
      if (now - window >= 1_000_000_000L) {
        if (windowStartNanos.compareAndSet(window, now)) {
          windowCount.set(1);
          return true;
        }
        // Another thread rolled the window; fall through to normal increment
      }
      int wc = windowCount.incrementAndGet();
      if (wc <= DEFAULT_MAX_PER_SECOND) {
        return true;
      }
      windowCount.decrementAndGet();
      // Rate-limited: roll back the hitCount increment so maxHits counts only successful
      // captures, not all attempts.
      hitCount.decrementAndGet();
      return false;
    }

    public boolean isDisabled() {
      return disabled;
    }

    public int getHitCount() {
      return hitCount.get();
    }

    /**
     * Capture a snapshot of this hit state. When {@code resetPeriodFlag} is true, the per-period
     * hit flag is atomically read-and-cleared; when false it is read without clearing.
     */
    HitSnapshot snapshot(boolean resetPeriodFlag) {
      boolean hit = resetPeriodFlag ? hitInLastPeriod.getAndSet(false) : hitInLastPeriod.get();
      return new HitSnapshot(hitCount.get(), disabled, hit);
    }
  }

  /**
   * Immutable point-in-time view of a {@link HitState} for status reporting. Read by the agent
   * classloader; lives on the bootstrap classpath with no dependencies on agent model classes.
   */
  public static final class HitSnapshot {
    public final int hitCount;
    public final boolean disabled;
    public final boolean hitInLastPeriod;

    public HitSnapshot(int hitCount, boolean disabled, boolean hitInLastPeriod) {
      this.hitCount = hitCount;
      this.disabled = disabled;
      this.hitInLastPeriod = hitInLastPeriod;
    }
  }
}
