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
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.kinesis.AmazonKinesisClient;
import com.amazonaws.services.kinesis.model.CreateStreamRequest;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CreateBucketRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.Region;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.time.Duration;
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
}
