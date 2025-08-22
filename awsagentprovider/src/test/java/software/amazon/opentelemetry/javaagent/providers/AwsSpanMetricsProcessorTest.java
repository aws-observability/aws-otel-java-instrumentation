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

import static io.opentelemetry.semconv.SemanticAttributes.HTTP_RESPONSE_STATUS_CODE;
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
    generatorMock = mock(MetricAttributeGenerator.class);
    samplerMock = mock(AwsXrayRemoteSampler.class);

    awsSpanMetricsProcessor =
        AwsSpanMetricsProcessor.create(
            errorHistogramMock,
            faultHistogramMock,
            latencyHistogramMock,
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
}
