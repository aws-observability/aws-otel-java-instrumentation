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

package com.softwareaws.xray.opentelemetry.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.primitives.Ints;
import io.opentelemetry.api.OpenTelemetry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;

class AwsOpenTelemetryFactoryTest {

  private static final OpenTelemetry openTelemetry = new AwsOpenTelemetryFactory().create();

  // The probability of this passing once without correct IDs is low, 20 times is inconceivable.
  @RepeatedTest(20)
  void providerGeneratesXrayIds() {
    int startTimeSecs = (int) TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
    var span = openTelemetry.getTracer("test").spanBuilder("test").startSpan();
    byte[] traceId = span.getSpanContext().getTraceIdBytes();
    int epoch = Ints.fromBytes(traceId[0], traceId[1], traceId[2], traceId[3]);
    assertThat(epoch).isGreaterThanOrEqualTo(startTimeSecs);
  }
}
