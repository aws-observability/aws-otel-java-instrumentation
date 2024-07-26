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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.CreateStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
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
            ex.printStackTrace();
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
            ex.printStackTrace();
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
            ex.printStackTrace();
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
              ex.printStackTrace();
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
          } catch (Exception ex) {
            ex.printStackTrace();
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
          } catch (Exception ex) {
            ex.printStackTrace();
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

    // Response with GetKnowledgeBaseRequest.
    get(
        "/knowledgebases/:knowledgeBaseId",
        (req, res) -> {
          String knowledgeBaseId = req.params(":knowledgeBaseId");
          String createdAt =
              Instant.ofEpochSecond(1720600797L)
                  .atOffset(ZoneOffset.UTC)
                  .format(DateTimeFormatter.ISO_INSTANT);
          String updatedAt =
              Instant.ofEpochSecond(1720773597L)
                  .atOffset(ZoneOffset.UTC)
                  .format(DateTimeFormatter.ISO_INSTANT);
          ObjectNode knowledgeBase = objectMapper.createObjectNode();
          knowledgeBase.put("createdAt", createdAt);
          knowledgeBase.put(
              "knowledgeBaseArn",
              "arn:aws:bedrock:us-west-2:000000000000:knowledge-base/" + knowledgeBaseId);
          ObjectNode knowledgeBaseConfiguration = objectMapper.createObjectNode();
          knowledgeBaseConfiguration.put("type", "VECTOR");
          knowledgeBase.set("knowledgeBaseConfiguration", knowledgeBaseConfiguration);
          knowledgeBase.put("knowledgeBaseId", knowledgeBaseId);
          knowledgeBase.put("name", "test-knowledge-base");
          knowledgeBase.put("roleArn", "arn:aws:iam::000000000000:role/TestKnowledgeBase");
          knowledgeBase.put("status", "ACTIVE");
          ObjectNode storageConfiguration = objectMapper.createObjectNode();
          storageConfiguration.put("type", "OPENSEARCH_SERVERLESS");
          knowledgeBase.set("storageConfiguration", storageConfiguration);
          knowledgeBase.put("updatedAt", updatedAt);
          ObjectNode responseBody = objectMapper.createObjectNode();
          responseBody.set("knowledgeBase", knowledgeBase);
          String jsonResponse = objectMapper.writeValueAsString(responseBody);

          res.status(mainStatus);
          res.type("application/json");
          return jsonResponse;
        });

    // Response with GetAgentRequest.
    get(
        "/agents/:agentId/",
        (req, res) -> {
          String agentId = req.params(":agentId");
          ObjectNode agentNode = objectMapper.createObjectNode();
          agentNode.put("agentArn", "arn:aws:bedrock:us-east-1:000000000000:agent/" + agentId);
          agentNode.put("agentId", agentId);
          agentNode.put("agentName", "test-bedrock-agent");
          agentNode.put("agentResourceRoleArn", "arn:aws:iam::000000000000:role/TestAgent");
          agentNode.put("agentStatus", "PREPARED");
          agentNode.put("agentVersion", "DRAFT");
          agentNode.put("createdAt", "2024-07-17T12:00:00Z");
          agentNode.put("idleSessionTTLInSeconds", 60);
          agentNode.put("updatedAt", "2024-07-17T12:30:00Z");
          ObjectNode responseBody = objectMapper.createObjectNode();
          responseBody.set("agent", agentNode);
          String jsonResponse = responseBody.toString();

          res.status(mainStatus);
          res.type("application/json");
          return jsonResponse;
        });

    // Response with GetDataSourceRequest.
    get(
        "/knowledgebases/:knowledgeBaseId/datasources/:dataSourceId",
        (req, res) -> {
          res.status(mainStatus);
          return res;
        });

    // Response with GetGuardrailRequest.
    get(
        "/guardrails/:guardrailIdentifier",
        (req, res) -> {
          String guardrailId = "test-bedrock-guardrail";
          ObjectNode jsonResponse = objectMapper.createObjectNode();
          jsonResponse.put(
              "guardrailArn", "arn:aws:bedrock:us-east-1:000000000000:guardrail/" + guardrailId);
          jsonResponse.put("guardrailId", guardrailId);
          jsonResponse.put("version", "DRAFT");
          jsonResponse.put("createdAt", "2024-07-17T12:00:00Z");
          jsonResponse.put("updatedAt", "2024-07-17T12:30:00Z");
          jsonResponse.put("blockedInputMessaging", "InputBlocked");
          jsonResponse.put("blockedOutputsMessaging", "OutputBlocked");
          jsonResponse.put("name", "test-guardrail");
          jsonResponse.put("status", "READY");

          res.status(mainStatus);
          res.type("application/json");
          return jsonResponse;
        });

    // Response with GetAgentMemoryRequest.
    get(
        "/agents/:agentId/agentAliases/:agentAliasId/memories",
        (req, res) -> {
          ObjectNode jsonResponse = objectMapper.createObjectNode();
          jsonResponse.put("nextToken", "testToken");

          ArrayNode memoryContents = objectMapper.createArrayNode();
          jsonResponse.set("memoryContents", memoryContents);

          res.status(mainStatus);
          res.type("application/json");
          return jsonResponse;
        });

    get(
        "/bedrockagent/getknowledgeBase/:knowledgeBaseId",
        (req, res) -> {
          setMainStatus(200);
          String knowledgeBaseId = req.params(":knowledgeBaseId");
          try {
            GetKnowledgeBaseRequest request =
                GetKnowledgeBaseRequest.builder().knowledgeBaseId(knowledgeBaseId).build();
            bedrockAgentClient.getKnowledgeBase(request);
          } catch (Exception ex) {
            ex.printStackTrace();
          }
          return "";
        });

    get(
        "/bedrockruntime/error",
        (req, res) -> {
          setMainStatus(404);
          BedrockRuntimeClient errorClient =
              BedrockRuntimeClient.builder()
                  .endpointOverride(URI.create("http://error.test:8080"))
                  .region(Region.US_WEST_2)
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

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
          errorClient.invokeModel(request);
          return "";
        });

    get(
        "/bedrockagent/fault",
        (req, res) -> {
          setMainStatus(500);
          BedrockAgentClient faultClient =
              BedrockAgentClient.builder()
                  .endpointOverride(URI.create("http://fault.test:8080"))
                  .overrideConfiguration(
                      ClientOverrideConfiguration.builder()
                          .retryPolicy(RetryPolicy.builder().numRetries(0).build())
                          .build())
                  .region(Region.US_WEST_2)
                  .credentialsProvider(CREDENTIALS_PROVIDER)
                  .build();

          try {
            GetDataSourceRequest request =
                GetDataSourceRequest.builder()
                    .dataSourceId("nonExistDatasourceId")
                    .knowledgeBaseId("nonExistKnowledgeBaseId")
                    .build();
            faultClient.getDataSource(request);
          } catch (Exception ex) {
            ex.printStackTrace();
          }
          return "";
        });

    get(
        "/bedrockagent/getagent/:agentId",
        (req, res) -> {
          setMainStatus(200);
          String agentId = req.params(":agentId");
          try {
            GetAgentRequest request = GetAgentRequest.builder().agentId(agentId).build();
            bedrockAgentClient.getAgent(request);
          } catch (Exception ex) {
            ex.printStackTrace();
          }
          return "";
        });

    get(
        "/bedrockagentruntime/getmemory/:agentId",
        (req, res) -> {
          setMainStatus(200);
          String agentId = req.params(":agentId");
          try {
            GetAgentMemoryRequest request =
                GetAgentMemoryRequest.builder()
                    .agentId(agentId)
                    .agentAliasId("agent-alias-id")
                    .build();
            bedrockAgentRuntimeClient.getAgentMemory(request);
          } catch (Exception ex) {
            ex.printStackTrace();
          }
          return "";
        });

    get(
        "/bedrock/getguardrail",
        (req, res) -> {
          setMainStatus(200);
          try {
            GetGuardrailRequest request =
                GetGuardrailRequest.builder()
                    .guardrailIdentifier("test-bedrock-guardrail")
                    .guardrailVersion("DRAFT")
                    .build();
            bedrockClient.getGuardrail(request);
          } catch (Exception ex) {
            ex.printStackTrace();
          }
          return "";
        });
  }
}
