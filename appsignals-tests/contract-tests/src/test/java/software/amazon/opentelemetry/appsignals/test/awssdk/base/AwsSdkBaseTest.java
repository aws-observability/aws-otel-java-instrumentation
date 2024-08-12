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

package software.amazon.opentelemetry.appsignals.test.awssdk.base;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.opentelemetry.appsignals.test.base.ContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.AppSignalsConstants;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeMetric;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

public abstract class AwsSdkBaseTest extends ContractTestBase {
  protected static final Logger LOGGER = Logger.getLogger(AwsSdkBaseTest.class.getName());

  private final LocalStackContainer localstack =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.5.0"))
          .withServices(
              LocalStackContainer.Service.S3,
              LocalStackContainer.Service.DYNAMODB,
              LocalStackContainer.Service.SQS,
              LocalStackContainer.Service.KINESIS)
          .withEnv("DEFAULT_REGION", "us-west-2")
          .withNetwork(network)
          .withEnv("LOCALSTACK_HOST", "127.0.0.1")
          .withNetworkAliases(
              "localstack",
              "s3.localstack",
              "create-bucket.s3.localstack",
              "put-object.s3.localstack",
              "get-object.s3.localstack");

  @BeforeAll
  private void startLocalStack() {
    localstack.start();
  }

  @AfterAll
  private void stopLocalStack() {
    localstack.stop();
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    return Map.of(
        "AWS_SDK_S3_ENDPOINT", "http://s3.localstack:4566",
        "AWS_SDK_ENDPOINT", "http://localstack:4566",
        "AWS_REGION", "us-west-2");
  }

  @Override
  protected List<String> getApplicationNetworkAliases() {
    // aliases used for the case there are errors or fault. In this case the target of the http
    // requests in the aws sdk is the instrumented service itself. We have to do this because
    // we cannot force localstack to return specific error codes.
    return List.of(
        "error-bucket.s3.test", "fault-bucket.s3.test", "error.test", "fault.test", "bedrock.test");
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*All routes initialized.*";
  }

  /** Methods that should be overriden in the implementation class * */
  protected abstract String getS3SpanNamePrefix();

  protected abstract String getDynamoDbSpanNamePrefix();

  protected abstract String getSqsSpanNamePrefix();

  protected abstract String getKinesisSpanNamePrefix();

  protected abstract String getBedrockSpanNamePrefix();

  protected abstract String getBedrockAgentSpanNamePrefix();

  protected abstract String getBedrockRuntimeSpanNamePrefix();

  protected abstract String getBedrockAgentRuntimeSpanNamePrefix();

  protected abstract String getS3RpcServiceName();

  protected abstract String getDynamoDbRpcServiceName();

  protected abstract String getSqsRpcServiceName();

  protected abstract String getKinesisRpcServiceName();

  protected abstract String getBedrockRpcServiceName();

  protected abstract String getBedrockAgentRpcServiceName();

  protected abstract String getBedrockRuntimeRpcServiceName();

  protected abstract String getBedrockAgentRuntimeRpcServiceName();

  private String getS3ServiceName() {
    return "AWS::S3";
  }

  private String getDynamoDbServiceName() {
    return "AWS::DynamoDB";
  }

  private String getSqsServiceName() {
    return "AWS::SQS";
  }

  private String getKinesisServiceName() {
    return "AWS::Kinesis";
  }

  private String getBedrockServiceName() {
    return "AWS::Bedrock";
  }

  private String getBedrockAgentServiceName() {
    return "AWS::Bedrock";
  }

  private String getBedrockAgentRuntimeServiceName() {
    return "AWS::Bedrock";
  }

  private String getBedrockRuntimeServiceName() {
    return "AWS::BedrockRuntime";
  }

  private String s3SpanName(String operation) {
    return String.format("%s.%s", getS3SpanNamePrefix(), operation);
  }

  private String dynamoDbSpanName(String operation) {
    return String.format("%s.%s", getDynamoDbSpanNamePrefix(), operation);
  }

  private String sqsSpanName(String operation) {
    return String.format("%s.%s", getSqsSpanNamePrefix(), operation);
  }

  private String kinesisSpanName(String operation) {
    return String.format("%s.%s", getKinesisSpanNamePrefix(), operation);
  }

  private String bedrockSpanName(String operation) {
    return String.format("%s.%s", getBedrockSpanNamePrefix(), operation);
  }

  private String bedrockAgentSpanName(String operation) {
    return String.format("%s.%s", getBedrockAgentSpanNamePrefix(), operation);
  }

  private String bedrockRuntimeSpanName(String operation) {
    return String.format("%s.%s", getBedrockRuntimeSpanNamePrefix(), operation);
  }

  private String bedrockAgentRuntimeSpanName(String operation) {
    return String.format("%s.%s", getBedrockAgentRuntimeSpanNamePrefix(), operation);
  }

  protected ThrowingConsumer<KeyValue> assertAttribute(String key, String value) {
    return (attribute) -> {
      assertThat(attribute.getKey()).isEqualTo(key);
      assertThat(attribute.getValue().getStringValue()).isEqualTo(value);
    };
  }

  protected ThrowingConsumer<KeyValue> assertAttributeStartsWith(String key, String value) {
    return (attribute) -> {
      assertThat(attribute.getKey()).isEqualTo(key);
      assertThat(attribute.getValue().getStringValue()).startsWith(value);
    };
  }

  protected ThrowingConsumer<KeyValue> assertAttribute(String key, int value) {
    return (attribute) -> {
      assertThat(attribute.getKey()).isEqualTo(key);
      assertThat(attribute.getValue().getIntValue()).isEqualTo(value);
    };
  }

  private ThrowingConsumer<KeyValue> assertKeyIsPresent(String key) {
    return (attribute) -> {
      assertThat(attribute.getKey()).isEqualTo(key);
    };
  }

  /** All the spans of the AWS SDK Should have a RPC properties. */
  private void assertSemanticConventionsAttributes(
      List<KeyValue> attributesList,
      String service,
      String method,
      String peerName,
      int peerPort,
      String url,
      int statusCode) {
    assertThat(attributesList)
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.RPC_METHOD, method))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.RPC_SERVICE, service))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.RPC_SYSTEM, "aws-api"))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.NET_PEER_NAME, peerName))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.NET_PEER_PORT, peerPort))
        .satisfiesOnlyOnce(
            assertAttribute(SemanticConventionsConstants.HTTP_STATUS_CODE, statusCode))
        .satisfiesOnlyOnce(assertAttributeStartsWith(SemanticConventionsConstants.HTTP_URL, url))
        .satisfiesOnlyOnce(assertKeyIsPresent(SemanticConventionsConstants.THREAD_ID));
  }

  /** All the spans of the AWS SDK Should have a RPC properties. */
  private void assertSemanticConventionsSqsConsumerAttributes(
      List<KeyValue> attributesList,
      String service,
      String method,
      String peerName,
      int peerPort,
      String url) {
    assertThat(attributesList)
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.RPC_METHOD, method))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.RPC_SERVICE, service))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.RPC_SYSTEM, "aws-api"))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.NET_PEER_NAME, peerName))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.NET_PEER_PORT, peerPort))
        .satisfiesOnlyOnce(assertAttributeStartsWith(SemanticConventionsConstants.HTTP_URL, url))
        .satisfiesOnlyOnce(assertKeyIsPresent(SemanticConventionsConstants.THREAD_ID));
  }

  private void assertSpanClientAttributes(
      List<ResourceScopeSpan> spans,
      String spanName,
      String rpcService,
      String localService,
      String localOperation,
      String service,
      String method,
      String type,
      String identifier,
      String peerName,
      int peerPort,
      String url,
      int statusCode,
      List<ThrowingConsumer<KeyValue>> extraAssertions) {

    assertSpanAttributes(
        spans,
        SpanKind.SPAN_KIND_CLIENT,
        "CLIENT",
        spanName,
        rpcService,
        localService,
        localOperation,
        service,
        method,
        type,
        identifier,
        peerName,
        peerPort,
        url,
        statusCode,
        extraAssertions);
  }

  private void assertSpanProducerAttributes(
      List<ResourceScopeSpan> spans,
      String spanName,
      String rpcService,
      String localService,
      String localOperation,
      String service,
      String method,
      String type,
      String identifier,
      String peerName,
      int peerPort,
      String url,
      int statusCode,
      List<ThrowingConsumer<KeyValue>> extraAssertions) {
    assertSpanAttributes(
        spans,
        SpanKind.SPAN_KIND_PRODUCER,
        "PRODUCER",
        spanName,
        rpcService,
        localService,
        localOperation,
        service,
        method,
        type,
        identifier,
        peerName,
        peerPort,
        url,
        statusCode,
        extraAssertions);
  }

  private void assertSpanConsumerAttributes(
      List<ResourceScopeSpan> spans,
      String spanName,
      String rpcService,
      String operation,
      String localService,
      String method,
      String peerName,
      int peerPort,
      String url,
      int statusCode,
      List<ThrowingConsumer<KeyValue>> extraAssertions) {

    assertThat(spans)
        .satisfiesOnlyOnce(
            rss -> {
              var span = rss.getSpan();
              var spanAttributes = span.getAttributesList();
              assertThat(span.getKind()).isEqualTo(SpanKind.SPAN_KIND_CONSUMER);
              assertThat(span.getName()).isEqualTo(spanName);
              assertSemanticConventionsSqsConsumerAttributes(
                  spanAttributes, rpcService, method, peerName, peerPort, url);
              assertSqsConsumerAwsAttributes(span.getAttributesList(), operation);
              for (var assertion : extraAssertions) {
                assertThat(spanAttributes).satisfiesOnlyOnce(assertion);
              }
            });
  }

  private void assertSpanAttributes(
      List<ResourceScopeSpan> spans,
      SpanKind spanKind,
      String awsSpanKind,
      String spanName,
      String rpcService,
      String localService,
      String localOperation,
      String service,
      String method,
      String type,
      String identifier,
      String peerName,
      int peerPort,
      String url,
      int statusCode,
      List<ThrowingConsumer<KeyValue>> extraAssertions) {

    assertThat(spans)
        .satisfiesOnlyOnce(
            rss -> {
              var span = rss.getSpan();
              var spanAttributes = span.getAttributesList();
              assertThat(span.getKind()).isEqualTo(spanKind);
              assertThat(span.getName()).isEqualTo(spanName);
              assertSemanticConventionsAttributes(
                  spanAttributes, rpcService, method, peerName, peerPort, url, statusCode);
              assertAwsAttributes(
                  spanAttributes,
                  localService,
                  localOperation,
                  service,
                  method,
                  type,
                  identifier,
                  awsSpanKind);
              for (var assertion : extraAssertions) {
                assertThat(spanAttributes).satisfiesOnlyOnce(assertion);
              }
            });
  }

  private void assertAwsAttributes(
      List<KeyValue> attributesList,
      String localService,
      String localOperation,
      String service,
      String operation,
      String type,
      String identifier,
      String spanKind) {

    var assertions =
        assertThat(attributesList)
            .satisfiesOnlyOnce(
                assertAttribute(AppSignalsConstants.AWS_LOCAL_OPERATION, localOperation))
            .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_LOCAL_SERVICE, localService))
            .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_REMOTE_OPERATION, operation))
            .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_REMOTE_SERVICE, service))
            .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_SPAN_KIND, spanKind));
    if (type != null && identifier != null) {
      assertions.satisfiesOnlyOnce(
          assertAttribute(AppSignalsConstants.AWS_REMOTE_RESOURCE_TYPE, type));
      assertions.satisfiesOnlyOnce(
          assertAttribute(AppSignalsConstants.AWS_REMOTE_RESOURCE_IDENTIFIER, identifier));
    }
  }

  private void assertSqsConsumerAwsAttributes(List<KeyValue> attributesList, String operation) {
    assertThat(attributesList)
        .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_LOCAL_OPERATION, operation))
        .satisfiesOnlyOnce(
            assertAttribute(AppSignalsConstants.AWS_LOCAL_SERVICE, getApplicationOtelServiceName()))
        .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_SPAN_KIND, "LOCAL_ROOT"))
        .satisfiesOnlyOnce(
            assertAttribute(AppSignalsConstants.AWS_REMOTE_OPERATION, "ReceiveMessage"))
        .satisfiesOnlyOnce(
            assertAttribute(AppSignalsConstants.AWS_REMOTE_SERVICE, getSqsServiceName()));
  }

  protected void assertMetricClientAttributes(
      List<ResourceScopeMetric> resourceScopeMetrics,
      String metricName,
      String localService,
      String localOperation,
      String service,
      String method,
      String type,
      String identifier,
      Double expectedSum) {
    assertMetricAttributes(
        resourceScopeMetrics,
        metricName,
        "CLIENT",
        localService,
        localOperation,
        service,
        method,
        type,
        identifier,
        expectedSum);
  }

  protected void assertMetricProducerAttributes(
      List<ResourceScopeMetric> resourceScopeMetrics,
      String metricName,
      String localService,
      String localOperation,
      String service,
      String method,
      String type,
      String identifier,
      Double expectedSum) {
    assertMetricAttributes(
        resourceScopeMetrics,
        metricName,
        "PRODUCER",
        localService,
        localOperation,
        service,
        method,
        type,
        identifier,
        expectedSum);
  }

  protected void assertMetricConsumerAttributes(
      List<ResourceScopeMetric> resourceScopeMetrics,
      String metricName,
      String localService,
      String localOperation,
      String service,
      String method,
      String type,
      String identifier,
      Double expectedSum) {
    assertMetricAttributes(
        resourceScopeMetrics,
        metricName,
        "CONSUMER",
        localService,
        localOperation,
        service,
        method,
        type,
        identifier,
        expectedSum);
  }

  protected void assertMetricAttributes(
      List<ResourceScopeMetric> resourceScopeMetrics,
      String metricName,
      String spanKind,
      String localService,
      String localOperation,
      String service,
      String method,
      String type,
      String identifier,
      Double expectedSum) {
    assertThat(resourceScopeMetrics)
        .anySatisfy(
            metric -> {
              assertThat(metric.getMetric().getName()).isEqualTo(metricName);
              var dataPoints = metric.getMetric().getExponentialHistogram().getDataPointsList();
              assertThat(dataPoints)
                  .satisfiesOnlyOnce(
                      dataPoint -> {
                        List<KeyValue> attributes = dataPoint.getAttributesList();
                        assertThat(attributes).isNotNull();
                        assertAwsAttributes(
                            attributes,
                            localService,
                            localOperation,
                            service,
                            method,
                            type,
                            identifier,
                            spanKind);
                        if (expectedSum != null) {
                          double actualSum = dataPoint.getSum();
                          switch (metricName) {
                            case AppSignalsConstants.LATENCY_METRIC:
                              assertThat(actualSum).isStrictlyBetween(0.0, expectedSum);
                              break;
                            default:
                              assertThat(actualSum).isEqualTo(expectedSum);
                          }
                        }
                      });
            });
  }

  protected void doTestS3CreateBucket() throws Exception {
    appClient.get("/s3/createbucket/create-bucket").aggregate().join();

    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /s3/createbucket/:bucketname";
    var type = "AWS::S3::Bucket";
    var identifier = "create-bucket";

    assertSpanClientAttributes(
        traces,
        s3SpanName("CreateBucket"),
        getS3RpcServiceName(),
        localService,
        localOperation,
        getS3ServiceName(),
        "CreateBucket",
        type,
        identifier,
        "create-bucket.s3.localstack",
        4566,
        "http://create-bucket.s3.localstack:4566",
        200,
        List.of(assertAttribute(SemanticConventionsConstants.AWS_BUCKET_NAME, "create-bucket")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "CreateBucket",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "CreateBucket",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "CreateBucket",
        type,
        identifier,
        0.0);
  }

  protected void doTestS3CreateObject() throws Exception {
    appClient.get("/s3/createobject/put-object/some-object").aggregate().join();

    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /s3/createobject/:bucketname/:objectname";
    var type = "AWS::S3::Bucket";
    var identifier = "put-object";

    assertSpanClientAttributes(
        traces,
        s3SpanName("PutObject"),
        getS3RpcServiceName(),
        localService,
        localOperation,
        getS3ServiceName(),
        "PutObject",
        type,
        identifier,
        "put-object.s3.localstack",
        4566,
        "http://put-object.s3.localstack:4566",
        200,
        List.of(assertAttribute(SemanticConventionsConstants.AWS_BUCKET_NAME, "put-object")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "PutObject",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "PutObject",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "PutObject",
        type,
        identifier,
        0.0);
  }

  protected void doTestS3GetObject() throws Exception {
    appClient.get("/s3/getobject/get-object/some-object").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /s3/getobject/:bucketName/:objectname";
    var type = "AWS::S3::Bucket";
    var identifier = "get-object";

    assertSpanClientAttributes(
        traces,
        s3SpanName("GetObject"),
        getS3RpcServiceName(),
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        type,
        identifier,
        "get-object.s3.localstack",
        4566,
        "http://get-object.s3.localstack:4566",
        200,
        List.of(assertAttribute(SemanticConventionsConstants.AWS_BUCKET_NAME, "get-object")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        type,
        identifier,
        0.0);
  }

  protected void doTestS3Error() {
    appClient.get("/s3/error").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /s3/error";
    var type = "AWS::S3::Bucket";
    var identifier = "error-bucket";

    assertSpanClientAttributes(
        traces,
        s3SpanName("GetObject"),
        getS3RpcServiceName(),
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        type,
        identifier,
        "error-bucket.s3.test",
        8080,
        "http://error-bucket.s3.test:8080",
        400,
        List.of(assertAttribute(SemanticConventionsConstants.AWS_BUCKET_NAME, "error-bucket")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        type,
        identifier,
        1.0);
  }

  protected void doTestS3Fault() {
    appClient.get("/s3/fault").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /s3/fault";
    var type = "AWS::S3::Bucket";
    var identifier = "fault-bucket";

    assertSpanClientAttributes(
        traces,
        s3SpanName("GetObject"),
        getS3RpcServiceName(),
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        type,
        identifier,
        "fault-bucket.s3.test",
        8080,
        "http://fault-bucket.s3.test:8080",
        500,
        List.of(assertAttribute(SemanticConventionsConstants.AWS_BUCKET_NAME, "fault-bucket")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        type,
        identifier,
        1.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        type,
        identifier,
        0.0);
  }

  protected List<ThrowingConsumer<KeyValue>> dynamoDbAttributes(
      String operation, String tableName) {
    return List.of(
        assertAttribute(SemanticConventionsConstants.AWS_TABLE_NAME, tableName),
        assertAttribute(SemanticConventionsConstants.DB_SYSTEM, "dynamodb"),
        assertAttribute(SemanticConventionsConstants.DB_OPERATION, operation));
  }

  protected void doTestDynamoDbCreateTable() {
    appClient.get("/ddb/createtable/some-table").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /ddb/createtable/:tablename";
    var type = "AWS::DynamoDB::Table";
    var identifier = "some-table";

    assertSpanClientAttributes(
        traces,
        dynamoDbSpanName("CreateTable"),
        getDynamoDbRpcServiceName(),
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "CreateTable",
        type,
        identifier,
        "localstack",
        4566,
        "http://localstack:4566",
        200,
        dynamoDbAttributes("CreateTable", "some-table"));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "CreateTable",
        type,
        identifier,
        20000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "CreateTable",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "CreateTable",
        type,
        identifier,
        0.0);
  }

  protected void doTestDynamoDbPutItem() {
    appClient.get("/ddb/putitem/putitem-table/key").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /ddb/putitem/:tablename/:partitionkey";
    var type = "AWS::DynamoDB::Table";
    var identifier = "putitem-table";

    assertSpanClientAttributes(
        traces,
        dynamoDbSpanName("PutItem"),
        getDynamoDbRpcServiceName(),
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        type,
        identifier,
        "localstack",
        4566,
        "http://localstack:4566",
        200,
        dynamoDbAttributes("PutItem", "putitem-table"));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        type,
        identifier,
        0.0);
  }

  protected void doTestDynamoDbError() throws Exception {
    appClient.get("/ddb/error").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /ddb/error";
    var type = "AWS::DynamoDB::Table";
    var identifier = "nonexistanttable";

    assertSpanClientAttributes(
        traces,
        dynamoDbSpanName("PutItem"),
        getDynamoDbRpcServiceName(),
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        type,
        identifier,
        "error.test",
        8080,
        "http://error.test:8080",
        400,
        dynamoDbAttributes("PutItem", "nonexistanttable"));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        type,
        identifier,
        1.0);
  }

  protected void doTestDynamoDbFault() throws Exception {
    appClient
        .prepare()
        .get("/ddb/fault")
        .responseTimeout(Duration.ofSeconds(20))
        .execute()
        .aggregate()
        .join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /ddb/fault";
    var type = "AWS::DynamoDB::Table";
    var identifier = "nonexistanttable";

    assertSpanClientAttributes(
        traces,
        dynamoDbSpanName("PutItem"),
        getDynamoDbRpcServiceName(),
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        type,
        identifier,
        "fault.test",
        8080,
        "http://fault.test:8080",
        500,
        dynamoDbAttributes("PutItem", "nonexistanttable"));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        type,
        identifier,
        20000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        type,
        identifier,
        1.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        type,
        identifier,
        0.0);
  }

  protected void doTestSQSCreateQueue() throws Exception {
    appClient.get("/sqs/createqueue/some-queue").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /sqs/createqueue/:queuename";
    var type = "AWS::SQS::Queue";
    var identifier = "some-queue";

    assertSpanClientAttributes(
        traces,
        sqsSpanName("CreateQueue"),
        getSqsRpcServiceName(),
        localService,
        localOperation,
        getSqsServiceName(),
        "CreateQueue",
        type,
        identifier,
        "localstack",
        4566,
        "http://localstack:4566",
        200,
        List.of(assertAttribute(SemanticConventionsConstants.AWS_QUEUE_NAME, "some-queue")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "CreateQueue",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "CreateQueue",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "CreateQueue",
        type,
        identifier,
        0.0);
  }

  protected void doTestSQSSendMessage() throws Exception {
    var response = appClient.get("/sqs/publishqueue/some-queue").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /sqs/publishqueue/:queuename";
    // SendMessage does not capture aws.queue.name
    String type = null;
    String identifier = null;

    assertSpanProducerAttributes(
        traces,
        "some-queue publish",
        getSqsRpcServiceName(),
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        type,
        identifier,
        "localstack",
        4566,
        "http://localstack:4566",
        200,
        List.of(
            assertAttribute(SemanticConventionsConstants.AWS_QUEUE_URL, response.contentUtf8())));
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        type,
        identifier,
        5000.0);
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        type,
        identifier,
        0.0);
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        type,
        identifier,
        0.0);
  }

  protected List<ThrowingConsumer<KeyValue>> testSQSReceiveMessageExtraAssertions(String queueUrl) {
    return List.of(assertAttribute(SemanticConventionsConstants.AWS_QUEUE_URL, queueUrl));
  }

  protected void doTestSQSReceiveMessage() throws Exception {
    var response = appClient.get("/sqs/consumequeue/some-queue").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "InternalOperation";
    // ReceiveMessage does not capture aws.queue.name
    String type = null;
    String identifier = null;
    // Consumer traces for SQS behave like a Server span (they create the local aws service
    // attributes), but have RPC attributes like a client span.
    assertSpanConsumerAttributes(
        traces,
        "some-queue process",
        getSqsRpcServiceName(),
        "InternalOperation",
        getApplicationOtelServiceName(),
        "ReceiveMessage",
        "localstack",
        4566,
        "http://localstack:4566",
        200,
        testSQSReceiveMessageExtraAssertions(response.contentUtf8()));

    assertMetricConsumerAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "ReceiveMessage",
        type,
        identifier,
        5000.0);
    assertMetricConsumerAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "ReceiveMessage",
        type,
        identifier,
        0.0);
  }

  protected void doTestSQSError() throws Exception {
    appClient.get("/sqs/error").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /sqs/error";
    // SendMessage does not capture aws.queue.name
    String type = null;
    String identifier = null;

    assertSpanProducerAttributes(
        traces,
        "error.test:8080 publish",
        getSqsRpcServiceName(),
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        type,
        identifier,
        "error.test",
        8080,
        "http://error.test:8080",
        400,
        List.of(
            assertAttribute(SemanticConventionsConstants.AWS_QUEUE_URL, "http://error.test:8080")));
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        type,
        identifier,
        5000.0);
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        type,
        identifier,
        0.0);
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        type,
        identifier,
        1.0);
  }

  protected void doTestSQSFault() throws Exception {
    appClient.get("/sqs/fault").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /sqs/fault";
    // SendMessage does not capture aws.queue.name
    String type = null;
    String identifier = null;

    assertSpanProducerAttributes(
        traces,
        "fault.test:8080 publish",
        getSqsRpcServiceName(),
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        type,
        identifier,
        "fault.test",
        8080,
        "http://fault.test:8080",
        500,
        List.of(
            assertAttribute(SemanticConventionsConstants.AWS_QUEUE_URL, "http://fault.test:8080")));
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        type,
        identifier,
        5000.0);
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        type,
        identifier,
        1.0);
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        type,
        identifier,
        0.0);
  }

  protected void doTestKinesisPutRecord() throws Exception {
    appClient.get("/kinesis/putrecord/my-stream").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /kinesis/putrecord/:streamname";
    var type = "AWS::Kinesis::Stream";
    var identifier = "my-stream";

    assertSpanClientAttributes(
        traces,
        kinesisSpanName("PutRecord"),
        getKinesisRpcServiceName(),
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        type,
        identifier,
        "localstack",
        4566,
        "http://localstack:4566",
        200,
        List.of(assertAttribute(SemanticConventionsConstants.AWS_STREAM_NAME, "my-stream")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        type,
        identifier,
        0.0);
  }

  protected void doTestKinesisError() throws Exception {
    appClient.get("/kinesis/error").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /kinesis/error";
    var type = "AWS::Kinesis::Stream";
    var identifier = "nonexistantstream";

    assertSpanClientAttributes(
        traces,
        kinesisSpanName("PutRecord"),
        getKinesisRpcServiceName(),
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        type,
        identifier,
        "error.test",
        8080,
        "http://error.test:8080",
        400,
        List.of(
            assertAttribute(SemanticConventionsConstants.AWS_STREAM_NAME, "nonexistantstream")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        type,
        identifier,
        1.0);
  }

  protected void doTestKinesisFault() throws Exception {
    appClient.get("/kinesis/fault").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /kinesis/fault";
    var type = "AWS::Kinesis::Stream";
    var identifier = "faultstream";

    assertSpanClientAttributes(
        traces,
        kinesisSpanName("PutRecord"),
        getKinesisRpcServiceName(),
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        type,
        identifier,
        "fault.test",
        8080,
        "http://fault.test:8080",
        500,
        List.of(assertAttribute(SemanticConventionsConstants.AWS_STREAM_NAME, "faultstream")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        type,
        identifier,
        1.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        type,
        identifier,
        0.0);
  }

  protected void doTestBedrockAgentKnowledgeBaseId() {
    var response =
        appClient.get("/bedrockagent/getknowledgeBase/knowledge-base-id").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrockagent/getknowledgeBase/:knowledgeBaseId";
    String type = "AWS::Bedrock::KnowledgeBase";
    String identifier = "knowledge-base-id";
    assertSpanClientAttributes(
        traces,
        bedrockAgentSpanName("GetKnowledgeBase"),
        getBedrockAgentRpcServiceName(),
        localService,
        localOperation,
        getBedrockAgentServiceName(),
        "GetKnowledgeBase",
        type,
        identifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.AWS_KNOWLEDGE_BASE_ID, "knowledge-base-id")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockAgentServiceName(),
        "GetKnowledgeBase",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getBedrockAgentServiceName(),
        "GetKnowledgeBase",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getBedrockAgentServiceName(),
        "GetKnowledgeBase",
        type,
        identifier,
        0.0);
  }

  protected void doTestBedrockAgentAgentId() {
    var response = appClient.get("/bedrockagent/getagent/test-agent-id").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrockagent/getagent/:agentId";
    String type = "AWS::Bedrock::Agent";
    String identifier = "test-agent-id";
    assertSpanClientAttributes(
        traces,
        bedrockAgentSpanName("GetAgent"),
        getBedrockAgentRpcServiceName(),
        localService,
        localOperation,
        getBedrockAgentServiceName(),
        "GetAgent",
        type,
        identifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(assertAttribute(SemanticConventionsConstants.AWS_AGENT_ID, "test-agent-id")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockAgentServiceName(),
        "GetAgent",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getBedrockAgentServiceName(),
        "GetAgent",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getBedrockAgentServiceName(),
        "GetAgent",
        type,
        identifier,
        0.0);
  }

  protected void doTestBedrockAgentDataSourceId() {
    var response = appClient.get("/bedrockagent/get-data-source").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrockagent/get-data-source";
    String type = "AWS::Bedrock::DataSource";
    String identifier = "nonExistDatasourceId";
    assertSpanClientAttributes(
        traces,
        bedrockAgentSpanName("GetDataSource"),
        getBedrockAgentRpcServiceName(),
        localService,
        localOperation,
        getBedrockAgentServiceName(),
        "GetDataSource",
        type,
        identifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.AWS_DATA_SOURCE_ID, "nonExistDatasourceId")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockAgentServiceName(),
        "GetDataSource",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getBedrockAgentServiceName(),
        "GetDataSource",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getBedrockAgentServiceName(),
        "GetDataSource",
        type,
        identifier,
        0.0);
  }

  protected void doTestBedrockRuntimeModelId() {
    var response = appClient.get("/bedrockruntime/invokeModel").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrockruntime/invokeModel";
    String type = "AWS::Bedrock::Model";
    String identifier = "anthropic.claude-v2";
    assertSpanClientAttributes(
        traces,
        bedrockRuntimeSpanName("InvokeModel"),
        getBedrockRuntimeRpcServiceName(),
        localService,
        localOperation,
        getBedrockRuntimeServiceName(),
        "InvokeModel",
        type,
        identifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.GEN_AI_REQUEST_MODEL, "anthropic.claude-v2")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockRuntimeServiceName(),
        "InvokeModel",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getBedrockRuntimeServiceName(),
        "InvokeModel",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getBedrockRuntimeServiceName(),
        "InvokeModel",
        type,
        identifier,
        0.0);
  }

  protected void doTestBedrockGuardrailId() {
    var response = appClient.get("/bedrock/getguardrail").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrock/getguardrail";
    String type = "AWS::Bedrock::Guardrail";
    String identifier = "test-bedrock-guardrail";
    assertSpanClientAttributes(
        traces,
        bedrockSpanName("GetGuardrail"),
        getBedrockRpcServiceName(),
        localService,
        localOperation,
        getBedrockServiceName(),
        "GetGuardrail",
        type,
        identifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.AWS_GUARDRAIL_ID, "test-bedrock-guardrail")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockServiceName(),
        "GetGuardrail",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getBedrockServiceName(),
        "GetGuardrail",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getBedrockServiceName(),
        "GetGuardrail",
        type,
        identifier,
        0.0);
  }

  protected void doTestBedrockAgentRuntimeAgentId() {
    var response = appClient.get("/bedrockagentruntime/getmemory/test-agent-id").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrockagentruntime/getmemory/:agentId";
    String type = "AWS::Bedrock::Agent";
    String identifier = "test-agent-id";
    assertSpanClientAttributes(
        traces,
        bedrockAgentRuntimeSpanName("GetAgentMemory"),
        getBedrockAgentRuntimeRpcServiceName(),
        localService,
        localOperation,
        getBedrockAgentRuntimeServiceName(),
        "GetAgentMemory",
        type,
        identifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(assertAttribute(SemanticConventionsConstants.AWS_AGENT_ID, "test-agent-id")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockAgentRuntimeServiceName(),
        "GetAgentMemory",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getBedrockAgentRuntimeServiceName(),
        "GetAgentMemory",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getBedrockAgentRuntimeServiceName(),
        "GetAgentMemory",
        type,
        identifier,
        0.0);
  }

  protected void doTestBedrockAgentRuntimeKnowledgeBaseId() {
    var response =
        appClient.get("/bedrockagentruntime/retrieve/test-knowledge-base-id").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrockagentruntime/retrieve/:knowledgeBaseId";
    String type = "AWS::Bedrock::KnowledgeBase";
    String identifier = "test-knowledge-base-id";
    assertSpanClientAttributes(
        traces,
        bedrockAgentRuntimeSpanName("Retrieve"),
        getBedrockAgentRuntimeRpcServiceName(),
        localService,
        localOperation,
        getBedrockAgentRuntimeServiceName(),
        "Retrieve",
        type,
        identifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.AWS_KNOWLEDGE_BASE_ID, "test-knowledge-base-id")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockAgentRuntimeServiceName(),
        "Retrieve",
        type,
        identifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getBedrockAgentRuntimeServiceName(),
        "Retrieve",
        type,
        identifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getBedrockAgentRuntimeServiceName(),
        "Retrieve",
        type,
        identifier,
        0.0);
  }
}
