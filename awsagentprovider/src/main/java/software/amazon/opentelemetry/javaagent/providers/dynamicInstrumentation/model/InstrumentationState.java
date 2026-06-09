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

/**
 * Holds the static metadata of an instrumentation configuration for status reporting.
 *
 * <p>Runtime hit tracking (hit counting, disable on maxHits/expiry, and capture rate limiting) lives
 * on the bootstrap classloader in {@code DIDataStore.HitState}, which is the single source of truth
 * incremented by the Advice path. The {@code StatusReporter} reads dynamic state (hitCount,
 * disabled, hitInLastPeriod) from there and combines it with the static metadata held here
 * (locationHash, instrumentationType, etc.) when emitting status.
 */
public class InstrumentationState {
  private final Instant createdAt;

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
  }

  /**
   * Check if instrumentation is within its active window (not expired).
   *
   * @return true if not past expiry
   */
  public boolean isActive() {
    return expiresAt == null || Instant.now().isBefore(expiresAt);
  }

  // Getters
  public String getLocationHash() {
    return locationHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
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
}
