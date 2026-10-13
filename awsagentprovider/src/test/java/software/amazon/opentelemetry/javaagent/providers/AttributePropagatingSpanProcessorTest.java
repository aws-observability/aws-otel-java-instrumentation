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

import static io.opentelemetry.semconv.HttpAttributes.HTTP_REQUEST_METHOD;
import static io.opentelemetry.semconv.UrlAttributes.URL_PATH;
import static io.opentelemetry.semconv.incubating.HttpIncubatingAttributes.HTTP_METHOD;
import static io.opentelemetry.semconv.incubating.HttpIncubatingAttributes.HTTP_TARGET;
import static io.opentelemetry.semconv.incubating.MessagingIncubatingAttributes.MESSAGING_OPERATION;
import static io.opentelemetry.semconv.incubating.MessagingIncubatingAttributes.MessagingOperationTypeIncubatingValues.PROCESS;
import static io.opentelemetry.semconv.incubating.RpcIncubatingAttributes.RPC_SYSTEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;

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
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

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

  // OTEL_AWS_HTTP_OPERATION_PATHS: children of a SERVER span must inherit the overridden operation
  // (the one used for the server span's own service metrics), not the original span name.
  @Test
  public void testChildOfServerSpanInheritsOperationPathOverride() {
    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/smart/v1/getAppraisers", "/smart/v1"));
      Span serverSpan = startCoarseServerSpan("/smart/v1/getAppraisers");
      validateSpanAttributesInheritance(serverSpan, "POST /smart/v1/getAppraisers", null, null);
      ReadableSpan clientChild = startChild(serverSpan, SpanKind.CLIENT);
      assertThat(clientChild.getAttribute(spanNameKey)).isEqualTo("POST /smart/v1/getAppraisers");
    }
  }

  @Test
  public void testChildOfServerSpanUnchangedWhenOperationPathsUnset() {
    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic.when(AwsSpanProcessingUtil::getOperationPaths).thenReturn(List.of());
      Span serverSpan = startCoarseServerSpan("/smart/v1/getAppraisers");
      validateSpanAttributesInheritance(serverSpan, "POST /smart/v1/**", null, null);
      ReadableSpan clientChild = startChild(serverSpan, SpanKind.CLIENT);
      assertThat(clientChild.getAttribute(spanNameKey)).isEqualTo("POST /smart/v1/**");
    }
  }

  @Test
  public void testChildOfServerSpanUnchangedWhenNoOperationPathMatches() {
    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/other/getAppraisers"));
      Span serverSpan = startCoarseServerSpan("/smart/v1/getAppraisers");
      validateSpanAttributesInheritance(serverSpan, "POST /smart/v1/**", null, null);
      ReadableSpan clientChild = startChild(serverSpan, SpanKind.CLIENT);
      assertThat(clientChild.getAttribute(spanNameKey)).isEqualTo("POST /smart/v1/**");
    }
  }

  @Test
  public void testChildOfServerSpanInheritsWildcardOperationPathTemplate() {
    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/smart/v1/schdefaults/{inscompId}", "/smart/v1"));
      Span serverSpan = startCoarseServerSpan("/smart/v1/schdefaults/123");
      ReadableSpan clientChild = startChild(serverSpan, SpanKind.CLIENT);
      assertThat(clientChild.getAttribute(spanNameKey))
          .isEqualTo("POST /smart/v1/schdefaults/{inscompId}");
    }
  }

  @Test
  public void testChildOfServerSpanInheritsOperationPathOverrideFromHttpTarget() {
    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/smart/v1/getAppraisers"));
      // Old HTTP semantic conventions: http.method + http.target (with a query string).
      Span serverSpan =
          tracer
              .spanBuilder("POST /smart/v1/**")
              .setSpanKind(SpanKind.SERVER)
              .setAttribute(HTTP_METHOD, "POST")
              .setAttribute(HTTP_TARGET, "/smart/v1/getAppraisers?id=1")
              .startSpan();
      ReadableSpan clientChild = startChild(serverSpan, SpanKind.CLIENT);
      assertThat(clientChild.getAttribute(spanNameKey)).isEqualTo("POST /smart/v1/getAppraisers");
    }
  }

  @Test
  public void testChildrenOfDifferentServerSpansInheritTheirOwnOperationPathOverride() {
    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/smart/v1/getAppraisers", "/smart/v1/appraiser/details"));
      Span serverA = startCoarseServerSpan("/smart/v1/getAppraisers");
      Span serverB = startCoarseServerSpan("/smart/v1/appraiser/details");
      ReadableSpan childA = startChild(serverA, SpanKind.CLIENT);
      ReadableSpan childB = startChild(serverB, SpanKind.CLIENT);
      assertThat(childA.getAttribute(spanNameKey)).isEqualTo("POST /smart/v1/getAppraisers");
      assertThat(childB.getAttribute(spanNameKey)).isEqualTo("POST /smart/v1/appraiser/details");
    }
  }

  // The operation propagated to children must equal the operation the server span's own service
  // metrics use (the override applied at span end, then getIngressOperation).
  @Test
  public void testChildOperationMatchesServerSpanServiceOperation() {
    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/smart/v1/getAppraisers", "/smart/v1"));
      Span serverSpan = startCoarseServerSpan("/smart/v1/getAppraisers");
      ReadableSpan internalChild = startChild(serverSpan, SpanKind.INTERNAL);
      serverSpan.end();
      String serviceOperation =
          AwsSpanProcessingUtil.getIngressOperation(
              AwsSpanProcessingUtil.applyOperationPathSpanName(
                  ((ReadableSpan) serverSpan).toSpanData()));
      assertThat(internalChild.getAttribute(spanNameKey)).isEqualTo(serviceOperation);
    }
  }

  // An INTERNAL local root keeps InternalOperation even when a pattern matches its URL path.
  @Test
  public void testInternalLocalRootUnchangedByOperationPathOverride() {
    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/smart/v1/getAppraisers"));
      Span localRoot =
          tracer
              .spanBuilder("POST /smart/v1/**")
              .setSpanKind(SpanKind.INTERNAL)
              .setAttribute(HTTP_REQUEST_METHOD, "POST")
              .setAttribute(URL_PATH, "/smart/v1/getAppraisers")
              .startSpan();
      assertThat(((ReadableSpan) localRoot).getAttribute(spanNameKey))
          .isEqualTo("InternalOperation");
      validateSpanAttributesInheritance(localRoot, "InternalOperation", null, null);
    }
  }

  private Span startCoarseServerSpan(String urlPath) {
    return tracer
        .spanBuilder("POST /smart/v1/**")
        .setSpanKind(SpanKind.SERVER)
        .setAttribute(HTTP_REQUEST_METHOD, "POST")
        .setAttribute(URL_PATH, urlPath)
        .startSpan();
  }

  private ReadableSpan startChild(Span parent, SpanKind kind) {
    Span child =
        tracer
            .spanBuilder("child")
            .setSpanKind(kind)
            .setParent(Context.current().with(parent))
            .startSpan();
    child.end();
    return (ReadableSpan) child;
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
