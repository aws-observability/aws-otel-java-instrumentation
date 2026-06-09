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

/**
 * Tests for {@link InstrumentationState}, which holds the static metadata of an instrumentation
 * configuration for status reporting.
 *
 * <p>Runtime hit gating (maxHits, expiry, rate limiting) lives in {@code DIDataStore.HitState} on
 * the bootstrap classloader and is covered by {@code DIDataStoreTest}.
 */
class InstrumentationStateTest {

  @Test
  void isActiveWhenNotExpired() {
    Instant futureExpiry = Instant.now().plusSeconds(3600);
    InstrumentationState state =
        new InstrumentationState(
            "test-hash", 10, futureExpiry, InstrumentationType.BREAKPOINT, null);

    assertThat(state.isActive()).isTrue();
  }

  @Test
  void isActiveWhenNoExpiry() {
    InstrumentationState state =
        new InstrumentationState("test-hash", 0, null, InstrumentationType.PROBE, null);

    assertThat(state.isActive()).isTrue();
  }

  @Test
  void isNotActiveWhenExpired() {
    Instant pastExpiry = Instant.now().minusSeconds(60);
    InstrumentationState state =
        new InstrumentationState("test-hash", 10, pastExpiry, InstrumentationType.BREAKPOINT, null);

    assertThat(state.isActive()).isFalse();
  }

  @Test
  void gettersReturnConfiguredMetadata() {
    String locationHash = "abc123";
    int maxHits = 5;
    Instant expiresAt = Instant.now().plusSeconds(3600);
    Instant createdAt = Instant.now().minusSeconds(60);
    InstrumentationState state =
        new InstrumentationState(
            locationHash, maxHits, expiresAt, InstrumentationType.BREAKPOINT, createdAt);

    assertThat(state.getLocationHash()).isEqualTo(locationHash);
    assertThat(state.getMaxHits()).isEqualTo(maxHits);
    assertThat(state.getExpiresAt()).isEqualTo(expiresAt);
    assertThat(state.getInstrumentationType()).isEqualTo(InstrumentationType.BREAKPOINT);
    assertThat(state.getCreatedAt()).isEqualTo(createdAt);
  }
}
