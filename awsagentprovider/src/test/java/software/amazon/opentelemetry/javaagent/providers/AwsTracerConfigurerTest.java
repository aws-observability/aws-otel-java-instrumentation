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

import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.primitives.Ints;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;

class AwsTracerConfigurerTest {

  private AutoConfiguredOpenTelemetrySdkBuilder createSdkBuilder() {
    InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
    return AutoConfiguredOpenTelemetrySdk.builder()
        .addTracerProviderCustomizer(
            (tracerProviderBuilder, config) -> {
              return tracerProviderBuilder.addSpanProcessor(
                  SimpleSpanProcessor.create(spanExporter));
            })
        .addPropertiesSupplier(
            () -> singletonMap("otel.aws.application.signals.runtime.enabled", "false"))
        .addPropertiesSupplier(() -> singletonMap("otel.metrics.exporter", "none"))
        .addPropertiesSupplier(() -> singletonMap("otel.traces.exporter", "none"))
        .addPropertiesSupplier(() -> singletonMap("otel.logs.exporter", "none"));
  }

  // The probability of this passing once without correct IDs is low, 20 times is inconceivable.
  @RepeatedTest(20)
  void providerGeneratesXrayIds() {
    OpenTelemetrySdk sdk = createSdkBuilder().build().getOpenTelemetrySdk();

    Tracer tracer = sdk.getTracer("test");
    int startTimeSecs = (int) TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
    var span = tracer.spanBuilder("test").startSpan();
    byte[] traceId = span.getSpanContext().getTraceIdBytes();
    int epoch = Ints.fromBytes(traceId[0], traceId[1], traceId[2], traceId[3]);
    assertThat(epoch).isGreaterThanOrEqualTo(startTimeSecs);
  }

  // Sanity check that the trace ID ratio sampler works fine with the x-ray generator.
  @RepeatedTest(20)
  void traceIdRatioSampler() {
    OpenTelemetrySdk sdk =
        createSdkBuilder()
            .addTracerProviderCustomizer(
                (tracerProviderBuilder, config) ->
                    tracerProviderBuilder.setSampler(Sampler.traceIdRatioBased(0.01)))
            .build()
            .getOpenTelemetrySdk();

    int numSpans = 100000;
    int numSampled = 0;
    Tracer tracer = sdk.getTracer("test");
    for (int i = 0; i < numSpans; i++) {
      var span = tracer.spanBuilder("test").startSpan();
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
