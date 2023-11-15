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

import static io.opentelemetry.semconv.SemanticAttributes.MESSAGING_OPERATION;
import static io.opentelemetry.semconv.SemanticAttributes.MessagingOperationValues.PROCESS;
import static io.opentelemetry.semconv.SemanticAttributes.RPC_SYSTEM;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Arrays;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttributePropagatingSpanProcessorTest {

  private Tracer tracer;

  Function<SpanData, String> spanNameExtractor = AwsSpanProcessingUtil::getIngressOperation;
  AttributeKey<String> spanNameKey = AttributeKey.stringKey("spanName");
  AttributeKey<String> testKey1 = AttributeKey.stringKey("key1");
  AttributeKey<String> testKey2 = AttributeKey.stringKey("key2");

  @BeforeEach
  public void setup() {
    tracer =
        SdkTracerProvider.builder()
            .addSpanProcessor(
                AttributePropagatingSpanProcessor.create(
                    spanNameExtractor, spanNameKey, Arrays.asList(testKey1, testKey2)))
            .build()
            .get("awsxray");
  }

  @Test
  public void testAttributesPropagationBySpanKind() {
    for (SpanKind value : SpanKind.values()) {
      Span spanWithAppOnly =
          tracer
              .spanBuilder("parent")
              .setSpanKind(value)
              .setAttribute(testKey1, "testValue1")
              .startSpan();

      Span spanWithOpOnly =
          tracer
              .spanBuilder("parent")
              .setSpanKind(value)
              .setAttribute(testKey2, "testValue2")
              .startSpan();

      Span spanWithAppAndOp =
          tracer
              .spanBuilder("parent")
              .setSpanKind(value)
              .setAttribute(testKey1, "testValue1")
              .setAttribute(testKey2, "testValue2")
              .startSpan();

      if (SpanKind.SERVER.equals(value)) {
        validateSpanAttributesInheritance(spanWithAppOnly, "parent", null, null);
        validateSpanAttributesInheritance(spanWithOpOnly, "parent", null, null);
        validateSpanAttributesInheritance(spanWithAppAndOp, "parent", null, null);
      } else if (SpanKind.INTERNAL.equals(value)) {
        validateSpanAttributesInheritance(spanWithAppOnly, "InternalOperation", "testValue1", null);
        validateSpanAttributesInheritance(spanWithOpOnly, "InternalOperation", null, "testValue2");
        validateSpanAttributesInheritance(
            spanWithAppAndOp, "InternalOperation", "testValue1", "testValue2");
      } else {
        validateSpanAttributesInheritance(spanWithOpOnly, "InternalOperation", null, null);
        validateSpanAttributesInheritance(spanWithAppOnly, "InternalOperation", null, null);
        validateSpanAttributesInheritance(spanWithAppAndOp, "InternalOperation", null, null);
      }
    }
  }

  @Test
  public void testAttributesPropagationWithInternalKinds() {
    Span grandParentSpan =
        tracer
            .spanBuilder("grandparent")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(testKey1, "testValue1")
            .startSpan();

    Span parentSpan =
        tracer
            .spanBuilder("parent")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(testKey2, "testValue2")
            .setParent(Context.current().with(grandParentSpan))
            .startSpan();

    Span childSpan =
        tracer
            .spanBuilder("child")
            .setSpanKind(SpanKind.CLIENT)
            .setParent(Context.current().with(parentSpan))
            .startSpan();

    Span grandchildSpan =
        tracer
            .spanBuilder("child")
            .setSpanKind(SpanKind.INTERNAL)
            .setParent(Context.current().with(childSpan))
            .startSpan();

    ReadableSpan grandParentReadableSpan = (ReadableSpan) grandParentSpan;
    ReadableSpan parentReadableSpan = (ReadableSpan) parentSpan;
    ReadableSpan childReadableSpan = (ReadableSpan) childSpan;
    ReadableSpan grandchildReadableSpan = (ReadableSpan) grandchildSpan;

    assertThat(grandParentReadableSpan.getAttribute(testKey1)).isEqualTo("testValue1");
    assertThat(grandParentReadableSpan.getAttribute(testKey2)).isNull();
    assertThat(parentReadableSpan.getAttribute(testKey1)).isEqualTo("testValue1");
    assertThat(parentReadableSpan.getAttribute(testKey2)).isEqualTo("testValue2");
    assertThat(childReadableSpan.getAttribute(testKey1)).isEqualTo("testValue1");
    assertThat(childReadableSpan.getAttribute(testKey2)).isEqualTo("testValue2");
    assertThat(grandchildReadableSpan.getAttribute(testKey1)).isNull();
    assertThat(grandchildReadableSpan.getAttribute(testKey2)).isNull();
  }

  @Test
  public void testOverrideAttributes() {
    Span parentSpan = tracer.spanBuilder("parent").setSpanKind(SpanKind.SERVER).startSpan();
    parentSpan.setAttribute(testKey1, "testValue1");
    parentSpan.setAttribute(testKey2, "testValue2");

    Span transmitSpans1 = createNestedSpan(parentSpan, 2);

    Span childSpan =
        tracer.spanBuilder("child:1").setParent(Context.current().with(transmitSpans1)).startSpan();

    childSpan.setAttribute(testKey2, "testValue3");

    Span transmitSpans2 = createNestedSpan(childSpan, 2);

    assertThat(((ReadableSpan) transmitSpans2).getAttribute(testKey2)).isEqualTo("testValue3");
  }

  @Test
  public void testSpanNamePropagationBySpanKind() {
    for (SpanKind value : SpanKind.values()) {
      Span span = tracer.spanBuilder("parent").setSpanKind(value).startSpan();
      if (value == SpanKind.SERVER) {
        validateSpanAttributesInheritance(span, "parent", null, null);
      } else {
        validateSpanAttributesInheritance(span, "InternalOperation", null, null);
      }
    }
  }

  @Test
  public void testSpanNamePropagationWithRemoteParentSpan() {
    Span remoteParent =
        Span.wrap(
            SpanContext.createFromRemoteParent(
                "00000000000000000000000000000001",
                "0000000000000002",
                TraceFlags.getSampled(),
                TraceState.getDefault()));
    Context parentcontext = Context.root().with(remoteParent);
    Span span =
        tracer
            .spanBuilder("parent")
            .setSpanKind(SpanKind.SERVER)
            .setParent(parentcontext)
            .startSpan();
    validateSpanAttributesInheritance(span, "parent", null, null);
  }

  @Test
  public void testAwsSdkDescendantSpan() {
    Span awsSdkSpan = tracer.spanBuilder("parent").setSpanKind(SpanKind.CLIENT).startSpan();
    awsSdkSpan.setAttribute(RPC_SYSTEM, "aws-api");
    assertThat(((ReadableSpan) awsSdkSpan).getAttribute(AwsAttributeKeys.AWS_SDK_DESCENDANT))
        .isNull();

    ReadableSpan childSpan = (ReadableSpan) createNestedSpan(awsSdkSpan, 1);
    assertThat(childSpan.getAttribute(AwsAttributeKeys.AWS_SDK_DESCENDANT)).isNotNull();
    assertThat(childSpan.getAttribute(AwsAttributeKeys.AWS_SDK_DESCENDANT)).isEqualTo("true");
  }

  @Test
  public void testConsumerParentSpanKindAttributePropagation() {
    Span grandParentSpan =
        tracer.spanBuilder("grandparent").setSpanKind(SpanKind.CONSUMER).startSpan();
    Span parentSpan =
        tracer
            .spanBuilder("parent")
            .setSpanKind(SpanKind.INTERNAL)
            .setParent(Context.current().with(grandParentSpan))
            .startSpan();

    Span childSpan =
        tracer
            .spanBuilder("child")
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute(MESSAGING_OPERATION, PROCESS)
            .setParent(Context.current().with(parentSpan))
            .startSpan();
    assertThat(
            ((ReadableSpan) parentSpan)
                .getAttribute(AwsAttributeKeys.AWS_CONSUMER_PARENT_SPAN_KIND))
        .isNull();
    assertThat(
            ((ReadableSpan) childSpan).getAttribute(AwsAttributeKeys.AWS_CONSUMER_PARENT_SPAN_KIND))
        .isNull();
  }

  @Test
  public void testNoConsumerParentSpanKindAttributeWithConsumerProcess() {
    Span parentSpan = tracer.spanBuilder("parent").setSpanKind(SpanKind.SERVER).startSpan();

    Span span =
        tracer
            .spanBuilder("parent")
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute(MESSAGING_OPERATION, PROCESS)
            .setParent(Context.current().with(parentSpan))
            .startSpan();
    assertThat(((ReadableSpan) span).getAttribute(AwsAttributeKeys.AWS_CONSUMER_PARENT_SPAN_KIND))
        .isNull();
  }

  @Test
  public void testConsumerParentSpanKindAttributeWithConsumerParent() {
    Span parentSpan = tracer.spanBuilder("parent").setSpanKind(SpanKind.CONSUMER).startSpan();

    Span span =
        tracer
            .spanBuilder("parent")
            .setSpanKind(SpanKind.CONSUMER)
            .setParent(Context.current().with(parentSpan))
            .startSpan();
    assertThat(((ReadableSpan) span).getAttribute(AwsAttributeKeys.AWS_CONSUMER_PARENT_SPAN_KIND))
        .isEqualTo(SpanKind.CONSUMER.name());
  }

  private Span createNestedSpan(Span parentSpan, int depth) {
    if (depth == 0) {
      return parentSpan;
    }
    Span childSpan =
        tracer
            .spanBuilder("child:" + depth)
            .setParent(Context.current().with(parentSpan))
            .startSpan();
    try {
      return createNestedSpan(childSpan, depth - 1);
    } finally {
      childSpan.end();
    }
  }

  private void validateSpanAttributesInheritance(
      Span parentSpan, String propagatedName, String propagationValue1, String propagatedValue2) {
    ReadableSpan leafSpan = (ReadableSpan) createNestedSpan(parentSpan, 10);

    assertThat(leafSpan.getParentSpanContext()).isNotNull();
    assertThat(leafSpan.getName()).isEqualTo("child:1");
    if (propagatedName != null) {
      assertThat(leafSpan.getAttribute(spanNameKey)).isEqualTo(propagatedName);
    } else {
      assertThat(leafSpan.getAttribute(spanNameKey)).isNull();
    }
    if (propagationValue1 != null) {
      assertThat(leafSpan.getAttribute(testKey1)).isEqualTo(propagationValue1);
    } else {
      assertThat(leafSpan.getAttribute(testKey1)).isNull();
    }
    if (propagatedValue2 != null) {
      assertThat(leafSpan.getAttribute(testKey2)).isEqualTo(propagatedValue2);
    } else {
      assertThat(leafSpan.getAttribute(testKey2)).isNull();
    }
  }
}
