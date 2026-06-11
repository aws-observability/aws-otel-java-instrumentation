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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
   * Generate a hash for deduplication based on operation and exception type.
   *
   * <p>Intentionally excludes exceptionMessage to avoid unbounded hash proliferation when messages
   * contain request-specific data (user IDs, timestamps, etc.).
   *
   * @return hex-encoded MD5 hash, or hashCode fallback if MD5 is unavailable
   */
  static String generateErrorHash(String operation, String exceptionType) {
    String hashInput;
    if (exceptionType == null) {
      hashInput = "op:" + operation;
    } else {
      hashInput = "op:" + operation + "|exc:" + exceptionType;
    }
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
      byte[] digest = md.digest(hashInput.getBytes());
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
