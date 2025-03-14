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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AwsLambdaSpanProcessorTest {

  private AwsLambdaSpanProcessor processor;
  private ReadWriteSpan mockLambdaServerSpan;
  private SpanData mockLambdaSpanData;
  private InstrumentationScopeInfo mockLambdaScopeInfo;
  private Map<AttributeKey<?>, Object> attributeMapForLambdaSpan;
  private SpanContext mockSpanContext;

  private ReadWriteSpan mockServletServerSpan;
  private SpanData mockServletSpanData;
  private InstrumentationScopeInfo mockServletScopeInfo;

  private Tracer lambdaTracer;
  private Tracer servletTracer;
  private Tracer otherTracer;

  @BeforeEach
  public void setup() {
    processor = new AwsLambdaSpanProcessor();
    lambdaTracer =
        SdkTracerProvider.builder()
            .addSpanProcessor(processor)
            .build()
            .get(AwsSpanProcessingUtil.LAMBDA_SCOPE_PREFIX + "core-1.0");

    servletTracer =
        SdkTracerProvider.builder()
            .addSpanProcessor(processor)
            .build()
            .get(AwsSpanProcessingUtil.SERVLET_SCOPE_PREFIX + "lib-3.0");

    otherTracer =
        SdkTracerProvider.builder().addSpanProcessor(processor).build().get("other-lib-2.0");
  }

  @Test
  void testOnStart_servletServerSpan_withLambdaServerSpan() {
    Span parentSpan =
        lambdaTracer.spanBuilder("parent-lambda").setSpanKind(SpanKind.SERVER).startSpan();
    servletTracer
        .spanBuilder("child-servlet")
        .setSpanKind(SpanKind.SERVER)
        .setParent(Context.current().with(parentSpan))
        .startSpan();

    ReadableSpan parentReadableSpan = (ReadableSpan) parentSpan;
    assertThat(parentReadableSpan.getAttribute(AwsAttributeKeys.AWS_TRACE_LAMBDA_MULTIPLE_SERVER))
        .isEqualTo(true);
  }

  @Test
  void testOnStart_servletInternalSpan_withLambdaServerSpan() {
    Span parentSpan =
        lambdaTracer.spanBuilder("parent-lambda").setSpanKind(SpanKind.SERVER).startSpan();

    servletTracer
        .spanBuilder("child-servlet")
        .setSpanKind(SpanKind.INTERNAL)
        .setParent(Context.current().with(parentSpan))
        .startSpan();

    ReadableSpan parentReadableSpan = (ReadableSpan) parentSpan;
    assertNull(parentReadableSpan.getAttribute(AwsAttributeKeys.AWS_TRACE_LAMBDA_MULTIPLE_SERVER));
  }

  @Test
  void testOnStart_servletServerSpan_withLambdaInternalSpan() {
    Span parentSpan =
        lambdaTracer.spanBuilder("parent-lambda").setSpanKind(SpanKind.INTERNAL).startSpan();

    servletTracer
        .spanBuilder("child-servlet")
        .setSpanKind(SpanKind.SERVER)
        .setParent(Context.current().with(parentSpan))
        .startSpan();

    ReadableSpan parentReadableSpan = (ReadableSpan) parentSpan;
    assertNull(parentReadableSpan.getAttribute(AwsAttributeKeys.AWS_TRACE_LAMBDA_MULTIPLE_SERVER));
  }

  @Test
  void testOnStart_servletServerSpan_withLambdaServerSpanAsGrandParent() {
    Span grandParentSpan =
        lambdaTracer.spanBuilder("grandparent-lambda").setSpanKind(SpanKind.SERVER).startSpan();

    Span parentSpan =
        otherTracer
            .spanBuilder("parent-other")
            .setSpanKind(SpanKind.SERVER)
            .setParent(Context.current().with(grandParentSpan))
            .startSpan();

    servletTracer
        .spanBuilder("child-servlet")
        .setSpanKind(SpanKind.SERVER)
        .setParent(Context.current().with(parentSpan))
        .startSpan();

    ReadableSpan grandParentReadableSpan = (ReadableSpan) grandParentSpan;
    assertNull(
        grandParentReadableSpan.getAttribute(AwsAttributeKeys.AWS_TRACE_LAMBDA_MULTIPLE_SERVER));
  }
}
