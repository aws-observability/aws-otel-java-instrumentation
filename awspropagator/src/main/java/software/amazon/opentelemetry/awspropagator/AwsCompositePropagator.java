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

package software.amazon.opentelemetry.awspropagator;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.extension.aws.AwsXrayPropagator;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A {@link TextMapPropagator} for use by AWS services or compatible ones such as localstack. As AWS
 * is generic cloud infrastructure, rather than enforcing a specific injection format such as
 * W3CTraceContext, it is intended to inject the same format as was extracted to allow compatibility
 * with client applications that may not understand newer formats like W3C.
 *
 * <p>To allow rolling this propagator out to independent services, it also supports a mode that
 * always injects in AWS format. This is intended for a transition period where most services will
 * only understand that format.
 */
public final class AwsCompositePropagator implements TextMapPropagator {

  // The currently supported propagators. This may grow or be made configurable in the future.
  private static final List<TextMapPropagator> PROPAGATORS =
      Arrays.asList(
          W3CBaggagePropagator.getInstance(),
          AwsXrayPropagator.getInstance(),
          W3CTraceContextPropagator.getInstance(),
          B3Propagator.injectingMultiHeaders());

  /**
   * Returns a {@link AwsCompositePropagator} which injects in the same format it extracted a trace
   * context with. For example, if an incoming request contained a trace context in B3 format, an
   * outgoing request will have B3 format injected.
   */
  public static AwsCompositePropagator injectingExtractedFormat() {
    return new AwsCompositePropagator(true);
  }

  /**
   * Returns a {@link AwsCompositePropagator} which injects in the AWS format (X-Amzn-Trace-Id). For
   * example, if an incoming request contained a trace context in B3 format, an outgoing request
   * will have AWS format injected.
   */
  public static AwsCompositePropagator injectingAwsFormat() {
    return new AwsCompositePropagator(false);
  }

  private static final ContextKey<TextMapPropagator> EXTRACTED_PROPAGATOR =
      ContextKey.named("extracted-propagator");

  private final List<String> fields;
  private final boolean injectExtractedFormat;

  private AwsCompositePropagator(boolean injectExtractedFormat) {
    this.injectExtractedFormat = injectExtractedFormat;

    fields = PROPAGATORS.stream().flatMap(p -> p.fields().stream()).collect(Collectors.toList());
  }

  @Override
  public Collection<String> fields() {
    return fields;
  }

  @Override
  public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
    if (injectExtractedFormat) {
      TextMapPropagator extractedPropagator = context.get(EXTRACTED_PROPAGATOR);
      if (extractedPropagator != null) {
        extractedPropagator.inject(context, carrier, setter);
        Baggage baggage = Baggage.fromContextOrNull(context);
        if (baggage != null && extractedPropagator != AwsXrayPropagator.getInstance()) {
          // We extracted a span from a format not supporting baggage within the trace context
          // itself, for example b3. if we have baggage we just propagate using w3c
          // baggage.
          W3CBaggagePropagator.getInstance().inject(context, carrier, setter);
        }
        return;
      }
    }
    // Unless injecting in the same format as extracted, always inject X-Amzn-Trace-Id, the only
    // format recognized by all AWS services as of now.
    AwsXrayPropagator.getInstance().inject(context, carrier, setter);
  }

  @Override
  public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
    for (TextMapPropagator propagator : PROPAGATORS) {
      context = propagator.extract(context, carrier, getter);
      if (Span.fromContextOrNull(context) != null) {
        if (injectExtractedFormat) {
          context = context.with(EXTRACTED_PROPAGATOR, propagator);
        }
        return context;
      }
    }

    return context;
  }
}
