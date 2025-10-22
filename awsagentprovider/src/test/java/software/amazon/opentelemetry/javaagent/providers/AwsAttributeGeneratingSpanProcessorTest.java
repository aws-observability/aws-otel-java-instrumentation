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

import static io.opentelemetry.semconv.incubating.MessagingIncubatingAttributes.MESSAGING_OPERATION;
import static io.opentelemetry.semconv.incubating.MessagingIncubatingAttributes.MessagingOperationTypeIncubatingValues.PROCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_SPAN_KIND;
import static software.amazon.opentelemetry.javaagent.providers.MetricAttributeGenerator.DEPENDENCY_METRIC;
import static software.amazon.opentelemetry.javaagent.providers.MetricAttributeGenerator.SERVICE_METRIC;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link AwsAttributeGeneratingSpanProcessor}. */
class AwsAttributeGeneratingSpanProcessorTest {

  private static final Resource testResource = Resource.empty();

  private Tracer tracer;
  private MetricAttributeGenerator generatorMock;

  @BeforeEach
  public void setup() {
    MockitoAnnotations.openMocks(this);
    generatorMock = mock(MetricAttributeGenerator.class);

    tracer =
        SdkTracerProvider.builder()
            .addSpanProcessor(
                AwsAttributeGeneratingSpanProcessor.create(generatorMock, testResource))
            .build()
            .get("awsxray");
  }

  @Test
  public void testOnEndingGeneratesBothWithoutOverride() {
    Span span = tracer.spanBuilder("test").setSpanKind(SpanKind.CLIENT).startSpan();

    // Mock attributes returned by generator
    Attributes metricAttributes =
        Attributes.of(
            AWS_LOCAL_SERVICE,
            "SERVICE",
            AWS_SPAN_KIND,
            SpanKind.CLIENT.name(),
            AttributeKey.stringKey("key"),
            "val");
    Map<String, Attributes> attributeMap = new HashMap<>();
    attributeMap.put(DEPENDENCY_METRIC, metricAttributes);
    when(generatorMock.generateMetricAttributeMapFromSpan(any(SpanData.class), any(Resource.class)))
        .thenReturn(attributeMap);

    // End span and verify it was modified appropriately
    span.end();
    ReadableSpan readableSpan = (ReadableSpan) span;
    assertThat(readableSpan.getAttribute(AWS_SPAN_KIND))
        .isEqualTo(AwsSpanProcessingUtil.LOCAL_ROOT);
    assertThat(readableSpan.getAttribute(AWS_LOCAL_SERVICE)).isEqualTo("SERVICE");
    assertThat(readableSpan.getAttributes().size()).isEqualTo(metricAttributes.size());
  }

  @Test
  public void testOnEndingGeneratesBothWithOverride() {
    Span span =
        tracer
            .spanBuilder("test")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("start_key", "start_val")
            .setAttribute("was_changed", false)
            .startSpan();

    // Mock attributes returned by generator
    Attributes metricAttributes =
        Attributes.builder()
            .put(AWS_LOCAL_SERVICE, "SERVICE")
            .put(AWS_SPAN_KIND, SpanKind.CLIENT.name())
            .put("ending_key", "ending_val")
            .put("was_changed", true)
            .build();
    Map<String, Attributes> attributeMap = new HashMap<>();
    attributeMap.put(DEPENDENCY_METRIC, metricAttributes);
    when(generatorMock.generateMetricAttributeMapFromSpan(any(SpanData.class), any(Resource.class)))
        .thenReturn(attributeMap);

    // End span and verify it was modified appropriately
    span.end();
    ReadableSpan readableSpan = (ReadableSpan) span;
    assertThat(readableSpan.getAttribute(AWS_SPAN_KIND))
        .isEqualTo(AwsSpanProcessingUtil.LOCAL_ROOT);
    assertThat(readableSpan.getAttribute(AWS_LOCAL_SERVICE)).isEqualTo("SERVICE");
    assertThat(readableSpan.getAttribute(AttributeKey.booleanKey("was_changed"))).isEqualTo(true);
    // 2 start attributes + 4 ending attributes - 1 overlap
    assertThat(readableSpan.getAttributes().size()).isEqualTo(5);
  }

  @Test
  public void testOnEndingGeneratesService() {
    Span span = tracer.spanBuilder("test").setSpanKind(SpanKind.SERVER).startSpan();

    // Mock attributes returned by generator
    Attributes metricAttributes =
        Attributes.of(AWS_LOCAL_SERVICE, "SERVICE", AWS_SPAN_KIND, SpanKind.SERVER.name());
    Map<String, Attributes> attributeMap = new HashMap<>();
    attributeMap.put(SERVICE_METRIC, metricAttributes);
    when(generatorMock.generateMetricAttributeMapFromSpan(any(SpanData.class), any(Resource.class)))
        .thenReturn(attributeMap);

    // End span and verify it was modified appropriately
    span.end();
    ReadableSpan readableSpan = (ReadableSpan) span;
    assertThat(readableSpan.getAttribute(AWS_LOCAL_SERVICE)).isEqualTo("SERVICE");
    assertThat(readableSpan.getAttribute(AWS_SPAN_KIND)).isEqualTo(SpanKind.SERVER.name());
    assertThat(readableSpan.getAttributes().size()).isEqualTo(2);
  }

  @Test
  public void testOnEndingGeneratesDependency() {
    Span span =
        tracer
            .spanBuilder("test")
            .setSpanKind(SpanKind.CONSUMER)
            .setParent(mock(Context.class))
            .startSpan();

    // Mock attributes returned by generator
    Attributes metricAttributes =
        Attributes.of(AWS_LOCAL_SERVICE, "SERVICE", AWS_SPAN_KIND, SpanKind.CONSUMER.name());
    Map<String, Attributes> attributeMap = new HashMap<>();
    attributeMap.put(DEPENDENCY_METRIC, metricAttributes);
    when(generatorMock.generateMetricAttributeMapFromSpan(any(SpanData.class), any(Resource.class)))
        .thenReturn(attributeMap);

    // Ensure span is treated as only having dependency metric information
    try (MockedStatic<AwsSpanProcessingUtil> mockUtil =
        Mockito.mockStatic(AwsSpanProcessingUtil.class)) {
      mockUtil
          .when(
              () ->
                  AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(any(SpanData.class)))
          .thenReturn(false);
      mockUtil
          .when(
              () ->
                  AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(
                      any(SpanData.class)))
          .thenReturn(true);

      // End span and verify it was modified appropriately
      span.end();
      ReadableSpan readableSpan = (ReadableSpan) span;
      assertThat(readableSpan.getAttribute(AWS_LOCAL_SERVICE)).isEqualTo("SERVICE");
      assertThat(readableSpan.getAttribute(AWS_SPAN_KIND)).isEqualTo(SpanKind.CONSUMER.name());
      assertThat(readableSpan.getAttributes().size()).isEqualTo(2);
    }
  }

  @Test
  public void testOnEndingGeneratesBothWithTwoMetrics() {
    Span span = tracer.spanBuilder("test").setSpanKind(SpanKind.CLIENT).startSpan();

    // Mock attributes returned by generator
    Map<String, Attributes> attributeMap = new HashMap<>();
    Attributes serviceMetricAttributes =
        Attributes.of(AttributeKey.stringKey("new service key"), "new service value");
    attributeMap.put(SERVICE_METRIC, serviceMetricAttributes);
    Attributes dependencyMetricAttributes =
        Attributes.of(
            AttributeKey.stringKey("new dependency key"),
            "new dependency value",
            AWS_SPAN_KIND,
            SpanKind.PRODUCER.name());
    attributeMap.put(DEPENDENCY_METRIC, dependencyMetricAttributes);
    when(generatorMock.generateMetricAttributeMapFromSpan(any(SpanData.class), any(Resource.class)))
        .thenReturn(attributeMap);

    // End span and verify it was modified appropriately
    span.end();
    ReadableSpan readableSpan = (ReadableSpan) span;
    assertThat(readableSpan.getAttribute(AWS_SPAN_KIND))
        .isEqualTo(AwsSpanProcessingUtil.LOCAL_ROOT);
    assertThat(readableSpan.getAttribute(AttributeKey.stringKey("new dependency key")))
        .isEqualTo("new dependency value");
    assertThat(readableSpan.getAttributes().size()).isEqualTo(dependencyMetricAttributes.size());
  }

  @Test
  public void testConsumerSpanHasEmptyAttributes() {
    AwsAttributeGeneratingSpanProcessor processor =
        AwsAttributeGeneratingSpanProcessorBuilder.create(Resource.create(Attributes.empty()))
            .build();

    Attributes attributesMock = mock(Attributes.class);
    SpanData spanDataMock = mock(SpanData.class);
    SpanContext parentSpanContextMock = mock(SpanContext.class);
    ReadWriteSpan readWriteSpanMock = mock(ReadWriteSpan.class);

    when(attributesMock.get(AwsAttributeKeys.AWS_CONSUMER_PARENT_SPAN_KIND))
        .thenReturn(SpanKind.CONSUMER.name());
    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn(PROCESS);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    when(spanDataMock.getAttributes()).thenReturn(attributesMock);
    when(spanDataMock.getParentSpanContext()).thenReturn(parentSpanContextMock);
    when(parentSpanContextMock.isValid()).thenReturn(true);
    when(parentSpanContextMock.isRemote()).thenReturn(false);
    when(readWriteSpanMock.toSpanData()).thenReturn(spanDataMock);

    // The dependencyAttributesMock will only be used if
    // AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(span) is true.
    // It shouldn't have any interaction since the spanData is a consumer process with parent span
    // of consumer
    Map<String, Attributes> attributeMap = new HashMap<>();
    Attributes dependencyAttributesMock = mock(Attributes.class);
    attributeMap.put(DEPENDENCY_METRIC, dependencyAttributesMock);
    // Configure generated attributes
    when(generatorMock.generateMetricAttributeMapFromSpan(eq(spanDataMock), eq(testResource)))
        .thenReturn(attributeMap);

    // End span and verify it was modified appropriately
    processor.onEnding(readWriteSpanMock);
    verify(readWriteSpanMock, times(0)).setAttribute(eq(AWS_SPAN_KIND), any());
    verify(readWriteSpanMock, times(0)).setAllAttributes(any());
  }
}
