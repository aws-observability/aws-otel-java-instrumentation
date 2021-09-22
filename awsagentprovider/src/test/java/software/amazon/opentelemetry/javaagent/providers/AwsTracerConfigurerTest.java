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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;

class AwsTracerConfigurerTest {

  private static final TracerProvider tracerProvider;

  static {
    SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
    new AwsTracerConfigurer().configure(builder, null);
    tracerProvider = builder.build();
  }

  // The probability of this passing once without correct IDs is low, 20 times is inconceivable.
  @RepeatedTest(20)
  void providerGeneratesXrayIds() {
    int startTimeSecs = (int) TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
    var span = tracerProvider.get("test").spanBuilder("test").startSpan();
    byte[] traceId = span.getSpanContext().getTraceIdBytes();
    int epoch = Ints.fromBytes(traceId[0], traceId[1], traceId[2], traceId[3]);
    assertThat(epoch).isGreaterThanOrEqualTo(startTimeSecs);
  }
}
