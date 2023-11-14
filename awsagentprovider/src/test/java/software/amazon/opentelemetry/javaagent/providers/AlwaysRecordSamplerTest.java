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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AlwaysRecordSampler}. */
class AlwaysRecordSamplerTest {

  // Mocks
  private Sampler mockSampler;

  private AlwaysRecordSampler sampler;

  @BeforeEach
  void setUpSamplers() {
    mockSampler = mock(Sampler.class);
    sampler = AlwaysRecordSampler.create(mockSampler);
  }

  @Test
  void testGetDescription() {
    when(mockSampler.getDescription()).thenReturn("mockDescription");
    assertThat(sampler.getDescription()).isEqualTo("AlwaysRecordSampler{mockDescription}");
  }

  @Test
  void testRecordAndSampleSamplingDecision() {
    validateShouldSample(SamplingDecision.RECORD_AND_SAMPLE, SamplingDecision.RECORD_AND_SAMPLE);
  }

  @Test
  void testRecordOnlySamplingDecision() {
    validateShouldSample(SamplingDecision.RECORD_ONLY, SamplingDecision.RECORD_ONLY);
  }

  @Test
  void testDropSamplingDecision() {
    validateShouldSample(SamplingDecision.DROP, SamplingDecision.RECORD_ONLY);
  }

  private void validateShouldSample(
      SamplingDecision rootDecision, SamplingDecision expectedDecision) {
    SamplingResult rootResult = buildRootSamplingResult(rootDecision);
    when(mockSampler.shouldSample(any(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(rootResult);
    SamplingResult actualResult =
        sampler.shouldSample(
            Context.current(),
            TraceId.fromLongs(1, 2),
            "name",
            SpanKind.CLIENT,
            Attributes.empty(),
            Collections.emptyList());

    if (rootDecision.equals(expectedDecision)) {
      assertThat(actualResult).isEqualTo(rootResult);
      assertThat(actualResult.getDecision()).isEqualTo(rootDecision);
    } else {
      assertThat(actualResult).isNotEqualTo(rootResult);
      assertThat(actualResult.getDecision()).isEqualTo(expectedDecision);
    }

    assertThat(actualResult.getAttributes()).isEqualTo(rootResult.getAttributes());
    TraceState traceState = TraceState.builder().build();
    assertThat(actualResult.getUpdatedTraceState(traceState))
        .isEqualTo(rootResult.getUpdatedTraceState(traceState));
  }

  private static SamplingResult buildRootSamplingResult(SamplingDecision samplingDecision) {
    return new SamplingResult() {
      @Override
      public SamplingDecision getDecision() {
        return samplingDecision;
      }

      @Override
      public Attributes getAttributes() {
        return Attributes.of(AttributeKey.stringKey("key"), samplingDecision.name());
      }

      @Override
      public TraceState getUpdatedTraceState(TraceState parentTraceState) {
        return TraceState.builder().put("key", samplingDecision.name()).build();
      }
    };
  }
}
