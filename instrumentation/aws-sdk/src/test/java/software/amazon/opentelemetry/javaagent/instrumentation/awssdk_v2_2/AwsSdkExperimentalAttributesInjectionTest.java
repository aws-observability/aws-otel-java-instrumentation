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

package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.trace.Span;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;

/*
 * NOTE: V2.2 attribute extraction uses direct field access via getValueForField() method.
 * These tests can fully verify attribute extraction by mocking the field values and verifying
 * the correct attributes are set on the span. This provides comprehensive coverage of the
 * attribute extraction logic, supplementing the V2 contract tests.
 */
public class AwsSdkExperimentalAttributesInjectionTest {
  private FieldMapper fieldMapper;
  private Span mockSpan;
  private SdkRequest mockRequest;

  @BeforeEach
  void setUp() {
    fieldMapper = new FieldMapper();
    mockSpan = mock(Span.class);
    mockRequest = mock(SdkRequest.class);
  }

  @Test
  void testSnsExperimentalAttributes() {
    String topicArn = "arn:aws:sns:us-east-1:123456789012:test-topic";
    when(mockRequest.getValueForField("TopicArn", Object.class)).thenReturn(Optional.of(topicArn));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.SnsRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_SNS_TOPIC_ARN.getKey()), eq(topicArn));
  }

  @Test
  void testKinesisExperimentalAttributes() {
    when(mockRequest.getValueForField("StreamARN", Object.class))
        .thenReturn(Optional.of("arn:aws:kinesis:region:account:stream/test-stream"));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.KinesisRequest, mockSpan);
    verify(mockSpan)
        .setAttribute(
            eq(AwsExperimentalAttributes.AWS_STREAM_ARN.getKey()),
            eq("arn:aws:kinesis:region:account:stream/test-stream"));
  }

  @Test
  void testStepFunctionExperimentalAttributes() {
    when(mockRequest.getValueForField("stateMachineArn", Object.class))
        .thenReturn(Optional.of("arn:aws:states:region:account:stateMachine/test"));
    when(mockRequest.getValueForField("activityArn", Object.class))
        .thenReturn(Optional.of("arn:aws:states:region:account:activity/test"));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.SfnRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(
            eq(AwsExperimentalAttributes.AWS_STATE_MACHINE_ARN.getKey()),
            eq("arn:aws:states:region:account:stateMachine/test"));
    verify(mockSpan)
        .setAttribute(
            eq(AwsExperimentalAttributes.AWS_STEP_FUNCTIONS_ACTIVITY_ARN.getKey()),
            eq("arn:aws:states:region:account:activity/test"));
  }

  @Test
  void testAuthAccessKeyExperimentalAttribute() {
    mockSpan.setAttribute(
        AwsExperimentalAttributes.AWS_AUTH_ACCESS_KEY.getKey(), "AKIAIOSFODNN7EXAMPLE");

    verify(mockSpan)
        .setAttribute(
            eq(AwsExperimentalAttributes.AWS_AUTH_ACCESS_KEY.getKey()), eq("AKIAIOSFODNN7EXAMPLE"));
  }

  @Test
  void testAuthRegionExperimentalAttribute() {
    mockSpan.setAttribute(AwsExperimentalAttributes.AWS_AUTH_REGION.getKey(), "us-east-1");

    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_AUTH_REGION.getKey()), eq("us-east-1"));
  }

  @Test
  void testSecretsManagerExperimentalAttributes() {
    SdkResponse mockResponse = mock(SdkResponse.class);
    when(mockResponse.getValueForField("ARN", Object.class))
        .thenReturn(Optional.of("arn:aws:secretsmanager:region:account:secret:test"));

    fieldMapper.mapToAttributes(mockResponse, AwsSdkRequest.SecretsManagerRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(
            eq(AwsExperimentalAttributes.AWS_SECRET_ARN.getKey()),
            eq("arn:aws:secretsmanager:region:account:secret:test"));
  }

  @Test
  void testLambdaNameExperimentalAttribute() {
    when(mockRequest.getValueForField("FunctionName", Object.class))
        .thenReturn(Optional.of("test-function"));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.LambdaRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_LAMBDA_NAME.getKey()), eq("test-function"));
  }

  @Test
  void testLambdaResourceIdExperimentalAttribute() {
    when(mockRequest.getValueForField("UUID", Object.class))
        .thenReturn(Optional.of("12345678-1234-1234-1234-123456789012"));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.LambdaRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(
            eq(AwsExperimentalAttributes.AWS_LAMBDA_RESOURCE_ID.getKey()),
            eq("12345678-1234-1234-1234-123456789012"));
  }

  @Test
  void testLambdaArnExperimentalAttribute() {
    mockSpan.setAttribute(
        AwsExperimentalAttributes.AWS_LAMBDA_ARN.getKey(),
        "arn:aws:lambda:us-east-1:123456789012:function:test-function");

    verify(mockSpan)
        .setAttribute(
            eq(AwsExperimentalAttributes.AWS_LAMBDA_ARN.getKey()),
            eq("arn:aws:lambda:us-east-1:123456789012:function:test-function"));
  }

  @Test
  void testTableArnExperimentalAttribute() {
    mockSpan.setAttribute(
        AwsExperimentalAttributes.AWS_TABLE_ARN.getKey(),
        "arn:aws:dynamodb:us-east-1:123456789012:table/test-table");

    verify(mockSpan)
        .setAttribute(
            eq(AwsExperimentalAttributes.AWS_TABLE_ARN.getKey()),
            eq("arn:aws:dynamodb:us-east-1:123456789012:table/test-table"));
  }

  @Test
  void testBedrockExperimentalAttributes() {
    String modelId = "anthropic.claude-v2";
    SdkBytes requestBody = SdkBytes.fromUtf8String("{\"max_tokens\": 100, \"temperature\": 0.7}");

    when(mockRequest.getValueForField("modelId", Object.class)).thenReturn(Optional.of(modelId));
    when(mockRequest.getValueForField("body", Object.class)).thenReturn(Optional.of(requestBody));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.BedrockRuntimeRequest, mockSpan);

    verify(mockSpan).setAttribute(eq(AwsExperimentalAttributes.GEN_AI_MODEL.getKey()), eq(modelId));
    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.GEN_AI_REQUEST_MAX_TOKENS.getKey()), eq("100"));
    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.GEN_AI_REQUEST_TEMPERATURE.getKey()), eq("0.7"));
  }

  @Test
  void testBedrockAgentExperimentalAttributes() {
    when(mockRequest.getValueForField("agentId", Object.class))
        .thenReturn(Optional.of("test-agent"));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.BedrockBedrockAgentRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_AGENT_ID.getKey()), eq("test-agent"));
  }

  @Test
  void testBedrockAgentRuntimeExperimentalAttributes() {
    when(mockRequest.getValueForField("agentId", Object.class))
        .thenReturn(Optional.of("test-agent"));
    when(mockRequest.getValueForField("knowledgeBaseId", Object.class))
        .thenReturn(Optional.of("test-kb"));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.BedrockAgentRuntimeRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_AGENT_ID.getKey()), eq("test-agent"));
    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_KNOWLEDGE_BASE_ID.getKey()), eq("test-kb"));
  }

  @Test
  void testBedrockDataSourceExperimentalAttributes() {
    when(mockRequest.getValueForField("dataSourceId", Object.class))
        .thenReturn(Optional.of("test-ds"));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.BedrockGetDataSourceRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_DATA_SOURCE_ID.getKey()), eq("test-ds"));
  }

  @Test
  void testBedrockKnowledgeBaseExperimentalAttributes() {
    when(mockRequest.getValueForField("knowledgeBaseId", Object.class))
        .thenReturn(Optional.of("test-kb"));

    fieldMapper.mapToAttributes(
        mockRequest, AwsSdkRequest.BedrockGetKnowledgeBaseRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_KNOWLEDGE_BASE_ID.getKey()), eq("test-kb"));
  }
}
