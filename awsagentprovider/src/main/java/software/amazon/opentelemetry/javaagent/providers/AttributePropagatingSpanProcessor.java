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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.function.Function;
import javax.annotation.concurrent.Immutable;

/**
 * AttributePropagatingSpanProcessor handles the propagation of attributes from parent spans to
 * child spans, specified in {@link #attributesKeysToPropagate}. AttributePropagatingSpanProcessor
 * also propagates configurable data from parent spans to child spans, as a new attribute specified
 * by {@link #propagationDataKey}. Propagated data can be configured via the {@link
 * #propagationDataExtractor}. Span data propagation only starts from local root server/consumer
 * spans, but from there will be propagated to any descendant spans. If the span is a CONSUMER
 * PROCESS with the parent also a CONSUMER, it will set attribute AWS_CONSUMER_PARENT_SPAN_KIND as
 * CONSUMER to indicate that dependency metrics should not be generated for this span.
 */
@Immutable
public final class AttributePropagatingSpanProcessor implements SpanProcessor {

  private final Function<SpanData, String> propagationDataExtractor;
  private final AttributeKey<String> propagationDataKey;
  private final List<AttributeKey<String>> attributesKeysToPropagate;

  public static AttributePropagatingSpanProcessor create(
      Function<SpanData, String> propagationDataExtractor,
      AttributeKey<String> propagationDataKey,
      List<AttributeKey<String>> attributesKeysToPropagate) {
    return new AttributePropagatingSpanProcessor(
        propagationDataExtractor, propagationDataKey, attributesKeysToPropagate);
  }

  private AttributePropagatingSpanProcessor(
      Function<SpanData, String> propagationDataExtractor,
      AttributeKey<String> propagationDataKey,
      List<AttributeKey<String>> attributesKeysToPropagate) {
    this.propagationDataExtractor = propagationDataExtractor;
    this.propagationDataKey = propagationDataKey;
    this.attributesKeysToPropagate = attributesKeysToPropagate;
  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    Span parentSpan = Span.fromContextOrNull(parentContext);

    ReadableSpan parentReadableSpan = null;
    if ((parentSpan instanceof ReadableSpan)) {
      parentReadableSpan = (ReadableSpan) parentSpan;

      // Add the AWS_SDK_DESCENDANT attribute to the immediate child spans of AWS SDK span.
      // This attribute helps the backend differentiate between SDK spans and their immediate
      // children.
      // It's assumed that the HTTP spans are immediate children of the AWS SDK span
      // TODO: we should have a contract test to check the immediate children are HTTP span
      if (AwsSpanProcessingUtil.isAwsSDKSpan(parentReadableSpan.toSpanData())) {
        span.setAttribute(AwsAttributeKeys.AWS_SDK_DESCENDANT, "true");
      }

      if (SpanKind.INTERNAL.equals(parentReadableSpan.getKind())) {
        for (AttributeKey<String> keyToPropagate : attributesKeysToPropagate) {
          String valueToPropagate = parentReadableSpan.getAttribute(keyToPropagate);
          if (valueToPropagate != null) {
            span.setAttribute(keyToPropagate, valueToPropagate);
          }
        }
      }

      // We cannot guarantee that messaging.operation is set onStart, it could be set after the
      // fact. To work around this, add the AWS_CONSUMER_PARENT_SPAN_KIND attribute if parent and
      // child are both CONSUMER
      // then check later if a metric should be generated.
      if (isConsumerKind(span) && isConsumerKind(parentReadableSpan)) {
        span.setAttribute(
            AwsAttributeKeys.AWS_CONSUMER_PARENT_SPAN_KIND, parentReadableSpan.getKind().name());
      }
    }

    String propagationData = null;
    SpanData spanData = span.toSpanData();
    if (AwsSpanProcessingUtil.isLocalRoot(spanData)) {
      if (!isServerKind(spanData)) {
        propagationData = propagationDataExtractor.apply(spanData);
      }
    } else if (isServerKind(parentReadableSpan.toSpanData())) {
      propagationData = propagationDataExtractor.apply(parentReadableSpan.toSpanData());
    } else {
      propagationData = parentReadableSpan.getAttribute(propagationDataKey);
    }

    if (propagationData != null) {
      span.setAttribute(propagationDataKey, propagationData);
    }
  }

  private boolean isConsumerKind(ReadableSpan span) {
    return SpanKind.CONSUMER.equals(span.getKind());
  }

  private static boolean isServerKind(SpanData span) {
    return SpanKind.SERVER.equals(span.getKind());
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {}

  @Override
  public boolean isEndRequired() {
    return false;
  }
}
