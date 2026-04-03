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

import static io.opentelemetry.semconv.HttpAttributes.HTTP_RESPONSE_STATUS_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.MetricAttributeGenerator.DEPENDENCY_METRIC;
import static software.amazon.opentelemetry.javaagent.providers.MetricAttributeGenerator.SERVICE_METRIC;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.contrib.awsxray.AwsXrayRemoteSampler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.ExceptionEventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AwsSpanMetricsProcessor}. */
class AwsSpanMetricsProcessorTest {
  // Test constants
  private static final boolean CONTAINS_ATTRIBUTES = true;
  private static final boolean CONTAINS_NO_ATTRIBUTES = false;
  private static final double TEST_LATENCY_MILLIS = 150.0;
  private static final long TEST_LATENCY_NANOS = 150_000_000L;

  // Resource is not mockable, but tests can safely rely on an empty resource.
  private static final Resource testResource = Resource.empty();

  // Useful enum for indicating expected HTTP status code-related metrics
  private enum ExpectedStatusMetric {
    ERROR,
    FAULT,
    NEITHER
  }

  // Mocks required for tests.
  private LongHistogram errorHistogramMock;
  private LongHistogram faultHistogramMock;
  private DoubleHistogram latencyHistogramMock;
  private LongHistogram customErrorHistogramMock;
  private LongHistogram customFaultHistogramMock;
  private DoubleHistogram customLatencyHistogramMock;
  private MetricAttributeGenerator generatorMock;
  private AwsXrayRemoteSampler samplerMock;
  private AwsSpanMetricsProcessor awsSpanMetricsProcessor;

  // Mock forceFlush function that returns success when invoked similar
  // to the default implementation of forceFlush.
  private CompletableResultCode forceFlushAction() {
    return CompletableResultCode.ofSuccess();
  }

  @BeforeEach
  public void setUpMocks() {
    errorHistogramMock = mock(LongHistogram.class);
    faultHistogramMock = mock(LongHistogram.class);
    latencyHistogramMock = mock(DoubleHistogram.class);
    customErrorHistogramMock = mock(LongHistogram.class);
    customFaultHistogramMock = mock(LongHistogram.class);
    customLatencyHistogramMock = mock(DoubleHistogram.class);
    generatorMock = mock(MetricAttributeGenerator.class);
    samplerMock = mock(AwsXrayRemoteSampler.class);

    awsSpanMetricsProcessor =
        AwsSpanMetricsProcessor.create(
            errorHistogramMock,
            faultHistogramMock,
            latencyHistogramMock,
            customErrorHistogramMock,
            customFaultHistogramMock,
            customLatencyHistogramMock,
            generatorMock,
            testResource,
            samplerMock,
            this::forceFlushAction);
  }

  @Test
  public void testIsRequired() {
    assertThat(awsSpanMetricsProcessor.isStartRequired()).isFalse();
    assertThat(awsSpanMetricsProcessor.isEndRequired()).isTrue();
  }

  @Test
  public void testStartDoesNothingToSpan() {
    Context parentContextMock = mock(Context.class);
    ReadWriteSpan spanMock = mock(ReadWriteSpan.class);
    awsSpanMetricsProcessor.onStart(parentContextMock, spanMock);
    verifyNoInteractions(parentContextMock, spanMock);
  }

  @Test
  public void testTearDown() {
    assertThat(awsSpanMetricsProcessor.shutdown()).isEqualTo(CompletableResultCode.ofSuccess());
    assertThat(awsSpanMetricsProcessor.forceFlush()).isEqualTo(CompletableResultCode.ofSuccess());

    // Not really much to test, just check that it doesn't cause issues/throw anything.
    awsSpanMetricsProcessor.close();
  }

  /**
   * Tests starting with testOnEndMetricsGeneration are testing the logic in
   * AwsSpanMetricsProcessor's onEnd method pertaining to metrics generation.
   */
  @Test
  public void testOnEndMetricsGenerationWithoutSpanAttributes() {
    Attributes spanAttributes = buildSpanAttributes(CONTAINS_NO_ATTRIBUTES);
    ReadableSpan readableSpanMock = buildReadableSpanMock(spanAttributes);
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verifyHistogramRecords(metricAttributesMap, 1, 0);
  }

  @Test
  public void testOnEndMetricsGenerationWithoutMetricAttributes() {
    Attributes spanAttributes = Attributes.of(HTTP_RESPONSE_STATUS_CODE, 500L);
    ReadableSpan readableSpanMock = buildReadableSpanMock(spanAttributes);
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_NO_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verifyNoInteractions(errorHistogramMock);
    verifyNoInteractions(faultHistogramMock);
    verifyNoInteractions(latencyHistogramMock);
  }

  @Test
  public void testsOnEndMetricsGenerationLocalRootServerSpan() {
    Attributes spanAttributes = buildSpanAttributes(CONTAINS_NO_ATTRIBUTES);
    ReadableSpan readableSpanMock =
        buildReadableSpanMock(
            spanAttributes, SpanKind.SERVER, SpanContext.getInvalid(), StatusData.unset());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verifyHistogramRecords(metricAttributesMap, 1, 0);
  }

  @Test
  public void testsOnEndMetricsGenerationLocalRootConsumerSpan() {
    Attributes spanAttributes = buildSpanAttributes(CONTAINS_NO_ATTRIBUTES);
    ReadableSpan readableSpanMock =
        buildReadableSpanMock(
            spanAttributes, SpanKind.CONSUMER, SpanContext.getInvalid(), StatusData.unset());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verifyHistogramRecords(metricAttributesMap, 1, 1);
  }

  @Test
  public void testsOnEndMetricsGenerationLocalRootClientSpan() {
    Attributes spanAttributes = buildSpanAttributes(CONTAINS_NO_ATTRIBUTES);
    ReadableSpan readableSpanMock =
        buildReadableSpanMock(
            spanAttributes, SpanKind.CLIENT, SpanContext.getInvalid(), StatusData.unset());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verifyHistogramRecords(metricAttributesMap, 1, 1);
  }

  @Test
  public void testsOnEndMetricsGenerationLocalRootProducerSpan() {
    Attributes spanAttributes = buildSpanAttributes(CONTAINS_NO_ATTRIBUTES);
    ReadableSpan readableSpanMock =
        buildReadableSpanMock(
            spanAttributes, SpanKind.PRODUCER, SpanContext.getInvalid(), StatusData.unset());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verifyHistogramRecords(metricAttributesMap, 1, 1);
  }

  @Test
  public void testsOnEndMetricsGenerationLocalRootInternalSpan() {
    Attributes spanAttributes = buildSpanAttributes(CONTAINS_NO_ATTRIBUTES);
    ReadableSpan readableSpanMock =
        buildReadableSpanMock(
            spanAttributes, SpanKind.INTERNAL, SpanContext.getInvalid(), StatusData.unset());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verifyHistogramRecords(metricAttributesMap, 1, 0);
  }

  @Test
  public void testsOnEndMetricsGenerationLocalRootProducerSpanWithoutMetricAttributes() {
    Attributes spanAttributes = buildSpanAttributes(CONTAINS_NO_ATTRIBUTES);
    ReadableSpan readableSpanMock =
        buildReadableSpanMock(
            spanAttributes, SpanKind.PRODUCER, SpanContext.getInvalid(), StatusData.unset());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_NO_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);
    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verifyNoInteractions(errorHistogramMock);
    verifyNoInteractions(faultHistogramMock);
    verifyNoInteractions(latencyHistogramMock);
  }

  @Test
  public void testsOnEndMetricsGenerationClientSpan() {
    SpanContext mockSpanContext = mock(SpanContext.class);
    when(mockSpanContext.isValid()).thenReturn(true);
    when(mockSpanContext.isRemote()).thenReturn(false);
    Attributes spanAttributes = buildSpanAttributes(CONTAINS_NO_ATTRIBUTES);
    ReadableSpan readableSpanMock =
        buildReadableSpanMock(spanAttributes, SpanKind.CLIENT, mockSpanContext, StatusData.unset());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verifyHistogramRecords(metricAttributesMap, 0, 1);
  }

  @Test
  public void testsOnEndMetricsGenerationProducerSpan() {
    SpanContext mockSpanContext = mock(SpanContext.class);
    when(mockSpanContext.isValid()).thenReturn(true);
    when(mockSpanContext.isRemote()).thenReturn(false);
    Attributes spanAttributes = buildSpanAttributes(CONTAINS_NO_ATTRIBUTES);
    ReadableSpan readableSpanMock =
        buildReadableSpanMock(
            spanAttributes, SpanKind.PRODUCER, mockSpanContext, StatusData.unset());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verifyHistogramRecords(metricAttributesMap, 0, 1);
  }

  @Test
  public void testOnEndMetricsGenerationWithoutEndRequired() {
    Attributes spanAttributes = Attributes.of(HTTP_RESPONSE_STATUS_CODE, 500L);
    ReadableSpan readableSpanMock = buildReadableSpanMock(spanAttributes);
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verify(errorHistogramMock, times(1))
        .record(eq(0L), eq(metricAttributesMap.get(SERVICE_METRIC)));
    verify(faultHistogramMock, times(1))
        .record(eq(1L), eq(metricAttributesMap.get(SERVICE_METRIC)));
    verify(latencyHistogramMock, times(1))
        .record(eq(TEST_LATENCY_MILLIS), eq(metricAttributesMap.get(SERVICE_METRIC)));
    verify(errorHistogramMock, times(0))
        .record(eq(0L), eq(metricAttributesMap.get(DEPENDENCY_METRIC)));
    verify(faultHistogramMock, times(0))
        .record(eq(0L), eq(metricAttributesMap.get(DEPENDENCY_METRIC)));
    verify(latencyHistogramMock, times(0))
        .record(eq(TEST_LATENCY_MILLIS), eq(metricAttributesMap.get(DEPENDENCY_METRIC)));
  }

  @Test
  public void testOnEndMetricsGenerationWithLatency() {
    Attributes spanAttributes = Attributes.of(HTTP_RESPONSE_STATUS_CODE, 200L);
    ReadableSpan readableSpanMock = buildReadableSpanMock(spanAttributes);
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    when(readableSpanMock.getLatencyNanos()).thenReturn(5_500_000L);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verify(errorHistogramMock, times(1))
        .record(eq(0L), eq(metricAttributesMap.get(SERVICE_METRIC)));
    verify(faultHistogramMock, times(1))
        .record(eq(0L), eq(metricAttributesMap.get(SERVICE_METRIC)));
    verify(latencyHistogramMock, times(1))
        .record(eq(5.5), eq(metricAttributesMap.get(SERVICE_METRIC)));
    verify(errorHistogramMock, times(0))
        .record(eq(0L), eq(metricAttributesMap.get(DEPENDENCY_METRIC)));
    verify(faultHistogramMock, times(0))
        .record(eq(0L), eq(metricAttributesMap.get(DEPENDENCY_METRIC)));
    verify(latencyHistogramMock, times(0))
        .record(eq(5.5), eq(metricAttributesMap.get(DEPENDENCY_METRIC)));
  }

  @Test
  public void testOnEndMetricsGenerationWithAwsStatusCodes() {
    // Invalid HTTP status codes
    validateMetricsGeneratedForAttributeStatusCode(null, ExpectedStatusMetric.NEITHER);

    // Valid HTTP status codes
    validateMetricsGeneratedForAttributeStatusCode(399L, ExpectedStatusMetric.NEITHER);
    validateMetricsGeneratedForAttributeStatusCode(400L, ExpectedStatusMetric.ERROR);
    validateMetricsGeneratedForAttributeStatusCode(499L, ExpectedStatusMetric.ERROR);
    validateMetricsGeneratedForAttributeStatusCode(500L, ExpectedStatusMetric.FAULT);
    validateMetricsGeneratedForAttributeStatusCode(599L, ExpectedStatusMetric.FAULT);
    validateMetricsGeneratedForAttributeStatusCode(600L, ExpectedStatusMetric.NEITHER);
  }

  @Test
  public void testOnEndMetricsGenerationWithStatusCodes() {
    // Invalid HTTP status codes
    validateMetricsGeneratedForHttpStatusCode(null, ExpectedStatusMetric.NEITHER);

    // Valid HTTP status codes
    validateMetricsGeneratedForHttpStatusCode(200L, ExpectedStatusMetric.NEITHER);
    validateMetricsGeneratedForHttpStatusCode(399L, ExpectedStatusMetric.NEITHER);
    validateMetricsGeneratedForHttpStatusCode(400L, ExpectedStatusMetric.ERROR);
    validateMetricsGeneratedForHttpStatusCode(499L, ExpectedStatusMetric.ERROR);
    validateMetricsGeneratedForHttpStatusCode(500L, ExpectedStatusMetric.FAULT);
    validateMetricsGeneratedForHttpStatusCode(599L, ExpectedStatusMetric.FAULT);
    validateMetricsGeneratedForHttpStatusCode(600L, ExpectedStatusMetric.NEITHER);
  }

  @Test
  public void testOnEndMetricsGenerationWithStatusDataError() {
    // Empty Status and HTTP with Error Status
    validateMetricsGeneratedForStatusDataError(null, ExpectedStatusMetric.FAULT);

    // Valid HTTP with Error Status
    validateMetricsGeneratedForStatusDataError(200L, ExpectedStatusMetric.FAULT);
    validateMetricsGeneratedForStatusDataError(399L, ExpectedStatusMetric.FAULT);
    validateMetricsGeneratedForStatusDataError(400L, ExpectedStatusMetric.ERROR);
    validateMetricsGeneratedForStatusDataError(499L, ExpectedStatusMetric.ERROR);
    validateMetricsGeneratedForStatusDataError(500L, ExpectedStatusMetric.FAULT);
    validateMetricsGeneratedForStatusDataError(599L, ExpectedStatusMetric.FAULT);
    validateMetricsGeneratedForStatusDataError(600L, ExpectedStatusMetric.FAULT);
  }

  @Test
  public void testOnEndMetricsGenerationWithStatusDataOk() {
    // Empty Status and HTTP with Ok Status
    validateMetricsGeneratedForStatusDataOk(null, ExpectedStatusMetric.NEITHER);

    // Valid HTTP with Ok Status
    validateMetricsGeneratedForStatusDataOk(200L, ExpectedStatusMetric.NEITHER);
    validateMetricsGeneratedForStatusDataOk(399L, ExpectedStatusMetric.NEITHER);
    validateMetricsGeneratedForStatusDataOk(400L, ExpectedStatusMetric.ERROR);
    validateMetricsGeneratedForStatusDataOk(499L, ExpectedStatusMetric.ERROR);
    validateMetricsGeneratedForStatusDataOk(500L, ExpectedStatusMetric.FAULT);
    validateMetricsGeneratedForStatusDataOk(599L, ExpectedStatusMetric.FAULT);
    validateMetricsGeneratedForStatusDataOk(600L, ExpectedStatusMetric.NEITHER);
  }

  @Test
  public void testOnEndMetricsGenerationFromEc2MetadataApi() {
    Attributes spanAttributes = Attributes.of(AWS_REMOTE_SERVICE, "169.254.169.254");
    ReadableSpan readableSpanMock =
        buildReadableSpanMock(
            spanAttributes, SpanKind.CLIENT, SpanContext.getInvalid(), StatusData.unset());
    Map<String, Attributes> metricAttributesMap = buildEc2MetadataApiMetricAttributes();
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    verifyNoInteractions(errorHistogramMock);
    verifyNoInteractions(faultHistogramMock);
    verifyNoInteractions(latencyHistogramMock);
  }

  private static Attributes buildSpanAttributes(boolean containsAttribute) {
    if (containsAttribute) {
      return Attributes.of(AttributeKey.stringKey("original key"), "original value");
    } else {
      return Attributes.empty();
    }
  }

  private static Map<String, Attributes> buildMetricAttributes(
      boolean containsAttribute, SpanData span) {
    Map<String, Attributes> attributesMap = new HashMap<>();
    if (containsAttribute) {
      Attributes attributes;
      if (AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(span)) {
        attributes = Attributes.of(AttributeKey.stringKey("new service key"), "new service value");
        attributesMap.put(MetricAttributeGenerator.SERVICE_METRIC, attributes);
      }
      if (AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(span)) {
        attributes =
            Attributes.of(AttributeKey.stringKey("new dependency key"), "new dependency value");
        attributesMap.put(MetricAttributeGenerator.DEPENDENCY_METRIC, attributes);
      }
    }
    return attributesMap;
  }

  private static Map<String, Attributes> buildEc2MetadataApiMetricAttributes() {
    Map<String, Attributes> attributesMap = new HashMap<>();
    Attributes attributes =
        Attributes.of(AttributeKey.stringKey(AWS_REMOTE_SERVICE.toString()), "169.254.169.254");
    attributesMap.put(MetricAttributeGenerator.DEPENDENCY_METRIC, attributes);
    return attributesMap;
  }

  private static ReadableSpan buildReadableSpanMock(Attributes spanAttributes) {
    return buildReadableSpanMock(spanAttributes, SpanKind.SERVER, null, StatusData.unset());
  }

  private static ReadableSpan buildReadableSpanMock(
      Attributes spanAttributes,
      SpanKind spanKind,
      SpanContext parentSpanContext,
      StatusData statusData) {
    ReadableSpan readableSpanMock = mock(ReadableSpan.class);

    // Configure latency
    when(readableSpanMock.getLatencyNanos()).thenReturn(TEST_LATENCY_NANOS);

    // Configure attributes
    when(readableSpanMock.getAttribute(any()))
        .thenAnswer(invocation -> spanAttributes.get(invocation.getArgument(0)));

    // Configure spanData
    SpanData mockSpanData = mock(SpanData.class);
    InstrumentationScopeInfo awsSdkScopeInfo =
        InstrumentationScopeInfo.builder("aws-sdk").setVersion("version").build();
    when(mockSpanData.getInstrumentationScopeInfo()).thenReturn(awsSdkScopeInfo);
    when(mockSpanData.getAttributes()).thenReturn(spanAttributes);
    when(mockSpanData.getTotalAttributeCount()).thenReturn(spanAttributes.size());
    when(mockSpanData.getKind()).thenReturn(spanKind);
    when(mockSpanData.getParentSpanContext()).thenReturn(parentSpanContext);
    when(mockSpanData.getStatus()).thenReturn(statusData);

    // Mock own SpanContext with a traceId so custom dim logic works
    SpanContext ownSpanContext = mock(SpanContext.class);
    when(ownSpanContext.getTraceId()).thenReturn("default-test-trace-id");
    when(ownSpanContext.getSpanId()).thenReturn("default-test-span-id");
    when(ownSpanContext.isValid()).thenReturn(true);
    when(mockSpanData.getSpanContext()).thenReturn(ownSpanContext);

    when(readableSpanMock.toSpanData()).thenReturn(mockSpanData);

    return readableSpanMock;
  }

  private static ReadableSpan buildReadableSpanWithThrowableMock(Throwable throwable) {
    // config http status code as null
    Attributes spanAttributes = Attributes.of(HTTP_RESPONSE_STATUS_CODE, null);
    ReadableSpan readableSpanMock = mock(ReadableSpan.class);
    SpanData mockSpanData = mock(SpanData.class);
    InstrumentationScopeInfo awsSdkScopeInfo =
        InstrumentationScopeInfo.builder("aws-sdk").setVersion("version").build();
    ExceptionEventData mockEventData = mock(ExceptionEventData.class);
    List<EventData> events = new ArrayList<>(Arrays.asList(mockEventData));

    // Configure latency
    when(readableSpanMock.getLatencyNanos()).thenReturn(TEST_LATENCY_NANOS);

    // Configure attributes
    when(readableSpanMock.getAttribute(any()))
        .thenAnswer(invocation -> spanAttributes.get(invocation.getArgument(0)));

    // Configure spanData
    when(mockSpanData.getInstrumentationScopeInfo()).thenReturn(awsSdkScopeInfo);
    when(mockSpanData.getAttributes()).thenReturn(spanAttributes);
    when(mockSpanData.getTotalAttributeCount()).thenReturn(spanAttributes.size());
    when(mockSpanData.getEvents()).thenReturn(events);
    when(mockEventData.getException()).thenReturn(throwable);
    when(readableSpanMock.toSpanData()).thenReturn(mockSpanData);

    return readableSpanMock;
  }

  private void configureMocksForOnEnd(
      ReadableSpan readableSpanMock, Map<String, Attributes> metricAttributesMap) {
    // Configure generated attributes
    when(generatorMock.generateMetricAttributeMapFromSpan(
            eq(readableSpanMock.toSpanData()), eq(testResource)))
        .thenReturn(metricAttributesMap);
  }

  private void validateMetricsGeneratedForHttpStatusCode(
      Long httpStatusCode, ExpectedStatusMetric expectedStatusMetric) {
    Attributes spanAttributes = Attributes.of(HTTP_RESPONSE_STATUS_CODE, httpStatusCode);
    ReadableSpan readableSpanMock =
        buildReadableSpanMock(spanAttributes, SpanKind.PRODUCER, null, StatusData.unset());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    validateMetrics(metricAttributesMap, expectedStatusMetric);
  }

  private void validateMetricsGeneratedForAttributeStatusCode(
      Long awsStatusCode, ExpectedStatusMetric expectedStatusMetric) {
    // Testing Dependency Metric
    Attributes attributes = Attributes.of(AttributeKey.stringKey("new key"), "new value");
    ReadableSpan readableSpanMock =
        buildReadableSpanMock(attributes, SpanKind.PRODUCER, null, StatusData.unset());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    if (awsStatusCode != null) {
      metricAttributesMap.put(
          SERVICE_METRIC,
          Attributes.of(
              AttributeKey.stringKey("new service key"),
              "new service value",
              HTTP_RESPONSE_STATUS_CODE,
              awsStatusCode));
      metricAttributesMap.put(
          DEPENDENCY_METRIC,
          Attributes.of(
              AttributeKey.stringKey("new dependency key"),
              "new dependency value",
              HTTP_RESPONSE_STATUS_CODE,
              awsStatusCode));
    }
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);
    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    validateMetrics(metricAttributesMap, expectedStatusMetric);
  }

  private void validateMetricsGeneratedForStatusDataError(
      Long httpStatusCode, ExpectedStatusMetric expectedStatusMetric) {
    Attributes spanAttributes = Attributes.of(HTTP_RESPONSE_STATUS_CODE, httpStatusCode);
    ReadableSpan readableSpanMock =
        buildReadableSpanMock(spanAttributes, SpanKind.PRODUCER, null, StatusData.error());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    validateMetrics(metricAttributesMap, expectedStatusMetric);
  }

  private void validateMetricsGeneratedForStatusDataOk(
      Long httpStatusCode, ExpectedStatusMetric expectedStatusMetric) {
    Attributes spanAttributes = Attributes.of(HTTP_RESPONSE_STATUS_CODE, httpStatusCode);

    ReadableSpan readableSpanMock =
        buildReadableSpanMock(spanAttributes, SpanKind.PRODUCER, null, StatusData.ok());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);
    validateMetrics(metricAttributesMap, expectedStatusMetric);
  }

  private void validateMetrics(
      Map<String, Attributes> metricAttributesMap, ExpectedStatusMetric expectedStatusMetric) {

    Attributes serviceAttributes = metricAttributesMap.get(SERVICE_METRIC);
    Attributes dependencyAttributes = metricAttributesMap.get(DEPENDENCY_METRIC);

    switch (expectedStatusMetric) {
      case ERROR:
        verify(errorHistogramMock, times(1)).record(eq(1L), eq(serviceAttributes));
        verify(faultHistogramMock, times(1)).record(eq(0L), eq(serviceAttributes));
        verify(errorHistogramMock, times(1)).record(eq(1L), eq(dependencyAttributes));
        verify(faultHistogramMock, times(1)).record(eq(0L), eq(dependencyAttributes));
        break;
      case FAULT:
        verify(errorHistogramMock, times(1)).record(eq(0L), eq(serviceAttributes));
        verify(faultHistogramMock, times(1)).record(eq(1L), eq(serviceAttributes));
        verify(errorHistogramMock, times(1)).record(eq(0L), eq(dependencyAttributes));
        verify(faultHistogramMock, times(1)).record(eq(1L), eq(dependencyAttributes));
        break;
      case NEITHER:
        verify(errorHistogramMock, times(1)).record(eq(0L), eq(serviceAttributes));
        verify(faultHistogramMock, times(1)).record(eq(0L), eq(serviceAttributes));
        verify(errorHistogramMock, times(1)).record(eq(0L), eq(dependencyAttributes));
        verify(faultHistogramMock, times(1)).record(eq(0L), eq(dependencyAttributes));
        break;
    }

    verify(latencyHistogramMock, times(1)).record(eq(TEST_LATENCY_MILLIS), eq(serviceAttributes));
    verify(latencyHistogramMock, times(1))
        .record(eq(TEST_LATENCY_MILLIS), eq(dependencyAttributes));

    // Clear invocations so this method can be called multiple times in one test.
    clearInvocations(errorHistogramMock);
    clearInvocations(faultHistogramMock);
    clearInvocations(latencyHistogramMock);
  }

  private void verifyHistogramRecords(
      Map<String, Attributes> metricAttributesMap,
      int wantedServiceMetricInvocation,
      int wantedDependencyMetricInvocation) {
    verify(errorHistogramMock, times(wantedServiceMetricInvocation))
        .record(eq(0L), eq(metricAttributesMap.get(SERVICE_METRIC)));
    verify(faultHistogramMock, times(wantedServiceMetricInvocation))
        .record(eq(0L), eq(metricAttributesMap.get(SERVICE_METRIC)));
    verify(latencyHistogramMock, times(wantedServiceMetricInvocation))
        .record(eq(TEST_LATENCY_MILLIS), eq(metricAttributesMap.get(SERVICE_METRIC)));
    verify(errorHistogramMock, times(wantedDependencyMetricInvocation))
        .record(eq(0L), eq(metricAttributesMap.get(DEPENDENCY_METRIC)));
    verify(faultHistogramMock, times(wantedDependencyMetricInvocation))
        .record(eq(0L), eq(metricAttributesMap.get(DEPENDENCY_METRIC)));
    verify(latencyHistogramMock, times(wantedDependencyMetricInvocation))
        .record(eq(TEST_LATENCY_MILLIS), eq(metricAttributesMap.get(DEPENDENCY_METRIC)));
  }

  // ===== Tests for Custom Dimension Metrics (Simplified, no dim_sets) =====

  @Test
  public void testCustomDim_SingleDimTriggersCustomMetrics() {
    // A SERVER span with one custom RED dim should produce 2 custom metric recordings
    // (with Operation + without Operation) plus standard metrics
    Attributes spanAttributes =
        Attributes.builder().put("aws.application_signals.custom.dim.CarrierId", "Fedex").build();

    ReadableSpan readableSpanMock =
        buildReadableSpanMock(spanAttributes, SpanKind.SERVER, null, StatusData.ok());

    // Build metric attributes with Operation so custom metrics can use it
    Map<String, Attributes> metricAttributesMap = new HashMap<>();
    metricAttributesMap.put(
        SERVICE_METRIC, Attributes.of(AwsAttributeKeys.AWS_LOCAL_OPERATION, "GET /api"));
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);

    // Standard histograms should receive 1 recording (SERVICE_METRIC)
    verify(errorHistogramMock, times(1)).record(eq(0L), any(Attributes.class));
    verify(faultHistogramMock, times(1)).record(eq(0L), any(Attributes.class));
    verify(latencyHistogramMock, times(1)).record(eq(TEST_LATENCY_MILLIS), any(Attributes.class));

    // Custom histograms: 2 recordings (with Operation, without Operation)
    verify(customErrorHistogramMock, times(2)).record(eq(0L), any(Attributes.class));
    verify(customFaultHistogramMock, times(2)).record(eq(0L), any(Attributes.class));
    verify(customLatencyHistogramMock, times(2))
        .record(eq(TEST_LATENCY_MILLIS), any(Attributes.class));
  }

  @Test
  public void testCustomDim_NoCustomDimsNoCustomMetrics() {
    // No custom dims -> no custom metrics
    Attributes spanAttributes = buildSpanAttributes(CONTAINS_NO_ATTRIBUTES);
    ReadableSpan readableSpanMock = buildReadableSpanMock(spanAttributes);
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);

    // Standard histograms should receive metrics
    verify(errorHistogramMock, times(1))
        .record(eq(0L), eq(metricAttributesMap.get(SERVICE_METRIC)));
    // Custom histograms should NOT receive metrics
    verifyNoInteractions(customErrorHistogramMock);
    verifyNoInteractions(customFaultHistogramMock);
    verifyNoInteractions(customLatencyHistogramMock);
  }

  @Test
  public void testCustomDim_ClientSpanNoCustomMetrics() {
    // CLIENT span with custom dims -> no custom metrics (only SERVICE type generates custom)
    SpanContext mockSpanContext = mock(SpanContext.class);
    when(mockSpanContext.isValid()).thenReturn(true);
    when(mockSpanContext.isRemote()).thenReturn(false);

    Attributes spanAttributes =
        Attributes.builder().put("aws.application_signals.custom.dim.CarrierId", "Fedex").build();

    ReadableSpan readableSpanMock =
        buildReadableSpanMock(spanAttributes, SpanKind.CLIENT, mockSpanContext, StatusData.ok());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);

    // Standard histograms should receive metrics (dependency)
    verify(errorHistogramMock, times(1)).record(eq(0L), any(Attributes.class));
    // Custom histograms should NOT (CLIENT is DEPENDENCY, not SERVICE)
    verifyNoInteractions(customErrorHistogramMock);
    verifyNoInteractions(customFaultHistogramMock);
    verifyNoInteractions(customLatencyHistogramMock);
  }

  @Test
  public void testCustomDim_MultipleDims_SeparateDimSets() {
    // 2 custom dims -> 4 recordings (2 per dim: with/without Operation)
    Attributes spanAttributes =
        Attributes.builder()
            .put("aws.application_signals.custom.dim.CarrierId", "Fedex")
            .put("aws.application_signals.custom.dim.Region", "US-West")
            .build();

    ReadableSpan readableSpanMock =
        buildReadableSpanMock(spanAttributes, SpanKind.SERVER, null, StatusData.ok());

    Map<String, Attributes> metricAttributesMap = new HashMap<>();
    metricAttributesMap.put(
        SERVICE_METRIC, Attributes.of(AwsAttributeKeys.AWS_LOCAL_OPERATION, "GET /api"));
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);

    // Standard: 1 recording
    verify(errorHistogramMock, times(1)).record(eq(0L), any(Attributes.class));

    // Custom: 4 recordings (2 dims * 2 sets each)
    verify(customErrorHistogramMock, times(4)).record(eq(0L), any(Attributes.class));
    verify(customFaultHistogramMock, times(4)).record(eq(0L), any(Attributes.class));
    verify(customLatencyHistogramMock, times(4))
        .record(eq(TEST_LATENCY_MILLIS), any(Attributes.class));
  }

  @Test
  public void testCustomDim_NullCustomHistograms_NoCrash() {
    // Null custom histograms should not crash, standard metrics still generated
    AwsSpanMetricsProcessor processorWithoutCustom =
        AwsSpanMetricsProcessor.create(
            errorHistogramMock,
            faultHistogramMock,
            latencyHistogramMock,
            null,
            null,
            null,
            generatorMock,
            testResource,
            samplerMock,
            this::forceFlushAction);

    Attributes spanAttributes =
        Attributes.builder().put("aws.application_signals.custom.dim.CarrierId", "Fedex").build();

    ReadableSpan readableSpanMock =
        buildReadableSpanMock(spanAttributes, SpanKind.SERVER, null, StatusData.ok());
    Map<String, Attributes> metricAttributesMap = new HashMap<>();
    metricAttributesMap.put(
        SERVICE_METRIC, Attributes.of(AwsAttributeKeys.AWS_LOCAL_OPERATION, "GET /api"));
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    processorWithoutCustom.onEnd(readableSpanMock);

    // Standard histograms should still receive metrics
    verify(errorHistogramMock, times(1)).record(eq(0L), any(Attributes.class));
    verify(faultHistogramMock, times(1)).record(eq(0L), any(Attributes.class));
    verify(latencyHistogramMock, times(1)).record(eq(TEST_LATENCY_MILLIS), any(Attributes.class));
  }

  @Test
  public void testCustomDim_StandardMetricsAlwaysGenerated() {
    // Standard metrics always generated regardless of custom dims
    Attributes spanAttributes =
        Attributes.builder().put("aws.application_signals.custom.dim.CarrierId", "Fedex").build();

    ReadableSpan readableSpanMock =
        buildReadableSpanMock(spanAttributes, SpanKind.SERVER, null, StatusData.ok());
    Map<String, Attributes> metricAttributesMap =
        buildMetricAttributes(CONTAINS_ATTRIBUTES, readableSpanMock.toSpanData());
    configureMocksForOnEnd(readableSpanMock, metricAttributesMap);

    awsSpanMetricsProcessor.onEnd(readableSpanMock);

    // Standard histograms should receive metrics
    verify(errorHistogramMock, times(1))
        .record(eq(0L), eq(metricAttributesMap.get(SERVICE_METRIC)));
    verify(faultHistogramMock, times(1))
        .record(eq(0L), eq(metricAttributesMap.get(SERVICE_METRIC)));
    verify(latencyHistogramMock, times(1))
        .record(eq(TEST_LATENCY_MILLIS), eq(metricAttributesMap.get(SERVICE_METRIC)));
  }

  @Test
  public void testCustomDim_TraceIdPropagation_ChildToParent() {
    // Child INTERNAL span has custom dim, parent SERVER span (same traceId, local root)
    // should pick up dims and generate custom metrics
    String sharedTraceId = "trace-id-aabbccdd";

    // Child span context (not local root: parent valid + not remote)
    SpanContext childOwnCtx = mock(SpanContext.class);
    when(childOwnCtx.getTraceId()).thenReturn(sharedTraceId);
    when(childOwnCtx.getSpanId()).thenReturn("child-span-id");
    when(childOwnCtx.isValid()).thenReturn(true);

    SpanContext childParentCtx = mock(SpanContext.class);
    when(childParentCtx.isValid()).thenReturn(true);
    when(childParentCtx.isRemote()).thenReturn(false);

    Attributes childAttrs =
        Attributes.builder().put("aws.application_signals.custom.dim.CarrierId", "Fedex").build();
    SpanData childData = mock(SpanData.class);
    when(childData.getAttributes()).thenReturn(childAttrs);
    when(childData.getKind()).thenReturn(SpanKind.INTERNAL);
    when(childData.getParentSpanContext()).thenReturn(childParentCtx);
    when(childData.getSpanContext()).thenReturn(childOwnCtx);
    when(childData.getStatus()).thenReturn(StatusData.ok());
    when(childData.getName()).thenReturn("getCarrierId");
    ReadableSpan childMock = mock(ReadableSpan.class);
    when(childMock.getLatencyNanos()).thenReturn(TEST_LATENCY_NANOS);
    when(childMock.toSpanData()).thenReturn(childData);
    when(generatorMock.generateMetricAttributeMapFromSpan(eq(childData), eq(testResource)))
        .thenReturn(new HashMap<>());

    // Process child -> stores CarrierId under traceId
    awsSpanMetricsProcessor.onEnd(childMock);
    verifyNoInteractions(customErrorHistogramMock);

    // Parent SERVER span (local root: parent invalid)
    SpanContext parentOwnCtx = mock(SpanContext.class);
    when(parentOwnCtx.getTraceId()).thenReturn(sharedTraceId);
    when(parentOwnCtx.getSpanId()).thenReturn("parent-span-id");
    when(parentOwnCtx.isValid()).thenReturn(true);

    SpanData parentData = mock(SpanData.class);
    when(parentData.getAttributes()).thenReturn(Attributes.empty());
    when(parentData.getKind()).thenReturn(SpanKind.SERVER);
    when(parentData.getParentSpanContext()).thenReturn(SpanContext.getInvalid());
    when(parentData.getSpanContext()).thenReturn(parentOwnCtx);
    when(parentData.getStatus()).thenReturn(StatusData.ok());
    when(parentData.getName()).thenReturn("GET /api");
    ReadableSpan parentMock = mock(ReadableSpan.class);
    when(parentMock.getLatencyNanos()).thenReturn(TEST_LATENCY_NANOS);
    when(parentMock.toSpanData()).thenReturn(parentData);

    Map<String, Attributes> parentMetricAttrs = new HashMap<>();
    parentMetricAttrs.put(
        SERVICE_METRIC, Attributes.of(AwsAttributeKeys.AWS_LOCAL_OPERATION, "GET /api"));
    when(generatorMock.generateMetricAttributeMapFromSpan(eq(parentData), eq(testResource)))
        .thenReturn(parentMetricAttrs);

    // Process parent (local root) -> picks up dims by traceId
    awsSpanMetricsProcessor.onEnd(parentMock);

    // 1 dim * 2 sets = 2 custom recordings
    verify(customErrorHistogramMock, times(2)).record(eq(0L), any(Attributes.class));
    verify(customFaultHistogramMock, times(2)).record(eq(0L), any(Attributes.class));
    verify(customLatencyHistogramMock, times(2))
        .record(eq(TEST_LATENCY_MILLIS), any(Attributes.class));
  }

  @Test
  public void testCustomDim_TraceIdPropagation_MultipleChildren() {
    // Two children with different dims, same traceId -> parent gets both
    String sharedTraceId = "trace-id-11223344";

    // Child 1
    SpanContext child1Ctx = mock(SpanContext.class);
    when(child1Ctx.getTraceId()).thenReturn(sharedTraceId);
    when(child1Ctx.getSpanId()).thenReturn("child1-id");
    when(child1Ctx.isValid()).thenReturn(true);
    SpanContext child1ParentCtx = mock(SpanContext.class);
    when(child1ParentCtx.isValid()).thenReturn(true);
    when(child1ParentCtx.isRemote()).thenReturn(false);

    Attributes child1Attrs =
        Attributes.of(
            AttributeKey.stringKey("aws.application_signals.custom.dim.CarrierId"), "Fedex");
    SpanData child1Data = mock(SpanData.class);
    when(child1Data.getAttributes()).thenReturn(child1Attrs);
    when(child1Data.getKind()).thenReturn(SpanKind.INTERNAL);
    when(child1Data.getParentSpanContext()).thenReturn(child1ParentCtx);
    when(child1Data.getSpanContext()).thenReturn(child1Ctx);
    when(child1Data.getStatus()).thenReturn(StatusData.ok());
    when(child1Data.getName()).thenReturn("child1");
    ReadableSpan child1Mock = mock(ReadableSpan.class);
    when(child1Mock.getLatencyNanos()).thenReturn(TEST_LATENCY_NANOS);
    when(child1Mock.toSpanData()).thenReturn(child1Data);
    when(generatorMock.generateMetricAttributeMapFromSpan(eq(child1Data), eq(testResource)))
        .thenReturn(new HashMap<>());

    // Child 2
    SpanContext child2Ctx = mock(SpanContext.class);
    when(child2Ctx.getTraceId()).thenReturn(sharedTraceId);
    when(child2Ctx.getSpanId()).thenReturn("child2-id");
    when(child2Ctx.isValid()).thenReturn(true);
    SpanContext child2ParentCtx = mock(SpanContext.class);
    when(child2ParentCtx.isValid()).thenReturn(true);
    when(child2ParentCtx.isRemote()).thenReturn(false);

    Attributes child2Attrs =
        Attributes.of(
            AttributeKey.stringKey("aws.application_signals.custom.dim.Region"), "US-West");
    SpanData child2Data = mock(SpanData.class);
    when(child2Data.getAttributes()).thenReturn(child2Attrs);
    when(child2Data.getKind()).thenReturn(SpanKind.INTERNAL);
    when(child2Data.getParentSpanContext()).thenReturn(child2ParentCtx);
    when(child2Data.getSpanContext()).thenReturn(child2Ctx);
    when(child2Data.getStatus()).thenReturn(StatusData.ok());
    when(child2Data.getName()).thenReturn("child2");
    ReadableSpan child2Mock = mock(ReadableSpan.class);
    when(child2Mock.getLatencyNanos()).thenReturn(TEST_LATENCY_NANOS);
    when(child2Mock.toSpanData()).thenReturn(child2Data);
    when(generatorMock.generateMetricAttributeMapFromSpan(eq(child2Data), eq(testResource)))
        .thenReturn(new HashMap<>());

    awsSpanMetricsProcessor.onEnd(child1Mock);
    awsSpanMetricsProcessor.onEnd(child2Mock);
    verifyNoInteractions(customErrorHistogramMock);

    // Parent SERVER span (local root, same traceId)
    SpanContext parentCtx = mock(SpanContext.class);
    when(parentCtx.getTraceId()).thenReturn(sharedTraceId);
    when(parentCtx.getSpanId()).thenReturn("parent-id");
    when(parentCtx.isValid()).thenReturn(true);

    SpanData parentData = mock(SpanData.class);
    when(parentData.getAttributes()).thenReturn(Attributes.empty());
    when(parentData.getKind()).thenReturn(SpanKind.SERVER);
    when(parentData.getParentSpanContext()).thenReturn(SpanContext.getInvalid());
    when(parentData.getSpanContext()).thenReturn(parentCtx);
    when(parentData.getStatus()).thenReturn(StatusData.ok());
    when(parentData.getName()).thenReturn("GET /api");
    ReadableSpan parentMock = mock(ReadableSpan.class);
    when(parentMock.getLatencyNanos()).thenReturn(TEST_LATENCY_NANOS);
    when(parentMock.toSpanData()).thenReturn(parentData);

    Map<String, Attributes> parentMetricAttrs = new HashMap<>();
    parentMetricAttrs.put(
        SERVICE_METRIC, Attributes.of(AwsAttributeKeys.AWS_LOCAL_OPERATION, "GET /api"));
    when(generatorMock.generateMetricAttributeMapFromSpan(eq(parentData), eq(testResource)))
        .thenReturn(parentMetricAttrs);

    awsSpanMetricsProcessor.onEnd(parentMock);

    // 2 dims * 2 sets = 4 custom recordings
    verify(customErrorHistogramMock, times(4)).record(eq(0L), any(Attributes.class));
    verify(customFaultHistogramMock, times(4)).record(eq(0L), any(Attributes.class));
    verify(customLatencyHistogramMock, times(4))
        .record(eq(TEST_LATENCY_MILLIS), any(Attributes.class));
  }
}
