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
import static io.opentelemetry.semconv.incubating.MessagingIncubatingAttributes.MESSAGING_OPERATION_TYPE;
import static io.opentelemetry.semconv.incubating.MessagingIncubatingAttributes.MessagingOperationTypeIncubatingValues.PROCESS;
import static io.opentelemetry.semconv.incubating.MessagingIncubatingAttributes.MessagingOperationTypeIncubatingValues.RECEIVE;
import static io.opentelemetry.semconv.incubating.RpcIncubatingAttributes.RPC_SYSTEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LAMBDA_LOCAL_OPERATION_OVERRIDE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.MAX_KEYWORD_LENGTH;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.getDialectKeywords;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

public class AwsSpanProcessingUtilTest {
  private static final String DEFAULT_PATH_VALUE = "/";
  private static final String UNKNOWN_OPERATION = "UnknownOperation";
  private static final String INTERNAL_OPERATION = "InternalOperation";

  private Attributes attributesMock;
  private SpanData spanDataMock;

  @BeforeEach
  public void setUpMocks() {
    attributesMock = mock(Attributes.class);
    spanDataMock = mock(SpanData.class);
    when(spanDataMock.getAttributes()).thenReturn(attributesMock);
    when(spanDataMock.getSpanContext()).thenReturn(mock(SpanContext.class));
  }

  @Test
  public void testGetIngressOperationValidName() {
    String validName = "ValidName";
    when(spanDataMock.getName()).thenReturn(validName);
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    String actualOperation = AwsSpanProcessingUtil.getIngressOperation(spanDataMock);
    assertThat(actualOperation).isEqualTo(validName);
  }

  @Test
  public void testGetIngressOperationWithNotServer() {
    String validName = "ValidName";
    when(spanDataMock.getName()).thenReturn(validName);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    String actualOperation = AwsSpanProcessingUtil.getIngressOperation(spanDataMock);
    assertThat(actualOperation).isEqualTo(INTERNAL_OPERATION);
  }

  @Test
  public void testGetIngressOperationHttpMethodNameAndNoFallback() {
    String invalidName = "GET";
    when(spanDataMock.getName()).thenReturn(invalidName);
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn(invalidName);
    String actualOperation = AwsSpanProcessingUtil.getIngressOperation(spanDataMock);
    assertThat(actualOperation).isEqualTo(UNKNOWN_OPERATION);
  }

  @Test
  public void testGetIngressOperationNullNameAndNoFallback() {
    String invalidName = null;
    when(spanDataMock.getName()).thenReturn(invalidName);
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    String actualOperation = AwsSpanProcessingUtil.getIngressOperation(spanDataMock);
    assertThat(actualOperation).isEqualTo(UNKNOWN_OPERATION);
  }

  @Test
  public void testGetIngressOperationUnknownNameAndNoFallback() {
    String invalidName = UNKNOWN_OPERATION;
    when(spanDataMock.getName()).thenReturn(invalidName);
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    String actualOperation = AwsSpanProcessingUtil.getIngressOperation(spanDataMock);
    assertThat(actualOperation).isEqualTo(UNKNOWN_OPERATION);
  }

  @Test
  public void testGetIngressOperationInvalidNameAndValidTarget() {
    String invalidName = null;
    String validTarget = "/";
    when(spanDataMock.getName()).thenReturn(invalidName);
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    when(attributesMock.get(URL_PATH)).thenReturn(validTarget);
    String actualOperation = AwsSpanProcessingUtil.getIngressOperation(spanDataMock);
    assertThat(actualOperation).isEqualTo(validTarget);
  }

  @Test
  public void testGetIngressOperationInvalidNameAndValidTargetAndMethod() {
    String invalidName = null;
    String validTarget = "/";
    String validMethod = "GET";
    when(spanDataMock.getName()).thenReturn(invalidName);
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    when(attributesMock.get(URL_PATH)).thenReturn(validTarget);
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn(validMethod);
    String actualOperation = AwsSpanProcessingUtil.getIngressOperation(spanDataMock);
    assertThat(actualOperation).isEqualTo(validMethod + " " + validTarget);
  }

  @Test
  public void testGetIngressOperationLambdaOverride() {
    try (MockedStatic<AwsApplicationSignalsCustomizerProvider> providerStatic =
        mockStatic(
            AwsApplicationSignalsCustomizerProvider.class,
            withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      // Force Lambda environment branch
      providerStatic
          .when(AwsApplicationSignalsCustomizerProvider::isLambdaEnvironment)
          .thenReturn(true);
      // Simulate an override attribute on the span
      when(attributesMock.get(AWS_LAMBDA_LOCAL_OPERATION_OVERRIDE)).thenReturn("MyOverrideOp");

      String actualOperation = AwsSpanProcessingUtil.getIngressOperation(spanDataMock);
      assertThat(actualOperation).isEqualTo("MyOverrideOp");
    }
  }

  @Test
  public void testGetIngressOperationLambdaDefault() throws Exception {
    try (
    // Mock the AWS environment check
    MockedStatic<AwsApplicationSignalsCustomizerProvider> providerStatic =
            mockStatic(
                AwsApplicationSignalsCustomizerProvider.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        // Mock only getFunctionNameFromEnv, leave all other util logic untouched
        MockedStatic<AwsSpanProcessingUtil> utilStatic =
            mockStatic(
                AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      // force lambda branch and no override attribute
      providerStatic
          .when(AwsApplicationSignalsCustomizerProvider::isLambdaEnvironment)
          .thenReturn(true);
      when(attributesMock.get(AWS_LAMBDA_LOCAL_OPERATION_OVERRIDE)).thenReturn(null);
      // Provide a deterministic function name
      utilStatic.when(AwsSpanProcessingUtil::getFunctionNameFromEnv).thenReturn("MockFunction");

      String actual = AwsSpanProcessingUtil.getIngressOperation(spanDataMock);
      assertThat(actual).isEqualTo("MockFunction/FunctionHandler");
    }
  }

  @Test
  public void testGetEgressOperationUseInternalOperation() {
    String invalidName = null;
    when(spanDataMock.getName()).thenReturn(invalidName);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    String actualOperation = AwsSpanProcessingUtil.getEgressOperation(spanDataMock);
    assertThat(actualOperation).isEqualTo(INTERNAL_OPERATION);
  }

  @Test
  public void testGetEgressOperationGetLocalOperation() {
    String operation = "TestOperation";
    when(attributesMock.get(AWS_LOCAL_OPERATION)).thenReturn(operation);
    when(spanDataMock.getAttributes()).thenReturn(attributesMock);
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    String actualOperation = AwsSpanProcessingUtil.getEgressOperation(spanDataMock);
    assertThat(actualOperation).isEqualTo(operation);
  }

  @Test
  public void testExtractAPIPathValueEmptyTarget() {
    String invalidTarget = "";
    String pathValue = AwsSpanProcessingUtil.extractAPIPathValue(invalidTarget);
    assertThat(pathValue).isEqualTo(DEFAULT_PATH_VALUE);
  }

  @Test
  public void testExtractAPIPathValueNullTarget() {
    String invalidTarget = null;
    String pathValue = AwsSpanProcessingUtil.extractAPIPathValue(invalidTarget);
    assertThat(pathValue).isEqualTo(DEFAULT_PATH_VALUE);
  }

  @Test
  public void testExtractAPIPathValueNoSlash() {
    String invalidTarget = "users";
    String pathValue = AwsSpanProcessingUtil.extractAPIPathValue(invalidTarget);
    assertThat(pathValue).isEqualTo(DEFAULT_PATH_VALUE);
  }

  @Test
  public void testExtractAPIPathValueOnlySlash() {
    String invalidTarget = "/";
    String pathValue = AwsSpanProcessingUtil.extractAPIPathValue(invalidTarget);
    assertThat(pathValue).isEqualTo(DEFAULT_PATH_VALUE);
  }

  @Test
  public void testExtractAPIPathValueOnlySlashAtEnd() {
    String invalidTarget = "users/";
    String pathValue = AwsSpanProcessingUtil.extractAPIPathValue(invalidTarget);
    assertThat(pathValue).isEqualTo(DEFAULT_PATH_VALUE);
  }

  @Test
  public void testExtractAPIPathValidPath() {
    String validTarget = "/users/1/pet?query#fragment";
    String pathValue = AwsSpanProcessingUtil.extractAPIPathValue(validTarget);
    assertThat(pathValue).isEqualTo("/users");
  }

  @Test
  public void testIsKeyPresentKeyPresent() {
    when(attributesMock.get(URL_PATH)).thenReturn("target");
    assertThat(AwsSpanProcessingUtil.isKeyPresent(spanDataMock, URL_PATH)).isTrue();
  }

  @Test
  public void testIsKeyPresentKeyAbsent() {
    assertThat(AwsSpanProcessingUtil.isKeyPresent(spanDataMock, URL_PATH)).isFalse();
  }

  @Test
  public void testIsAwsSpanTrue() {
    when(attributesMock.get(RPC_SYSTEM)).thenReturn("aws-api");
    assertThat(AwsSpanProcessingUtil.isAwsSDKSpan(spanDataMock)).isTrue();
  }

  @Test
  public void testIsAwsSpanFalse() {
    assertThat(AwsSpanProcessingUtil.isAwsSDKSpan(spanDataMock)).isFalse();
  }

  @Test
  public void testShouldUseInternalOperationFalse() {
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    assertThat(AwsSpanProcessingUtil.shouldUseInternalOperation(spanDataMock)).isFalse();

    SpanContext parentSpanContext = mock(SpanContext.class);
    when(parentSpanContext.isRemote()).thenReturn(false);
    when(parentSpanContext.isValid()).thenReturn(true);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    when(spanDataMock.getParentSpanContext()).thenReturn(parentSpanContext);

    assertThat(AwsSpanProcessingUtil.shouldUseInternalOperation(spanDataMock)).isFalse();
  }

  @Test
  public void testShouldGenerateServiceMetricAttributes() {
    SpanContext parentSpanContext = mock(SpanContext.class);
    when(parentSpanContext.isRemote()).thenReturn(false);
    when(parentSpanContext.isValid()).thenReturn(true);
    when(spanDataMock.getParentSpanContext()).thenReturn(parentSpanContext);

    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isTrue();

    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isFalse();

    when(spanDataMock.getKind()).thenReturn(SpanKind.INTERNAL);
    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isFalse();

    when(spanDataMock.getKind()).thenReturn(SpanKind.PRODUCER);
    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isFalse();

    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isFalse();

    // It's a local root, so should return true
    when(parentSpanContext.isRemote()).thenReturn(true);
    when(spanDataMock.getKind()).thenReturn(SpanKind.PRODUCER);
    when(spanDataMock.getParentSpanContext()).thenReturn(parentSpanContext);
    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isTrue();
  }

  @Test
  public void testShouldGenerateDependencyMetricAttributes() {
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isFalse();

    when(spanDataMock.getKind()).thenReturn(SpanKind.INTERNAL);
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isFalse();

    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isTrue();

    when(spanDataMock.getKind()).thenReturn(SpanKind.PRODUCER);
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isTrue();

    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isTrue();

    SpanContext parentSpanContextMock = mock(SpanContext.class);
    when(parentSpanContextMock.isValid()).thenReturn(true);
    when(parentSpanContextMock.isRemote()).thenReturn(false);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    when(spanDataMock.getParentSpanContext()).thenReturn(parentSpanContextMock);
    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn(PROCESS);
    when(attributesMock.get(AwsAttributeKeys.AWS_CONSUMER_PARENT_SPAN_KIND))
        .thenReturn(SpanKind.CONSUMER.name());
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isFalse();

    when(parentSpanContextMock.isValid()).thenReturn(false);
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isTrue();
  }

  @Test
  public void testIsLocalRoot() {
    // Parent Context is empty
    assertThat(AwsSpanProcessingUtil.isLocalRoot(spanDataMock)).isTrue();

    SpanContext parentSpanContext = mock(SpanContext.class);
    when(spanDataMock.getParentSpanContext()).thenReturn(parentSpanContext);

    when(parentSpanContext.isRemote()).thenReturn(false);
    when(parentSpanContext.isValid()).thenReturn(true);
    assertThat(AwsSpanProcessingUtil.isLocalRoot(spanDataMock)).isFalse();

    when(parentSpanContext.isRemote()).thenReturn(true);
    when(parentSpanContext.isValid()).thenReturn(true);
    assertThat(AwsSpanProcessingUtil.isLocalRoot(spanDataMock)).isTrue();

    when(parentSpanContext.isRemote()).thenReturn(false);
    when(parentSpanContext.isValid()).thenReturn(false);
    assertThat(AwsSpanProcessingUtil.isLocalRoot(spanDataMock)).isTrue();

    when(parentSpanContext.isRemote()).thenReturn(true);
    when(parentSpanContext.isValid()).thenReturn(false);
    assertThat(AwsSpanProcessingUtil.isLocalRoot(spanDataMock)).isTrue();
  }

  @Test
  public void testIsConsumerProcessSpanFalse() {
    assertThat(AwsSpanProcessingUtil.isConsumerProcessSpan(spanDataMock)).isFalse();
  }

  @Test
  public void testIsConsumerProcessSpanFalse_with_MESSAGING_OPERATION_TYPE() {
    when(attributesMock.get(MESSAGING_OPERATION_TYPE)).thenReturn(RECEIVE);
    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn(PROCESS);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    assertThat(AwsSpanProcessingUtil.isConsumerProcessSpan(spanDataMock)).isFalse();
  }

  @Test
  public void testIsConsumerProcessSpanTrue() {
    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn(PROCESS);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    assertThat(AwsSpanProcessingUtil.isConsumerProcessSpan(spanDataMock)).isTrue();
  }

  @Test
  public void testIsConsumerProcessSpanTrue_with_MESSAGING_OPERATION_TYPE() {
    when(attributesMock.get(MESSAGING_OPERATION_TYPE)).thenReturn(PROCESS);
    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn(RECEIVE);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    assertThat(AwsSpanProcessingUtil.isConsumerProcessSpan(spanDataMock)).isTrue();
  }

  // check that AWS SDK v1 SQS ReceiveMessage consumer spans metrics are suppressed
  @Test
  public void testNoMetricAttributesForSqsConsumerSpanAwsSdkV1() {
    InstrumentationScopeInfo instrumentationScopeInfo = mock(InstrumentationScopeInfo.class);
    when(instrumentationScopeInfo.getName()).thenReturn("io.opentelemetry.aws-sdk-1.11");
    when(spanDataMock.getInstrumentationScopeInfo()).thenReturn(instrumentationScopeInfo);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    when(spanDataMock.getName()).thenReturn("SQS.ReceiveMessage");

    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isFalse();
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isFalse();
  }

  // check that AWS SDK v2 SQS ReceiveMessage consumer spans metrics are suppressed
  @Test
  public void testNoMetricAttributesForSqsConsumerSpanAwsSdkV2() {
    InstrumentationScopeInfo instrumentationScopeInfo = mock(InstrumentationScopeInfo.class);
    when(instrumentationScopeInfo.getName()).thenReturn("io.opentelemetry.aws-sdk-2.2");
    when(spanDataMock.getInstrumentationScopeInfo()).thenReturn(instrumentationScopeInfo);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    when(spanDataMock.getName()).thenReturn("Sqs.ReceiveMessage");

    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isFalse();
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isFalse();
  }

  // check that SQS ReceiveMessage consumer spans metrics are still generated for other
  // instrumentation
  @Test
  public void testMetricAttributesGeneratedForOtherInstrumentationSqsConsumerSpan() {
    InstrumentationScopeInfo instrumentationScopeInfo = mock(InstrumentationScopeInfo.class);
    when(instrumentationScopeInfo.getName()).thenReturn("my-instrumentation");
    when(spanDataMock.getInstrumentationScopeInfo()).thenReturn(instrumentationScopeInfo);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    when(spanDataMock.getName()).thenReturn("Sqs.ReceiveMessage");

    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isTrue();
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isTrue();
  }

  // check that SQS ReceiveMessage consumer span metrics are suppressed if messaging operation is
  // process and not receive
  @Test
  public void testNoMetricAttributesForAwsSdkSqsConsumerProcessSpan() {
    InstrumentationScopeInfo instrumentationScopeInfo = mock(InstrumentationScopeInfo.class);
    when(instrumentationScopeInfo.getName()).thenReturn("io.opentelemetry.aws-sdk-2.2");
    when(spanDataMock.getInstrumentationScopeInfo()).thenReturn(instrumentationScopeInfo);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    when(spanDataMock.getName()).thenReturn("Sqs.ReceiveMessage");
    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn(PROCESS);

    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isFalse();
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isFalse();

    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn(RECEIVE);
    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isTrue();
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isTrue();
  }

  @Test
  public void
      testNoMetricAttributesForAwsSdkSqsConsumerProcessSpan_with_MESSAGING_OPERATION_TYPE() {
    InstrumentationScopeInfo instrumentationScopeInfo = mock(InstrumentationScopeInfo.class);
    when(instrumentationScopeInfo.getName()).thenReturn("io.opentelemetry.aws-sdk-2.2");
    when(spanDataMock.getInstrumentationScopeInfo()).thenReturn(instrumentationScopeInfo);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    when(spanDataMock.getName()).thenReturn("Sqs.ReceiveMessage");
    when(attributesMock.get(MESSAGING_OPERATION_TYPE)).thenReturn(PROCESS);

    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isFalse();
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isFalse();

    when(attributesMock.get(MESSAGING_OPERATION_TYPE)).thenReturn(RECEIVE);
    assertThat(AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanDataMock)).isTrue();
    assertThat(AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanDataMock))
        .isTrue();
  }

  @Test
  public void testSqlDialectKeywordsOrder() {
    List<String> keywords = getDialectKeywords();
    int prevKeywordLength = Integer.MAX_VALUE;
    for (String keyword : keywords) {
      int currKeywordLength = keyword.length();
      assertThat(prevKeywordLength >= currKeywordLength);
      prevKeywordLength = currKeywordLength;
    }
  }

  @Test
  public void testSqlDialectKeywordsMaxLength() {
    List<String> keywords = getDialectKeywords();
    for (String keyword : keywords) {
      assertThat(MAX_KEYWORD_LENGTH >= keyword.length());
    }
  }

  @Test
  public void testIsKeyPresentWithFallback_NewKeyPresent() {
    when(attributesMock.get(MESSAGING_OPERATION_TYPE)).thenReturn("publish");
    assertThat(
            AwsSpanProcessingUtil.isKeyPresentWithFallback(
                spanDataMock, MESSAGING_OPERATION_TYPE, MESSAGING_OPERATION))
        .isTrue();
  }

  @Test
  public void testIsKeyPresentWithFallback_DeprecatedKeyPresent() {
    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn("publish");
    assertThat(
            AwsSpanProcessingUtil.isKeyPresentWithFallback(
                spanDataMock, MESSAGING_OPERATION_TYPE, MESSAGING_OPERATION))
        .isTrue();
  }

  @Test
  public void testIsKeyPresentWithFallback_BothKeysAbsent() {
    assertThat(
            AwsSpanProcessingUtil.isKeyPresentWithFallback(
                spanDataMock, MESSAGING_OPERATION_TYPE, MESSAGING_OPERATION))
        .isFalse();
  }

  @Test
  public void testGetKeyValueWithFallback_NewKeyPresent() {
    when(attributesMock.get(MESSAGING_OPERATION_TYPE)).thenReturn("send");
    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn("publish");
    assertThat(
            AwsSpanProcessingUtil.getKeyValueWithFallback(
                spanDataMock, MESSAGING_OPERATION_TYPE, MESSAGING_OPERATION))
        .isEqualTo("send");
  }

  @Test
  public void testGetKeyValueWithFallback_DeprecatedKeyPresent() {
    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn("publish");
    assertThat(
            AwsSpanProcessingUtil.getKeyValueWithFallback(
                spanDataMock, MESSAGING_OPERATION_TYPE, MESSAGING_OPERATION))
        .isEqualTo("publish");
  }

  @Test
  public void testGetKeyValueWithFallback_BothKeysAbsent() {
    assertThat(
            AwsSpanProcessingUtil.getKeyValueWithFallback(
                spanDataMock, MESSAGING_OPERATION_TYPE, MESSAGING_OPERATION))
        .isNull();
  }

  @Test
  public void testIsLambdaServerSpan_withLambdaScope() {
    ReadableSpan span = mock(ReadableSpan.class);
    SpanData spanData = mock(SpanData.class);
    InstrumentationScopeInfo scopeInfo = mock(InstrumentationScopeInfo.class);
    when(span.toSpanData()).thenReturn(spanData);
    when(spanData.getInstrumentationScopeInfo()).thenReturn(scopeInfo);
    when(scopeInfo.getName()).thenReturn(AwsSpanProcessingUtil.LAMBDA_SCOPE_PREFIX + "-lib-1.0");
    when(span.getKind()).thenReturn(SpanKind.SERVER);

    assertTrue(AwsSpanProcessingUtil.isLambdaServerSpan(span));
  }

  @Test
  public void testIsLambdaServerSpan_withNonLambdaScope() {
    ReadableSpan span = mock(ReadableSpan.class);
    SpanData spanData = mock(SpanData.class);
    InstrumentationScopeInfo scopeInfo = mock(InstrumentationScopeInfo.class);
    when(span.toSpanData()).thenReturn(spanData);
    when(spanData.getInstrumentationScopeInfo()).thenReturn(scopeInfo);
    when(scopeInfo.getName())
        .thenReturn("org.abc." + AwsSpanProcessingUtil.LAMBDA_SCOPE_PREFIX + "-lib-3.0");
    when(span.getKind()).thenReturn(SpanKind.SERVER);

    assertFalse(AwsSpanProcessingUtil.isLambdaServerSpan(span));
  }

  @Test
  public void testIsLambdaServerSpan_withNullScope() {
    ReadableSpan span = mock(ReadableSpan.class);
    SpanData spanData = mock(SpanData.class);
    when(span.toSpanData()).thenReturn(spanData);
    when(spanData.getInstrumentationScopeInfo()).thenReturn(null);
    when(span.getKind()).thenReturn(SpanKind.SERVER);

    assertFalse(AwsSpanProcessingUtil.isLambdaServerSpan(span));
  }

  @Test
  public void testIsLambdaServerSpan_withNonServerSpanKind() {
    ReadableSpan span = mock(ReadableSpan.class);
    SpanData spanData = mock(SpanData.class);
    InstrumentationScopeInfo scopeInfo = mock(InstrumentationScopeInfo.class);
    when(span.toSpanData()).thenReturn(spanData);
    when(spanData.getInstrumentationScopeInfo()).thenReturn(scopeInfo);
    when(scopeInfo.getName()).thenReturn(AwsSpanProcessingUtil.LAMBDA_SCOPE_PREFIX + "-core-1.0");
    when(span.getKind()).thenReturn(SpanKind.CLIENT);

    assertFalse(AwsSpanProcessingUtil.isLambdaServerSpan(span));
  }

  @Test
  public void testIsServletServerSpan_withServletScope() {
    ReadableSpan span = mock(ReadableSpan.class);
    SpanData spanData = mock(SpanData.class);
    InstrumentationScopeInfo scopeInfo = mock(InstrumentationScopeInfo.class);
    when(span.toSpanData()).thenReturn(spanData);
    when(spanData.getInstrumentationScopeInfo()).thenReturn(scopeInfo);
    when(scopeInfo.getName()).thenReturn(AwsSpanProcessingUtil.SERVLET_SCOPE_PREFIX + "-3.0");
    when(span.getKind()).thenReturn(SpanKind.SERVER);

    assertTrue(AwsSpanProcessingUtil.isServletServerSpan(span));
  }

  @Test
  public void testIsServletServerSpan_withNonServletScope() {
    ReadableSpan span = mock(ReadableSpan.class);
    SpanData spanData = mock(SpanData.class);
    InstrumentationScopeInfo scopeInfo = mock(InstrumentationScopeInfo.class);
    when(span.toSpanData()).thenReturn(spanData);
    when(spanData.getInstrumentationScopeInfo()).thenReturn(scopeInfo);
    when(scopeInfo.getName()).thenReturn(AwsSpanProcessingUtil.LAMBDA_SCOPE_PREFIX + "-2.0");
    when(span.getKind()).thenReturn(SpanKind.SERVER);

    assertFalse(AwsSpanProcessingUtil.isServletServerSpan(span));
  }

  @Test
  public void testIsServletServerSpan_withNullScope() {
    ReadableSpan span = mock(ReadableSpan.class);
    SpanData spanData = mock(SpanData.class);
    when(span.toSpanData()).thenReturn(spanData);
    when(spanData.getInstrumentationScopeInfo()).thenReturn(null);
    when(span.getKind()).thenReturn(SpanKind.SERVER);

    assertFalse(AwsSpanProcessingUtil.isServletServerSpan(span));
  }

  @Test
  public void testIsServletServerSpan_withNonServerSpanKind() {
    ReadableSpan span = mock(ReadableSpan.class);
    SpanData spanData = mock(SpanData.class);
    InstrumentationScopeInfo scopeInfo = mock(InstrumentationScopeInfo.class);
    when(span.toSpanData()).thenReturn(spanData);
    when(spanData.getInstrumentationScopeInfo()).thenReturn(scopeInfo);
    when(scopeInfo.getName()).thenReturn(AwsSpanProcessingUtil.SERVLET_SCOPE_PREFIX + "-5.0");
    when(span.getKind()).thenReturn(SpanKind.CLIENT);

    assertFalse(AwsSpanProcessingUtil.isServletServerSpan(span));
  }

  // Helper to call the private segmentsMatch method via reflection
  private static boolean segmentsMatch(String[] urlSegments, String[] patternSegments) {
    try {
      java.lang.reflect.Method method =
          AwsSpanProcessingUtil.class.getDeclaredMethod(
              "segmentsMatch", String[].class, String[].class);
      method.setAccessible(true);
      return (boolean) method.invoke(null, urlSegments, patternSegments);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean matchSegments(String urlPath, String pattern) {
    return segmentsMatch(urlPath.split("/", -1), pattern.split("/", -1));
  }

  // Helper to call the private getUrlPath method via reflection
  private static String getUrlPath(SpanData span) {
    try {
      java.lang.reflect.Method method =
          AwsSpanProcessingUtil.class.getDeclaredMethod("getUrlPath", SpanData.class);
      method.setAccessible(true);
      return (String) method.invoke(null, span);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // --- getUrlPath: attribute priority ---

  @Test
  public void testGetUrlPath_prefersUrlPathOverHttpTarget() {
    when(attributesMock.get(URL_PATH)).thenReturn("/from/url-path");
    when(attributesMock.get(HTTP_TARGET)).thenReturn("/from/http-target");
    assertThat(getUrlPath(spanDataMock)).isEqualTo("/from/url-path");
  }

  @Test
  public void testGetUrlPath_fallsBackToHttpTarget() {
    when(attributesMock.get(URL_PATH)).thenReturn(null);
    when(attributesMock.get(HTTP_TARGET)).thenReturn("/from/http-target");
    assertThat(getUrlPath(spanDataMock)).isEqualTo("/from/http-target");
  }

  @Test
  public void testGetUrlPath_returnsNullWhenNeitherPresent() {
    when(attributesMock.get(URL_PATH)).thenReturn(null);
    when(attributesMock.get(HTTP_TARGET)).thenReturn(null);
    assertThat(getUrlPath(spanDataMock)).isNull();
  }

  // --- segmentsMatch: exact literal matching ---

  @Test
  public void testSegmentsMatch_exactMatch() {
    assertThat(matchSegments("/api/contests", "/api/contests")).isTrue();
  }

  @Test
  public void testSegmentsMatch_noMatch() {
    assertThat(matchSegments("/api/players", "/api/contests")).isFalse();
  }

  @Test
  public void testSegmentsMatch_extraUrlSegmentsAllowed() {
    assertThat(matchSegments("/api/contests/123/extra", "/api/contests")).isTrue();
  }

  @Test
  public void testSegmentsMatch_patternLongerThanUrl() {
    assertThat(matchSegments("/api", "/api/contests/{id}")).isFalse();
  }

  // --- segmentsMatch: {param} wildcard in pattern ---

  @Test
  public void testSegmentsMatch_curlyBraceMatchesLiteral() {
    assertThat(matchSegments("/api/contests/123", "/api/contests/{id}")).isTrue();
  }

  @Test
  public void testSegmentsMatch_curlyBraceMatchesDeepPath() {
    assertThat(matchSegments("/api/contests/123/leaderboard", "/api/contests/{id}/leaderboard"))
        .isTrue();
  }

  @Test
  public void testSegmentsMatch_curlyBraceDoesNotMatchEmpty() {
    assertThat(matchSegments("/api/contests/", "/api/contests/{id}")).isFalse();
  }

  // --- segmentsMatch: :param wildcard in pattern ---

  @Test
  public void testSegmentsMatch_colonParamMatchesLiteral() {
    assertThat(matchSegments("/api/users/42", "/api/users/:userId")).isTrue();
  }

  @Test
  public void testSegmentsMatch_colonParamMatchesDeepPath() {
    assertThat(matchSegments("/api/users/42/stats", "/api/users/:userId/stats")).isTrue();
  }

  @Test
  public void testSegmentsMatch_colonParamDoesNotMatchEmpty() {
    assertThat(matchSegments("/api/users/", "/api/users/:userId")).isFalse();
  }

  // --- segmentsMatch: * wildcard in pattern ---

  @Test
  public void testSegmentsMatch_trailingStarMatchesRemaining() {
    assertThat(matchSegments("/api/contests/123/anything/else", "/api/contests/*")).isTrue();
  }

  @Test
  public void testSegmentsMatch_midStarMatchesSingleSegment() {
    assertThat(matchSegments("/api/contests/123/leaderboard", "/api/contests/*/leaderboard"))
        .isTrue();
  }

  @Test
  public void testSegmentsMatch_starDoesNotMatchEmpty() {
    assertThat(matchSegments("/api/contests/", "/api/contests/*/leaderboard")).isFalse();
  }

  // --- applyOperationPathSpanName: integration tests ---

  @Test
  public void testApplyOperationPathSpanName_matchesUrlPath() {
    when(spanDataMock.getName()).thenReturn("GET /api");
    when(attributesMock.get(URL_PATH)).thenReturn("/api/contests/123/leaderboard");
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn("GET");

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(
              List.of("/api/contests/{id}/leaderboard", "/api/contests/{id}", "/api/contests"));

      SpanData result = AwsSpanProcessingUtil.applyOperationPathSpanName(spanDataMock);
      assertThat(result.getName()).isEqualTo("GET /api/contests/{id}/leaderboard");
    }
  }

  @Test
  public void testApplyOperationPathSpanName_matchesParameterizedUrl() {
    when(spanDataMock.getName()).thenReturn("GET /api/users/:userId/stats");
    when(attributesMock.get(URL_PATH)).thenReturn("/api/users/42/stats");
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn("GET");

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/api/users/{userId}/stats", "/api/users/{userId}", "/api/users"));

      SpanData result = AwsSpanProcessingUtil.applyOperationPathSpanName(spanDataMock);
      assertThat(result.getName()).isEqualTo("GET /api/users/{userId}/stats");
    }
  }

  @Test
  public void testApplyOperationPathSpanName_matchesHttpTarget() {
    when(spanDataMock.getName()).thenReturn("GET /api");
    when(attributesMock.get(URL_PATH)).thenReturn(null);
    when(attributesMock.get(HTTP_TARGET)).thenReturn("/api/teams/5?include=roster");
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn("GET");

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/api/teams/{id}", "/api/teams"));

      SpanData result = AwsSpanProcessingUtil.applyOperationPathSpanName(spanDataMock);
      assertThat(result.getName()).isEqualTo("GET /api/teams/{id}");
    }
  }

  @Test
  public void testApplyOperationPathSpanName_httpTargetWithFragment() {
    when(spanDataMock.getName()).thenReturn("GET /api");
    when(attributesMock.get(URL_PATH)).thenReturn(null);
    when(attributesMock.get(HTTP_TARGET)).thenReturn("/api/teams/5#section");
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn("GET");

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/api/teams/{id}"));

      SpanData result = AwsSpanProcessingUtil.applyOperationPathSpanName(spanDataMock);
      assertThat(result.getName()).isEqualTo("GET /api/teams/{id}");
    }
  }

  @Test
  public void testApplyOperationPathSpanName_sameLengthPatternsFirstConfigWins() {
    when(spanDataMock.getName()).thenReturn("GET /api");
    when(attributesMock.get(URL_PATH)).thenReturn("/api/v1/user1");
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn("GET");

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      // Both patterns have 3 segments — first one in config order should win
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/api/v1/{userId}", "/api/{version}/user1"));

      SpanData result = AwsSpanProcessingUtil.applyOperationPathSpanName(spanDataMock);
      assertThat(result.getName()).isEqualTo("GET /api/v1/{userId}");
    }
  }

  @Test
  public void testApplyOperationPathSpanName_noMatch_returnsOriginal() {
    when(spanDataMock.getName()).thenReturn("GET /unknown");
    when(attributesMock.get(URL_PATH)).thenReturn("/unknown/path");
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn("GET");

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/api/contests/{id}"));

      SpanData result = AwsSpanProcessingUtil.applyOperationPathSpanName(spanDataMock);
      assertThat(result).isSameAs(spanDataMock);
    }
  }

  @Test
  public void testApplyOperationPathSpanName_emptyConfig_returnsOriginal() {
    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic.when(AwsSpanProcessingUtil::getOperationPaths).thenReturn(List.of());

      SpanData result = AwsSpanProcessingUtil.applyOperationPathSpanName(spanDataMock);
      assertThat(result).isSameAs(spanDataMock);
    }
  }

  @Test
  public void testApplyOperationPathSpanName_longestMatchWins() {
    when(spanDataMock.getName()).thenReturn("GET /api");
    when(attributesMock.get(URL_PATH)).thenReturn("/api/contests/42");
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn("GET");

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      // Sorted longest first
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(
              List.of(
                  "/api/contests/{id}/leaderboard", "/api/contests/{id}", "/api/contests", "/api"));

      SpanData result = AwsSpanProcessingUtil.applyOperationPathSpanName(spanDataMock);
      assertThat(result.getName()).isEqualTo("GET /api/contests/{id}");
    }
  }

  @Test
  public void testApplyOperationPathSpanName_queryStringStripped() {
    when(spanDataMock.getName()).thenReturn("GET /api");
    when(attributesMock.get(URL_PATH)).thenReturn("/api/contests?page=1&size=10");
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn("GET");

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/api/contests"));

      SpanData result = AwsSpanProcessingUtil.applyOperationPathSpanName(spanDataMock);
      assertThat(result.getName()).isEqualTo("GET /api/contests");
    }
  }

  @Test
  public void testApplyOperationPathSpanName_noHttpMethod() {
    when(spanDataMock.getName()).thenReturn("/api");
    when(attributesMock.get(URL_PATH)).thenReturn("/api/contests");
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn(null);
    when(attributesMock.get(HTTP_METHOD)).thenReturn(null);

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/api/contests"));

      SpanData result = AwsSpanProcessingUtil.applyOperationPathSpanName(spanDataMock);
      assertThat(result.getName()).isEqualTo("/api/contests");
    }
  }

  @Test
  public void testApplyOperationPathSpanName_trailingSlashNormalized() {
    when(spanDataMock.getName()).thenReturn("GET /api");
    when(attributesMock.get(URL_PATH)).thenReturn("/api/contests/");
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn("GET");

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/api/contests"));

      SpanData result = AwsSpanProcessingUtil.applyOperationPathSpanName(spanDataMock);
      assertThat(result.getName()).isEqualTo("GET /api/contests");
    }
  }

  @Test
  public void testApplyOperationPathSpanName_patternTrailingSlashNormalized() {
    when(spanDataMock.getName()).thenReturn("GET /api");
    when(attributesMock.get(URL_PATH)).thenReturn("/api/contests");
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn("GET");

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      // Pattern has trailing slash
      utilStatic
          .when(AwsSpanProcessingUtil::getOperationPaths)
          .thenReturn(List.of("/api/contests/"));

      SpanData result = AwsSpanProcessingUtil.applyOperationPathSpanName(spanDataMock);
      // Matches, and preserves the original pattern format in the name
      assertThat(result.getName()).isEqualTo("GET /api/contests/");
    }
  }

  // --- getIngressOperation: uses span name (no longer reads operation paths directly) ---

  @Test
  public void testGetIngressOperation_validSpanName_usedDirectly() {
    when(spanDataMock.getName()).thenReturn("GET /api/contests/{id}");
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn("GET");

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic.when(AwsSpanProcessingUtil::getOperationPaths).thenReturn(List.of());

      String actual = AwsSpanProcessingUtil.getIngressOperation(spanDataMock);
      assertThat(actual).isEqualTo("GET /api/contests/{id}");
    }
  }

  @Test
  public void testGetIngressOperation_invalidSpanName_fallsBackToUrlTruncation() {
    when(spanDataMock.getName()).thenReturn("GET");
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    when(attributesMock.get(HTTP_REQUEST_METHOD)).thenReturn("GET");
    when(attributesMock.get(URL_PATH)).thenReturn("/api/contests/123");

    try (MockedStatic<AwsSpanProcessingUtil> utilStatic =
        mockStatic(AwsSpanProcessingUtil.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
      utilStatic.when(AwsSpanProcessingUtil::getOperationPaths).thenReturn(List.of());

      String actual = AwsSpanProcessingUtil.getIngressOperation(spanDataMock);
      assertThat(actual).isEqualTo("GET /api");
    }
  }
}
