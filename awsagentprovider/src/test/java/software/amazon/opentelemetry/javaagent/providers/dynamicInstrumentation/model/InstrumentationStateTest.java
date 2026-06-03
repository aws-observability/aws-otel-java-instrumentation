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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InstrumentationStateTest {

  @Test
  void testIncrementAndCheckDisable_allowsExactlyMaxHitsCaptures() {
    // Given: maxHits = 3
    InstrumentationState state =
        new InstrumentationState("test-hash", 3, null, InstrumentationType.BREAKPOINT, null);

    // When: Hit 1
    boolean disabled1 = state.incrementAndCheckDisable();
    // Then: Should NOT be disabled (still active)
    assertThat(disabled1).isFalse();
    assertThat(state.getHitCount()).isEqualTo(1);
    assertThat(state.isDisabled()).isFalse();

    // When: Hit 2
    boolean disabled2 = state.incrementAndCheckDisable();
    // Then: Should NOT be disabled (still active)
    assertThat(disabled2).isFalse();
    assertThat(state.getHitCount()).isEqualTo(2);
    assertThat(state.isDisabled()).isFalse();

    // When: Hit 3 (the maxHits limit)
    boolean disabled3 = state.incrementAndCheckDisable();
    // Then: Should NOT be disabled (allows exactly maxHits captures)
    assertThat(disabled3).isFalse();
    assertThat(state.getHitCount()).isEqualTo(3);
    assertThat(state.isDisabled()).isFalse();

    // When: Hit 4 (exceeds maxHits)
    boolean disabled4 = state.incrementAndCheckDisable();
    // Then: Should be disabled
    assertThat(disabled4).isTrue();
    assertThat(state.getHitCount()).isEqualTo(4);
    assertThat(state.isDisabled()).isTrue();
    assertThat(state.getDisableReason()).isEqualTo(DisableReason.MAX_HITS_REACHED);

    // When: Hit 5 (already disabled)
    boolean disabled5 = state.incrementAndCheckDisable();
    // Then: Should still be disabled
    assertThat(disabled5).isTrue();
    assertThat(state.getHitCount()).isEqualTo(5);
    assertThat(state.isDisabled()).isTrue();
  }

  @Test
  void testIncrementAndCheckDisable_noMaxHitsLimit() {
    // Given: maxHits = 0 (no limit)
    InstrumentationState state =
        new InstrumentationState("test-hash", 0, null, InstrumentationType.PROBE, null);

    // When/Then: Hits within the rate limit should never permanently disable
    // (rate limiter allows DEFAULT_MAX_CAPTURES_PER_SECOND per window)
    for (int i = 1; i <= CaptureRateLimiter.DEFAULT_MAX_CAPTURES_PER_SECOND; i++) {
      boolean disabled = state.incrementAndCheckDisable();
      assertThat(disabled).isFalse();
      assertThat(state.getHitCount()).isEqualTo(i);
      assertThat(state.isDisabled()).isFalse();
    }
  }

  @Test
  void testIncrementAndCheckDisable_maxHitsOne() {
    // Given: maxHits = 1
    InstrumentationState state =
        new InstrumentationState("test-hash", 1, null, InstrumentationType.BREAKPOINT, null);

    // When: Hit 1
    boolean disabled1 = state.incrementAndCheckDisable();
    // Then: Should NOT be disabled (allows 1 capture)
    assertThat(disabled1).isFalse();
    assertThat(state.getHitCount()).isEqualTo(1);
    assertThat(state.isDisabled()).isFalse();

    // When: Hit 2 (exceeds maxHits)
    boolean disabled2 = state.incrementAndCheckDisable();
    // Then: Should be disabled
    assertThat(disabled2).isTrue();
    assertThat(state.getHitCount()).isEqualTo(2);
    assertThat(state.isDisabled()).isTrue();
    assertThat(state.getDisableReason()).isEqualTo(DisableReason.MAX_HITS_REACHED);
  }

  @Test
  void testIncrementAndCheckDisable_expiryCheck() {
    // Given: Instrumentation that expires in the past
    Instant pastExpiry = Instant.now().minusSeconds(60);
    InstrumentationState state =
        new InstrumentationState("test-hash", 10, pastExpiry, InstrumentationType.BREAKPOINT, null);

    // When: Hit 1
    boolean disabled = state.incrementAndCheckDisable();

    // Then: Should be disabled due to expiry
    assertThat(disabled).isTrue();
    assertThat(state.getHitCount()).isEqualTo(1);
    assertThat(state.isDisabled()).isTrue();
    assertThat(state.getDisableReason()).isEqualTo(DisableReason.EXPIRED);
  }

  @Test
  void testIncrementAndCheckDisable_noExpiry() {
    // Given: Instrumentation with no expiry (PROBE)
    InstrumentationState state =
        new InstrumentationState("test-hash", 0, null, InstrumentationType.PROBE, null);

    // When/Then: Hits within the rate limit should never disable due to expiry
    for (int i = 0; i < CaptureRateLimiter.DEFAULT_MAX_CAPTURES_PER_SECOND; i++) {
      boolean disabled = state.incrementAndCheckDisable();
      assertThat(disabled).isFalse();
      assertThat(state.isDisabled()).isFalse();
    }
  }

  @Test
  void testIsActive_notExpiredNotDisabled() {
    // Given: Fresh state with future expiry
    Instant futureExpiry = Instant.now().plusSeconds(3600);
    InstrumentationState state =
        new InstrumentationState(
            "test-hash", 10, futureExpiry, InstrumentationType.BREAKPOINT, null);

    // Then: Should be active
    assertThat(state.isActive()).isTrue();
  }

  @Test
  void testIsActive_expired() {
    // Given: State with past expiry
    Instant pastExpiry = Instant.now().minusSeconds(60);
    InstrumentationState state =
        new InstrumentationState("test-hash", 10, pastExpiry, InstrumentationType.BREAKPOINT, null);

    // Then: Should NOT be active
    assertThat(state.isActive()).isFalse();
  }

  @Test
  void testIsActive_disabled() {
    // Given: State that is manually disabled
    InstrumentationState state =
        new InstrumentationState("test-hash", 10, null, InstrumentationType.PROBE, null);
    state.disable(DisableReason.MAX_HITS_REACHED);

    // Then: Should NOT be active
    assertThat(state.isActive()).isFalse();
  }

  @Test
  void testHitInLastPeriod() {
    // Given: Fresh state
    InstrumentationState state =
        new InstrumentationState("test-hash", 10, null, InstrumentationType.PROBE, null);

    // Then: No hit in last period initially
    assertThat(state.isHitInLastPeriod()).isFalse();

    // When: Record a hit
    state.incrementAndCheckDisable();

    // Then: Should have hit in last period
    assertThat(state.isHitInLastPeriod()).isTrue();

    // When: Reset the flag
    state.resetHitInLastPeriod();

    // Then: Should be false again
    assertThat(state.isHitInLastPeriod()).isFalse();
  }

  @Test
  void testGetters() {
    // Given: State with specific values
    String locationHash = "abc123";
    int maxHits = 5;
    Instant expiresAt = Instant.now().plusSeconds(3600);
    Instant createdAt = Instant.now().minusSeconds(60);
    InstrumentationState state =
        new InstrumentationState(
            locationHash, maxHits, expiresAt, InstrumentationType.BREAKPOINT, createdAt);

    // Then: Getters return correct values
    assertThat(state.getLocationHash()).isEqualTo(locationHash);
    assertThat(state.getMaxHits()).isEqualTo(maxHits);
    assertThat(state.getExpiresAt()).isEqualTo(expiresAt);
    assertThat(state.getInstrumentationType()).isEqualTo(InstrumentationType.BREAKPOINT);
    assertThat(state.getHitCount()).isEqualTo(0);
    assertThat(state.getCreatedAt()).isEqualTo(createdAt);
    assertThat(state.getLastHitAt()).isNull();

    // When: Record a hit
    state.incrementAndCheckDisable();

    // Then: Hit count and last hit time updated
    assertThat(state.getHitCount()).isEqualTo(1);
    assertThat(state.getLastHitAt()).isNotNull();
  }

  @Test
  void testRateLimiter_isPresent() {
    InstrumentationState state =
        new InstrumentationState("test-hash", 0, null, InstrumentationType.PROBE, null);
    assertThat(state.getRateLimiter()).isNotNull();
    assertThat(state.getRateLimiter().getMaxCapturesPerSecond())
        .isEqualTo(CaptureRateLimiter.DEFAULT_MAX_CAPTURES_PER_SECOND);
  }

  @Test
  void testRateLimiting_probeThrottledAfterLimit() {
    // Given: PROBE with no maxHits (unlimited) — rate limiter is the only control
    InstrumentationState state =
        new InstrumentationState("test-hash", 0, null, InstrumentationType.PROBE, null);

    // When: Fire many hits rapidly (all within 1 second)
    int allowed = 0;
    int rejected = 0;
    for (int i = 0; i < 100; i++) {
      boolean disabled = state.incrementAndCheckDisable();
      if (!disabled) {
        allowed++;
      } else {
        rejected++;
      }
    }

    // Then: Only DEFAULT_MAX_CAPTURES_PER_SECOND should be allowed
    assertThat(allowed).isEqualTo(CaptureRateLimiter.DEFAULT_MAX_CAPTURES_PER_SECOND);
    assertThat(rejected).isEqualTo(100 - CaptureRateLimiter.DEFAULT_MAX_CAPTURES_PER_SECOND);

    // Hit count should still be 100 (all hits are counted, rate limiting just skips capture)
    assertThat(state.getHitCount()).isEqualTo(100);

    // Should NOT be permanently disabled — just rate-limited
    assertThat(state.isDisabled()).isFalse();
    assertThat(state.isActive()).isTrue();
  }

  @Test
  void testRateLimiting_breakpointThrottledAfterLimit() {
    // Given: BREAKPOINT with maxHits=50 — both hit limit and rate limiter apply
    InstrumentationState state =
        new InstrumentationState("test-hash", 50, null, InstrumentationType.BREAKPOINT, null);

    // When: Fire 20 hits rapidly (within 1 second)
    int allowed = 0;
    for (int i = 0; i < 20; i++) {
      boolean disabled = state.incrementAndCheckDisable();
      if (!disabled) {
        allowed++;
      }
    }

    // Then: Only 5 (rate limit) should be allowed, not 20
    assertThat(allowed).isEqualTo(CaptureRateLimiter.DEFAULT_MAX_CAPTURES_PER_SECOND);
    assertThat(state.isDisabled()).isFalse(); // Not disabled yet, just rate-limited
  }

  @Test
  void testRateLimiting_maxHitsStillDisables() {
    // Given: BREAKPOINT with maxHits=3
    InstrumentationState state =
        new InstrumentationState("test-hash", 3, null, InstrumentationType.BREAKPOINT, null);

    // When: Hit 4 times (maxHits=3, so hit 4 triggers disable)
    state.incrementAndCheckDisable(); // hit 1 — allowed (rate limit ok)
    state.incrementAndCheckDisable(); // hit 2 — rate limited (but not disabled)
    state.incrementAndCheckDisable(); // hit 3 — rate limited (but not disabled)
    boolean disabled4 = state.incrementAndCheckDisable(); // hit 4 — exceeds maxHits

    // Then: Should be permanently disabled (maxHits takes precedence)
    assertThat(disabled4).isTrue();
    assertThat(state.isDisabled()).isTrue();
    assertThat(state.getDisableReason()).isEqualTo(DisableReason.MAX_HITS_REACHED);
  }
}
