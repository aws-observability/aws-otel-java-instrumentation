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

package software.amazon.opentelemetry.serviceevents;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rate limiter for incident snapshots. Handles global per-minute limiting and per-error-hash
 * deduplication.
 *
 * <p>Uses an {@link AtomicReference} to a {@link Window} object that is atomically swapped on
 * minute boundaries. All per-minute counters live inside the window — when the minute rolls over,
 * the old window is simply GC'd, eliminating the need for pruning or expiry tracking.
 *
 * <p>Package-private: accessed only from {@link ServiceEventsDataStore}.
 */
final class IncidentRateLimiter {

  private IncidentRateLimiter() {}

  /** Rate-limit period in milliseconds. Fixed at 1 minute. */
  static final long periodMs = 60_000L;

  /**
   * Maximum incident snapshots per minute. Settable at startup via {@link
   * ServiceEventsDataStore#setIncidentSnapshotMaxPerMinute(int)}.
   *
   * <p>Default: 100.
   */
  private static volatile int incidentSnapshotMaxPerMinute = 100;

  /**
   * Maximum snapshots for the same error hash within one rate-limit period. Settable at startup via
   * {@link ServiceEventsDataStore#setIncidentSnapshotMaxSameError(int)}.
   *
   * <p>Default: 1.
   */
  private static volatile int incidentSnapshotMaxSameError = 1;

  /** Maximum distinct error hashes tracked for deduplication. */
  private static final int MAX_ERROR_HASH_ENTRIES = 1000;

  /**
   * Matches a complete top JVM frame line: indented {@code "at <class.method>(<source>)"} running
   * to end of line. Compiled once — {@link Pattern} is thread-safe and {@link #extractOriginMethod}
   * runs on every recorded incident.
   */
  private static final Pattern TOP_FRAME_PATTERN =
      Pattern.compile("(?m)^\\s+at\\s+([\\w$.<>/]+)\\([^()]*\\)\\s*$");

  /** All per-window rate-limiting state, atomically swapped on window boundaries. */
  private static final class Window {
    final long windowTimestamp;
    final AtomicInteger globalCount = new AtomicInteger(0);
    final ConcurrentHashMap<String, AtomicInteger> errorCounts =
        new ConcurrentHashMap<String, AtomicInteger>();

    Window(long windowTimestamp) {
      this.windowTimestamp = windowTimestamp;
    }
  }

  private static final AtomicReference<Window> currentWindow =
      new AtomicReference<Window>(new Window(System.currentTimeMillis() / periodMs));

  /** Return the current window, swapping to a fresh one if the window has rolled over. */
  private static Window getWindow() {
    long nowBucket = System.currentTimeMillis() / periodMs;
    Window window = currentWindow.get();
    if (window.windowTimestamp != nowBucket) {
      Window newWindow = new Window(nowBucket);
      currentWindow.compareAndSet(window, newWindow);
      return currentWindow.get();
    }
    return window;
  }

  /** Set the maximum incident snapshots per window (called from agent classloader). */
  static void setMaxPerMinute(int value) {
    incidentSnapshotMaxPerMinute = Math.max(1, value);
  }

  /** Set the maximum snapshots for the same error per period (called from agent classloader). */
  static void setMaxSameError(int value) {
    incidentSnapshotMaxSameError = Math.max(1, value);
  }

  /**
   * Check global incident rate limit. Returns true if the incident is allowed, false if rate limit
   * exceeded. Uses a lock-free tumbling window of 1 minute.
   */
  static boolean checkIncidentRateLimit() {
    Window window = getWindow();
    return window.globalCount.incrementAndGet() <= incidentSnapshotMaxPerMinute;
  }

  /**
   * Generate a hash for deduplication based on operation, exception type, and throw-site method.
   *
   * <p>Intentionally excludes exceptionMessage to avoid unbounded hash proliferation when messages
   * contain request-specific data (user IDs, timestamps, etc.). The origin method (parsed from the
   * stack trace by {@link #extractOriginMethod}) is a bounded, deploy-stable substitute that still
   * keeps distinct errors sharing one exception type apart — mirroring the file-qualified
   * throw-site origin the Python ({@code module/path.function}) and JS ({@code file.function})
   * distros fold into their key.
   *
   * @param operation the operation the incident occurred on (never null at the call site)
   * @param exceptionType exception class name, or null for a latency incident
   * @param originMethod fully-qualified throw-site method, or null/empty if unavailable
   * @return hex-encoded MD5 hash, or hashCode fallback if MD5 is unavailable
   */
  static String generateErrorHash(String operation, String exceptionType, String originMethod) {
    String hashInput;
    if (exceptionType == null) {
      // Latency incident: no exception → operation-only key (matches the pre-refactor behavior).
      hashInput = "op:" + operation;
    } else if (originMethod == null || originMethod.isEmpty()) {
      hashInput = "op:" + operation + "|exc:" + exceptionType;
    } else {
      hashInput = "op:" + operation + "|exc:" + exceptionType + ":" + originMethod;
    }
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
      byte[] digest = md.digest(hashInput.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < digest.length; i++) {
        sb.append(String.format("%02x", Byte.valueOf(digest[i])));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      return String.valueOf(hashInput.hashCode());
    }
  }

  /**
   * Extract the fully-qualified throw-site method from the top frame of a JVM stack trace string.
   *
   * <p>The stack trace text (as produced by {@code Throwable.printStackTrace}) lists the throw site
   * first, on the first complete frame line — indented and of the form {@code "\tat
   * com.example.Foo.bar(Foo.java:42)"}. This returns the {@code com.example.Foo.bar} portion —
   * class + method, without the {@code (file:line)} suffix. The line number is deliberately
   * dropped: it is the least stable field (any edit above the throw site shifts it), so including
   * it would make a recurring error re-fire as a brand-new incident after every deploy.
   *
   * @param stackTrace the exception stack trace string, or null
   * @return the {@code class.method} of the throw site, or empty string if unparseable/absent
   */
  static String extractOriginMethod(String stackTrace) {
    if (stackTrace == null || stackTrace.isEmpty()) {
      return "";
    }
    // Match a complete frame line, not the header. A real JVM frame is indented and has the full
    // "at <class.method>(<source>)" grammar: a method reference (word chars, '.', '$', '/' for the
    // module prefix, and '<>' for the <init>/<clinit> constructor and static-initializer names)
    // followed by a balanced "(...)" running to end of line. Requiring the whole grammar — not just
    // a bare "at X(" prefix — means a message that embeds a truncated fragment (e.g.
    // "boom\n\tat evil.pwn(") cannot false-match the real top frame. (A message embedding a
    // syntactically perfect, tab-indented full frame is indistinguishable from a real one in a flat
    // string and would still match; that is an accepted residual for this seam, which only ever
    // receives an already-formatted string.)
    Matcher m = TOP_FRAME_PATTERN.matcher(stackTrace);
    if (m.find()) {
      return m.group(1);
    }
    return "";
  }

  /**
   * Check per-error deduplication. Returns true if the error is allowed, false if the same error
   * hash has been seen too many times within the rate-limit period or the map has reached its
   * maximum size.
   */
  static boolean checkErrorDeduplication(String errorHash) {
    Window window = getWindow();

    // Lock-free fast-path rejection
    AtomicInteger existing = window.errorCounts.get(errorHash);
    if (existing != null && existing.get() >= incidentSnapshotMaxSameError) {
      return false;
    }

    synchronized (window) {
      existing = window.errorCounts.get(errorHash);
      if (existing == null) {
        if (window.errorCounts.size() >= MAX_ERROR_HASH_ENTRIES) {
          return false;
        }
        existing = window.errorCounts.computeIfAbsent(errorHash, k -> new AtomicInteger(0));
      }
      if (existing.get() < incidentSnapshotMaxSameError) {
        existing.incrementAndGet();
        return true;
      }
    }
    return false;
  }

  /** Reset all rate-limiting state. Used for testing. */
  static void resetState() {
    currentWindow.set(new Window(System.currentTimeMillis() / periodMs));
  }
}
