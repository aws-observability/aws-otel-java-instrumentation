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
  void testS3ExperimentalAttributes() {
    when(mockRequest.getValueForField("Bucket", Object.class))
        .thenReturn(Optional.of("test-bucket"));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.S3Request, mockSpan);

    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_BUCKET_NAME.getKey()), eq("test-bucket"));
  }

  @Test
  void testSqsExperimentalAttributes() {
    String queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue";
    when(mockRequest.getValueForField("QueueUrl", Object.class)).thenReturn(Optional.of(queueUrl));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.SqsRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_QUEUE_URL.getKey()), eq(queueUrl));
  }

  @Test
  void testDynamoDbExperimentalAttributes() {
    when(mockRequest.getValueForField("TableName", Object.class))
        .thenReturn(Optional.of("test-table"));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.DynamoDbRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_TABLE_NAME.getKey()), eq("test-table"));
  }

  @Test
  void testLambdaExperimentalAttributes() {
    when(mockRequest.getValueForField("FunctionName", Object.class))
        .thenReturn(Optional.of("test-function"));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.LambdaRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_LAMBDA_NAME.getKey()), eq("test-function"));
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
    when(mockRequest.getValueForField("StreamName", Object.class))
        .thenReturn(Optional.of("test-stream"));
    when(mockRequest.getValueForField("StreamARN", Object.class))
        .thenReturn(Optional.of("arn:aws:kinesis:region:account:stream/test-stream"));

    fieldMapper.mapToAttributes(mockRequest, AwsSdkRequest.KinesisRequest, mockSpan);

    verify(mockSpan)
        .setAttribute(eq(AwsExperimentalAttributes.AWS_STREAM_NAME.getKey()), eq("test-stream"));
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
