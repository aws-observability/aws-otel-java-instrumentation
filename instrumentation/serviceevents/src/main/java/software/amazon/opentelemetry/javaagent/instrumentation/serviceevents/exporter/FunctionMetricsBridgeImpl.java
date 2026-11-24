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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import software.amazon.opentelemetry.serviceevents.FunctionMetricsBridge;

/**
 * Agent-classloader implementation of {@link FunctionMetricsBridge} that records each sampled
 * function call into the {@code service.function.duration} OTel Exponential Histogram.
 *
 * <p>See {@code SERVICE_FUNCTION_DURATION_METRIC_SPEC.md} for the full attribute contract.
 *
 * <p>Per-data-point base attributes (today: just {@code Telemetry.Source}) are written once at
 * construction time and never mutated; per-call attributes are layered on top via a fresh {@link
 * AttributesBuilder} so this class is safe to share across threads with no locking. The
 * process-constant ServiceEvents attributes ({@code aws.service_events.version}, {@code
 * aws.service_events.deployment.id}, {@code vcs.*}) ride on the dedicated MeterProvider's Resource
 * and are sent once per OTLP batch.
 */
public final class FunctionMetricsBridgeImpl implements FunctionMetricsBridge {

  private final DoubleHistogram durationHistogram;
  private final Attributes baseAttributes;

  /**
   * @param durationHistogram the {@code service.function.duration} Histogram
   * @param baseAttributes shared, immutable set of per-data-point attributes attached to every data
   *     point (currently just {@code Telemetry.Source})
   */
  public FunctionMetricsBridgeImpl(DoubleHistogram durationHistogram, Attributes baseAttributes) {
    this.durationHistogram = durationHistogram;
    this.baseAttributes = baseAttributes;
  }

  @Override
  public void recordFunctionCall(
      String functionId, String operation, String caller, long durationNs, String exceptionType) {
    AttributesBuilder builder = baseAttributes.toBuilder();
    builder.put("function.name", functionId);
    if (caller != null && !caller.isEmpty()) {
      builder.put("aws.service_events.caller", caller);
    }
    builder.put("status", exceptionType != null && !exceptionType.isEmpty() ? "error" : "success");

    double durationUs = durationNs / 1000.0;
    durationHistogram.record(durationUs, builder.build());
  }
}
