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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.cloudwatch.spanmetrics.SpanMetrics;
import software.amazon.opentelemetry.cloudwatch.spanmetrics.SpanMetricsProcessor;

/**
 * When the host has no metrics pipeline (reader-less MeterProvider, e.g.
 * OTEL_METRICS_EXPORTER=none) the SDK returns the API no-op meter. The plugin must stay inert: it
 * must not stamp the dedup marker (which would suppress backend generation for a metric it never
 * emits) and must not throw.
 *
 * <p>Runs under the min/latest compat matrix (via -PotelTestVersion), so a future SDK release that
 * changes reader-less-MeterProvider behavior — and breaks the no-op detection — fails here.
 */
class NoMetricsPipelineTest {

  private static final AttributeKey<String> SCHEMA_ATTR =
      AttributeKey.stringKey("aws.otel.span.metrics.schema");

  @BeforeEach
  @AfterEach
  void clear() {
    OpenTelemetryHolder.reset();
  }

  @Test
  void readerlessMeterProviderLeavesTheProcessorInert() {
    // MeterProvider with no reader registered == OTEL_METRICS_EXPORTER=none.
    SdkMeterProvider meterProvider = SdkMeterProvider.builder().build();
    SpanMetricsProcessor processor = new SpanMetricsProcessor();
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(processor).build())
            .setMeterProvider(meterProvider)
            .build();
    SpanMetrics.bind(sdk);

    Span span = sdk.getTracer("test").spanBuilder("op").startSpan();
    // The dedup marker must NOT be stamped, so the backend still generates for this span.
    assertThat(span.getSpanContext().isValid()).isTrue();
    assertThatCode(span::end).doesNotThrowAnyException();

    // Reading the marker back off the ended span: it must be absent.
    io.opentelemetry.sdk.trace.data.SpanData data =
        ((io.opentelemetry.sdk.trace.ReadableSpan) span).toSpanData();
    assertThat(data.getAttributes().get(SCHEMA_ATTR)).isNull();
  }

  @Test
  void meterProviderWithReaderStampsAndRecords() {
    // Control case: a real metrics pipeline — the plugin stamps the marker and records the metric.
    InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(exporter).build())
            .build();
    SpanMetricsProcessor processor = new SpanMetricsProcessor();
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(processor).build())
            .setMeterProvider(meterProvider)
            .build();
    SpanMetrics.bind(sdk);

    Span span = sdk.getTracer("test").spanBuilder("op").startSpan();
    span.end();

    io.opentelemetry.sdk.trace.data.SpanData data =
        ((io.opentelemetry.sdk.trace.ReadableSpan) span).toSpanData();
    assertThat(data.getAttributes().get(SCHEMA_ATTR)).isEqualTo("v1");

    meterProvider.forceFlush().join(10, TimeUnit.SECONDS);
    Collection<MetricData> metrics = exporter.getFinishedMetricItems();
    assertThat(metrics).anyMatch(m -> "traces.span.metrics.calls".equals(m.getName()));
  }
}
