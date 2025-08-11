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

package com.amazon.sampleapp;

import static spark.Spark.awaitInitialization;
import static spark.Spark.get;
import static spark.Spark.ipAddress;
import static spark.Spark.port;
import static spark.Spark.post;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.bedrock.AmazonBedrockClient;
import com.amazonaws.services.bedrock.model.GetGuardrailRequest;
import com.amazonaws.services.bedrockagent.AWSBedrockAgentClient;
import com.amazonaws.services.bedrockagent.model.GetAgentRequest;
import com.amazonaws.services.bedrockagent.model.GetDataSourceRequest;
import com.amazonaws.services.bedrockagent.model.GetKnowledgeBaseRequest;
import com.amazonaws.services.bedrockagentruntime.AWSBedrockAgentRuntimeClient;
import com.amazonaws.services.bedrockagentruntime.model.GetAgentMemoryRequest;
import com.amazonaws.services.bedrockagentruntime.model.RetrieveRequest;
import com.amazonaws.services.bedrockruntime.AmazonBedrockRuntimeClient;
import com.amazonaws.services.bedrockruntime.model.InvokeModelRequest;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient;
import com.amazonaws.services.identitymanagement.model.CreateRoleRequest;
import com.amazonaws.services.identitymanagement.model.PutRolePolicyRequest;
import com.amazonaws.services.kinesis.AmazonKinesisClient;
import com.amazonaws.services.kinesis.model.CreateStreamRequest;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CreateBucketRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.Region;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClient;
import com.amazonaws.services.secretsmanager.model.CreateSecretRequest;
import com.amazonaws.services.secretsmanager.model.DescribeSecretRequest;
import com.amazonaws.services.secretsmanager.model.ListSecretsRequest;
import com.amazonaws.services.secretsmanager.model.SecretListEntry;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.CreateTopicRequest;
import com.amazonaws.services.sns.model.GetTopicAttributesRequest;
import com.amazonaws.services.sns.model.ListTopicsRequest;
import com.amazonaws.services.sns.model.Topic;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.stepfunctions.AWSStepFunctionsClient;
import com.amazonaws.services.stepfunctions.model.ActivityListItem;
import com.amazonaws.services.stepfunctions.model.CreateActivityRequest;
import com.amazonaws.services.stepfunctions.model.CreateStateMachineRequest;
import com.amazonaws.services.stepfunctions.model.DescribeActivityRequest;
import com.amazonaws.services.stepfunctions.model.DescribeStateMachineRequest;
import com.amazonaws.services.stepfunctions.model.ListActivitiesRequest;
import com.amazonaws.services.stepfunctions.model.ListStateMachinesRequest;
import com.amazonaws.services.stepfunctions.model.StateMachineListItem;
import com.amazonaws.services.stepfunctions.model.StateMachineType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {

  static final Logger logger = LoggerFactory.getLogger(App.class);
  private static final HttpClient httpClient =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  private static final AWSStaticCredentialsProvider CREDENTIALS_PROVIDER =
      new AWSStaticCredentialsProvider(new BasicAWSCredentials("foo", "bar"));

  static int mainStatus = 200;

  static void setMainStatus(int status) {
    mainStatus = status;
  }

  private static final String s3Endpoint =
      System.getenv().getOrDefault("AWS_SDK_S3_ENDPOINT", "http://s3.localhost:8080");
  private static final EndpointConfiguration s3EndpointConfiguration =
      new EndpointConfiguration(s3Endpoint, Regions.US_WEST_2.getName());

  private static final String endpoint =
      System.getenv().getOrDefault("AWS_SDK_ENDPOINT", "http://s3.localhost:8080");
  private static final EndpointConfiguration endpointConfiguration =
      new EndpointConfiguration(endpoint, Regions.US_WEST_2.getName());

  public static void main(String[] args) throws IOException, InterruptedException {

    port(Integer.parseInt("8080"));
    ipAddress("0.0.0.0");
    get(
        "/:object",
        (req, res) -> {
          res.status(mainStatus);
          return res;
        });
    get(
        "/",
        (req, res) -> {
          res.status(mainStatus);
          return res;
        });
    post(
        "/",
        (req, res) -> {
          res.status(mainStatus);
          return res;
        });

    setupDynamoDb();
    setupS3();
    setupSqs();
    setupKinesis();
    setupSecretsManager();
    setupStepFunctions();
    setupSns();
    setupBedrock();

    // Add this log line so that we only start testing after all routes are configured.
    awaitInitialization();
    logger.info("All routes initialized");
  }

  private static void setupSqs() {
    var sqsClient =
        AmazonSQSClient.builder()
            .withCredentials(CREDENTIALS_PROVIDER)
            .withEndpointConfiguration(endpointConfiguration)
            .build();

    get(
        "/sqs/createqueue/:queuename",
        (req, res) -> {
          var queueName = req.params(":queuename");
          var createQueueRequest = new CreateQueueRequest(queueName);
          sqsClient.createQueue(createQueueRequest);
          return "";
        });
    get(
        "/sqs/publishqueue/:queuename",
        (req, res) -> {
          var queueName = req.params(":queuename");
          var createQueueRequest = new CreateQueueRequest(queueName);
          var response = sqsClient.createQueue(createQueueRequest);

          var sendMessageRequest =
              new SendMessageRequest().withMessageBody("test").withQueueUrl(response.getQueueUrl());

          sqsClient.sendMessage(sendMessageRequest);
          return response.getQueueUrl();
        });

    get(
        "/sqs/consumequeue/:queuename",
        (req, res) -> {
          var queueName = req.params(":queuename");
          var createQueueRequest = new CreateQueueRequest(queueName);
          var response = sqsClient.createQueue(createQueueRequest);

          var sendMessageRequest =
              new SendMessageRequest().withMessageBody("test").withQueueUrl(response.getQueueUrl());

          sqsClient.sendMessage(sendMessageRequest);

          var readMessageRequest = new ReceiveMessageRequest(response.getQueueUrl());
          var messages = sqsClient.receiveMessage(readMessageRequest).getMessages();
          logger.info("Message Received: ", messages.toString());
          return response.getQueueUrl();
        });

    get(
        "/sqs/error",
        (req, res) -> {
          setMainStatus(400);
          var errorClient =
              AmazonSQSClient.builder()
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .withEndpointConfiguration(
                      new EndpointConfiguration(
                          "http://error.test:8080", Regions.US_WEST_2.getName()))
                  .build();

          var sendMessageRequest =
              new SendMessageRequest()
                  .withMessageBody("error")
                  .withQueueUrl("http://error.test:8080");
          try {
            errorClient.sendMessage(sendMessageRequest);
          } catch (Exception ex) {
            logger.info("Exception Caught in Sample App");
            ex.printStackTrace();
          }
          return "";
        });

    get(
        "/sqs/fault",
        (req, res) -> {
          setMainStatus(500);
          var errorClient =
              AmazonSQSClient.builder()
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .withEndpointConfiguration(
                      new EndpointConfiguration(
                          "http://fault.test:8080", Regions.US_WEST_2.getName()))
                  .build();

          var sendMessageRequest =
              new SendMessageRequest()
                  .withMessageBody("error")
                  .withQueueUrl("http://fault.test:8080");
          try {
            errorClient.sendMessage(sendMessageRequest);
          } catch (Exception ex) {
          }
          return "";
        });
  }

  private static void setupDynamoDb() {
    var dynamoDbClient =
        AmazonDynamoDBClient.builder()
            .withCredentials(CREDENTIALS_PROVIDER)
            .withEndpointConfiguration(endpointConfiguration)
            .build();

    get(
        "/ddb/createtable/:tablename",
        (req, res) -> {
          var tableName = req.params(":tablename");

          var createTableRequest =
              new CreateTableRequest()
                  .withTableName(tableName)
                  .withAttributeDefinitions(
                      new AttributeDefinition()
                          .withAttributeName("partitionKey")
                          .withAttributeType("S"))
                  .withKeySchema(
                      new KeySchemaElement()
                          .withAttributeName("partitionKey")
                          .withKeyType(KeyType.HASH))
                  .withProvisionedThroughput(
                      new ProvisionedThroughput()
                          .withReadCapacityUnits(1L)
                          .withWriteCapacityUnits(1L));
          dynamoDbClient.createTable(createTableRequest);
          return "";
        });
    //
    get(
        "/ddb/putitem/:tablename/:partitionkey",
        (req, res) -> {
          var partitionKey = req.params(":partitionkey");
          var tableName = req.params(":tablename");

          var createTableRequest =
              new CreateTableRequest()
                  .withTableName(tableName)
                  .withAttributeDefinitions(
                      new AttributeDefinition()
                          .withAttributeName(partitionKey)
                          .withAttributeType("S"))
                  .withKeySchema(
                      new KeySchemaElement()
                          .withAttributeName(partitionKey)
                          .withKeyType(KeyType.HASH))
                  .withProvisionedThroughput(
                      new ProvisionedThroughput()
                          .withReadCapacityUnits(1L)
                          .withWriteCapacityUnits(1L));
          dynamoDbClient.createTable(createTableRequest);

          var item =
              Map.of(
                  partitionKey,
                  new AttributeValue("value"),
                  "otherAttribute",
                  new AttributeValue("value"));

          var putItemRequest = new PutItemRequest(tableName, item);

          dynamoDbClient.putItem(putItemRequest);
          return "";
        });
    get(
        "/ddb/error",
        (req, res) -> {
          setMainStatus(400);
          var errorClient =
              AmazonDynamoDBClient.builder()
                  .withEndpointConfiguration(
                      new EndpointConfiguration(
                          "http://error.test:8080", Regions.US_WEST_2.getName()))
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .build();

          try {
            var putItemRequest =
                new PutItemRequest(
                    "nonexistanttable", Map.of("partitionKey", new AttributeValue("value")));
            errorClient.putItem(putItemRequest);
          } catch (Exception ex) {
          }
          return "";
        });

    get(
        "/ddb/fault",
        (req, res) -> {
          setMainStatus(500);
          var faultClient =
              AmazonDynamoDBClient.builder()
                  .withEndpointConfiguration(
                      new EndpointConfiguration(
                          "http://fault.test:8080", Regions.US_WEST_2.getName()))
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .build();

          try {
            var putItemRequest =
                new PutItemRequest(
                    "nonexistanttable", Map.of("partitionKey", new AttributeValue("value")));
            faultClient.putItem(putItemRequest);
          } catch (Exception ex) {
          }
          return "";
        });
  }

  private static void setupKinesis() {
    get(
        "/kinesis/putrecord/:streamname",
        (req, res) -> {
          var streamName = req.params(":streamname");

          var kinesisClient =
              AmazonKinesisClient.builder()
                  .withEndpointConfiguration(endpointConfiguration)
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .build();

          var createStreamRequest = new CreateStreamRequest();
          createStreamRequest.setStreamName(streamName);

          kinesisClient.createStream(createStreamRequest);

          byte[] data = {0x0, 0x1};
          var trials = 5;
          // reference:
          // https://docs.aws.amazon.com/streams/latest/dev/kinesis-using-sdk-java-create-stream.html#kinesis-using-sdk-java-create-the-stream
          for (int i = 0; i < trials; i++) {
            try {
              Thread.sleep(1000);
            } catch (Exception ex) {
            }
            var streamDescription = kinesisClient.describeStream(streamName);
            if (streamDescription.getStreamDescription().getStreamStatus().equals("ACTIVE")) {
              break;
            }
          }
          var putRecordRequest = new PutRecordRequest();

          putRecordRequest.setStreamName(streamName);
          putRecordRequest.setData(ByteBuffer.wrap(data));
          putRecordRequest.setPartitionKey("key");
          kinesisClient.putRecord(putRecordRequest);
          return "";
        });

    get(
        "/kinesis/error",
        (req, res) -> {
          setMainStatus(400);
          var kinesisClient =
              AmazonKinesisClient.builder()
                  .withEndpointConfiguration(
                      new EndpointConfiguration(
                          "http://error.test:8080", Regions.US_WEST_2.getName()))
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .build();

          var streamName = "nonexistantstream";
          var partitionKey = "key";
          byte[] data = {0x0, 0x1};
          var putRecordRequest = new PutRecordRequest();

          putRecordRequest.setStreamName(streamName);
          putRecordRequest.setData(ByteBuffer.wrap(data));
          putRecordRequest.setPartitionKey(partitionKey);

          kinesisClient.putRecord(putRecordRequest);
          return "";
        });

    get(
        "/kinesis/fault",
        (req, res) -> {
          setMainStatus(500);
          var kinesisClient =
              AmazonKinesisClient.builder()
                  .withEndpointConfiguration(
                      new EndpointConfiguration(
                          "http://fault.test:8080", Regions.US_WEST_2.getName()))
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .build();

          var streamName = "faultstream";
          var shardId = "key";
          byte[] data = {0x0, 0x1};
          var putRecordRequest = new PutRecordRequest();

          putRecordRequest.setStreamName(streamName);
          putRecordRequest.setData(ByteBuffer.wrap(data));
          putRecordRequest.setPartitionKey(shardId);

          kinesisClient.putRecord(putRecordRequest);
          return "";
        });
  }

  private static void setupS3() {
    var s3Client =
        AmazonS3Client.builder()
            .withCredentials(CREDENTIALS_PROVIDER)
            .withEndpointConfiguration(
                new EndpointConfiguration(s3Endpoint, Regions.US_WEST_2.getName()))
            .build();

    get(
        "/s3/createbucket/:bucketname",
        (req, res) -> {
          String bucketName = req.params(":bucketname");
          CreateBucketRequest createBucketRequest =
              new CreateBucketRequest(bucketName, Region.US_West_2);
          var response = s3Client.createBucket(createBucketRequest);
          return "";
        });

    get(
        "/s3/createobject/:bucketname/:objectname",
        (req, res) -> {
          String objectName = req.params(":objectname");
          String bucketName = req.params(":bucketname");
          CreateBucketRequest createBucketRequest =
              new CreateBucketRequest(bucketName, Region.US_West_2);
          var response = s3Client.createBucket(createBucketRequest);
          var tempfile = File.createTempFile("foo", "bar");
          var putObjectRequest = new PutObjectRequest(bucketName, objectName, tempfile);
          s3Client.putObject(putObjectRequest);

          return "";
        });

    get(
        "/s3/getobject/:bucketName/:objectname",
        (req, res) -> {
          var objectName = req.params(":objectname");
          var bucketName = req.params(":bucketName");
          CreateBucketRequest createBucketRequest =
              new CreateBucketRequest(bucketName, Region.US_West_2);
          var response = s3Client.createBucket(createBucketRequest);
          var tempfile = File.createTempFile("foo", "bar");
          var putObjectRequest = new PutObjectRequest(bucketName, objectName, tempfile);
          s3Client.putObject(putObjectRequest);
          var getObjectRequest = new GetObjectRequest(bucketName, objectName);
          var object = s3Client.getObject(getObjectRequest);
          return "";
        });
    get(
        "/s3/error",
        (req, res) -> {
          setMainStatus(400);
          var errorClient =
              AmazonS3Client.builder()
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .withEndpointConfiguration(
                      new EndpointConfiguration("http://s3.test:8080", Regions.US_WEST_2.getName()))
                  .build();

          try {
            var response =
                errorClient.getObject(new GetObjectRequest("error-bucket", "error-object"));
          } catch (Exception e) {

          }
          return "";
        });
    get(
        "/s3/fault",
        (req, res) -> {
          setMainStatus(500);
          var faultClient =
              AmazonS3Client.builder()
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .withEndpointConfiguration(
                      new EndpointConfiguration("http://s3.test:8080", Regions.US_WEST_2.getName()))
                  .build();

          try {
            var response =
                faultClient.getObject(new GetObjectRequest("fault-bucket", "fault-object"));
          } catch (Exception e) {

          }
          return "";
        });
  }

  private static void setupSecretsManager() {
    var secretsManagerClient =
        AWSSecretsManagerClient.builder()
            .withCredentials(CREDENTIALS_PROVIDER)
            .withEndpointConfiguration(endpointConfiguration)
            .build();
    var secretName = "test-secret-id";
    String existingSecretArn = null;
    try {
      var listRequest = new ListSecretsRequest();
      var listResponse = secretsManagerClient.listSecrets(listRequest);
      existingSecretArn =
          listResponse.getSecretList().stream()
              .filter(secret -> secret.getName().contains(secretName))
              .findFirst()
              .map(SecretListEntry::getARN)
              .orElse(null);
    } catch (Exception e) {
      logger.error("Error listing secrets", e);
    }

    if (existingSecretArn != null) {
      logger.debug("Secret already exists, skipping creation");
    } else {
      logger.info("Secret not found, creating new one");
      var createSecretRequest = new CreateSecretRequest().withName(secretName);
      var createSecretResponse = secretsManagerClient.createSecret(createSecretRequest);
      existingSecretArn = createSecretResponse.getARN();
    }

    String finalExistingSecretArn = existingSecretArn;
    get(
        "/secretsmanager/describesecret/:secretId",
        (req, res) -> {
          var describeRequest = new DescribeSecretRequest().withSecretId(finalExistingSecretArn);
          secretsManagerClient.describeSecret(describeRequest);
          return "";
        });

    get(
        "/secretsmanager/error",
        (req, res) -> {
          setMainStatus(400);
          var errorClient =
              AWSSecretsManagerClient.builder()
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .withEndpointConfiguration(
                      new EndpointConfiguration(
                          "http://error.test:8080", Regions.US_WEST_2.getName()))
                  .build();

          try {
            var describeRequest =
                new DescribeSecretRequest()
                    .withSecretId(
                        "arn:aws:secretsmanager:us-west-2:000000000000:secret:nonexistent-secret-id");
            errorClient.describeSecret(describeRequest);
          } catch (Exception e) {
            logger.debug("Error describing secret", e);
          }
          return "";
        });

    get(
        "/secretsmanager/fault",
        (req, res) -> {
          setMainStatus(500);
          var faultClient =
              AWSSecretsManagerClient.builder()
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .withEndpointConfiguration(
                      new EndpointConfiguration(
                          "http://fault.test:8080", Regions.US_WEST_2.getName()))
                  .build();

          try {
            var describeRequest =
                new DescribeSecretRequest()
                    .withSecretId(
                        "arn:aws:secretsmanager:us-west-2:000000000000:secret:fault-secret-id");
            faultClient.describeSecret(describeRequest);
          } catch (Exception e) {
            logger.debug("Error describing secret", e);
          }
          return "";
        });
  }

  private static void setupStepFunctions() {
    var stepFunctionsClient =
        AWSStepFunctionsClient.builder()
            .withCredentials(CREDENTIALS_PROVIDER)
            .withEndpointConfiguration(endpointConfiguration)
            .build();
    var iamClient =
        AmazonIdentityManagementClient.builder()
            .withCredentials(CREDENTIALS_PROVIDER)
            .withEndpointConfiguration(endpointConfiguration)
            .build();

    var sfnName = "test-state-machine";
    String existingStateMachineArn = null;
    try {
      var listRequest = new ListStateMachinesRequest();
      var listResponse = stepFunctionsClient.listStateMachines(listRequest);
      existingStateMachineArn =
          listResponse.getStateMachines().stream()
              .filter(machine -> machine.getName().equals(sfnName))
              .findFirst()
              .map(StateMachineListItem::getStateMachineArn)
              .orElse(null);
    } catch (Exception e) {
      logger.error("Error listing state machines", e);
    }

    if (existingStateMachineArn != null) {
      logger.debug("State machine already exists, skipping creation");
    } else {
      logger.debug("State machine not found, creating new one");
      String trustPolicy =
          "{"
              + "\"Version\": \"2012-10-17\","
              + "\"Statement\": ["
              + "  {"
              + "    \"Effect\": \"Allow\","
              + "    \"Principal\": {"
              + "      \"Service\": \"states.amazonaws.com\""
              + "    },"
              + "    \"Action\": \"sts:AssumeRole\""
              + "  }"
              + "]}";
      var roleRequest =
          new CreateRoleRequest()
              .withRoleName(sfnName + "-role")
              .withAssumeRolePolicyDocument(trustPolicy);
      var roleArn = iamClient.createRole(roleRequest).getRole().getArn();
      String policyDocument =
          "{"
              + "\"Version\": \"2012-10-17\","
              + "\"Statement\": ["
              + "  {"
              + "    \"Effect\": \"Allow\","
              + "    \"Action\": ["
              + "      \"lambda:InvokeFunction\""
              + "    ],"
              + "    \"Resource\": ["
              + "      \"*\""
              + "    ]"
              + "  }"
              + "]}";
      var policyRequest =
          new PutRolePolicyRequest()
              .withRoleName(sfnName + "-role")
              .withPolicyName(sfnName + "-policy")
              .withPolicyDocument(policyDocument);
      iamClient.putRolePolicy(policyRequest);
      String stateMachineDefinition =
          "{"
              + "  \"Comment\": \"A Hello World example of the Amazon States Language using a Pass state\","
              + "  \"StartAt\": \"HelloWorld\","
              + "  \"States\": {"
              + "    \"HelloWorld\": {"
              + "      \"Type\": \"Pass\","
              + "      \"Result\": \"Hello World!\","
              + "      \"End\": true"
              + "    }"
              + "  }"
              + "}";
      var sfnRequest =
          new CreateStateMachineRequest()
              .withName(sfnName)
              .withRoleArn(roleArn)
              .withDefinition(stateMachineDefinition)
              .withType(StateMachineType.STANDARD);
      var createResponse = stepFunctionsClient.createStateMachine(sfnRequest);
      existingStateMachineArn = createResponse.getStateMachineArn();
    }

    var activityName = "test-activity";
    String existingActivityArn = null;
    try {
      var listRequest = new ListActivitiesRequest();
      var listResponse = stepFunctionsClient.listActivities(listRequest);
      existingActivityArn =
          listResponse.getActivities().stream()
              .filter(activity -> activity.getName().equals(activityName))
              .findFirst()
              .map(ActivityListItem::getActivityArn)
              .orElse(null);
    } catch (Exception e) {
      logger.error("Error listing activities", e);
    }

    if (existingActivityArn != null) {
      logger.debug("Activity already exists, skipping creation");
    } else {
      logger.debug("Activity does not exist, creating new one");
      var createRequest = new CreateActivityRequest().withName(activityName);
      var createResponse = stepFunctionsClient.createActivity(createRequest);
      existingActivityArn = createResponse.getActivityArn();
    }

    String finalExistingStateMachineArn = existingStateMachineArn;
    String finalExistingActivityArn = existingActivityArn;

    get(
        "/sfn/describestatemachine/:name",
        (req, res) -> {
          var describeRequest =
              new DescribeStateMachineRequest().withStateMachineArn(finalExistingStateMachineArn);
          stepFunctionsClient.describeStateMachine(describeRequest);
          return "";
        });

    get(
        "/sfn/describeactivity/:name",
        (req, res) -> {
          var describeRequest =
              new DescribeActivityRequest().withActivityArn(finalExistingActivityArn);
          stepFunctionsClient.describeActivity(describeRequest);
          return "";
        });

    get(
        "/sfn/error",
        (req, res) -> {
          setMainStatus(400);
          var errorClient =
              AWSStepFunctionsClient.builder()
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .withEndpointConfiguration(
                      new EndpointConfiguration(
                          "http://error.test:8080", Regions.US_WEST_2.getName()))
                  .build();

          try {
            var describeRequest =
                new DescribeActivityRequest()
                    .withActivityArn(
                        "arn:aws:states:us-west-2:000000000000:activity:nonexistent-activity");
            errorClient.describeActivity(describeRequest);
          } catch (Exception e) {
            logger.error("Error describing activity", e);
          }
          return "";
        });

    get(
        "/sfn/fault",
        (req, res) -> {
          setMainStatus(500);
          var faultClient =
              AWSStepFunctionsClient.builder()
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .withEndpointConfiguration(
                      new EndpointConfiguration(
                          "http://fault.test:8080", Regions.US_WEST_2.getName()))
                  .build();

          try {
            var describeRequest =
                new DescribeActivityRequest()
                    .withActivityArn(
                        "arn:aws:states:us-west-2:000000000000:activity:fault-activity");
            faultClient.describeActivity(describeRequest);
          } catch (Exception e) {
            logger.error("Error describing activity", e);
          }
          return "";
        });
  }

  private static void setupSns() {
    var snsClient =
        AmazonSNSClient.builder()
            .withCredentials(CREDENTIALS_PROVIDER)
            .withEndpointConfiguration(endpointConfiguration)
            .build();

    var topicName = "test-topic";
    String existingTopicArn = null;

    try {
      var listTopicsRequest = new ListTopicsRequest();
      var listTopicsResult = snsClient.listTopics(listTopicsRequest);
      existingTopicArn =
          listTopicsResult.getTopics().stream()
              .filter(topic -> topic.getTopicArn().contains(topicName))
              .findFirst()
              .map(Topic::getTopicArn)
              .orElse(null);
    } catch (Exception e) {
      logger.error("Error listing topics", e);
    }

    if (existingTopicArn != null) {
      logger.debug("Topic already exists, skipping creation");
    } else {
      logger.debug("Topic does not exist, creating new one");
      var createTopicRequest = new CreateTopicRequest().withName(topicName);
      var createTopicResult = snsClient.createTopic(createTopicRequest);
      existingTopicArn = createTopicResult.getTopicArn();
    }

    String finalExistingTopicArn = existingTopicArn;
    get(
        "/sns/gettopicattributes/:topicId",
        (req, res) -> {
          var getTopicAttributesRequest =
              new GetTopicAttributesRequest().withTopicArn(finalExistingTopicArn);
          snsClient.getTopicAttributes(getTopicAttributesRequest);
          return "";
        });

    get(
        "/sns/error",
        (req, res) -> {
          setMainStatus(400);
          var errorClient =
              AmazonSNSClient.builder()
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .withEndpointConfiguration(
                      new EndpointConfiguration(
                          "https://error.test:8080", Regions.US_WEST_2.getName()))
                  .build();

          try {
            var getTopicAttributesRequest =
                new GetTopicAttributesRequest()
                    .withTopicArn("arn:aws:sns:us-west-2:000000000000:nonexistent-topic");
            errorClient.getTopicAttributes(getTopicAttributesRequest);
          } catch (Exception e) {
            logger.error("Error describing topic", e);
          }
          return "";
        });

    get(
        "/sns/fault",
        (req, res) -> {
          setMainStatus(500);
          var faultClient =
              AmazonSNSClient.builder()
                  .withCredentials(CREDENTIALS_PROVIDER)
                  .withEndpointConfiguration(
                      new EndpointConfiguration(
                          "http://fault.test:8080", Regions.US_WEST_2.getName()))
                  .build();

          try {
            var getTopicAttributesRequest =
                new GetTopicAttributesRequest()
                    .withTopicArn("arn:aws:sns:us-west-2:000000000000:fault-topic");
            faultClient.getTopicAttributes(getTopicAttributesRequest);
          } catch (Exception e) {
            logger.error("Error describing topic", e);
          }
          return "";
        });
  }

  private static void setupBedrock() {
    // Localstack does not support Bedrock related services.
    // We point all Bedrock related request endpoints to the local app,
    // and then specifically handle each request to return the expected response.
    // For the full list of services supported by Localstack, see:
    // https://github.com/testcontainers/testcontainers-java/blob/1f38f0d9604edb9e89fd3b3ee1eff6728e2d1e07/modules/localstack/src/main/java/org/testcontainers/containers/localstack/LocalStackContainer.java#L402
    var bedrockAgentClient =
        AWSBedrockAgentClient.builder()
            .withCredentials(CREDENTIALS_PROVIDER)
            .withEndpointConfiguration(
                new EndpointConfiguration("http://bedrock.test:8080", Regions.US_WEST_2.getName()))
            .build();
    var bedrockClient =
        AmazonBedrockClient.builder()
            .withCredentials(CREDENTIALS_PROVIDER)
            .withEndpointConfiguration(
                new EndpointConfiguration("http://bedrock.test:8080", Regions.US_WEST_2.getName()))
            .build();
    var bedrockAgentRuntimeClient =
        AWSBedrockAgentRuntimeClient.builder()
            .withCredentials(CREDENTIALS_PROVIDER)
            .withEndpointConfiguration(
                new EndpointConfiguration("http://bedrock.test:8080", Regions.US_WEST_2.getName()))
            .build();
    var bedrockRuntimeClient =
        AmazonBedrockRuntimeClient.builder()
            .withCredentials(CREDENTIALS_PROVIDER)
            .withEndpointConfiguration(
                new EndpointConfiguration("http://bedrock.test:8080", Regions.US_WEST_2.getName()))
            .build();

    // Setup API routes for Bedrock， BedrockAgent, BedrockAgentRuntime, and BedrockRuntime services.
    Utils.setupGetKnowledgeBaseRoute(mainStatus);
    Utils.setupGetAgentRoute(mainStatus);
    Utils.setupGetGuardrailRoute(mainStatus);
    Utils.setupGetAgentMemoryRoute(mainStatus);
    Utils.setupGetDataSourceRoute(mainStatus);
    Utils.setupInvokeModelRoute(mainStatus);
    Utils.setupRetrieveRoute(mainStatus);

    get(
        "/bedrockagent/getknowledgeBase/:knowledgeBaseId",
        (req, res) -> {
          setMainStatus(200);
          String knowledgeBaseId = req.params(":knowledgeBaseId");
          GetKnowledgeBaseRequest request =
              new GetKnowledgeBaseRequest().withKnowledgeBaseId(knowledgeBaseId);
          bedrockAgentClient.getKnowledgeBase(request);
          return "";
        });
    get(
        "/bedrockruntime/invokeModel",
        (req, res) -> {
          setMainStatus(200);
          String modelId = "anthropic.claude-v2";
          InvokeModelRequest invokeModelRequest =
              new InvokeModelRequest()
                  .withModelId(modelId)
                  .withBody(
                      StandardCharsets.UTF_8.encode(
                          "{\"prompt\":\"Hello, world!\",\"temperature\":0.7,\"top_p\":0.9,\"max_tokens_to_sample\":100}\n"));
          bedrockRuntimeClient.invokeModel(invokeModelRequest);
          return "";
        });
    get(
        "/bedrockruntime/invokeModel/ai21Jamba",
        (req, res) -> {
          setMainStatus(200);
          String modelId = "ai21.jamba-1-5-mini-v1:0";

          ObjectMapper mapper = new ObjectMapper();
          Map<String, Object> request = new HashMap<>();

          List<Map<String, String>> messages = new ArrayList<>();
          Map<String, String> message = new HashMap<>();
          message.put("role", "user");
          message.put("content", "Which LLM are you?");
          messages.add(message);

          request.put("messages", messages);
          request.put("max_tokens", 1000);
          request.put("top_p", 0.8);
          request.put("temperature", 0.7);

          InvokeModelRequest invokeModelRequest =
              new InvokeModelRequest()
                  .withModelId(modelId)
                  .withBody(StandardCharsets.UTF_8.encode(mapper.writeValueAsString(request)));

          var response = bedrockRuntimeClient.invokeModel(invokeModelRequest);
          var responseBody = new String(response.getBody().array(), StandardCharsets.UTF_8);

          return "";
        });
    get(
        "/bedrockruntime/invokeModel/amazonTitan",
        (req, res) -> {
          setMainStatus(200);
          String modelId = "amazon.titan-text-premier-v1:0";

          ObjectMapper mapper = new ObjectMapper();
          Map<String, Object> request = new HashMap<>();
          request.put("inputText", "Hello, world!");

          Map<String, Object> config = new HashMap<>();
          config.put("temperature", 0.7);
          config.put("topP", 0.9);
          config.put("maxTokenCount", 100);

          request.put("textGenerationConfig", config);

          InvokeModelRequest invokeModelRequest =
              new InvokeModelRequest()
                  .withModelId(modelId)
                  .withBody(StandardCharsets.UTF_8.encode(mapper.writeValueAsString(request)));

          var response = bedrockRuntimeClient.invokeModel(invokeModelRequest);
          var responseBody = new String(response.getBody().array(), StandardCharsets.UTF_8);

          return "";
        });
    get(
        "/bedrockruntime/invokeModel/anthropicClaude",
        (req, res) -> {
          setMainStatus(200);
          String modelId = "anthropic.claude-3-haiku-20240307-v1:0";

          ObjectMapper mapper = new ObjectMapper();
          Map<String, Object> request = new HashMap<>();

          List<Map<String, String>> messages = new ArrayList<>();
          Map<String, String> message = new HashMap<>();
          message.put("role", "user");
          message.put("content", "Describe a cache in one line");
          messages.add(message);

          request.put("messages", messages);
          request.put("anthropic_version", "bedrock-2023-05-31");
          request.put("max_tokens", 512);
          request.put("top_p", 0.53);
          request.put("temperature", 0.6);

          InvokeModelRequest invokeModelRequest =
              new InvokeModelRequest()
                  .withModelId(modelId)
                  .withBody(StandardCharsets.UTF_8.encode(mapper.writeValueAsString(request)));

          var response = bedrockRuntimeClient.invokeModel(invokeModelRequest);
          var responseBody = new String(response.getBody().array(), StandardCharsets.UTF_8);

          return "";
        });
    get(
        "/bedrockruntime/invokeModel/cohereCommandR",
        (req, res) -> {
          setMainStatus(200);
          String modelId = "cohere.command-r-v1:0";

          ObjectMapper mapper = new ObjectMapper();
          Map<String, Object> request = new HashMap<>();

          request.put("message", "Convince me to write a LISP interpreter in one line");
          request.put("temperature", 0.8);
          request.put("max_tokens", 4096);
          request.put("p", 0.45);

          InvokeModelRequest invokeModelRequest =
              new InvokeModelRequest()
                  .withModelId(modelId)
                  .withBody(StandardCharsets.UTF_8.encode(mapper.writeValueAsString(request)));

          var response = bedrockRuntimeClient.invokeModel(invokeModelRequest);
          var responseBody = new String(response.getBody().array(), StandardCharsets.UTF_8);

          return "";
        });
    get(
        "/bedrockruntime/invokeModel/metaLlama",
        (req, res) -> {
          setMainStatus(200);
          String modelId = "meta.llama3-70b-instruct-v1:0";

          ObjectMapper mapper = new ObjectMapper();
          Map<String, Object> request = new HashMap<>();

          String prompt = "Describe the purpose of a 'hello world' program in one line";
          String instruction =
              String.format(
                  "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n%s<|eot_id|>\n<|start_header_id|>assistant<|end_header_id|>\n",
                  prompt);

          request.put("prompt", instruction);
          request.put("max_gen_len", 128);
          request.put("temperature", 0.1);
          request.put("top_p", 0.9);

          InvokeModelRequest invokeModelRequest =
              new InvokeModelRequest()
                  .withModelId(modelId)
                  .withBody(StandardCharsets.UTF_8.encode(mapper.writeValueAsString(request)));

          var response = bedrockRuntimeClient.invokeModel(invokeModelRequest);
          var responseBody = new String(response.getBody().array(), StandardCharsets.UTF_8);

          return "";
        });
    get(
        "/bedrockruntime/invokeModel/mistralAi",
        (req, res) -> {
          setMainStatus(200);
          String modelId = "mistral.mistral-large-2402-v1:0";

          ObjectMapper mapper = new ObjectMapper();
          Map<String, Object> request = new HashMap<>();

          String prompt = "Describe the difference between a compiler and interpreter in one line.";
          String instruction = String.format("<s>[INST] %s [/INST]\n", prompt);

          request.put("prompt", instruction);
          request.put("max_tokens", 4096);
          request.put("temperature", 0.75);
          request.put("top_p", 0.25);

          InvokeModelRequest invokeModelRequest =
              new InvokeModelRequest()
                  .withModelId(modelId)
                  .withBody(StandardCharsets.UTF_8.encode(mapper.writeValueAsString(request)));

          var response = bedrockRuntimeClient.invokeModel(invokeModelRequest);
          var responseBody = new String(response.getBody().array(), StandardCharsets.UTF_8);

          return "";
        });

    get(
        "/bedrockagent/get-data-source",
        (req, res) -> {
          setMainStatus(200);

          GetDataSourceRequest request =
              new GetDataSourceRequest()
                  .withDataSourceId("nonExistDatasourceId")
                  .withKnowledgeBaseId("nonExistKnowledgeBaseId");
          bedrockAgentClient.getDataSource(request);
          return "";
        });
    get(
        "/bedrockagent/getagent/:agentId",
        (req, res) -> {
          setMainStatus(200);
          String testAgentId = req.params(":agentId");
          GetAgentRequest request = new GetAgentRequest().withAgentId(testAgentId);
          bedrockAgentClient.getAgent(request);
          return "";
        });
    get(
        "/bedrock/getguardrail",
        (req, res) -> {
          setMainStatus(200);
          GetGuardrailRequest request =
              new GetGuardrailRequest()
                  .withGuardrailIdentifier("test-bedrock-guardrail")
                  .withGuardrailVersion("DRAFT");
          bedrockClient.getGuardrail(request);
          return "";
        });
    get(
        "/bedrockagentruntime/getmemory/:agentId",
        (req, res) -> {
          setMainStatus(200);
          String agentId = req.params(":agentId");
          GetAgentMemoryRequest request =
              new GetAgentMemoryRequest().withAgentId(agentId).withAgentAliasId("agent-alias-id");
          var repo = bedrockAgentRuntimeClient.getAgentMemory(request);
          return "";
        });
    get(
        "/bedrockagentruntime/retrieve/:knowledgeBaseId",
        (req, res) -> {
          setMainStatus(200);
          String knowledgeBaseId = req.params(":knowledgeBaseId");
          RetrieveRequest request = new RetrieveRequest().withKnowledgeBaseId(knowledgeBaseId);
          var repo = bedrockAgentRuntimeClient.retrieve(request);
          return "";
        });
  }
}
