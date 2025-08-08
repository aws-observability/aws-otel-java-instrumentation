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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.GetGuardrailRequest;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.GetAgentRequest;
import software.amazon.awssdk.services.bedrockagent.model.GetDataSourceRequest;
import software.amazon.awssdk.services.bedrockagent.model.GetKnowledgeBaseRequest;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.GetAgentMemoryRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.CreateStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsRequest;
import software.amazon.awssdk.services.secretsmanager.model.SecretListEntry;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ActivityListItem;
import software.amazon.awssdk.services.sfn.model.CreateActivityRequest;
import software.amazon.awssdk.services.sfn.model.CreateStateMachineRequest;
import software.amazon.awssdk.services.sfn.model.DescribeActivityRequest;
import software.amazon.awssdk.services.sfn.model.DescribeStateMachineRequest;
import software.amazon.awssdk.services.sfn.model.ListActivitiesRequest;
import software.amazon.awssdk.services.sfn.model.ListStateMachinesRequest;
import software.amazon.awssdk.services.sfn.model.StateMachineListItem;
import software.amazon.awssdk.services.sfn.model.StateMachineType;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.GetTopicAttributesRequest;
import software.amazon.awssdk.services.sns.model.ListTopicsRequest;
import software.amazon.awssdk.services.sns.model.Topic;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class App {
  static final Logger logger = LoggerFactory.getLogger(App.class);
  private static final HttpClient httpClient =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  private static final StaticCredentialsProvider CREDENTIALS_PROVIDER =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar"));

  static int mainStatus = 200;

  static void setMainStatus(int status) {
    mainStatus = status;
  }

  private static final URI s3Endpoint =
      URI.create(System.getenv().getOrDefault("AWS_SDK_S3_ENDPOINT", "http://s3.localhost:8080"));
  private static final URI endpoint =
      URI.create(System.getenv().getOrDefault("AWS_SDK_ENDPOINT", "http://s3.localhost:8080"));

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
    setupSfn();
    setupBedrock();
    setupSns();
    setupCrossAccount();
    // Add this log line so that we only start testing after all routes are configured.
    awaitInitialization();
    logger.info("All routes initialized");
  }

  private static void setupSqs() {
    var sqsClient =
        SqsClient.builder()
            .endpointOverride(endpoint)
            .credentialsProvider(CREDENTIALS_PROVIDER)
            .build();

    get(
        "/sqs/createqueue/:queuename",
        (req, res) -> {
          var createQueueRequest =
              CreateQueueRequest.builder().queueName(req.params(":queuename")).build();

          var response = sqsClient.createQueue(createQueueRequest);
          return response.queueUrl();
        });
    get(
        "/sqs/publishqueue/:queuename",
        (req, res) -> {
          var queueName = req.params(":queuename");
          var createQueueRequest = CreateQueueRequest.builder().queueName(queueName).build();
          var response = sqsClient.createQueue(createQueueRequest);

          var sendMessageRequest =
              SendMessageRequest.builder()
                  .messageBody("test")
                  .queueUrl(response.queueUrl())
                  .build();
          sqsClient.sendMessage(sendMessageRequest);
          return response.queueUrl();
        });

    get(
        "/sqs/consumequeue/:queuename",
        (req, res) -> {
          var queueName = req.params(":queuename");
          var createQueueRequest = CreateQueueRequest.builder().queueName(queueName).build();
          var response = sqsClient.createQueue(createQueueRequest);

          var sendMessageRequest =
              SendMessageRequest.builder()
                  .messageBody("test")
                  .queueUrl(response.queueUrl())
                  .build();
          sqsClient.sendMessage(sendMessageRequest);

          var receiveMessageRequest =
              ReceiveMessageRequest.builder().queueUrl(response.queueUrl()).build();

          var messages = sqsClient.receiveMessage(receiveMessageRequest).messages();
          logger.info("Message Received: ", messages.toString());
          return response.queueUrl();
        });

    get(
        "/sqs/error",
        (req, res) -> {
          var errorClient =
              SqsClient.builder()
                  .endpointOverride(URI.create("http://error.test:8080"))
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          setMainStatus(400);
          var sendMessageRequest =
              SendMessageRequest.builder()
                  .messageBody("error")
                  .queueUrl("http://error.test:8080")
                  .build();
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
          var errorClient =
              SqsClient.builder()
                  .endpointOverride(URI.create("http://fault.test:8080"))
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          setMainStatus(500);
          var sendMessageRequest =
              SendMessageRequest.builder()
                  .messageBody("error")
                  .queueUrl("http://fault.test:8080")
                  .build();
          try {
            errorClient.sendMessage(sendMessageRequest);
          } catch (Exception ex) {

          }
          return "";
        });
  }

  private static void setupDynamoDb() {
    DynamoDbClient dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(endpoint)
            .credentialsProvider(CREDENTIALS_PROVIDER)
            .build();
    get(
        "/ddb/createtable/:tablename",
        (req, res) -> {
          var tableName = req.params(":tablename");

          var createTableRequest =
              CreateTableRequest.builder()
                  .tableName(tableName)
                  .attributeDefinitions(
                      AttributeDefinition.builder()
                          .attributeName("partitionKey")
                          .attributeType("S")
                          .build())
                  .keySchema(
                      KeySchemaElement.builder()
                          .attributeName("partitionKey")
                          .keyType(KeyType.HASH)
                          .build())
                  .provisionedThroughput(
                      ProvisionedThroughput.builder()
                          .readCapacityUnits(1L)
                          .writeCapacityUnits(1L)
                          .build())
                  .build();
          dynamoDbClient.createTable(createTableRequest);
          return "";
        });

    get(
        "/ddb/putitem/:tablename/:partitionkey",
        (req, res) -> {
          var partitionKey = req.params(":partitionkey");
          var tableName = req.params(":tablename");

          var createTableRequest =
              CreateTableRequest.builder()
                  .tableName(req.params(":tablename"))
                  .attributeDefinitions(
                      AttributeDefinition.builder()
                          .attributeName(partitionKey)
                          .attributeType("S")
                          .build())
                  .keySchema(
                      KeySchemaElement.builder()
                          .attributeName(partitionKey)
                          .keyType(KeyType.HASH)
                          .build())
                  .provisionedThroughput(
                      ProvisionedThroughput.builder()
                          .readCapacityUnits(1L)
                          .writeCapacityUnits(1L)
                          .build())
                  .build();

          dynamoDbClient.createTable(createTableRequest);
          var item =
              Map.of(
                  partitionKey,
                  AttributeValue.fromS("value"),
                  "otherAttribute",
                  AttributeValue.fromS("value"));

          var putItemRequest = PutItemRequest.builder().tableName(tableName).item(item).build();

          dynamoDbClient.putItem(putItemRequest);
          return "";
        });

    get(
        "/ddb/describetable/:tablename",
        (req, res) -> {
          var tableName = req.params(":tablename");

          var createTableRequest =
              CreateTableRequest.builder()
                  .tableName(tableName)
                  .attributeDefinitions(
                      AttributeDefinition.builder()
                          .attributeName("partitionKey")
                          .attributeType("S")
                          .build())
                  .keySchema(
                      KeySchemaElement.builder()
                          .attributeName("partitionKey")
                          .keyType(KeyType.HASH)
                          .build())
                  .provisionedThroughput(
                      ProvisionedThroughput.builder()
                          .readCapacityUnits(1L)
                          .writeCapacityUnits(1L)
                          .build())
                  .build();
          dynamoDbClient.createTable(createTableRequest);
          dynamoDbClient.describeTable(r -> r.tableName(tableName));
          return "";
        });

    get(
        "/ddb/error",
        (req, res) -> {
          setMainStatus(400);
          var errorClient =
              DynamoDbClient.builder()
                  .endpointOverride(URI.create("http://error.test:8080"))
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          try {
            var putItemRequest =
                PutItemRequest.builder()
                    .tableName("nonexistanttable")
                    .item(Map.of("partitionKey", AttributeValue.fromS("value")))
                    .build();
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
              DynamoDbClient.builder()
                  .endpointOverride(URI.create("http://fault.test:8080"))
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          try {
            var putItemRequest =
                PutItemRequest.builder()
                    .tableName("nonexistanttable")
                    .item(Map.of("partitionKey", AttributeValue.fromS("value")))
                    .build();
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
              KinesisClient.builder()
                  .endpointOverride(endpoint)
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          kinesisClient.createStream(CreateStreamRequest.builder().streamName(streamName).build());
          byte[] data = {0x0, 0x1};
          var partitionKey = "key";

          var trials = 5;
          // reference:
          // https://docs.aws.amazon.com/streams/latest/dev/kinesis-using-sdk-java-create-stream.html#kinesis-using-sdk-java-create-the-stream
          for (int i = 0; i < trials; i++) {
            try {
              Thread.sleep(1000);
            } catch (Exception ex) {
            }
            var streamDescription =
                kinesisClient.describeStream(
                    DescribeStreamRequest.builder().streamName(streamName).build());
            if (streamDescription.streamDescription().streamStatus().equals("ACTIVE")) {
              break;
            }
          }
          kinesisClient.putRecord(
              PutRecordRequest.builder()
                  .streamName(streamName)
                  .data(SdkBytes.fromByteArray(data))
                  .partitionKey(partitionKey)
                  .build());
          return "";
        });

    get(
        "/kinesis/describestream/:streamname",
        (req, res) -> {
          var streamName = req.params(":streamname");

          var kinesisClient =
              KinesisClient.builder()
                  .endpointOverride(endpoint)
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          kinesisClient.createStream(CreateStreamRequest.builder().streamName(streamName).build());

          // Describe stream using ARN
          var streamArn = "arn:aws:kinesis:us-west-2:000000000000:stream/" + streamName;
          var describeStreamRequest = DescribeStreamRequest.builder().streamARN(streamArn).build();
          kinesisClient.describeStream(describeStreamRequest);
          return "";
        });

    get(
        "/kinesis/error",
        (req, res) -> {
          setMainStatus(400);
          var kinesisClient =
              KinesisClient.builder()
                  .endpointOverride(URI.create("http://error.test:8080"))
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          var streamName = "nonexistantstream";
          var partitionKey = "key";
          byte[] data = {0x0, 0x1};
          kinesisClient.putRecord(
              PutRecordRequest.builder()
                  .streamName(streamName)
                  .data(SdkBytes.fromByteArray(data))
                  .partitionKey(partitionKey)
                  .build());
          return "";
        });

    get(
        "/kinesis/fault",
        (req, res) -> {
          setMainStatus(500);
          var kinesisClient =
              KinesisClient.builder()
                  .endpointOverride(URI.create("http://fault.test:8080"))
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          var streamName = "faultstream";
          var partitionKey = "key";
          byte[] data = {0x0, 0x1};
          kinesisClient.putRecord(
              PutRecordRequest.builder()
                  .streamName(streamName)
                  .data(SdkBytes.fromByteArray(data))
                  .partitionKey(partitionKey)
                  .build());
          return "";
        });
  }

  private static void setupS3() {
    S3Client client =
        S3Client.builder()
            .endpointOverride(s3Endpoint)
            .region(Region.US_WEST_2)
            .credentialsProvider(CREDENTIALS_PROVIDER)
            .build();

    get(
        "/s3/createbucket/:bucketname",
        (req, res) -> {
          String bucketName = req.params(":bucketname");
          CreateBucketRequest createBucketRequest =
              CreateBucketRequest.builder().bucket(bucketName).build();
          var response = client.createBucket(createBucketRequest);
          return "";
        });

    get(
        "/s3/createobject/:bucketname/:objectname",
        (req, res) -> {
          String objectName = req.params(":objectname");
          String bucketName = req.params(":bucketname");
          CreateBucketRequest createBucketRequest =
              CreateBucketRequest.builder().bucket(bucketName).build();
          client.createBucket(createBucketRequest);

          var putObjectRequest =
              PutObjectRequest.builder().bucket(bucketName).key(objectName).build();
          var body = RequestBody.fromString("Hello World");
          client.putObject(putObjectRequest, body);

          return "";
        });

    get(
        "/s3/getobject/:bucketName/:objectname",
        (req, res) -> {
          var objectName = req.params(":objectname");
          var bucketName = req.params(":bucketName");
          CreateBucketRequest createBucketRequest =
              CreateBucketRequest.builder().bucket(bucketName).build();
          client.createBucket(createBucketRequest);

          var putObjectRequest =
              PutObjectRequest.builder().bucket(bucketName).key(objectName).build();
          var body = RequestBody.fromString("Hello World");

          var getOjectRequest =
              GetObjectRequest.builder().bucket(bucketName).key(objectName).build();
          client.putObject(putObjectRequest, body);

          var response = client.getObjectAsBytes(getOjectRequest);
          return response.asString(Charset.defaultCharset());
        });
    get(
        "/s3/error",
        (req, res) -> {
          setMainStatus(400);
          System.out.println("received request");
          S3Client errorClient =
              S3Client.builder()
                  .endpointOverride(URI.create("http://s3.test:8080"))
                  .region(Region.US_WEST_2)
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          try {
            var response =
                errorClient.getObject(
                    GetObjectRequest.builder().bucket("error-bucket").key("error-object").build());
          } catch (Exception e) {
          }
          return "";
        });
    get(
        "/s3/fault",
        (req, res) -> {
          setMainStatus(500);
          S3Client faultClient =
              S3Client.builder()
                  .endpointOverride(URI.create("http://s3.test:8080"))
                  .overrideConfiguration(
                      ClientOverrideConfiguration.builder()
                          .retryPolicy(RetryPolicy.builder().numRetries(0).build())
                          .build())
                  .region(Region.US_WEST_2)
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          try {
            var response =
                faultClient.getObject(
                    GetObjectRequest.builder().bucket("fault-bucket").key("fault-object").build());
          } catch (Exception e) {
          }
          return "";
        });
  }

  private static void setupSecretsManager() {
    var secretsManagerClient =
        SecretsManagerClient.builder()
            .endpointOverride(endpoint)
            .credentialsProvider(CREDENTIALS_PROVIDER)
            .build();
    var secretName = "test-secret-id";
    String existingSecretArn = null;
    try {
      var listRequest = ListSecretsRequest.builder().build();
      var listResponse = secretsManagerClient.listSecrets(listRequest);
      existingSecretArn =
          listResponse.secretList().stream()
              .filter(secret -> secret.name().contains(secretName))
              .findFirst()
              .map(SecretListEntry::arn)
              .orElse(null);
    } catch (Exception e) {
      logger.error("Error listing secrets", e);
    }

    if (existingSecretArn != null) {
      logger.debug("Secret already exists, skipping creation");
    } else {
      logger.debug("Secret not found, creating a new one");
      var createSecretRequest = CreateSecretRequest.builder().name(secretName).build();
      var createSecretResponse = secretsManagerClient.createSecret(createSecretRequest);
      existingSecretArn = createSecretResponse.arn();
    }

    String finalExistingSecretArn = existingSecretArn;
    get(
        "/secretsmanager/describesecret/:secretId",
        (req, res) -> {
          var describeRequest =
              DescribeSecretRequest.builder().secretId(finalExistingSecretArn).build();
          secretsManagerClient.describeSecret(describeRequest);
          return "";
        });

    get(
        "/secretsmanager/error",
        (req, res) -> {
          setMainStatus(400);
          var errorClient =
              SecretsManagerClient.builder()
                  .endpointOverride(URI.create("http://error.test:8080"))
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          try {
            var describeRequest =
                DescribeSecretRequest.builder()
                    .secretId(
                        "arn:aws:secretsmanager:us-west-2:000000000000:secret:nonexistent-secret-id")
                    .build();
            errorClient.describeSecret(describeRequest);
          } catch (Exception e) {
            logger.error("Error describing secret", e);
          }
          return "";
        });

    get(
        "/secretsmanager/fault",
        (req, res) -> {
          setMainStatus(500);
          var faultClient =
              SecretsManagerClient.builder()
                  .endpointOverride(URI.create("http://fault.test:8080"))
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          try {
            var describeRequest =
                DescribeSecretRequest.builder()
                    .secretId(
                        "arn:aws:secretsmanager:us-west-2:000000000000:secret:fault-secret-id")
                    .build();
            faultClient.describeSecret(describeRequest);
          } catch (Exception e) {
            logger.error("Error describing secret", e);
          }
          return "";
        });
  }

  private static void setupSfn() {
    var sfnClient =
        SfnClient.builder()
            .endpointOverride(endpoint)
            .credentialsProvider(CREDENTIALS_PROVIDER)
            .build();
    var iamClient =
        IamClient.builder()
            .endpointOverride(endpoint)
            .credentialsProvider(CREDENTIALS_PROVIDER)
            .build();

    var sfnName = "test-state-machine";
    String existingStateMachineArn = null;
    try {
      var listRequest = ListStateMachinesRequest.builder().build();
      var listResponse = sfnClient.listStateMachines(listRequest);
      existingStateMachineArn =
          listResponse.stateMachines().stream()
              .filter(machine -> machine.name().equals(sfnName))
              .findFirst()
              .map(StateMachineListItem::stateMachineArn)
              .orElse(null);
    } catch (Exception e) {
      logger.error("Error listing state machines", e);
    }

    if (existingStateMachineArn != null) {
      logger.debug("State machine already exists, skipping creation");
    } else {
      logger.debug("State machine not found, creating a new one");
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
          CreateRoleRequest.builder()
              .roleName(sfnName + "-role")
              .assumeRolePolicyDocument(trustPolicy)
              .build();
      var roleArn = iamClient.createRole(roleRequest).role().arn();
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
          PutRolePolicyRequest.builder()
              .roleName(sfnName + "-role")
              .policyName(sfnName + "-policy")
              .policyDocument(policyDocument)
              .build();
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
          CreateStateMachineRequest.builder()
              .name(sfnName)
              .roleArn(roleArn)
              .definition(stateMachineDefinition)
              .type(StateMachineType.STANDARD)
              .build();
      existingStateMachineArn = sfnClient.createStateMachine(sfnRequest).stateMachineArn();
    }

    var activityName = "test-activity";
    String existingActivityArn = null;

    try {
      var listRequest = ListActivitiesRequest.builder().build();
      var listResponse = sfnClient.listActivities(listRequest);
      existingActivityArn =
          listResponse.activities().stream()
              .filter(activity -> activity.name().equals(activityName))
              .findFirst()
              .map(ActivityListItem::activityArn)
              .orElse(null);
    } catch (Exception e) {
      logger.error("Error listing activities", e);
    }

    if (existingActivityArn != null) {
      logger.debug("Activities already exists, skipping creation");
    } else {
      logger.debug("Activities not found, creating a new one");
      var createRequest = CreateActivityRequest.builder().name(activityName).build();
      existingActivityArn = sfnClient.createActivity(createRequest).activityArn();
    }

    String finalExistingStateMachineArn = existingStateMachineArn;
    String finalExistingActivityArn = existingActivityArn;

    get(
        "/sfn/describestatemachine/:name",
        (req, res) -> {
          var describeRequest =
              DescribeStateMachineRequest.builder()
                  .stateMachineArn(finalExistingStateMachineArn)
                  .build();
          sfnClient.describeStateMachine(describeRequest);
          return "";
        });

    get(
        "/sfn/describeactivity/:name",
        (req, res) -> {
          var describeRequest =
              DescribeActivityRequest.builder().activityArn(finalExistingActivityArn).build();
          sfnClient.describeActivity(describeRequest);
          return "";
        });

    get(
        "/sfn/error",
        (req, res) -> {
          setMainStatus(400);
          var errorClient =
              SfnClient.builder()
                  .endpointOverride(URI.create("http://error.test:8080"))
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          try {
            var describeRequest =
                DescribeActivityRequest.builder()
                    .activityArn(
                        "arn:aws:states:us-west-2:000000000000:activity:nonexistent-activity")
                    .build();
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
              SfnClient.builder()
                  .endpointOverride(URI.create("http://fault.test:8080"))
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          try {
            var describeRequest =
                DescribeActivityRequest.builder()
                    .activityArn("arn:aws:states:us-west-2:000000000000:activity:fault-activity")
                    .build();
            faultClient.describeActivity(describeRequest);
          } catch (Exception e) {
            logger.error("Error describing activity", e);
          }
          return "";
        });
  }

  private static void setupSns() {
    var snsClient =
        SnsClient.builder()
            .endpointOverride(endpoint)
            .credentialsProvider(CREDENTIALS_PROVIDER)
            .build();

    var topicName = "test-topic";
    String existingTopicArn = null;

    try {
      var listRequest = ListTopicsRequest.builder().build();
      var listResponse = snsClient.listTopics(listRequest);
      existingTopicArn =
          listResponse.topics().stream()
              .filter(topic -> topic.topicArn().contains(topicName))
              .findFirst()
              .map(Topic::topicArn)
              .orElse(null);
    } catch (Exception e) {
      logger.error("Error listing topics", e);
    }

    if (existingTopicArn != null) {
      logger.debug("Topics already exists, skipping creation");
    } else {
      logger.debug("Topics not found, creating a new one");
      var createTopicRequest = CreateTopicRequest.builder().name(topicName).build();
      var createTopicResponse = snsClient.createTopic(createTopicRequest);
      existingTopicArn = createTopicResponse.topicArn();
    }

    String finalExistingTopicArn = existingTopicArn;
    get(
        "/sns/gettopicattributes/:topicId",
        (req, res) -> {
          var getTopicAttributesRequest =
              GetTopicAttributesRequest.builder().topicArn(finalExistingTopicArn).build();
          snsClient.getTopicAttributes(getTopicAttributesRequest);
          return "";
        });

    get(
        "/sns/error",
        (req, res) -> {
          setMainStatus(400);
          var errorClient =
              SnsClient.builder()
                  .endpointOverride(URI.create("http://error.test:8080"))
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          try {
            var getTopicAttributesRequest =
                GetTopicAttributesRequest.builder()
                    .topicArn("arn:aws:sns:us-west-2:000000000000:nonexistent-topic")
                    .build();
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
              SnsClient.builder()
                  .endpointOverride(URI.create("http://fault.test:8080"))
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          try {
            var getTopicAttributesRequest =
                GetTopicAttributesRequest.builder()
                    .topicArn("arn:aws:sns:us-west-2:000000000000:fault-topic")
                    .build();
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
    var objectMapper = new ObjectMapper();
    var bedrockClient =
        BedrockClient.builder()
            .endpointOverride(URI.create("http://bedrock.test:8080"))
            .region(Region.US_WEST_2)
            .credentialsProvider(CREDENTIALS_PROVIDER)
            .build();
    var bedrockAgentClient =
        BedrockAgentClient.builder()
            .endpointOverride(URI.create("http://bedrock.test:8080"))
            .region(Region.US_WEST_2)
            .credentialsProvider(CREDENTIALS_PROVIDER)
            .build();
    var bedrockAgentRuntimeClient =
        BedrockAgentRuntimeClient.builder()
            .endpointOverride(URI.create("http://bedrock.test:8080"))
            .region(Region.US_WEST_2)
            .credentialsProvider(CREDENTIALS_PROVIDER)
            .build();
    var bedrockRuntimeClient =
        BedrockRuntimeClient.builder()
            .endpointOverride(URI.create("http://bedrock.test:8080"))
            .region(Region.US_WEST_2)
            .credentialsProvider(CREDENTIALS_PROVIDER)
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
              GetKnowledgeBaseRequest.builder().knowledgeBaseId(knowledgeBaseId).build();
          bedrockAgentClient.getKnowledgeBase(request);
          return "";
        });
    get(
        "/bedrockruntime/invokeModel",
        (req, res) -> {
          setMainStatus(200);
          String llama2ModelId = "anthropic.claude-v2";
          ObjectNode payload = objectMapper.createObjectNode();
          payload.put("prompt", "test prompt");
          payload.put("max_gen_len", 1000);
          payload.put("temperature", 0.5);
          payload.put("top_p", 0.9);
          InvokeModelRequest request =
              InvokeModelRequest.builder()
                  .body(SdkBytes.fromUtf8String(payload.toString()))
                  .modelId(llama2ModelId)
                  .contentType("application/json")
                  .accept("application/json")
                  .build();
          bedrockRuntimeClient.invokeModel(request);
          return "";
        });
    get(
        "/bedrockruntime/invokeModel/ai21Jamba",
        (req, res) -> {
          setMainStatus(200);

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
              InvokeModelRequest.builder()
                  .modelId("ai21.jamba-1-5-mini-v1:0")
                  .body(SdkBytes.fromUtf8String(mapper.writeValueAsString(request)))
                  .build();

          bedrockRuntimeClient.invokeModel(invokeModelRequest);

          return "";
        });
    get(
        "/bedrockruntime/invokeModel/amazonTitan",
        (req, res) -> {
          setMainStatus(200);

          ObjectMapper mapper = new ObjectMapper();
          Map<String, Object> request = new HashMap<>();
          request.put("inputText", "Hello, world!");

          Map<String, Object> config = new HashMap<>();
          config.put("temperature", 0.7);
          config.put("topP", 0.9);
          config.put("maxTokenCount", 100);

          request.put("textGenerationConfig", config);

          InvokeModelRequest invokeModelRequest =
              InvokeModelRequest.builder()
                  .modelId("amazon.titan-text-premier-v1:0")
                  .body(SdkBytes.fromUtf8String(mapper.writeValueAsString(request)))
                  .build();

          bedrockRuntimeClient.invokeModel(invokeModelRequest);

          return "";
        });
    get(
        "/bedrockruntime/invokeModel/anthropicClaude",
        (req, res) -> {
          setMainStatus(200);

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
              InvokeModelRequest.builder()
                  .modelId("anthropic.claude-3-haiku-20240307-v1:0")
                  .body(SdkBytes.fromUtf8String(mapper.writeValueAsString(request)))
                  .build();

          bedrockRuntimeClient.invokeModel(invokeModelRequest);

          return "";
        });
    get(
        "/bedrockruntime/invokeModel/cohereCommandR",
        (req, res) -> {
          setMainStatus(200);

          ObjectMapper mapper = new ObjectMapper();
          Map<String, Object> request = new HashMap<>();

          request.put("message", "Convince me to write a LISP interpreter in one line");
          request.put("temperature", 0.8);
          request.put("max_tokens", 4096);
          request.put("p", 0.45);

          InvokeModelRequest invokeModelRequest =
              InvokeModelRequest.builder()
                  .modelId("cohere.command-r-v1:0")
                  .body(SdkBytes.fromUtf8String(mapper.writeValueAsString(request)))
                  .build();

          bedrockRuntimeClient.invokeModel(invokeModelRequest);

          return "";
        });
    get(
        "/bedrockruntime/invokeModel/metaLlama",
        (req, res) -> {
          setMainStatus(200);

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
              InvokeModelRequest.builder()
                  .modelId("meta.llama3-70b-instruct-v1:0")
                  .body(SdkBytes.fromUtf8String(mapper.writeValueAsString(request)))
                  .build();

          bedrockRuntimeClient.invokeModel(invokeModelRequest);

          return "";
        });
    get(
        "/bedrockruntime/invokeModel/mistralAi",
        (req, res) -> {
          setMainStatus(200);

          ObjectMapper mapper = new ObjectMapper();
          Map<String, Object> request = new HashMap<>();

          String prompt = "Describe the difference between a compiler and interpreter in one line.";
          String instruction = String.format("<s>[INST] %s [/INST]\n", prompt);

          request.put("prompt", instruction);
          request.put("max_tokens", 4096);
          request.put("temperature", 0.75);
          request.put("top_p", 0.25);

          InvokeModelRequest invokeModelRequest =
              InvokeModelRequest.builder()
                  .modelId("mistral.mistral-large-2402-v1:0")
                  .body(SdkBytes.fromUtf8String(mapper.writeValueAsString(request)))
                  .build();

          bedrockRuntimeClient.invokeModel(invokeModelRequest);

          return "";
        });
    get(
        "/bedrockagent/get-data-source",
        (req, res) -> {
          setMainStatus(200);

          GetDataSourceRequest request =
              GetDataSourceRequest.builder()
                  .dataSourceId("nonExistDatasourceId")
                  .knowledgeBaseId("nonExistKnowledgeBaseId")
                  .build();
          bedrockAgentClient.getDataSource(request);
          return "";
        });
    get(
        "/bedrockagent/getagent/:agentId",
        (req, res) -> {
          setMainStatus(200);
          String agentId = req.params(":agentId");
          GetAgentRequest request = GetAgentRequest.builder().agentId(agentId).build();
          bedrockAgentClient.getAgent(request);
          return "";
        });
    get(
        "/bedrockagentruntime/getmemory/:agentId",
        (req, res) -> {
          setMainStatus(200);
          String agentId = req.params(":agentId");
          GetAgentMemoryRequest request =
              GetAgentMemoryRequest.builder()
                  .agentId(agentId)
                  .agentAliasId("agent-alias-id")
                  .build();
          bedrockAgentRuntimeClient.getAgentMemory(request);
          return "";
        });
    get(
        "/bedrock/getguardrail",
        (req, res) -> {
          setMainStatus(200);
          GetGuardrailRequest request =
              GetGuardrailRequest.builder()
                  .guardrailIdentifier("test-bedrock-guardrail")
                  .guardrailVersion("DRAFT")
                  .build();
          bedrockClient.getGuardrail(request);
          return "";
        });
    get(
        "/bedrockagentruntime/retrieve/:knowledgeBaseId",
        (req, res) -> {
          setMainStatus(200);
          String knowledgeBaseId = req.params(":knowledgeBaseId");
          RetrieveRequest request =
              RetrieveRequest.builder().knowledgeBaseId(knowledgeBaseId).build();
          var repo = bedrockAgentRuntimeClient.retrieve(request);
          return "";
        });
  }

  private static void setupCrossAccount() {
    // Create credentials provider with temporary credentials
    AwsSessionCredentials sessionCredentials =
        AwsSessionCredentials.create(
            "account_b_access_key_id", "account_b_secret_access_key", "account_b_token");
    StaticCredentialsProvider sessionCredentialsProvider =
        StaticCredentialsProvider.create(sessionCredentials);

    // Create S3 client with temporary credentials
    var crossAccountS3Client =
        S3Client.builder()
            .credentialsProvider(sessionCredentialsProvider)
            .endpointOverride(s3Endpoint)
            .region(Region.EU_CENTRAL_1)
            .build();

    get(
        "/crossaccount/createbucket/accountb",
        (req, res) -> {
          CreateBucketRequest createBucketRequest =
              CreateBucketRequest.builder()
                  .bucket("cross-account-bucket")
                  .createBucketConfiguration(
                      CreateBucketConfiguration.builder()
                          .locationConstraint(Region.EU_CENTRAL_1.id())
                          .build())
                  .build();
          crossAccountS3Client.createBucket(createBucketRequest);
          return "";
        });
  }
}
