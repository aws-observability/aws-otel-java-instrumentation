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

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks runtime state of an instrumentation configuration.
 *
 * <p>Maintains hit counting, disable logic, and status reporting state for each instrumentation
 * point. Thread-safe for concurrent hits from instrumented code.
 */
public class InstrumentationState {
  // Hit tracking
  private final AtomicInteger hitCount = new AtomicInteger(0);
  private volatile boolean hitInLastPeriod = false;
  private final Instant createdAt;
  private volatile Instant lastHitAt;

  // Disable tracking
  private volatile boolean isDisabled = false;
  private volatile DisableReason disableReason;

  // Rate limiting
  private final CaptureRateLimiter rateLimiter;

  // Configuration metadata
  private final String locationHash;
  private final int maxHits;
  private final Instant expiresAt; // null for PROBE
  private final InstrumentationType instrumentationType;

  public InstrumentationState(
      String locationHash,
      int maxHits,
      Instant expiresAt,
      InstrumentationType instrumentationType,
      Instant createdAt) {
    this.locationHash = locationHash;
    this.maxHits = maxHits;
    this.expiresAt = expiresAt;
    this.instrumentationType = instrumentationType;
    this.createdAt = createdAt;
    this.rateLimiter = new CaptureRateLimiter(CaptureRateLimiter.DEFAULT_MAX_CAPTURES_PER_SECOND);
  }

  /**
   * Record a hit and check if instrumentation should be disabled or rate-limited.
   *
   * <p>Called from Advice classes on each instrumentation hit. Thread-safe.
   *
   * <p>Checks are performed in order: disabled → expired → maxHits → rate limit. The rate limit
   * check uses a per-instrumentation token bucket that allows a fixed number of captures per
   * second.
   *
   * @return true if disabled or rate-limited (caller should stop capturing), false if capture is
   *     allowed
   */
  public boolean incrementAndCheckDisable() {
    // Fast path: already disabled
    if (isDisabled) {
      hitCount.incrementAndGet();
      lastHitAt = Instant.now();
      hitInLastPeriod = true;
      return true;
    }

    int newCount = hitCount.incrementAndGet();
    lastHitAt = Instant.now();
    hitInLastPeriod = true;

    // Check maxHits disable condition (BREAKPOINT only, not PROBE)
    // Use > instead of >= to allow exactly maxHits captures before disabling
    if (maxHits > 0 && newCount > maxHits) {
      disable(DisableReason.MAX_HITS_REACHED);
      return true; // disabled
    }

    // Check expiry disable condition (BREAKPOINT only, PROBE has null expiresAt)
    if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
      disable(DisableReason.EXPIRED);
      return true; // disabled
    }

    // Check rate limit — allows maxCapturesPerSecond through per 1-second window
    if (!rateLimiter.tryAcquire()) {
      return true; // rate-limited, skip this capture
    }

    return false; // capture allowed
  }

  /**
   * Mark instrumentation as disabled.
   *
   * @param reason Why it was disabled
   */
  public synchronized void disable(DisableReason reason) {
    if (!isDisabled) {
      isDisabled = true;
      disableReason = reason;
    }
  }

  /**
   * Reset hitInLastPeriod flag after status report.
   *
   * <p>Called by StatusReporter after reporting ACTIVE status.
   */
  public void resetHitInLastPeriod() {
    hitInLastPeriod = false;
  }

  /**
   * Check if instrumentation is active (not disabled and not expired).
   *
   * @return true if active
   */
  public boolean isActive() {
    return !isDisabled && (expiresAt == null || Instant.now().isBefore(expiresAt));
  }

  // Getters
  public int getHitCount() {
    return hitCount.get();
  }

  public boolean isHitInLastPeriod() {
    return hitInLastPeriod;
  }

  public boolean isDisabled() {
    return isDisabled;
  }

  public DisableReason getDisableReason() {
    return disableReason;
  }

  public String getLocationHash() {
    return locationHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastHitAt() {
    return lastHitAt;
  }

  public int getMaxHits() {
    return maxHits;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public InstrumentationType getInstrumentationType() {
    return instrumentationType;
  }

  public CaptureRateLimiter getRateLimiter() {
    return rateLimiter;
  }
}
