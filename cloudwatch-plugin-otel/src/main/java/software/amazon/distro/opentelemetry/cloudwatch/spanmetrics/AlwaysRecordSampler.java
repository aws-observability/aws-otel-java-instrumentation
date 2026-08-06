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

package software.amazon.distro.opentelemetry.cloudwatch.spanmetrics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;
import java.util.logging.Logger;

/**
 * Wraps the configured sampler and turns {@code DROP} decisions into {@code RECORD_ONLY}. Spans the
 * delegate would drop are still recorded, so {@link SpanMetricsProcessor#onEnd} sees them, but they
 * are not exported, so trace volume still honors the configured sampling rate.
 *
 * <p>This is a deliberate local copy of the upstream {@code AlwaysRecord} sampler (defined by the
 * OpenTelemetry specification and implemented as {@code AlwaysRecordSampler} in
 * {@code opentelemetry-sdk-extension-incubator}). We keep our own copy rather than depend on that
 * artifact because it ships only as an {@code -alpha} module whose API is explicitly marked internal
 * and unstable ("can change at any time"); a GA library should not inherit that churn for a class
 * this small. The DROP-to-RECORD_ONLY behavior is frozen by the spec, so there is nothing to keep in
 * sync. Revisit depending on the incubator artifact once that sampler graduates to the stable API.
 */
public final class AlwaysRecordSampler implements Sampler {

  private static final Logger logger = Logger.getLogger(AlwaysRecordSampler.class.getName());

  private final Sampler delegate;

  public static AlwaysRecordSampler create(Sampler delegate) {
    logger.info(
        "Span metrics: sampler wrapped ("
            + delegate.getDescription()
            + "); metrics reflect 100% of spans while span export honors the configured sampling"
            + " rate");
    return new AlwaysRecordSampler(delegate);
  }

  private AlwaysRecordSampler(Sampler delegate) {
    this.delegate = delegate;
  }

  @Override
  public SamplingResult shouldSample(
      Context parentContext,
      String traceId,
      String name,
      SpanKind spanKind,
      Attributes attributes,
      List<LinkData> parentLinks) {
    SamplingResult result =
        delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    if (result.getDecision() == SamplingDecision.DROP) {
      return SamplingResult.create(SamplingDecision.RECORD_ONLY, result.getAttributes());
    }
    return result;
  }

  @Override
  public String getDescription() {
    return "AlwaysRecordSampler{" + delegate.getDescription() + "}";
  }
}
