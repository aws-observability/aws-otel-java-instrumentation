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
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.5.0"))
          .withServices(
              LocalStackContainer.Service.S3,
              LocalStackContainer.Service.DYNAMODB,
              LocalStackContainer.Service.SQS,
              LocalStackContainer.Service.KINESIS,
              LocalStackContainer.Service.SECRETSMANAGER,
              LocalStackContainer.Service.IAM,
              LocalStackContainer.Service.STEPFUNCTIONS,
              LocalStackContainer.Service.SNS)
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

  protected abstract String getSecretsManagerSpanNamePrefix();

  protected abstract String getStepFunctionsSpanNamePrefix();

  protected abstract String getSnsSpanNamePrefix();

  protected abstract String getS3RpcServiceName();

  protected abstract String getDynamoDbRpcServiceName();

  protected abstract String getSqsRpcServiceName();

  protected abstract String getKinesisRpcServiceName();

  protected abstract String getBedrockRpcServiceName();

  protected abstract String getBedrockAgentRpcServiceName();

  protected abstract String getBedrockRuntimeRpcServiceName();

  protected abstract String getBedrockAgentRuntimeRpcServiceName();

  protected abstract String getSecretsManagerRpcServiceName();

  protected abstract String getSnsRpcServiceName();

  protected abstract String getStepFunctionsRpcServiceName();

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

  private String getSecretsManagerServiceName() {
    return "AWS::SecretsManager";
  }

  private String getStepFunctionsServiceName() {
    return "AWS::StepFunctions";
  }

  protected String getSnsServiceName() {
    return "AWS::SNS";
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

  private String secretsManagerSpanName(String operation) {
    return String.format("%s.%s", getSecretsManagerSpanNamePrefix(), operation);
  }

  private String stepFunctionsSpanName(String operation) {
    return String.format("%s.%s", getStepFunctionsSpanNamePrefix(), operation);
  }

  private String snsSpanName(String operation) {
    return String.format("%s.%s", getSnsSpanNamePrefix(), operation);
  }

  protected ThrowingConsumer<KeyValue> assertAttribute(String key, String value) {
    return (attribute) -> {
      var actualKey = attribute.getKey();
      var actualValue = attribute.getValue().getStringValue();

      assertThat(actualKey).isEqualTo(key);

      // We only want to Regex Pattern Match on the Secret Id and Secret Arn
      if (actualValue.contains("secret-id")) {
        assertThat(actualValue).matches(value);
      } else {
        assertThat(actualValue).isEqualTo(value);
      }
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
      String address,
      int port,
      String url,
      int statusCode) {
    assertThat(attributesList)
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.RPC_METHOD, method))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.RPC_SERVICE, service))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.RPC_SYSTEM, "aws-api"))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.SERVER_ADDRESS, address))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.SERVER_PORT, port))
        .satisfiesOnlyOnce(
            assertAttribute(SemanticConventionsConstants.HTTP_RESPONSE_STATUS_CODE, statusCode))
        .satisfiesOnlyOnce(assertAttributeStartsWith(SemanticConventionsConstants.URL_FULL, url))
        .satisfiesOnlyOnce(assertKeyIsPresent(SemanticConventionsConstants.THREAD_ID));
  }

  /** All the spans of the AWS SDK Should have a RPC properties. */
  private void assertSemanticConventionsSqsConsumerAttributes(
      List<KeyValue> attributesList,
      String service,
      String method,
      String address,
      int port,
      String url) {
    assertThat(attributesList)
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.RPC_METHOD, method))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.RPC_SERVICE, service))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.RPC_SYSTEM, "aws-api"))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.SERVER_ADDRESS, address))
        .satisfiesOnlyOnce(assertAttribute(SemanticConventionsConstants.SERVER_PORT, port))
        .satisfiesOnlyOnce(assertAttributeStartsWith(SemanticConventionsConstants.URL_FULL, url))
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
      String cloudformationIdentifier,
      String address,
      int port,
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
        cloudformationIdentifier,
        address,
        port,
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
      String cloudformationIdentifier,
      String address,
      int port,
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
        cloudformationIdentifier,
        address,
        port,
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
      String address,
      int port,
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
                  spanAttributes, rpcService, method, address, port, url);
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
      String cloudformationIdentifier,
      String address,
      int port,
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
                  spanAttributes, rpcService, method, address, port, url, statusCode);
              assertAwsAttributes(
                  spanAttributes,
                  localService,
                  localOperation,
                  service,
                  method,
                  type,
                  identifier,
                  cloudformationIdentifier,
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
      String clouformationIdentifier,
      String spanKind) {

    var assertions =
        assertThat(attributesList)
            .satisfiesOnlyOnce(
                assertAttribute(AppSignalsConstants.AWS_LOCAL_OPERATION, localOperation))
            .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_LOCAL_SERVICE, localService))
            .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_REMOTE_OPERATION, operation))
            .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_REMOTE_SERVICE, service))
            .satisfiesOnlyOnce(assertAttribute(AppSignalsConstants.AWS_SPAN_KIND, spanKind));
    if (type != null && identifier != null && clouformationIdentifier != null) {
      assertions.satisfiesOnlyOnce(
          assertAttribute(AppSignalsConstants.AWS_REMOTE_RESOURCE_TYPE, type));
      assertions.satisfiesOnlyOnce(
          assertAttribute(AppSignalsConstants.AWS_REMOTE_RESOURCE_IDENTIFIER, identifier));
      assertions.satisfiesOnlyOnce(
          assertAttribute(
              AppSignalsConstants.AWS_CLOUDFORMATION_PRIMARY_IDENTIFIER, clouformationIdentifier));
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
      String cloudformationIdentifier,
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
        cloudformationIdentifier,
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
      String cloudformationIdentifier,
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
        cloudformationIdentifier,
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
      String cloudformationIdentifier,
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
        cloudformationIdentifier,
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
      String cloudformationIdentifier,
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
                            cloudformationIdentifier,
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
    var cloudformationIdentifier = "create-bucket";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    var cloudformationIdentifier = "put-object";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    var cloudformationIdentifier = "get-object";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    var cloudformationIdentifier = "error-bucket";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    var cloudformationIdentifier = "fault-bucket";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    var cloudformationIdentifier = "some-table";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    var cloudformationIdentifier = "putitem-table";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    var cloudformationIdentifier = "nonexistanttable";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    var cloudformationIdentifier = "nonexistanttable";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    var cloudformationIdentifier = "some-queue";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    String cloudformationIdentifier = null;

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    String cloudformationIdentifier = null;
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    String cloudformationIdentifier = null;

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    String cloudformationIdentifier = null;

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    var cloudformationIdentifier = "my-stream";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    var cloudformationIdentifier = "nonexistantstream";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    var cloudformationIdentifier = "faultstream";

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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    String cloudformationIdentifier = "knowledge-base-id";
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    String cloudformationIdentifier = "test-agent-id";
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    String cloudformationIdentifier = "nonExistDatasourceId";
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
        0.0);
  }

  protected void doTestBedrockRuntimeAi21Jamba() {
    var response = appClient.get("/bedrockruntime/invokeModel/ai21Jamba").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrockruntime/invokeModel/ai21Jamba";
    String type = "AWS::Bedrock::Model";
    String identifier = "ai21.jamba-1-5-mini-v1:0";
    String cloudformationIdentifier = "ai21.jamba-1-5-mini-v1:0";
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
        cloudformationIdentifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.GEN_AI_REQUEST_MODEL, "ai21.jamba-1-5-mini-v1:0"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_SYSTEM, "aws.bedrock"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_TEMPERATURE, "0.7"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_TOP_P, "0.8"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_RESPONSE_FINISH_REASONS, "[stop]"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_USAGE_INPUT_TOKENS, "5"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_USAGE_OUTPUT_TOKENS, "42")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockRuntimeServiceName(),
        "InvokeModel",
        type,
        identifier,
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
        0.0);
  }

  protected void doTestBedrockRuntimeAmazonTitan() {
    var response = appClient.get("/bedrockruntime/invokeModel/amazonTitan").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrockruntime/invokeModel/amazonTitan";
    String type = "AWS::Bedrock::Model";
    String identifier = "amazon.titan-text-premier-v1:0";
    String cloudformationIdentifier = "amazon.titan-text-premier-v1:0";
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
        cloudformationIdentifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.GEN_AI_REQUEST_MODEL,
                "amazon.titan-text-premier-v1:0"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_SYSTEM, "aws.bedrock"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_MAX_TOKENS, "100"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_TEMPERATURE, "0.7"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_TOP_P, "0.9"),
            assertAttribute(
                SemanticConventionsConstants.GEN_AI_RESPONSE_FINISH_REASONS, "[FINISHED]"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_USAGE_INPUT_TOKENS, "10"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_USAGE_OUTPUT_TOKENS, "15")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockRuntimeServiceName(),
        "InvokeModel",
        type,
        identifier,
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
        0.0);
  }

  protected void doTestBedrockRuntimeAnthropicClaude() {
    var response = appClient.get("/bedrockruntime/invokeModel/anthropicClaude").aggregate().join();

    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrockruntime/invokeModel/anthropicClaude";
    String type = "AWS::Bedrock::Model";
    String identifier = "anthropic.claude-3-haiku-20240307-v1:0";
    String cloudformationIdentifier = "anthropic.claude-3-haiku-20240307-v1:0";

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
        cloudformationIdentifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.GEN_AI_REQUEST_MODEL,
                "anthropic.claude-3-haiku-20240307-v1:0"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_SYSTEM, "aws.bedrock"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_MAX_TOKENS, "512"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_TEMPERATURE, "0.6"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_TOP_P, "0.53"),
            assertAttribute(
                SemanticConventionsConstants.GEN_AI_RESPONSE_FINISH_REASONS, "[end_turn]"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_USAGE_INPUT_TOKENS, "2095"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_USAGE_OUTPUT_TOKENS, "503")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockRuntimeServiceName(),
        "InvokeModel",
        type,
        identifier,
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
        0.0);
  }

  protected void doTestBedrockRuntimeCohereCommandR() {
    var response = appClient.get("/bedrockruntime/invokeModel/cohereCommandR").aggregate().join();

    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrockruntime/invokeModel/cohereCommandR";
    String type = "AWS::Bedrock::Model";
    String identifier = "cohere.command-r-v1:0";
    String cloudformationIdentifier = "cohere.command-r-v1:0";

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
        cloudformationIdentifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.GEN_AI_REQUEST_MODEL, "cohere.command-r-v1:0"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_SYSTEM, "aws.bedrock"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_MAX_TOKENS, "4096"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_TEMPERATURE, "0.8"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_TOP_P, "0.45"),
            assertAttribute(
                SemanticConventionsConstants.GEN_AI_RESPONSE_FINISH_REASONS, "[COMPLETE]"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_USAGE_INPUT_TOKENS, "9"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_USAGE_OUTPUT_TOKENS, "16")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockRuntimeServiceName(),
        "InvokeModel",
        type,
        identifier,
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
        0.0);
  }

  protected void doTestBedrockRuntimeMetaLlama() {
    var response = appClient.get("/bedrockruntime/invokeModel/metaLlama").aggregate().join();

    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrockruntime/invokeModel/metaLlama";
    String type = "AWS::Bedrock::Model";
    String identifier = "meta.llama3-70b-instruct-v1:0";
    String cloudformationIdentifier = "meta.llama3-70b-instruct-v1:0";

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
        cloudformationIdentifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.GEN_AI_REQUEST_MODEL, "meta.llama3-70b-instruct-v1:0"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_SYSTEM, "aws.bedrock"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_MAX_TOKENS, "128"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_TEMPERATURE, "0.1"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_TOP_P, "0.9"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_RESPONSE_FINISH_REASONS, "[stop]"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_USAGE_INPUT_TOKENS, "2095"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_USAGE_OUTPUT_TOKENS, "503")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockRuntimeServiceName(),
        "InvokeModel",
        type,
        identifier,
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
        0.0);
  }

  protected void doTestBedrockRuntimeMistral() {
    var response = appClient.get("/bedrockruntime/invokeModel/mistralAi").aggregate().join();

    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /bedrockruntime/invokeModel/mistralAi";
    String type = "AWS::Bedrock::Model";
    String identifier = "mistral.mistral-large-2402-v1:0";
    String cloudformationIdentifier = "mistral.mistral-large-2402-v1:0";

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
        cloudformationIdentifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.GEN_AI_REQUEST_MODEL,
                "mistral.mistral-large-2402-v1:0"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_SYSTEM, "aws.bedrock"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_MAX_TOKENS, "4096"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_TEMPERATURE, "0.75"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_REQUEST_TOP_P, "0.25"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_RESPONSE_FINISH_REASONS, "[stop]"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_USAGE_INPUT_TOKENS, "16"),
            assertAttribute(SemanticConventionsConstants.GEN_AI_USAGE_OUTPUT_TOKENS, "24")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockRuntimeServiceName(),
        "InvokeModel",
        type,
        identifier,
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    String cloudformationIdentifier =
        "arn:aws:bedrock:us-east-1:000000000000:guardrail/test-bedrock-guardrail";
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
        cloudformationIdentifier,
        "bedrock.test",
        8080,
        "http://bedrock.test:8080",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.AWS_GUARDRAIL_ID, "test-bedrock-guardrail"),
            assertAttribute(
                SemanticConventionsConstants.AWS_GUARDRAIL_ARN,
                "arn:aws:bedrock:us-east-1:000000000000:guardrail/test-bedrock-guardrail")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getBedrockServiceName(),
        "GetGuardrail",
        type,
        identifier,
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    String cloudformationIdentifier = "test-agent-id";
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
    String cloudformationIdentifier = "test-knowledge-base-id";
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
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
        cloudformationIdentifier,
        0.0);
  }

  protected void doTestSecretsManagerDescribeSecret() throws Exception {
    appClient.get("/secretsmanager/describesecret/test-secret-id").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));
    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /secretsmanager/describesecret/:secretId";
    var type = "AWS::SecretsManager::Secret";
    var identifier = "test-secret-id-[A-Za-z0-9]{6}";
    var cloudformationIdentifier =
        "arn:aws:secretsmanager:us-west-2:000000000000:secret:test-secret-id-[A-Za-z0-9]{6}";
    assertSpanClientAttributes(
        traces,
        secretsManagerSpanName("DescribeSecret"),
        getSecretsManagerRpcServiceName(),
        localService,
        localOperation,
        getSecretsManagerServiceName(),
        "DescribeSecret",
        type,
        identifier,
        cloudformationIdentifier,
        "localstack",
        4566,
        "http://localstack:4566",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.AWS_SECRET_ARN,
                "arn:aws:secretsmanager:us-west-2:000000000000:secret:test-secret-id-[A-Za-z0-9]{6}")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getSecretsManagerServiceName(),
        "DescribeSecret",
        type,
        identifier,
        cloudformationIdentifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSecretsManagerServiceName(),
        "DescribeSecret",
        type,
        identifier,
        cloudformationIdentifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSecretsManagerServiceName(),
        "DescribeSecret",
        type,
        identifier,
        cloudformationIdentifier,
        0.0);
  }

  protected void doTestSecretsManagerError() throws Exception {
    appClient.get("/secretsmanager/error").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));
    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /secretsmanager/error";
    assertSpanClientAttributes(
        traces,
        secretsManagerSpanName("DescribeSecret"),
        getSecretsManagerRpcServiceName(),
        localService,
        localOperation,
        getSecretsManagerServiceName(),
        "DescribeSecret",
        null,
        null,
        null,
        "error.test",
        8080,
        "http://error.test:8080",
        400,
        List.of());
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getSecretsManagerServiceName(),
        "DescribeSecret",
        null,
        null,
        null,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSecretsManagerServiceName(),
        "DescribeSecret",
        null,
        null,
        null,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSecretsManagerServiceName(),
        "DescribeSecret",
        null,
        null,
        null,
        1.0);
  }

  protected void doTestSecretsManagerFault() throws Exception {
    appClient.get("/secretsmanager/fault").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /secretsmanager/fault";
    assertSpanClientAttributes(
        traces,
        secretsManagerSpanName("DescribeSecret"),
        getSecretsManagerRpcServiceName(),
        localService,
        localOperation,
        getSecretsManagerServiceName(),
        "DescribeSecret",
        null,
        null,
        null,
        "fault.test",
        8080,
        "http://fault.test:8080",
        500,
        List.of());
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getSecretsManagerServiceName(),
        "DescribeSecret",
        null,
        null,
        null,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSecretsManagerServiceName(),
        "DescribeSecret",
        null,
        null,
        null,
        1.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSecretsManagerServiceName(),
        "DescribeSecret",
        null,
        null,
        null,
        0.0);
  }

  protected void doTestStepFunctionsDescribeStateMachine() throws Exception {
    appClient.get("/sfn/describestatemachine/test-state-machine").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));
    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /sfn/describestatemachine/:name";
    var type = "AWS::StepFunctions::StateMachine";
    var identifier = "test-state-machine";
    var cloudformationIdentifier =
        "arn:aws:states:us-west-2:000000000000:stateMachine:test-state-machine";

    assertSpanClientAttributes(
        traces,
        stepFunctionsSpanName("DescribeStateMachine"),
        getStepFunctionsRpcServiceName(),
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeStateMachine",
        type,
        identifier,
        cloudformationIdentifier,
        "localstack",
        4566,
        "http://localstack:4566",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.AWS_STATE_MACHINE_ARN,
                "arn:aws:states:us-west-2:000000000000:stateMachine:test-state-machine")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeStateMachine",
        type,
        identifier,
        cloudformationIdentifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeStateMachine",
        type,
        identifier,
        cloudformationIdentifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeStateMachine",
        type,
        identifier,
        cloudformationIdentifier,
        0.0);
  }

  protected void doTestStepFunctionsDescribeActivity() throws Exception {
    appClient.get("/sfn/describeactivity/test-activity").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));
    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /sfn/describeactivity/:name";
    var type = "AWS::StepFunctions::Activity";
    var identifier = "test-activity";
    var cloudformationIdentifier = "arn:aws:states:us-west-2:000000000000:activity:test-activity";

    assertSpanClientAttributes(
        traces,
        stepFunctionsSpanName("DescribeActivity"),
        getStepFunctionsRpcServiceName(),
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeActivity",
        type,
        identifier,
        cloudformationIdentifier,
        "localstack",
        4566,
        "http://localstack:4566",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.AWS_ACTIVITY_ARN,
                "arn:aws:states:us-west-2:000000000000:activity:test-activity")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeActivity",
        type,
        identifier,
        cloudformationIdentifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeActivity",
        type,
        identifier,
        cloudformationIdentifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeActivity",
        type,
        identifier,
        cloudformationIdentifier,
        0.0);
  }

  protected void doTestStepFunctionsError() throws Exception {
    appClient.get("/sfn/error").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /sfn/error";
    var type = "AWS::StepFunctions::Activity";
    var identifier = "nonexistent-activity";
    var cloudformationIdentifier =
        "arn:aws:states:us-west-2:000000000000:activity:nonexistent-activity";

    assertSpanClientAttributes(
        traces,
        stepFunctionsSpanName("DescribeActivity"),
        getStepFunctionsRpcServiceName(),
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeActivity",
        type,
        identifier,
        cloudformationIdentifier,
        "error.test",
        8080,
        "http://error.test:8080",
        400,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.AWS_ACTIVITY_ARN,
                "arn:aws:states:us-west-2:000000000000:activity:nonexistent-activity")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeActivity",
        type,
        identifier,
        cloudformationIdentifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeActivity",
        type,
        identifier,
        cloudformationIdentifier,
        0.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeActivity",
        type,
        identifier,
        cloudformationIdentifier,
        1.0);
  }

  protected void doTestStepFunctionsFault() throws Exception {
    appClient.get("/sfn/fault").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /sfn/fault";
    var type = "AWS::StepFunctions::Activity";
    var identifier = "fault-activity";
    var cloudformationIdentifier = "arn:aws:states:us-west-2:000000000000:activity:fault-activity";

    assertSpanClientAttributes(
        traces,
        stepFunctionsSpanName("DescribeActivity"),
        getStepFunctionsRpcServiceName(),
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeActivity",
        type,
        identifier,
        cloudformationIdentifier,
        "fault.test",
        8080,
        "http://fault.test:8080",
        500,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.AWS_ACTIVITY_ARN,
                "arn:aws:states:us-west-2:000000000000:activity:fault-activity")));
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeActivity",
        type,
        identifier,
        cloudformationIdentifier,
        5000.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeActivity",
        type,
        identifier,
        cloudformationIdentifier,
        1.0);
    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getStepFunctionsServiceName(),
        "DescribeActivity",
        type,
        identifier,
        cloudformationIdentifier,
        0.0);
  }

  protected void doTestSnsGetTopicAttributes() throws Exception {
    appClient.get("/sns/gettopicattributes/test-topic").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /sns/gettopicattributes/:topicId";
    var type = "AWS::SNS::Topic";
    var identifier = "test-topic";
    var cloudformationIdentifier = "arn:aws:sns:us-west-2:000000000000:test-topic";

    assertSpanClientAttributes(
        traces,
        snsSpanName("GetTopicAttributes"),
        getSnsRpcServiceName(),
        localService,
        localOperation,
        getSnsServiceName(),
        "GetTopicAttributes",
        type,
        identifier,
        cloudformationIdentifier,
        "localstack",
        4566,
        "http://localstack:4566",
        200,
        List.of(
            assertAttribute(
                SemanticConventionsConstants.AWS_TOPIC_ARN,
                "arn:aws:sns:us-west-2:000000000000:test-topic")));
  }

  protected void doTestSnsError() throws Exception {
    appClient.get("/sns/error").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /sns/error";
    assertSpanClientAttributes(
        traces,
        snsSpanName("GetTopicAttributes"),
        getSnsRpcServiceName(),
        localService,
        localOperation,
        getSnsServiceName(),
        "GetTopicAttributes",
        null,
        null,
        null,
        "error.test",
        8080,
        "http://error.test:8080",
        400,
        List.of());

    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getSnsServiceName(),
        "GetTopicAttributes",
        null,
        null,
        null,
        5000.0);

    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSnsServiceName(),
        "GetTopicAttributes",
        null,
        null,
        null,
        0.0);

    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSnsServiceName(),
        "GetTopicAttributes",
        null,
        null,
        null,
        1.0);
  }

  protected void doTestSnsFault() throws Exception {
    appClient.get("/sns/fault").aggregate().join();
    var traces = mockCollectorClient.getTraces();
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC,
                AppSignalsConstants.LATENCY_METRIC));

    var localService = getApplicationOtelServiceName();
    var localOperation = "GET /sns/fault";
    assertSpanClientAttributes(
        traces,
        snsSpanName("GetTopicAttributes"),
        getSnsRpcServiceName(),
        localService,
        localOperation,
        getSnsServiceName(),
        "GetTopicAttributes",
        null,
        null,
        null,
        "fault.test",
        8080,
        "http://fault.test:8080",
        500,
        List.of());

    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.LATENCY_METRIC,
        localService,
        localOperation,
        getSnsServiceName(),
        "GetTopicAttributes",
        null,
        null,
        null,
        5000.0);

    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.FAULT_METRIC,
        localService,
        localOperation,
        getSnsServiceName(),
        "GetTopicAttributes",
        null,
        null,
        null,
        1.0);

    assertMetricClientAttributes(
        metrics,
        AppSignalsConstants.ERROR_METRIC,
        localService,
        localOperation,
        getSnsServiceName(),
        "GetTopicAttributes",
        null,
        null,
        null,
        0.0);
  }
}
