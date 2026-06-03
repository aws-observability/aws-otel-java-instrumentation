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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-instrumentation rate limiter using a fixed-window token bucket algorithm.
 *
 * <p>Limits the number of snapshot captures per second for a single instrumentation configuration
 * (probe or breakpoint). This prevents high-throughput methods from generating excessive capture
 * overhead.
 *
 * <p>The algorithm divides time into 1-second windows. Each window allows up to {@code
 * maxCapturesPerSecond} captures. When the window rolls over, the counter resets.
 *
 * <p>Thread-safe using atomic operations. Designed to be called on the application's hot path with
 * minimal overhead (two volatile reads + one CAS in the common case).
 */
public final class CaptureRateLimiter {

  /** Default capture rate: 5 snapshots per second per instrumentation. */
  public static final int DEFAULT_MAX_CAPTURES_PER_SECOND = 5;

  private final int maxCapturesPerSecond;
  private final AtomicLong windowStartNanos;
  private final AtomicInteger captureCount;

  private static final long ONE_SECOND_NANOS = 1_000_000_000L;

  /**
   * Create a rate limiter with the specified captures-per-second limit.
   *
   * @param maxCapturesPerSecond Maximum captures allowed per 1-second window. Must be positive.
   */
  public CaptureRateLimiter(int maxCapturesPerSecond) {
    if (maxCapturesPerSecond <= 0) {
      throw new IllegalArgumentException(
          "maxCapturesPerSecond must be positive, got: " + maxCapturesPerSecond);
    }
    this.maxCapturesPerSecond = maxCapturesPerSecond;
    this.windowStartNanos = new AtomicLong(System.nanoTime());
    this.captureCount = new AtomicInteger(0);
  }

  /** Create a rate limiter with the default rate (5 captures/sec). */
  public CaptureRateLimiter() {
    this(DEFAULT_MAX_CAPTURES_PER_SECOND);
  }

  /**
   * Try to acquire a capture permit.
   *
   * @return true if capture is allowed, false if rate limit is exceeded
   */
  public boolean tryAcquire() {
    return tryAcquire(System.nanoTime());
  }

  /**
   * Try to acquire a capture permit using the provided timestamp. Package-private for testing.
   *
   * @param nowNanos Current time in nanoseconds (from System.nanoTime())
   * @return true if capture is allowed, false if rate limit is exceeded
   */
  boolean tryAcquire(long nowNanos) {
    long windowStart = windowStartNanos.get();
    long elapsed = nowNanos - windowStart;

    if (elapsed >= ONE_SECOND_NANOS) {
      // Window has expired — try to roll over to a new window
      if (windowStartNanos.compareAndSet(windowStart, nowNanos)) {
        captureCount.set(1);
        return true;
      }
      // Another thread rolled the window; fall through to normal increment path
    }

    int currentCount = captureCount.incrementAndGet();
    if (currentCount <= maxCapturesPerSecond) {
      return true;
    }

    // Over limit — decrement back so we don't inflate the counter
    captureCount.decrementAndGet();
    return false;
  }

  /** Get the configured maximum captures per second. */
  public int getMaxCapturesPerSecond() {
    return maxCapturesPerSecond;
  }

  /** Get the current capture count in the active window. For monitoring/testing only. */
  public int getCurrentCount() {
    return captureCount.get();
  }
}
