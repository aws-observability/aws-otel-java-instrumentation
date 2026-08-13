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

package software.amazon.opentelemetry.cloudwatch.spanmetrics.jmh;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.Collection;
import software.amazon.opentelemetry.cloudwatch.spanmetrics.SpanMetrics;

/** Shared setup: a bound SDK (so the processor records into real instruments) and sample spans. */
final class BenchmarkSupport {

  private static final SpanContext SPAN_CONTEXT =
      SpanContext.create(
          "00000000000000000000000000000001",
          "0000000000000001",
          TraceFlags.getSampled(),
          TraceState.getDefault());

  private static final Resource RESOURCE =
      Resource.create(Attributes.builder().put("service.name", "bench").build());

  /**
   * Binds a real SdkMeterProvider whose reader drops on export, so onEnd exercises the true record
   * path without measuring serialization/IO. First-wins bind, safe to call repeatedly.
   */
  static void bindSdk() {
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(new NoopExporter()).build())
            .build();
    SpanMetrics.bind(OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build());
  }

  static SpanData httpServerSpan() {
    return baseSpan(SpanKind.SERVER, "GET /orders/{id}")
        .setAttributes(
            Attributes.builder()
                .put("http.request.method", "GET")
                .put("http.response.status_code", 200L)
                .put("http.route", "/orders/{id}")
                .put("url.path", "/orders/12345") // not allowlisted — realistic noise
                .put("url.scheme", "http")
                .put("network.peer.port", 55123L)
                .build())
        .build();
  }

  /**
   * DB CLIENT span emitting only legacy semconv keys — exercises the fallback path (worst case).
   */
  static SpanData databaseLegacySpan() {
    return baseSpan(SpanKind.CLIENT, "SELECT orders")
        .setAttributes(
            Attributes.builder()
                .put("db.system", "postgresql")
                .put("db.operation", "SELECT")
                .put("db.sql.table", "orders")
                .put("db.statement", "SELECT * FROM orders WHERE id = ?") // not allowlisted
                .build())
        .build();
  }

  static SpanData internalSpan() {
    return baseSpan(SpanKind.INTERNAL, "compute").setAttributes(Attributes.empty()).build();
  }

  /**
   * A real recording SDK span (implements ReadWriteSpan) for measuring onStart against production.
   */
  static io.opentelemetry.sdk.trace.ReadWriteSpan recordingSpan() {
    io.opentelemetry.sdk.trace.SdkTracerProvider tp =
        io.opentelemetry.sdk.trace.SdkTracerProvider.builder().build();
    return (io.opentelemetry.sdk.trace.ReadWriteSpan)
        tp.get("bench").spanBuilder("s").setSpanKind(SpanKind.SERVER).startSpan();
  }

  private static TestSpanData.Builder baseSpan(SpanKind kind, String name) {
    return TestSpanData.builder()
        .setName(name)
        .setKind(kind)
        .setStatus(StatusData.unset())
        .setResource(RESOURCE)
        .setSpanContext(SPAN_CONTEXT)
        .setStartEpochNanos(0)
        .setEndEpochNanos(5_000_000)
        .setHasEnded(true);
  }

  private BenchmarkSupport() {}

  /** Discards metrics on export — keeps the benchmark focused on the record path. */
  private static final class NoopExporter implements MetricExporter {
    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
      return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
