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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DIDataStore}'s runtime hit gating ({@link DIDataStore.HitState}) — the
 * safety-critical path that caps captures (maxHits), enforces expiry, and rate-limits, plus the
 * {@code hitInLastPeriod} flag and {@link DIDataStore#snapshotAll(boolean)} used for status
 * reporting.
 *
 * <p>This logic previously lived in the agent-classloader {@code InstrumentationState}; it now lives
 * on the bootstrap classloader as the single source of truth incremented by the Advice path.
 */
class DIDataStoreTest {

  // Default rate-limit window allowance in HitState (captures per 1-second window).
  private static final int RATE_LIMIT_PER_SECOND = 5;

  private static final int[] LIMITS = {3, 20, 20, 255, 3};

  @AfterEach
  void cleanup() {
    // Remove any keys used by tests so static state does not leak across tests.
    for (String key :
        new String[] {"maxHits", "noLimit", "maxHitsOne", "expired", "rate", "period", "snap"}) {
      DIDataStore.removeConfig(key);
    }
  }

  private void register(String key, int maxHits, long expiresAtMillis) {
    DIDataStore.registerConfig(key, LIMITS, new String[0], null, true, maxHits, expiresAtMillis);
  }

  @Test
  void allowsExactlyMaxHitsCaptures() {
    register("maxHits", 3, 0L);

    // First maxHits hits are allowed (use > maxHits to disable, so exactly 3 captures pass).
    assertThat(DIDataStore.recordHit("maxHits")).isTrue();
    assertThat(DIDataStore.recordHit("maxHits")).isTrue();
    assertThat(DIDataStore.recordHit("maxHits")).isTrue();

    // The 4th hit exceeds maxHits and is denied (disabled).
    assertThat(DIDataStore.recordHit("maxHits")).isFalse();
    // Subsequent hits remain denied.
    assertThat(DIDataStore.recordHit("maxHits")).isFalse();
  }

  @Test
  void maxHitsOneDisablesAfterFirstCapture() {
    register("maxHitsOne", 1, 0L);

    assertThat(DIDataStore.recordHit("maxHitsOne")).isTrue();
    assertThat(DIDataStore.recordHit("maxHitsOne")).isFalse();
  }

  @Test
  void unlimitedConfigNeverDisablesWithinRateLimit() {
    // Integer.MAX_VALUE = unlimited (PROBE-style); only the rate limiter applies.
    register("noLimit", Integer.MAX_VALUE, 0L);

    int allowed = 0;
    for (int i = 0; i < RATE_LIMIT_PER_SECOND; i++) {
      if (DIDataStore.recordHit("noLimit")) {
        allowed++;
      }
    }
    assertThat(allowed).isEqualTo(RATE_LIMIT_PER_SECOND);
    assertThat(DIDataStore.snapshotAll(true).get("noLimit").disabled).isFalse();
  }

  @Test
  void expiredConfigIsDenied() {
    long past = System.currentTimeMillis() - 60_000L;
    register("expired", 10, past);

    assertThat(DIDataStore.recordHit("expired")).isFalse();
    assertThat(DIDataStore.snapshotAll(true).get("expired").disabled).isTrue();
  }

  @Test
  void rateLimitsCapturesWithinOneSecondWindow() {
    register("rate", Integer.MAX_VALUE, 0L);

    int allowed = 0;
    for (int i = 0; i < 100; i++) {
      if (DIDataStore.recordHit("rate")) {
        allowed++;
      }
    }

    // Only the per-second window allowance passes; the rest are rate-limited (not disabled).
    assertThat(allowed).isEqualTo(RATE_LIMIT_PER_SECOND);
    assertThat(DIDataStore.snapshotAll(true).get("rate").disabled).isFalse();
  }

  @Test
  void hitInLastPeriodSetOnHitAndResetBySnapshot() {
    register("period", 10, 0L);

    // No hit yet.
    assertThat(DIDataStore.snapshotAll(true).get("period").hitInLastPeriod).isFalse();

    // A hit marks the period.
    DIDataStore.recordHit("period");
    Map<String, DIDataStore.HitSnapshot> first = DIDataStore.snapshotAll(true);
    assertThat(first.get("period").hitInLastPeriod).isTrue();

    // The reset pass cleared the flag: a subsequent snapshot with no new hit reports false.
    assertThat(DIDataStore.snapshotAll(true).get("period").hitInLastPeriod).isFalse();
  }

  @Test
  void snapshotWithoutResetDoesNotClearPeriodFlag() {
    // Regression guard: an out-of-band/initial report (resetPeriodFlag=false) must read the period
    // flag without consuming it, so a later periodic report can still emit ACTIVE.
    register("period", 10, 0L);

    DIDataStore.recordHit("period");

    // Read without reset, twice — the flag must stay set.
    assertThat(DIDataStore.snapshotAll(false).get("period").hitInLastPeriod).isTrue();
    assertThat(DIDataStore.snapshotAll(false).get("period").hitInLastPeriod).isTrue();

    // A reset pass consumes it; the next read sees false.
    assertThat(DIDataStore.snapshotAll(true).get("period").hitInLastPeriod).isTrue();
    assertThat(DIDataStore.snapshotAll(true).get("period").hitInLastPeriod).isFalse();
  }

  @Test
  void hitInLastPeriodSetEvenWhenCaptureGated() {
    // maxHits=1: the 2nd hit is denied, but traffic still occurred — period flag must be set.
    register("snap", 1, 0L);

    DIDataStore.recordHit("snap"); // allowed
    DIDataStore.snapshotAll(true); // clears flag from the first hit

    boolean allowed = DIDataStore.recordHit("snap"); // denied (over maxHits)
    assertThat(allowed).isFalse();

    DIDataStore.HitSnapshot snap = DIDataStore.snapshotAll(true).get("snap");
    assertThat(snap.hitInLastPeriod).isTrue();
    assertThat(snap.disabled).isTrue();
  }

  @Test
  void recordHitOnUnknownKeyIsAllowed() {
    // No registered state -> allow the hit (fail-open; do not break the application).
    assertThat(DIDataStore.recordHit("never-registered")).isTrue();
  }

  @Test
  void snapshotAllIsEmptyWhenNoConfigs() {
    assertThat(DIDataStore.snapshotAll(true)).isEmpty();
  }
}
