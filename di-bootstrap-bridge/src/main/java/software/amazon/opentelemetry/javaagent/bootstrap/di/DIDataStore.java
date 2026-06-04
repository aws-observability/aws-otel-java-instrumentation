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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class DIDataStore {

  private static volatile DISerializer serializer;
  private static final ConcurrentLinkedQueue<PendingCapture> queue =
      new ConcurrentLinkedQueue<PendingCapture>();

  /** Holds pending method entry data per thread, keyed by method key. */
  private static final ThreadLocal<Map<String, PendingEntryData>> pendingEntries =
      new ThreadLocal<Map<String, PendingEntryData>>() {
        @Override
        protected Map<String, PendingEntryData> initialValue() {
          return new HashMap<String, PendingEntryData>();
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
      pendingEntries
          .get()
          .put(
              key,
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
      // Retrieve and remove the pending entry for this method
      PendingEntryData entry = pendingEntries.get().remove(key);

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
  }
}
