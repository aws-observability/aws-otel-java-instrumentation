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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CaptureRateLimiterTest {

  @Test
  void defaultRate_isFivePerSecond() {
    CaptureRateLimiter limiter = new CaptureRateLimiter();
    assertThat(limiter.getMaxCapturesPerSecond()).isEqualTo(5);
  }

  @Test
  void customRate_isRespected() {
    CaptureRateLimiter limiter = new CaptureRateLimiter(10);
    assertThat(limiter.getMaxCapturesPerSecond()).isEqualTo(10);
  }

  @Test
  void constructor_rejectsZero() {
    assertThatThrownBy(() -> new CaptureRateLimiter(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructor_rejectsNegative() {
    assertThatThrownBy(() -> new CaptureRateLimiter(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void allowsExactlyMaxCapturesInOneWindow() {
    CaptureRateLimiter limiter = new CaptureRateLimiter(5);
    long baseTime = System.nanoTime();

    // First 5 should all be allowed within the same window
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire(baseTime + i)).isTrue();
    }

    // 6th in the same window should be rejected
    assertThat(limiter.tryAcquire(baseTime + 5)).isFalse();
    assertThat(limiter.tryAcquire(baseTime + 6)).isFalse();
  }

  @Test
  void resetsAfterOneSecondWindow() {
    CaptureRateLimiter limiter = new CaptureRateLimiter(3);
    long baseTime = System.nanoTime();

    // Use up all 3 permits in window 1
    assertThat(limiter.tryAcquire(baseTime)).isTrue();
    assertThat(limiter.tryAcquire(baseTime + 100)).isTrue();
    assertThat(limiter.tryAcquire(baseTime + 200)).isTrue();
    assertThat(limiter.tryAcquire(baseTime + 300)).isFalse(); // over limit

    // Advance past 1 second — new window
    long newWindow = baseTime + 1_000_000_001L;
    assertThat(limiter.tryAcquire(newWindow)).isTrue();
    assertThat(limiter.tryAcquire(newWindow + 100)).isTrue();
    assertThat(limiter.tryAcquire(newWindow + 200)).isTrue();
    assertThat(limiter.tryAcquire(newWindow + 300)).isFalse(); // over limit again
  }

  @Test
  void singleCapturePerSecond_allowsOneRejectsRest() {
    CaptureRateLimiter limiter = new CaptureRateLimiter(1);
    long baseTime = System.nanoTime();

    assertThat(limiter.tryAcquire(baseTime)).isTrue();
    assertThat(limiter.tryAcquire(baseTime + 100)).isFalse();
    assertThat(limiter.tryAcquire(baseTime + 1000)).isFalse();

    // Next second window
    assertThat(limiter.tryAcquire(baseTime + 1_000_000_001L)).isTrue();
    assertThat(limiter.tryAcquire(baseTime + 1_000_000_002L)).isFalse();
  }

  @Test
  void highRate_allowsMany() {
    CaptureRateLimiter limiter = new CaptureRateLimiter(1000);
    long baseTime = System.nanoTime();

    int allowed = 0;
    for (int i = 0; i < 1500; i++) {
      if (limiter.tryAcquire(baseTime + i)) {
        allowed++;
      }
    }

    assertThat(allowed).isEqualTo(1000);
  }

  @Test
  void getCurrentCount_tracksCaptures() {
    CaptureRateLimiter limiter = new CaptureRateLimiter(10);
    long baseTime = System.nanoTime();

    assertThat(limiter.getCurrentCount()).isEqualTo(0);

    limiter.tryAcquire(baseTime);
    assertThat(limiter.getCurrentCount()).isEqualTo(1);

    limiter.tryAcquire(baseTime + 1);
    limiter.tryAcquire(baseTime + 2);
    assertThat(limiter.getCurrentCount()).isEqualTo(3);
  }

  @Test
  void multipleWindowRollovers() {
    CaptureRateLimiter limiter = new CaptureRateLimiter(2);
    long baseTime = System.nanoTime();
    long oneSecond = 1_000_000_000L;

    // Window 1: allow 2
    assertThat(limiter.tryAcquire(baseTime)).isTrue();
    assertThat(limiter.tryAcquire(baseTime + 1)).isTrue();
    assertThat(limiter.tryAcquire(baseTime + 2)).isFalse();

    // Window 2
    assertThat(limiter.tryAcquire(baseTime + oneSecond + 1)).isTrue();
    assertThat(limiter.tryAcquire(baseTime + oneSecond + 2)).isTrue();
    assertThat(limiter.tryAcquire(baseTime + oneSecond + 3)).isFalse();

    // Window 3
    assertThat(limiter.tryAcquire(baseTime + 2 * oneSecond + 1)).isTrue();
    assertThat(limiter.tryAcquire(baseTime + 2 * oneSecond + 2)).isTrue();
    assertThat(limiter.tryAcquire(baseTime + 2 * oneSecond + 3)).isFalse();
  }
}
