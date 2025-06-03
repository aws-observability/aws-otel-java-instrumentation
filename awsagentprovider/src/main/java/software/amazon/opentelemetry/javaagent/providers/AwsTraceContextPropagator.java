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

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.contrib.awsxray.AwsXrayRemoteSampler;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;
import javax.annotation.Nullable;

public class AwsTraceContextPropagator implements TextMapPropagator {

  private static final Logger logger = Logger.getLogger(AwsTraceContextPropagator.class.getName());

  private static final String AWS_XRAY_SAMPLING_RULE_CARRIER_KEY = "aws-xray-sampling-rule";
  private static final List<String> FIELDS = Arrays.asList(AWS_XRAY_SAMPLING_RULE_CARRIER_KEY);

  private Sampler sampler;

  public AwsTraceContextPropagator(Sampler sampler) {
    this.sampler = sampler;
  }

  // This happens after the span is done being generated and processed
  @Override
  public <C> void inject(Context context, @Nullable C carrier, TextMapSetter<C> textMapSetter) {
    Span s = Span.fromContext(context);
    if (s instanceof ReadableSpan) {
      String rule = ((ReadableSpan) s).getAttribute(AwsAttributeKeys.AWS_XRAY_SAMPLING_RULE);
      if (rule != null) {
        textMapSetter.set(carrier, AWS_XRAY_SAMPLING_RULE_CARRIER_KEY, rule);
      }
    }
  }

  // This happens in a new span downstream from a potential previous injection
  @Override
  public <C> Context extract(Context context, @Nullable C carrier, TextMapGetter<C> textMapGetter) {
    if (context == null) {
      return Context.root();
    }
    if (sampler instanceof AwsXrayRemoteSampler) {
      String traceId = Span.fromContext(context).getSpanContext().getTraceId();
      // TODO: majanjua@ - Don't hardcode this...
      // TODO: majanjua@ - Check null for xray sampling rule carrier key
      if (traceId != null && !traceId.equals("00000000000000000000000000000000")) {
        ((AwsXrayRemoteSampler) sampler)
            .saveTraceToMatchedSamplingRuleMapping(
                Span.fromContext(context).getSpanContext().getTraceId(),
                textMapGetter.get(carrier, AWS_XRAY_SAMPLING_RULE_CARRIER_KEY));
      } else {
        // TODO: majanjua@ - Choose a suitable log level/output message
      }
    }
    return context;
  }

  @Override
  public Collection<String> fields() {
    return FIELDS;
  }
}
