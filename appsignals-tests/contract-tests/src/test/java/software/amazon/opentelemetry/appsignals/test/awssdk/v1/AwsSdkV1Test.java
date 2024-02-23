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

import io.opentelemetry.proto.common.v1.KeyValue;
import java.util.List;
import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.awssdk.base.AwsSdkBaseTest;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

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

  @Override
  protected String getS3ServiceName() {
    return "AWS.SDK.Amazon S3";
  }

  @Override
  protected String getDynamoDbServiceName() {
    return "AWS.SDK.AmazonDynamoDBv2";
  }

  @Override
  protected String getSqsServiceName() {
    return "AWS.SDK.AmazonSQS";
  }

  @Override
  protected String getKinesisServiceName() {
    return "AWS.SDK.AmazonKinesis";
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

  protected String getKinesisRpcServiceName() {
    return "AmazonKinesis";
  }

  @Test
  void testS3CreateBucket() throws Exception {
    doTestS3CreateBucket();
  }

  @Test
  void testS3CreateObject() throws Exception {
    doTestS3CreateObject();
  }

  @Test
  void testS3GetObject() throws Exception {
    doTestS3GetObject();
  }

  @Test
  void testS3Error() {
    doTestS3Error();
  }

  @Test
  void testS3Fault() {
    doTestS3Fault();
  }

  @Override
  protected List<ThrowingConsumer<KeyValue>> dynamoDbAttributes(
      String operation, String tableName) {
    return List.of(assertAttribute(SemanticConventionsConstants.AWS_TABLE_NAME, tableName));
  }

  @Test
  void testDynamoDbCreateTable() {
    doTestDynamoDbCreateTable();
  }

  @Test
  void testDynamoDbPutItem() {
    doTestDynamoDbPutItem();
  }

  @Test
  void testDynamoDbError() throws Exception {
    doTestDynamoDbError();
  }

  @Test
  void testDynamoDbFault() throws Exception {
    doTestDynamoDbFault();
  }

  @Test
  void testSQSCreateQueue() throws Exception {
    doTestSQSCreateQueue();
  }

  @Test
  void testSQSSendMessage() throws Exception {
    doTestSQSSendMessage();
  }

  @Test
  void testSQSReceiveMessage() throws Exception {
    doTestSQSReceiveMessage();
  }

  @Test
  void testSQSError() throws Exception {
    doTestSQSError();
  }

  @Test
  void testSQSFault() throws Exception {
    doTestSQSFault();
  }

  @Test
  void testKinesisPutRecord() throws Exception {
    doTestKinesisPutRecord();
  }

  @Test
  void testKinsesisError() throws Exception {
    doTestKinesisError();
  }

  @Test
  void testKinesisFault() throws Exception {
    doTestKinesisFault();
  }
}
