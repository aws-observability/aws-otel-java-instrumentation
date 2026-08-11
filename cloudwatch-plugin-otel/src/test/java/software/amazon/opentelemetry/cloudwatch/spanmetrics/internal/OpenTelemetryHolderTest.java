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

package software.amazon.opentelemetry.cloudwatch.spanmetrics.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.opentelemetry.cloudwatch.spanmetrics.SpanMetricsProcessor;

class OpenTelemetryHolderTest {

  @BeforeEach
  @AfterEach
  void clear() {
    OpenTelemetryHolder.reset();
  }

  @Test
  void unboundReturnsNull() {
    assertThat(OpenTelemetryHolder.get()).isNull();
  }

  @Test
  void processorDropsMetricsWhenHolderUnbound() {
    // Unbound holder is the production state of a wiring mode that never binds the SDK. onEnd must
    // not obtain instruments and must not throw.
    SpanMetricsProcessor processor = new SpanMetricsProcessor();
    assertThatCode(() -> processor.onEnd(unboundSpan())).doesNotThrowAnyException();
    assertThat(OpenTelemetryHolder.get()).isNull();
  }

  private static ReadableSpan unboundSpan() {
    SpanData data =
        TestSpanData.builder()
            .setName("op")
            .setKind(SpanKind.SERVER)
            .setStatus(StatusData.unset())
            .setResource(
                Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "svc")))
            .setAttributes(Attributes.empty())
            .setSpanContext(
                SpanContext.create(
                    "00000000000000000000000000000001",
                    "0000000000000001",
                    TraceFlags.getDefault(),
                    TraceState.getDefault()))
            .setStartEpochNanos(0)
            .setEndEpochNanos(5_000_000)
            .setHasEnded(true)
            .build();
    ReadableSpan span = Mockito.mock(ReadableSpan.class);
    Mockito.when(span.toSpanData()).thenReturn(data);
    return span;
  }

  @Test
  void firstBindWins() {
    OpenTelemetry first = OpenTelemetry.noop();
    OpenTelemetry second = OpenTelemetry.propagating(io.opentelemetry.context.propagation.ContextPropagators.noop());

    OpenTelemetryHolder.set(first);
    OpenTelemetryHolder.set(second);

    assertThat(OpenTelemetryHolder.get()).isSameAs(first);
  }

  @Test
  void rebindingSameInstanceIsIdempotent() {
    OpenTelemetry only = OpenTelemetry.noop();
    OpenTelemetryHolder.set(only);
    OpenTelemetryHolder.set(only);
    assertThat(OpenTelemetryHolder.get()).isSameAs(only);
  }
}
