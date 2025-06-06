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

package software.amazon.opentelemetry.appsignals.test.awssdk.v1;

import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.awssdk.base.AwsSdkBaseTest;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AwsSdkV1Test extends AwsSdkBaseTest {

  @Override
  protected String getApplicationImageName() {
    return "aws-appsignals-tests-aws-sdk-v1";
  }

  protected String getApplicationOtelServiceName() {
    return "aws-sdk-v1";
  }

  @Override
  protected String getS3SpanNamePrefix() {
    return "S3";
  }

  @Override
  protected String getDynamoDbSpanNamePrefix() {
    return "DynamoDBv2";
  }

  @Override
  protected String getSqsSpanNamePrefix() {
    return "SQS";
  }

  @Override
  protected String getKinesisSpanNamePrefix() {
    return "Kinesis";
  }

  protected String getBedrockSpanNamePrefix() {
    return "Bedrock";
  }

  @Override
  protected String getBedrockAgentSpanNamePrefix() {
    return "AWSBedrockAgent";
  }

  @Override
  protected String getBedrockRuntimeSpanNamePrefix() {
    return "BedrockRuntime";
  }

  @Override
  protected String getBedrockAgentRuntimeSpanNamePrefix() {
    return "AWSBedrockAgentRuntime";
  }

  @Override
  protected String getSecretsManagerSpanNamePrefix() {
    return "AWSSecretsManager";
  }

  @Override
  protected String getStepFunctionsSpanNamePrefix() {
    return "AWSStepFunctions";
  }

  @Override
  protected String getSnsSpanNamePrefix() {
    return "SNS";
  }

  protected String getS3RpcServiceName() {
    return "Amazon S3";
  }

  @Override
  protected String getDynamoDbRpcServiceName() {
    return "AmazonDynamoDBv2";
  }

  @Override
  protected String getSqsRpcServiceName() {
    return "AmazonSQS";
  }

  @Override
  protected String getSecretsManagerRpcServiceName() {
    return "AWSSecretsManager";
  }

  @Override
  protected String getStepFunctionsRpcServiceName() {
    return "AWSStepFunctions";
  }

  @Override
  protected String getSnsRpcServiceName() {
    return "AmazonSNS";
  }

  protected String getKinesisRpcServiceName() {
    return "AmazonKinesis";
  }

  protected String getBedrockRpcServiceName() {
    return "AmazonBedrock";
  }

  protected String getBedrockAgentRpcServiceName() {
    return "AWSBedrockAgent";
  }

  protected String getBedrockRuntimeRpcServiceName() {
    return "AmazonBedrockRuntime";
  }

  protected String getBedrockAgentRuntimeRpcServiceName() {
    return "AWSBedrockAgentRuntime";
  }

  //  @Test
  //  void testS3CreateBucket() throws Exception {
  //    doTestS3CreateBucket();
  //  }
  //
  //  @Test
  //  void testS3CreateObject() throws Exception {
  //    doTestS3CreateObject();
  //  }
  //
  //  @Test
  //  void testS3GetObject() throws Exception {
  //    doTestS3GetObject();
  //  }
  //
  //  @Test
  //  void testS3Error() {
  //    doTestS3Error();
  //  }
  //
  //  @Test
  //  void testS3Fault() {
  //    doTestS3Fault();
  //  }
  //
  //  @Override
  //  protected List<ThrowingConsumer<KeyValue>> dynamoDbAttributes(
  //      String operation, String tableName) {
  //    return List.of(assertAttribute(SemanticConventionsConstants.AWS_TABLE_NAME, tableName));
  //  }
  //
  //  @Test
  //  void testDynamoDbCreateTable() {
  //    doTestDynamoDbCreateTable();
  //  }
  //
  //  @Test
  //  void testDynamoDbPutItem() {
  //    doTestDynamoDbPutItem();
  //  }
  //
  //  @Test
  //  void testDynamoDbError() throws Exception {
  //    doTestDynamoDbError();
  //  }
  //
  //  @Test
  //  void testDynamoDbFault() throws Exception {
  //    doTestDynamoDbFault();
  //  }
  //
  //  @Test
  //  void testSQSCreateQueue() throws Exception {
  //    doTestSQSCreateQueue();
  //  }
  //
  //  @Test
  //  void testSQSSendMessage() throws Exception {
  //    doTestSQSSendMessage();
  //  }
  //
  //  @Test
  //  void testSQSReceiveMessage() throws Exception {
  //    doTestSQSReceiveMessage();
  //  }
  //
  //  @Test
  //  void testSQSError() throws Exception {
  //    doTestSQSError();
  //  }
  //
  //  @Test
  //  void testSQSFault() throws Exception {
  //    doTestSQSFault();
  //  }
  //
  //  @Test
  //  void testKinesisPutRecord() throws Exception {
  //    doTestKinesisPutRecord();
  //  }
  //
  //  @Test
  //  void testKinsesisError() throws Exception {
  //    doTestKinesisError();
  //  }
  //
  //  @Test
  //  void testKinesisFault() throws Exception {
  //    doTestKinesisFault();
  //  }
  //
  //  @Test
  //  void testBedrockAgentGetKnowledgeBaseId() {
  //    doTestBedrockAgentKnowledgeBaseId();
  //  }
  //
  //  @Test
  //  void testBedrockAgentAgentId() {
  //    doTestBedrockAgentAgentId();
  //  }
  //
  //  @Test
  //  void testBedrockAgentDataSourceId() {
  //    doTestBedrockAgentDataSourceId();
  //  }
  //
  //  //  @Test
  //  //  void testBedrockRuntimeAmazonTitan() {
  //  //    doTestBedrockRuntimeAmazonTitan();
  //  //  }
  //  //
  //  //  @Test
  //  //  void testBedrockRuntimeAi21Jamba() {
  //  //    doTestBedrockRuntimeAi21Jamba();
  //  //  }
  //  //
  //  //  @Test
  //  //  void testBedrockRuntimeAnthropicClaude() {
  //  //    doTestBedrockRuntimeAnthropicClaude();
  //  //  }
  //  //
  //  //  @Test
  //  //  void testBedrockRuntimeCohereCommandR() {
  //  //    doTestBedrockRuntimeCohereCommandR();
  //  //  }
  //  //
  //  //  @Test
  //  //  void testBedrockRuntimeMetaLlama() {
  //  //    doTestBedrockRuntimeMetaLlama();
  //  //  }
  //  //
  //  //  @Test
  //  //  void testBedrockRuntimeMistral() {
  //  //    doTestBedrockRuntimeMistral();
  //  //  }
  //
  //  @Test
  //  void testBedrockGuardrailId() {
  //    doTestBedrockGuardrailId();
  //  }
  //
  //  @Test
  //  void testBedrockAgentRuntimeAgentId() {
  //    doTestBedrockAgentRuntimeAgentId();
  //  }
  //
  //  @Test
  //  void testBedrockAgentRuntimeKnowledgeBaseId() {
  //    doTestBedrockAgentRuntimeKnowledgeBaseId();
  //  }
  //
  //  @Test
  //  void testSecretsManagerDescribeSecret() throws Exception {
  //    doTestSecretsManagerDescribeSecret();
  //  }
  //
  //  @Test
  //  void testSecretsManagerError() throws Exception {
  //    doTestSecretsManagerError();
  //  }
  //
  //  @Test
  //  void testSecretsManagerFault() throws Exception {
  //    doTestSecretsManagerFault();
  //  }
  //
  //  @Test
  //  void testStepFunctionsDescribeStateMachine() throws Exception {
  //    doTestStepFunctionsDescribeStateMachine();
  //  }
  //
  //  @Test
  //  void testStepFunctionsDescribeActivity() throws Exception {
  //    doTestStepFunctionsDescribeActivity();
  //  }
  //
  //  @Test
  //  void testStepFunctionsError() throws Exception {
  //    doTestStepFunctionsError();
  //  }
  //
  //  @Test
  //  void testStepFunctionsFault() throws Exception {
  //    doTestStepFunctionsFault();
  //  }
  //
  //  @Test
  //  void testSnsGetTopicAttributes() throws Exception {
  //    doTestSnsGetTopicAttributes();
  //  }
  //
  //  @Test
  //  void testSnsError() throws Exception {
  //    doTestStepFunctionsError();
  //  }
  //
  //  @Test
  //  void testSnsFault() throws Exception {
  //    doTestStepFunctionsFault();
  //  }
}
