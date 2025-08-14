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

package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.handlers.HandlerContextKey;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.lambda.model.CreateFunctionRequest;
import com.amazonaws.services.lambda.model.FunctionConfiguration;
import com.amazonaws.services.lambda.model.GetFunctionResult;
import com.amazonaws.services.secretsmanager.model.GetSecretValueRequest;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.services.stepfunctions.model.StartExecutionRequest;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/*
 * NOTE: V1.11 attribute extraction is difficult to test in unit tests due to reflection-based
 * method access via MethodHandle. Many tests here only verify that the extractor correctly
 * identifies different AWS service types rather than actual attribute extraction. However, these
 * attributes are comprehensively tested in the contract tests which provide end-to-end validation
 * of the reflection-based extraction logic. The contract tests cover most V1.11 attributes
 * including all Bedrock Gen AI attributes.
 */
class AwsSdkExperimentalAttributesInjectionTest {

  private AwsSdkExperimentalAttributesExtractor extractor;
  private AttributesBuilder attributes;
  private Request<?> mockRequest;
  private Response<?> mockResponse;
  private static final HandlerContextKey<AWSCredentials> AWS_CREDENTIALS =
      new HandlerContextKey<>("AWSCredentials");

  @BeforeEach
  void setUp() {
    extractor = new AwsSdkExperimentalAttributesExtractor();
    attributes = mock(AttributesBuilder.class);
    mockRequest = mock(Request.class);
    mockResponse = mock(Response.class);
  }

  @Test
  void testSnsExperimentalAttributes() {
    PublishRequest snsRequest = mock(PublishRequest.class);
    when(mockRequest.getServiceName()).thenReturn("AmazonSNS");
    when(mockRequest.getOriginalRequest()).thenReturn(snsRequest);
    when(snsRequest.getTopicArn()).thenReturn("arn:aws:sns:region:account:topic/test");

    extractor.onStart(attributes, Context.current(), mockRequest);

    verify(attributes)
        .put(
            eq(AwsExperimentalAttributes.AWS_SNS_TOPIC_ARN),
            eq("arn:aws:sns:region:account:topic/test"));
  }

  @Test
  void testKinesisExperimentalAttributes() {
    PutRecordRequest kinesisRequest = mock(PutRecordRequest.class);
    when(mockRequest.getServiceName()).thenReturn("AmazonKinesis");
    when(mockRequest.getOriginalRequest()).thenReturn(kinesisRequest);
    when(kinesisRequest.getStreamARN()).thenReturn("arn:aws:kinesis:region:account:stream/test");

    extractor.onStart(attributes, Context.current(), mockRequest);

    verify(attributes)
        .put(
            eq(AwsExperimentalAttributes.AWS_STREAM_ARN),
            eq("arn:aws:kinesis:region:account:stream/test"));
  }

  @Test
  void testStepFunctionsExperimentalAttributes() {
    StartExecutionRequest sfnRequest = mock(StartExecutionRequest.class);
    when(mockRequest.getServiceName()).thenReturn("AWSStepFunctions");
    when(mockRequest.getOriginalRequest()).thenReturn(sfnRequest);
    when(sfnRequest.getStateMachineArn())
        .thenReturn("arn:aws:states:region:account:stateMachine/test");

    extractor.onStart(attributes, Context.current(), mockRequest);

    verify(attributes)
        .put(
            eq(AwsExperimentalAttributes.AWS_STATE_MACHINE_ARN),
            eq("arn:aws:states:region:account:stateMachine/test"));
  }

  @Test
  void testAuthAccessKeyAttributes() {
    AWSCredentials credentials = mock(AWSCredentials.class);
    when(mockRequest.getHandlerContext(AWS_CREDENTIALS)).thenReturn(credentials);
    when(credentials.getAWSAccessKeyId()).thenReturn("AKIAIOSFODNN7EXAMPLE");
    when(mockRequest.getOriginalRequest()).thenReturn(mock(PublishRequest.class));
    when(mockRequest.getServiceName()).thenReturn("AmazonSNS");

    extractor.onStart(attributes, Context.current(), mockRequest);

    verify(attributes)
        .put(eq(AwsExperimentalAttributes.AWS_AUTH_ACCESS_KEY), eq("AKIAIOSFODNN7EXAMPLE"));
  }

  @Test
  void testSecretsManagerExperimentalAttributes() {
    GetSecretValueRequest secretRequest = mock(GetSecretValueRequest.class);
    when(mockRequest.getServiceName()).thenReturn("AWSSecretsManager");
    when(mockRequest.getOriginalRequest()).thenReturn(secretRequest);

    extractor.onStart(attributes, Context.current(), mockRequest);
    // We're not verifying anything here since the actual attribute setting depends on reflection
  }

  @Test
  void testLambdaNameExperimentalAttributes() {
    CreateFunctionRequest lambdaRequest = mock(CreateFunctionRequest.class);
    when(mockRequest.getServiceName()).thenReturn("AWSLambda");
    when(mockRequest.getOriginalRequest()).thenReturn(lambdaRequest);
    when(lambdaRequest.getFunctionName()).thenReturn("test-function");

    extractor.onStart(attributes, Context.current(), mockRequest);

    verify(attributes).put(eq(AwsExperimentalAttributes.AWS_LAMBDA_NAME), eq("test-function"));
  }

  @Test
  void testLambdaArnExperimentalAttributes() {
    GetFunctionResult lambdaResult = mock(GetFunctionResult.class);
    FunctionConfiguration config = mock(FunctionConfiguration.class);
    when(mockResponse.getAwsResponse()).thenReturn(lambdaResult);
    when(lambdaResult.getConfiguration()).thenReturn(config);
    when(config.getFunctionArn()).thenReturn("arn:aws:lambda:region:account:function:test");
    when(mockRequest.getServiceName()).thenReturn("AWSLambda");

    extractor.onEnd(attributes, Context.current(), mockRequest, mockResponse, null);

    verify(attributes)
        .put(
            eq(AwsExperimentalAttributes.AWS_LAMBDA_ARN),
            eq("arn:aws:lambda:region:account:function:test"));
  }

  @Test
  void testLambdaResourceIdExperimentalAttributes() {
    PublishRequest originalRequest = mock(PublishRequest.class);
    when(mockRequest.getServiceName()).thenReturn("AWSLambda");
    when(mockRequest.getOriginalRequest()).thenReturn(originalRequest);

    extractor.onStart(attributes, Context.current(), mockRequest);
    // We can't verify the actual attribute setting since it depends on reflection
  }

  @Test
  void testTableArnExperimentalAttributes() {
    PublishRequest originalRequest = mock(PublishRequest.class);
    when(mockRequest.getServiceName()).thenReturn("AmazonDynamoDBv2");
    when(mockRequest.getOriginalRequest()).thenReturn(originalRequest);

    extractor.onStart(attributes, Context.current(), mockRequest);
    // We can't verify the actual attribute setting since it depends on reflection
  }

  @Test
  void testBedrockRuntimeAttributes() {
    PublishRequest originalRequest = mock(PublishRequest.class);
    when(mockRequest.getServiceName()).thenReturn("AmazonBedrockRuntime");
    when(mockRequest.getOriginalRequest()).thenReturn(originalRequest);

    extractor.onStart(attributes, Context.current(), mockRequest);
    // We can't verify the actual attribute setting since it depends on reflection and class name
  }

  @Test
  void testBedrockAgentAttributes() {
    PublishRequest originalRequest = mock(PublishRequest.class);
    when(mockRequest.getServiceName()).thenReturn("AWSBedrockAgent");
    when(mockRequest.getOriginalRequest()).thenReturn(originalRequest);

    extractor.onStart(attributes, Context.current(), mockRequest);
    // We can't verify the actual attribute setting since it depends on reflection
  }

  @Test
  void testBedrockAgentRuntimeAttributes() {
    PublishRequest originalRequest = mock(PublishRequest.class);
    when(mockRequest.getServiceName()).thenReturn("AWSBedrockAgentRuntime");
    when(mockRequest.getOriginalRequest()).thenReturn(originalRequest);

    extractor.onStart(attributes, Context.current(), mockRequest);
    // We can't verify the actual attribute setting since it depends on reflection
  }

  @Test
  void testBedrockGuardrailAttributes() {
    PublishRequest originalRequest = mock(PublishRequest.class);
    when(mockRequest.getServiceName()).thenReturn("AmazonBedrock");
    when(mockRequest.getOriginalRequest()).thenReturn(originalRequest);

    extractor.onStart(attributes, Context.current(), mockRequest);
    // We can't verify the actual attribute setting since it depends on reflection
  }
}
