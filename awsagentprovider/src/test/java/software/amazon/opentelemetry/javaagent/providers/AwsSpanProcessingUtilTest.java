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

import static io.opentelemetry.semconv.SemanticAttributes.*;
import static io.opentelemetry.semconv.SemanticAttributes.MessagingOperationValues.PROCESS;
import static io.opentelemetry.semconv.SemanticAttributes.MessagingOperationValues.RECEIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.MAX_KEYWORD_LENGTH;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.getDialectKeywords;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    when(attributesMock.get(HTTP_METHOD)).thenReturn(invalidName);
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
    when(attributesMock.get(HTTP_TARGET)).thenReturn(validTarget);
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
    when(attributesMock.get(HTTP_TARGET)).thenReturn(validTarget);
    when(attributesMock.get(HTTP_METHOD)).thenReturn(validMethod);
    String actualOperation = AwsSpanProcessingUtil.getIngressOperation(spanDataMock);
    assertThat(actualOperation).isEqualTo(validMethod + " " + validTarget);
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
    when(attributesMock.get(HTTP_TARGET)).thenReturn("target");
    assertThat(AwsSpanProcessingUtil.isKeyPresent(spanDataMock, HTTP_TARGET)).isTrue();
  }

  @Test
  public void testIsKeyPresentKeyAbsent() {
    assertThat(AwsSpanProcessingUtil.isKeyPresent(spanDataMock, HTTP_TARGET)).isFalse();
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
  public void testIsConsumerProcessSpanTrue() {
    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn(PROCESS);
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
}
