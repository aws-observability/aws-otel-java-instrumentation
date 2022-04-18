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

package software.amazon.opentelemetry.javaagent.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.primitives.Ints;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;

class AwsTracerConfigurerTest {

  // The probability of this passing once without correct IDs is low, 20 times is inconceivable.
  @RepeatedTest(20)
  void providerGeneratesXrayIds() {
    SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
    new AwsTracerConfigurer().configure(builder, null);
    TracerProvider tracerProvider = builder.build();

    int startTimeSecs = (int) TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
    var span = tracerProvider.get("test").spanBuilder("test").startSpan();
    byte[] traceId = span.getSpanContext().getTraceIdBytes();
    int epoch = Ints.fromBytes(traceId[0], traceId[1], traceId[2], traceId[3]);
    assertThat(epoch).isGreaterThanOrEqualTo(startTimeSecs);
  }

  // Sanity check that the trace ID ratio sampler works fine with the x-ray generator.
  @RepeatedTest(20)
  void traceIdRatioSampler() {
    SdkTracerProviderBuilder builder =
        SdkTracerProvider.builder().setSampler(Sampler.traceIdRatioBased(0.01));
    new AwsTracerConfigurer().configure(builder, null);
    TracerProvider tracerProvider = builder.build();
    int numSpans = 100000;
    int numSampled = 0;
    for (int i = 0; i < numSpans; i++) {
      var span = tracerProvider.get("test").spanBuilder("test").startSpan();
      if (span.getSpanContext().getTraceFlags().isSampled()) {
        numSampled++;
      }
      span.end();
    }
    // Configured for 1%, confirm there are at most 5% to account for randomness and reduce test
    // flakiness.
    assertThat((double) numSampled / numSpans).isLessThan(0.05);
  }
}
