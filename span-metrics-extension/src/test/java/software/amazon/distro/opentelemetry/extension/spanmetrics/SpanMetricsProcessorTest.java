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

package software.amazon.distro.opentelemetry.extension.spanmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.Collection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SpanMetricsProcessorTest {

  private static InMemoryMetricReader reader;
  private static SpanMetricsProcessor processor;

  @BeforeAll
  static void setUp() {
    // The holder is a first-wins singleton, so all tests share one bound SDK.
    reader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder().registerMetricReader(reader).build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();
    SpanMetrics.bind(sdk);
    processor = new SpanMetricsProcessor();
  }

  private static ReadableSpan readableSpan(SpanKind kind, StatusData status, Attributes attrs) {
    SpanData data =
        TestSpanData.builder()
            .setName("op")
            .setKind(kind)
            .setStatus(status)
            .setResource(
                Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "svc")))
            .setAttributes(attrs)
            .setSpanContext(
                SpanContext.create(
                    "00000000000000000000000000000001",
                    "0000000000000001",
                    TraceFlags.getDefault(),
                    TraceState.getDefault()))
            .setStartEpochNanos(0)
            .setEndEpochNanos(5_000_000) // 5 ms
            .setHasEnded(true)
            .build();
    ReadableSpan span = Mockito.mock(ReadableSpan.class);
    Mockito.when(span.toSpanData()).thenReturn(data);
    return span;
  }

  @Test
  void emitsCallsAndDurationWithExpectedShape() {
    processor.onEnd(readableSpan(SpanKind.SERVER, StatusData.unset(), Attributes.empty()));

    Collection<MetricData> metrics = reader.collectAllMetrics();
    MetricData calls =
        metrics.stream()
            .filter(m -> m.getName().equals("traces.span.metrics.calls"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("calls metric missing"));
    MetricData duration =
        metrics.stream()
            .filter(m -> m.getName().equals("traces.span.metrics.duration"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("duration metric missing"));

    // Duration is in seconds; a 5ms span records 0.005s, with the connector default buckets.
    assertThat(duration.getUnit()).isEqualTo("s");
    assertThat(duration.getHistogramData().getPoints())
        .anySatisfy(
            p -> {
              assertThat(p.getSum()).isEqualTo(0.005);
              assertThat(p.getBoundaries())
                  .containsExactly(
                      0.002, 0.004, 0.006, 0.008, 0.01, 0.05, 0.1, 0.2, 0.4, 0.8, 1.0, 1.4, 2.0,
                      5.0, 10.0, 15.0);
            });

    // calls is a monotonic Sum with no unit (connector parity).
    assertThat(calls.getUnit()).isEmpty();
    assertThat(calls.getLongSumData().isMonotonic()).isTrue();
    assertThat(calls.getLongSumData().getPoints())
        .anySatisfy(
            p -> {
              assertThat(p.getValue()).isEqualTo(1);
              // Short-form base attribute values.
              assertThat(p.getAttributes().get(AttributeKey.stringKey("span.kind")))
                  .isEqualTo("SERVER");
              assertThat(p.getAttributes().get(AttributeKey.stringKey("status.code")))
                  .isEqualTo("UNSET");
              // Schema + lib-version markers on the metric (spec §6).
              assertThat(p.getAttributes().get(AttributeKey.stringKey("aws.otel.span.metrics.schema")))
                  .isEqualTo("v1");
              assertThat(p.getAttributes().get(AttributeKey.stringKey("aws.otel.extension.lib.version")))
                  .isEqualTo(SpanMetricsProcessor.LIB_VERSION);
            });
  }

  @Test
  void onEndSwallowsExceptions() {
    ReadableSpan bad = Mockito.mock(ReadableSpan.class);
    Mockito.when(bad.toSpanData()).thenThrow(new RuntimeException("boom"));
    // Must not propagate — a bad span cannot break the host pipeline.
    processor.onEnd(bad);
  }

  @Test
  void lifecycleFlags() {
    assertThat(processor.isEndRequired()).isTrue();
    // onStart is required now: the processor stamps dedup/schema attributes on the span.
    assertThat(processor.isStartRequired()).isTrue();
  }

  @Test
  void onStartStampsSchemaAndLibVersionOnSpan() {
    io.opentelemetry.sdk.trace.ReadWriteSpan span =
        Mockito.mock(io.opentelemetry.sdk.trace.ReadWriteSpan.class);
    processor.onStart(io.opentelemetry.context.Context.root(), span);
    Mockito.verify(span)
        .setAttribute(AttributeKey.stringKey("aws.otel.span.metrics.schema"), "v1");
    Mockito.verify(span)
        .setAttribute(
            AttributeKey.stringKey("aws.otel.extension.lib.version"),
            SpanMetricsProcessor.LIB_VERSION);
  }

  @Test
  void onStartSwallowsExceptions() {
    io.opentelemetry.sdk.trace.ReadWriteSpan span =
        Mockito.mock(io.opentelemetry.sdk.trace.ReadWriteSpan.class);
    Mockito.when(span.setAttribute(Mockito.any(AttributeKey.class), Mockito.any()))
        .thenThrow(new RuntimeException("boom"));
    // Must not propagate — a failure stamping attributes cannot break the host pipeline.
    processor.onStart(io.opentelemetry.context.Context.root(), span);
  }
}
