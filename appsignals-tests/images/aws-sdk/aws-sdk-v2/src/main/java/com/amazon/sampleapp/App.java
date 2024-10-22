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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
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
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsRequest;
import software.amazon.awssdk.services.secretsmanager.model.SecretListEntry;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;
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
    setupBedrock();
    setupSecretsManager();
    setupStepFunctions();
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
      logger.info("Secret already exists, skipping creation");
    } else {
      logger.info("Secret not found, creating a new one");
      var createSecretRequest = CreateSecretRequest.builder().name(secretName).build();
      var createResponse = secretsManagerClient.createSecret(createSecretRequest);
      existingSecretArn = createResponse.arn();
    }

    String finalExistingSecretArn = existingSecretArn;
    logger.info("Existing secret arn {}", finalExistingSecretArn);
    get(
        "/secretsmanager/describesecret/:secretId",
        (req, res) -> {
          var secretId = req.params(":secretId");
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
            logger.info("Error describing secret", e);
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
            logger.info("Fault caught in Sample App", e);
          }
          return "";
        });
  }

  private static void setupStepFunctions() {
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
      logger.info("State machine already exists, skipping creation");
    } else {
      logger.info("State machine not found, creating a new one");
      // Create role for Step Functions
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
      // Attach policy allowing Lambda invocation
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
      // Simple state machine definition
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
      // Create state machine
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
      logger.info("Activity already exists, skipping creation");
    } else {
      logger.info("Activity not found, creating a new one");
      var createRequest = CreateActivityRequest.builder().name(activityName).build();
      existingActivityArn = sfnClient.createActivity(createRequest).activityArn();
    }

    String finalExistingStateMachineArn = existingStateMachineArn;
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

    String finalExistingActivityArn = existingActivityArn;
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
            logger.info("Error caught in Sample App", e);
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
            logger.info("Fault caught in Sample App", e);
          }
          return "";
        });
  }
}
