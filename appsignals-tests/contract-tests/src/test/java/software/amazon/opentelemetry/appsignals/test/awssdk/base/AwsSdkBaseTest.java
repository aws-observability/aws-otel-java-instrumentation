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

  private final LocalStackContainer localstack =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:2.1.0"))
          .withServices(
              LocalStackContainer.Service.S3,
              LocalStackContainer.Service.DYNAMODB,
              LocalStackContainer.Service.SQS,
              LocalStackContainer.Service.KINESIS)
          .withEnv("DEFAULT_REGION", "us-west-2")
          .withNetwork(network)
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
    return List.of("error-bucket.s3.test", "fault-bucket.s3.test", "error.test", "fault.test");
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

  protected abstract String getS3ServiceName();

  protected abstract String getDynamoDbServiceName();

  protected abstract String getSqsServiceName();

  protected abstract String getKinesisServiceName();

  protected abstract String getS3RpcServiceName();

  protected abstract String getDynamoDbRpcServiceName();

  protected abstract String getSqsRpcServiceName();

  protected abstract String getKinesisRpcServiceName();

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
      String target,
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
        target,
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
      String target,
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
        target,
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
      String target,
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
                  target,
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
      String target,
      String spanKind) {

    assertThat(attributesList)
        .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_LOCAL_OPERATION, localOperation))
        .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_LOCAL_SERVICE, localService))
        .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_REMOTE_OPERATION, operation))
        .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_REMOTE_SERVICE, service))
        .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_SPAN_KIND, spanKind))
        .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_REMOTE_TARGET, target));
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
      String target,
      Double expectedSum) {
    assertMetricAttributes(
        resourceScopeMetrics,
        metricName,
        "CLIENT",
        localService,
        localOperation,
        service,
        method,
        target,
        expectedSum);
  }

  protected void assertMetricProducerAttributes(
      List<ResourceScopeMetric> resourceScopeMetrics,
      String metricName,
      String localService,
      String localOperation,
      String service,
      String method,
      String target,
      Double expectedSum) {
    assertMetricAttributes(
        resourceScopeMetrics,
        metricName,
        "PRODUCER",
        localService,
        localOperation,
        service,
        method,
        target,
        expectedSum);
  }

  protected void assertMetricConsumerAttributes(
      List<ResourceScopeMetric> resourceScopeMetrics,
      String metricName,
      String localService,
      String localOperation,
      String service,
      String method,
      String target,
      Double expectedSum) {
    assertMetricAttributes(
        resourceScopeMetrics,
        metricName,
        "CONSUMER",
        localService,
        localOperation,
        service,
        method,
        target,
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
      String target,
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
                            target,
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
    var target = "::s3:::create-bucket";

    assertSpanClientAttributes(
        traces,
        s3SpanName("CreateBucket"),
        getS3RpcServiceName(),
        localService,
        localOperation,
        getS3ServiceName(),
        "CreateBucket",
        target,
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
        target,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "CreateBucket",
        target,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "CreateBucket",
        target,
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
    var target = "::s3:::put-object";

    assertSpanClientAttributes(
        traces,
        s3SpanName("PutObject"),
        getS3RpcServiceName(),
        localService,
        localOperation,
        getS3ServiceName(),
        "PutObject",
        target,
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
        target,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "PutObject",
        target,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "PutObject",
        target,
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
    var target = "::s3:::get-object";

    assertSpanClientAttributes(
        traces,
        s3SpanName("GetObject"),
        getS3RpcServiceName(),
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        target,
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
        target,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        target,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        target,
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
    var target = "::s3:::error-bucket";

    assertSpanClientAttributes(
        traces,
        s3SpanName("GetObject"),
        getS3RpcServiceName(),
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        target,
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
        target,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        target,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        target,
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
    var target = "::s3:::fault-bucket";

    assertSpanClientAttributes(
        traces,
        s3SpanName("GetObject"),
        getS3RpcServiceName(),
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        target,
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
        target,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        target,
        1.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getS3ServiceName(),
        "GetObject",
        target,
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
    var target = "::dynamodb:::table/some-table";

    assertSpanClientAttributes(
        traces,
        dynamoDbSpanName("CreateTable"),
        getDynamoDbRpcServiceName(),
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "CreateTable",
        target,
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
        target,
        20000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "CreateTable",
        target,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "CreateTable",
        target,
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
    var target = "::dynamodb:::table/putitem-table";

    assertSpanClientAttributes(
        traces,
        dynamoDbSpanName("PutItem"),
        getDynamoDbRpcServiceName(),
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        target,
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
        target,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        target,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        target,
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
    var target = "::dynamodb:::table/nonexistanttable";

    assertSpanClientAttributes(
        traces,
        dynamoDbSpanName("PutItem"),
        getDynamoDbRpcServiceName(),
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        target,
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
        target,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        target,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        target,
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
    var target = "::dynamodb:::table/nonexistanttable";

    assertSpanClientAttributes(
        traces,
        dynamoDbSpanName("PutItem"),
        getDynamoDbRpcServiceName(),
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        target,
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
        target,
        20000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        target,
        1.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getDynamoDbServiceName(),
        "PutItem",
        target,
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
    var target = "::sqs:::some-queue";

    assertSpanClientAttributes(
        traces,
        sqsSpanName("CreateQueue"),
        getSqsRpcServiceName(),
        localService,
        localOperation,
        getSqsServiceName(),
        "CreateQueue",
        target,
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
        target,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "CreateQueue",
        target,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "CreateQueue",
        target,
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
    String target = "::sqs::000000000000:some-queue";

    assertSpanProducerAttributes(
        traces,
        "some-queue publish",
        getSqsRpcServiceName(),
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        target,
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
        target,
        5000.0);
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        target,
        0.0);
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        target,
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
    String target = "::sqs::000000000000:some-queue";

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
        target,
        5000.0);
    assertMetricConsumerAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "ReceiveMessage",
        target,
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
    String target = "::sqs::000000000000:some-queue";

    assertSpanProducerAttributes(
        traces,
        "error.test:8080 publish",
        getSqsRpcServiceName(),
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        target,
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
        target,
        5000.0);
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        target,
        0.0);
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        target,
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
    String target = "::sqs::000000000000:some-queue";

    assertSpanProducerAttributes(
        traces,
        "fault.test:8080 publish",
        getSqsRpcServiceName(),
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        target,
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
        target,
        5000.0);
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        target,
        1.0);
    assertMetricProducerAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSqsServiceName(),
        "SendMessage",
        target,
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
    var target = "::kinesis:::stream/my-stream";

    assertSpanClientAttributes(
        traces,
        kinesisSpanName("PutRecord"),
        getKinesisRpcServiceName(),
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        target,
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
        target,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        target,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        target,
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
    var target = "::kinesis:::stream/nonexistantstream";

    assertSpanClientAttributes(
        traces,
        kinesisSpanName("PutRecord"),
        getKinesisRpcServiceName(),
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        target,
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
        target,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        target,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        target,
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
    var target = "::kinesis:::stream/faultstream";

    assertSpanClientAttributes(
        traces,
        kinesisSpanName("PutRecord"),
        getKinesisRpcServiceName(),
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        target,
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
        target,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        target,
        1.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getKinesisServiceName(),
        "PutRecord",
        target,
        0.0);
  }
}
