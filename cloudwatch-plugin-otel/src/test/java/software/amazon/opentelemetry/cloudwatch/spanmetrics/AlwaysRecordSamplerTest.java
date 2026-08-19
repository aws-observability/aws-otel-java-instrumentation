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

package software.amazon.opentelemetry.cloudwatch.spanmetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlwaysRecordSamplerTest {

  @Mock private Sampler delegate;

  private SamplingResult sample(SamplingDecision decision, Attributes attributes) {
    when(delegate.shouldSample(
            Context.root(),
            "trace",
            "span",
            SpanKind.SERVER,
            Attributes.empty(),
            Collections.emptyList()))
        .thenReturn(SamplingResult.create(decision, attributes));
    return AlwaysRecordSampler.create(delegate)
        .shouldSample(
            Context.root(),
            "trace",
            "span",
            SpanKind.SERVER,
            Attributes.empty(),
            Collections.emptyList());
  }

  @Test
  void dropBecomesRecordOnly() {
    Attributes attrs = Attributes.of(AttributeKey.stringKey("k"), "v");
    SamplingResult result = sample(SamplingDecision.DROP, attrs);
    assertThat(result.getDecision()).isEqualTo(SamplingDecision.RECORD_ONLY);
    assertThat(result.getAttributes()).isEqualTo(attrs);
  }

  @Test
  void dropForwardsDelegateTraceState() {
    TraceState delegateState = TraceState.builder().put("k", "v").build();
    SamplingResult delegateResult =
        new SamplingResult() {
          @Override
          public SamplingDecision getDecision() {
            return SamplingDecision.DROP;
          }

          @Override
          public Attributes getAttributes() {
            return Attributes.empty();
          }

          @Override
          public TraceState getUpdatedTraceState(TraceState parentTraceState) {
            return delegateState;
          }
        };
    when(delegate.shouldSample(
            Context.root(),
            "trace",
            "span",
            SpanKind.SERVER,
            Attributes.empty(),
            Collections.emptyList()))
        .thenReturn(delegateResult);

    SamplingResult result =
        AlwaysRecordSampler.create(delegate)
            .shouldSample(
                Context.root(),
                "trace",
                "span",
                SpanKind.SERVER,
                Attributes.empty(),
                Collections.emptyList());

    assertThat(result.getDecision()).isEqualTo(SamplingDecision.RECORD_ONLY);
    assertThat(result.getUpdatedTraceState(TraceState.getDefault())).isEqualTo(delegateState);
  }

  @Test
  void recordAndSamplePassesThrough() {
    SamplingResult result = sample(SamplingDecision.RECORD_AND_SAMPLE, Attributes.empty());
    assertThat(result.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
  }

  @Test
  void recordOnlyPassesThrough() {
    SamplingResult result = sample(SamplingDecision.RECORD_ONLY, Attributes.empty());
    assertThat(result.getDecision()).isEqualTo(SamplingDecision.RECORD_ONLY);
  }

  @Test
  void descriptionWrapsDelegate() {
    when(delegate.getDescription()).thenReturn("Delegate");
    assertThat(AlwaysRecordSampler.create(delegate).getDescription())
        .isEqualTo("AlwaysRecordSampler{Delegate}");
  }
}
