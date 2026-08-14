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

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.Collection;

/**
 * Runs the plugin's core hot path end to end on a Java 8 runtime, so a Java 9+ API that slipped
 * past the compile-time gate fails the build here. Not a JUnit test: it runs under a Java 8
 * toolchain via a plain {@code main}, and exits non-zero on failure.
 */
public final class Java8SmokeTest {

  private Java8SmokeTest() {}

  public static void main(String[] args) {
    InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(exporter).build())
            .build();
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .setSampler(AlwaysRecordSampler.create(Sampler.alwaysOff()))
                    .addSpanProcessor(new SpanMetricsProcessor())
                    .build())
            .setMeterProvider(meterProvider)
            .build();
    SpanMetrics.bind(sdk);

    Span span = sdk.getTracer("smoke").spanBuilder("op").startSpan();
    span.end();

    meterProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
    Collection<MetricData> metrics = exporter.getFinishedMetricItems();
    boolean sawCalls =
        metrics.stream().anyMatch(m -> "traces.span.metrics.calls".equals(m.getName()));
    if (!sawCalls) {
      throw new IllegalStateException(
          "Java 8 smoke test failed: calls metric not produced; metrics=" + metrics);
    }
    System.out.println("Java 8 smoke test passed on " + System.getProperty("java.version"));
  }
}
