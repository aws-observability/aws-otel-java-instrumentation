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

package software.amazon.opentelemetry.javaagent.providers;

import static io.opentelemetry.semconv.ResourceAttributes.SERVICE_NAME;
import static io.opentelemetry.semconv.SemanticAttributes.*;
import static io.opentelemetry.semconv.SemanticAttributes.MessagingOperationValues.PROCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.*;
import static software.amazon.opentelemetry.javaagent.providers.MetricAttributeGenerator.DEPENDENCY_METRIC;
import static software.amazon.opentelemetry.javaagent.providers.MetricAttributeGenerator.SERVICE_METRIC;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.internal.data.ExceptionEventData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AwsMetricAttributeGenerator}. */
class AwsMetricAttributeGeneratorTest {

  private static final AwsMetricAttributeGenerator GENERATOR = new AwsMetricAttributeGenerator();

  // String constants that are used many times in these tests.
  private static final String AWS_LOCAL_OPERATION_VALUE = "AWS local operation";
  private static final String AWS_REMOTE_SERVICE_VALUE = "AWS remote service";
  private static final String AWS_REMOTE_OPERATION_VALUE = "AWS remote operation";
  private static final String SERVICE_NAME_VALUE = "Service name";
  private static final String SPAN_NAME_VALUE = "Span name";
  private static final String UNKNOWN_SERVICE = "UnknownService";
  private static final String UNKNOWN_OPERATION = "UnknownOperation";
  private static final String UNKNOWN_REMOTE_SERVICE = "UnknownRemoteService";
  private static final String UNKNOWN_REMOTE_OPERATION = "UnknownRemoteOperation";
  private static final String INTERNAL_OPERATION = "InternalOperation";
  private static final String LOCAL_ROOT = "LOCAL_ROOT";

  private Attributes attributesMock;
  private SpanData spanDataMock;
  private InstrumentationScopeInfo instrumentationScopeInfoMock;
  private Resource resource;
  private SpanContext parentSpanContextMock;

  static class ThrowableWithMethodGetStatusCode extends Throwable {
    private final int httpStatusCode;

    ThrowableWithMethodGetStatusCode(int httpStatusCode) {
      this.httpStatusCode = httpStatusCode;
    }

    public int getStatusCode() {
      return this.httpStatusCode;
    }
  }

  static class ThrowableWithMethodStatusCode extends Throwable {
    private final int httpStatusCode;

    ThrowableWithMethodStatusCode(int httpStatusCode) {
      this.httpStatusCode = httpStatusCode;
    }

    public int statusCode() {
      return this.httpStatusCode;
    }
  }

  static class ThrowableWithoutStatusCode extends Throwable {}

  @BeforeEach
  public void setUpMocks() {
    attributesMock = mock(Attributes.class);
    instrumentationScopeInfoMock = mock(InstrumentationScopeInfo.class);
    when(instrumentationScopeInfoMock.getName()).thenReturn("Scope name");
    spanDataMock = mock(SpanData.class);
    when(spanDataMock.getAttributes()).thenReturn(attributesMock);
    when(spanDataMock.getInstrumentationScopeInfo()).thenReturn(instrumentationScopeInfoMock);
    when(spanDataMock.getSpanContext()).thenReturn(mock(SpanContext.class));
    parentSpanContextMock = mock(SpanContext.class);
    when(parentSpanContextMock.isValid()).thenReturn(true);
    when(parentSpanContextMock.isRemote()).thenReturn(false);
    when(spanDataMock.getParentSpanContext()).thenReturn(parentSpanContextMock);

    // OTel strongly recommends to start out with the default instead of Resource.empty()
    resource = Resource.getDefault();
  }

  @Test
  public void testSpanAttributesForEmptyResource() {
    resource = Resource.empty();
    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.SERVER.name(),
            AWS_LOCAL_SERVICE, UNKNOWN_SERVICE,
            AWS_LOCAL_OPERATION, UNKNOWN_OPERATION);
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.SERVER);
  }

  @Test
  public void testConsumerSpanWithoutAttributes() {
    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.CONSUMER.name(),
            AWS_LOCAL_SERVICE, UNKNOWN_SERVICE,
            AWS_LOCAL_OPERATION, UNKNOWN_OPERATION,
            AWS_REMOTE_SERVICE, UNKNOWN_REMOTE_SERVICE,
            AWS_REMOTE_OPERATION, UNKNOWN_REMOTE_OPERATION);
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.CONSUMER);
  }

  @Test
  public void testServerSpanWithoutAttributes() {
    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.SERVER.name(),
            AWS_LOCAL_SERVICE, UNKNOWN_SERVICE,
            AWS_LOCAL_OPERATION, UNKNOWN_OPERATION);
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.SERVER);
  }

  @Test
  public void testProducerSpanWithoutAttributes() {
    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.PRODUCER.name(),
            AWS_LOCAL_SERVICE, UNKNOWN_SERVICE,
            AWS_LOCAL_OPERATION, UNKNOWN_OPERATION,
            AWS_REMOTE_SERVICE, UNKNOWN_REMOTE_SERVICE,
            AWS_REMOTE_OPERATION, UNKNOWN_REMOTE_OPERATION);
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.PRODUCER);
  }

  @Test
  public void testClientSpanWithoutAttributes() {
    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.CLIENT.name(),
            AWS_LOCAL_SERVICE, UNKNOWN_SERVICE,
            AWS_LOCAL_OPERATION, UNKNOWN_OPERATION,
            AWS_REMOTE_SERVICE, UNKNOWN_REMOTE_SERVICE,
            AWS_REMOTE_OPERATION, UNKNOWN_REMOTE_OPERATION);
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.CLIENT);
  }

  @Test
  public void testInternalSpan() {
    // Spans with internal span kind should not produce any attributes.
    validateAttributesProducedForNonLocalRootSpanOfKind(Attributes.empty(), SpanKind.INTERNAL);
  }

  @Test
  public void testLocalRootServerSpan() {
    updateResourceWithServiceName();
    when(parentSpanContextMock.isValid()).thenReturn(false);
    when(spanDataMock.getName()).thenReturn(SPAN_NAME_VALUE);

    Map<String, Attributes> expectedAttributesMap = new HashMap<>();
    expectedAttributesMap.put(
        MetricAttributeGenerator.SERVICE_METRIC,
        Attributes.of(
            AWS_SPAN_KIND, LOCAL_ROOT,
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, SPAN_NAME_VALUE));

    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    Map<String, Attributes> actualAttributesMap =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource);
    assertThat(actualAttributesMap).isEqualTo(expectedAttributesMap);
  }

  @Test
  public void testLocalRootInternalSpan() {
    updateResourceWithServiceName();
    when(parentSpanContextMock.isValid()).thenReturn(false);
    when(spanDataMock.getName()).thenReturn(SPAN_NAME_VALUE);

    Map<String, Attributes> expectedAttributesMap = new HashMap<>();
    expectedAttributesMap.put(
        MetricAttributeGenerator.SERVICE_METRIC,
        Attributes.of(
            AWS_SPAN_KIND, LOCAL_ROOT,
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, INTERNAL_OPERATION));

    when(spanDataMock.getKind()).thenReturn(SpanKind.INTERNAL);
    Map<String, Attributes> actualAttributesMap =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource);
    assertThat(actualAttributesMap).isEqualTo(expectedAttributesMap);
  }

  @Test
  public void testLocalRootClientSpan() {
    updateResourceWithServiceName();
    when(parentSpanContextMock.isValid()).thenReturn(false);
    when(spanDataMock.getName()).thenReturn(SPAN_NAME_VALUE);
    mockAttribute(AWS_REMOTE_SERVICE, AWS_REMOTE_SERVICE_VALUE);
    mockAttribute(AWS_REMOTE_OPERATION, AWS_REMOTE_OPERATION_VALUE);

    Map<String, Attributes> expectedAttributesMap = new HashMap<>();

    expectedAttributesMap.put(
        MetricAttributeGenerator.SERVICE_METRIC,
        Attributes.of(
            AWS_SPAN_KIND, LOCAL_ROOT,
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, INTERNAL_OPERATION));
    expectedAttributesMap.put(
        MetricAttributeGenerator.DEPENDENCY_METRIC,
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.CLIENT.name(),
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, INTERNAL_OPERATION,
            AWS_REMOTE_SERVICE, AWS_REMOTE_SERVICE_VALUE,
            AWS_REMOTE_OPERATION, AWS_REMOTE_OPERATION_VALUE));

    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    Map<String, Attributes> actualAttributesMap =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource);
    assertThat(actualAttributesMap).isEqualTo(expectedAttributesMap);
  }

  @Test
  public void testLocalRootConsumerSpan() {
    updateResourceWithServiceName();
    when(parentSpanContextMock.isValid()).thenReturn(false);
    when(spanDataMock.getName()).thenReturn(SPAN_NAME_VALUE);
    mockAttribute(AWS_REMOTE_SERVICE, AWS_REMOTE_SERVICE_VALUE);
    mockAttribute(AWS_REMOTE_OPERATION, AWS_REMOTE_OPERATION_VALUE);

    Map<String, Attributes> expectedAttributesMap = new HashMap<>();

    expectedAttributesMap.put(
        MetricAttributeGenerator.SERVICE_METRIC,
        Attributes.of(
            AWS_SPAN_KIND, LOCAL_ROOT,
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, INTERNAL_OPERATION));

    expectedAttributesMap.put(
        DEPENDENCY_METRIC,
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.CONSUMER.name(),
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, INTERNAL_OPERATION,
            AWS_REMOTE_SERVICE, AWS_REMOTE_SERVICE_VALUE,
            AWS_REMOTE_OPERATION, AWS_REMOTE_OPERATION_VALUE));

    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    Map<String, Attributes> actualAttributesMap =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource);
    assertThat(actualAttributesMap).isEqualTo(expectedAttributesMap);
  }

  @Test
  public void testLocalRootProducerSpan() {
    updateResourceWithServiceName();
    when(parentSpanContextMock.isValid()).thenReturn(false);
    when(spanDataMock.getName()).thenReturn(SPAN_NAME_VALUE);
    mockAttribute(AWS_REMOTE_SERVICE, AWS_REMOTE_SERVICE_VALUE);
    mockAttribute(AWS_REMOTE_OPERATION, AWS_REMOTE_OPERATION_VALUE);

    Map<String, Attributes> expectedAttributesMap = new HashMap<>();

    expectedAttributesMap.put(
        MetricAttributeGenerator.SERVICE_METRIC,
        Attributes.of(
            AWS_SPAN_KIND, LOCAL_ROOT,
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, INTERNAL_OPERATION));

    expectedAttributesMap.put(
        MetricAttributeGenerator.DEPENDENCY_METRIC,
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.PRODUCER.name(),
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, INTERNAL_OPERATION,
            AWS_REMOTE_SERVICE, AWS_REMOTE_SERVICE_VALUE,
            AWS_REMOTE_OPERATION, AWS_REMOTE_OPERATION_VALUE));

    when(spanDataMock.getKind()).thenReturn(SpanKind.PRODUCER);
    Map<String, Attributes> actualAttributesMap =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource);
    assertThat(actualAttributesMap).isEqualTo(expectedAttributesMap);
  }

  @Test
  public void testConsumerSpanWithAttributes() {
    updateResourceWithServiceName();
    when(spanDataMock.getName()).thenReturn(SPAN_NAME_VALUE);

    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.CONSUMER.name(),
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, UNKNOWN_OPERATION,
            AWS_REMOTE_SERVICE, UNKNOWN_REMOTE_SERVICE,
            AWS_REMOTE_OPERATION, UNKNOWN_REMOTE_OPERATION);
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.CONSUMER);
  }

  @Test
  public void testServerSpanWithAttributes() {
    updateResourceWithServiceName();
    when(spanDataMock.getName()).thenReturn(SPAN_NAME_VALUE);

    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.SERVER.name(),
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, SPAN_NAME_VALUE);
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.SERVER);
  }

  @Test
  public void testServerSpanWithNullSpanName() {
    updateResourceWithServiceName();
    when(spanDataMock.getName()).thenReturn(null);

    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.SERVER.name(),
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, UNKNOWN_OPERATION);
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.SERVER);
  }

  @Test
  public void testServerSpanWithSpanNameAsHttpMethod() {
    updateResourceWithServiceName();
    when(spanDataMock.getName()).thenReturn("GET");
    mockAttribute(HTTP_METHOD, "GET");

    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.SERVER.name(),
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, UNKNOWN_OPERATION);
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.SERVER);
    mockAttribute(HTTP_METHOD, null);
  }

  @Test
  public void testServerSpanWithSpanNameWithHttpTarget() {
    updateResourceWithServiceName();
    when(spanDataMock.getName()).thenReturn("POST");
    mockAttribute(HTTP_METHOD, "POST");
    mockAttribute(HTTP_TARGET, "/payment/123");

    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND,
            SpanKind.SERVER.name(),
            AWS_LOCAL_SERVICE,
            SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION,
            "POST /payment");
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.SERVER);
    mockAttribute(HTTP_METHOD, null);
    mockAttribute(HTTP_TARGET, null);
  }

  @Test
  public void testProducerSpanWithAttributes() {
    updateResourceWithServiceName();
    mockAttribute(AWS_LOCAL_OPERATION, AWS_LOCAL_OPERATION_VALUE);
    mockAttribute(AWS_REMOTE_SERVICE, AWS_REMOTE_SERVICE_VALUE);
    mockAttribute(AWS_REMOTE_OPERATION, AWS_REMOTE_OPERATION_VALUE);

    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.PRODUCER.name(),
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, AWS_LOCAL_OPERATION_VALUE,
            AWS_REMOTE_SERVICE, AWS_REMOTE_SERVICE_VALUE,
            AWS_REMOTE_OPERATION, AWS_REMOTE_OPERATION_VALUE);
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.PRODUCER);
  }

  @Test
  public void testClientSpanWithAttributes() {
    updateResourceWithServiceName();
    mockAttribute(AWS_LOCAL_OPERATION, AWS_LOCAL_OPERATION_VALUE);
    mockAttribute(AWS_REMOTE_SERVICE, AWS_REMOTE_SERVICE_VALUE);
    mockAttribute(AWS_REMOTE_OPERATION, AWS_REMOTE_OPERATION_VALUE);

    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.CLIENT.name(),
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, AWS_LOCAL_OPERATION_VALUE,
            AWS_REMOTE_SERVICE, AWS_REMOTE_SERVICE_VALUE,
            AWS_REMOTE_OPERATION, AWS_REMOTE_OPERATION_VALUE);
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.CLIENT);
  }

  @Test
  public void testRemoteAttributesCombinations() {
    // Set all expected fields to a test string, we will overwrite them in descending order to test
    // the priority-order logic in AwsMetricAttributeGenerator remote attribute methods.
    mockAttribute(AWS_REMOTE_SERVICE, "TestString");
    mockAttribute(AWS_REMOTE_OPERATION, "TestString");
    mockAttribute(RPC_SERVICE, "TestString");
    mockAttribute(RPC_METHOD, "TestString");
    mockAttribute(DB_SYSTEM, "TestString");
    mockAttribute(DB_OPERATION, "TestString");
    mockAttribute(FAAS_INVOKED_PROVIDER, "TestString");
    mockAttribute(FAAS_INVOKED_NAME, "TestString");
    mockAttribute(MESSAGING_SYSTEM, "TestString");
    mockAttribute(MESSAGING_OPERATION, "TestString");
    mockAttribute(GRAPHQL_OPERATION_TYPE, "TestString");
    // Do not set dummy value for PEER_SERVICE, since it has special behaviour.

    // Two unused attributes to show that we will not make use of unrecognized attributes
    mockAttribute(AttributeKey.stringKey("unknown.service.key"), "TestString");
    mockAttribute(AttributeKey.stringKey("unknown.operation.key"), "TestString");

    // Validate behaviour of various combinations of AWS remote attributes, then remove them.
    validateAndRemoveRemoteAttributes(
        AWS_REMOTE_SERVICE,
        AWS_REMOTE_SERVICE_VALUE,
        AWS_REMOTE_OPERATION,
        AWS_REMOTE_OPERATION_VALUE);

    // Validate behaviour of various combinations of RPC attributes, then remove them.
    validateAndRemoveRemoteAttributes(RPC_SERVICE, "RPC service", RPC_METHOD, "RPC method");

    // Validate behaviour of various combinations of DB attributes, then remove them.
    validateAndRemoveRemoteAttributes(DB_SYSTEM, "DB system", DB_OPERATION, "DB operation");

    // Validate behaviour of various combinations of FAAS attributes, then remove them.
    validateAndRemoveRemoteAttributes(
        FAAS_INVOKED_NAME, "FAAS invoked name", FAAS_TRIGGER, "FAAS trigger name");

    // Validate behaviour of various combinations of Messaging attributes, then remove them.
    validateAndRemoveRemoteAttributes(
        MESSAGING_SYSTEM, "Messaging system", MESSAGING_OPERATION, "Messaging operation");

    // Validate behaviour of GraphQL operation type attribute, then remove it.
    mockAttribute(GRAPHQL_OPERATION_TYPE, "GraphQL operation type");
    validateExpectedRemoteAttributes("graphql", "GraphQL operation type");
    mockAttribute(GRAPHQL_OPERATION_TYPE, null);

    // Validate behaviour of extracting Remote Service from net.peer.name
    mockAttribute(NET_PEER_NAME, "www.example.com");
    validateExpectedRemoteAttributes("www.example.com", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(NET_PEER_NAME, null);

    // Validate behaviour of extracting Remote Service from net.peer.name and net.peer.port
    mockAttribute(NET_PEER_NAME, "192.168.0.0");
    mockAttribute(NET_PEER_PORT, 8081L);
    validateExpectedRemoteAttributes("192.168.0.0:8081", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(NET_PEER_NAME, null);
    mockAttribute(NET_PEER_PORT, null);

    // Validate behaviour of extracting Remote Service from net.peer.socket.addr
    mockAttribute(NET_SOCK_PEER_ADDR, "www.example.com");
    validateExpectedRemoteAttributes("www.example.com", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(NET_SOCK_PEER_ADDR, null);

    // Validate behaviour of extracting Remote Service from net.peer.socket.addr and
    // net.sock.peer.port
    mockAttribute(NET_SOCK_PEER_ADDR, "192.168.0.0");
    mockAttribute(NET_SOCK_PEER_PORT, 8081L);
    validateExpectedRemoteAttributes("192.168.0.0:8081", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(NET_SOCK_PEER_ADDR, null);
    mockAttribute(NET_SOCK_PEER_PORT, null);

    // Validate behavior of Remote Operation from HttpTarget - with 1st api part, then remove it
    mockAttribute(HTTP_URL, "http://www.example.com/payment/123");
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, "/payment");
    mockAttribute(HTTP_URL, null);

    // Validate behavior of Remote Operation from HttpTarget - without 1st api part, then remove it
    mockAttribute(HTTP_URL, "http://www.example.com");
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, "/");
    mockAttribute(HTTP_URL, null);

    // Validate behavior of Remote Operation from HttpTarget - invalid url, then remove it
    mockAttribute(HTTP_URL, "abc");
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, UNKNOWN_REMOTE_OPERATION);
    mockAttribute(HTTP_URL, null);

    // Validate behaviour of Peer service attribute, then remove it.
    mockAttribute(PEER_SERVICE, "Peer service");
    validateExpectedRemoteAttributes("Peer service", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(PEER_SERVICE, null);

    // Once we have removed all usable metrics, we only have "unknown" attributes, which are unused.
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, UNKNOWN_REMOTE_OPERATION);
  }

  @Test
  public void testPeerServiceDoesOverrideOtherRemoteServices() {
    validatePeerServiceDoesOverride(RPC_SERVICE);
    validatePeerServiceDoesOverride(DB_SYSTEM);
    validatePeerServiceDoesOverride(FAAS_INVOKED_PROVIDER);
    validatePeerServiceDoesOverride(MESSAGING_SYSTEM);
    validatePeerServiceDoesOverride(GRAPHQL_OPERATION_TYPE);
    validatePeerServiceDoesOverride(NET_PEER_NAME);
    validatePeerServiceDoesOverride(NET_SOCK_PEER_ADDR);
    // Actually testing that peer service overrides "UnknownRemoteService".
    validatePeerServiceDoesOverride(AttributeKey.stringKey("unknown.service.key"));
  }

  @Test
  public void testPeerServiceDoesNotOverrideAwsRemoteService() {
    mockAttribute(AWS_REMOTE_SERVICE, "TestString");
    mockAttribute(PEER_SERVICE, "PeerService");

    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_SERVICE)).isEqualTo("TestString");
  }

  @Test
  public void testClientSpanWithRemoteTargetAttributes() {
    // Validate behaviour of aws bucket name attribute, then remove it.
    mockAttribute(AWS_BUCKET_NAME, "aws_s3_bucket_name");
    validateRemoteTargetAttributes(AWS_REMOTE_TARGET, "::s3:::aws_s3_bucket_name");
    mockAttribute(AWS_BUCKET_NAME, null);

    // Validate behaviour of AWS_QUEUE_NAME attribute, then remove it.
    mockAttribute(AWS_QUEUE_NAME, "aws_queue_name");
    validateRemoteTargetAttributes(AWS_REMOTE_TARGET, "::sqs:::aws_queue_name");
    mockAttribute(AWS_QUEUE_NAME, null);

    // Validate behaviour of having both AWS_QUEUE_NAME and AWS_QUEUE_URL attribute, then remove
    // them.
    mockAttribute(AWS_QUEUE_URL, "https://sqs.us-east-2.amazonaws.com/123456789012/Queue");
    mockAttribute(AWS_QUEUE_NAME, "aws_queue_name");
    validateRemoteTargetAttributes(AWS_REMOTE_TARGET, "arn:aws:sqs:us-east-2:123456789012:Queue");
    mockAttribute(AWS_QUEUE_URL, null);
    mockAttribute(AWS_QUEUE_NAME, null);

    // Valid queue name with invalid queue URL, we should default to using the queue name.
    mockAttribute(AWS_QUEUE_URL, "invalidUrl");
    mockAttribute(AWS_QUEUE_NAME, "aws_queue_name");
    validateRemoteTargetAttributes(AWS_REMOTE_TARGET, "::sqs:::aws_queue_name");
    mockAttribute(AWS_QUEUE_URL, null);
    mockAttribute(AWS_QUEUE_NAME, null);

    // Validate behaviour of AWS_STREAM_NAME attribute, then remove it.
    mockAttribute(AWS_STREAM_NAME, "aws_stream_name");
    validateRemoteTargetAttributes(AWS_REMOTE_TARGET, "::kinesis:::stream/aws_stream_name");
    mockAttribute(AWS_STREAM_NAME, null);

    // Validate behaviour of AWS_TABLE_NAME attribute, then remove it.
    mockAttribute(AWS_TABLE_NAME, "aws_table_name");
    validateRemoteTargetAttributes(AWS_REMOTE_TARGET, "::dynamodb:::table/aws_table_name");
    mockAttribute(AWS_TABLE_NAME, null);
  }

  @Test
  public void testSqsClientSpanBasicUrls() {
    testSqsUrl(
        "https://sqs.us-east-1.amazonaws.com/123412341234/Q_Name-5",
        "arn:aws:sqs:us-east-1:123412341234:Q_Name-5");
    testSqsUrl(
        "https://sqs.af-south-1.amazonaws.com/999999999999/-_ThisIsValid",
        "arn:aws:sqs:af-south-1:999999999999:-_ThisIsValid");
    testSqsUrl(
        "http://sqs.eu-west-3.amazonaws.com/000000000000/FirstQueue",
        "arn:aws:sqs:eu-west-3:000000000000:FirstQueue");
    testSqsUrl(
        "sqs.sa-east-1.amazonaws.com/123456781234/SecondQueue",
        "arn:aws:sqs:sa-east-1:123456781234:SecondQueue");
  }

  @Test
  public void testSqsClientSpanUsGovUrls() {
    testSqsUrl(
        "https://sqs.us-gov-east-1.amazonaws.com/123456789012/MyQueue",
        "arn:aws-us-gov:sqs:us-gov-east-1:123456789012:MyQueue");
    testSqsUrl(
        "sqs.us-gov-west-1.amazonaws.com/112233445566/Queue",
        "arn:aws-us-gov:sqs:us-gov-west-1:112233445566:Queue");
  }

  @Test
  public void testSqsClientSpanLegacyFormatUrls() {
    testSqsUrl(
        "https://ap-northeast-2.queue.amazonaws.com/123456789012/MyQueue",
        "arn:aws:sqs:ap-northeast-2:123456789012:MyQueue");
    testSqsUrl(
        "http://cn-northwest-1.queue.amazonaws.com/123456789012/MyQueue",
        "arn:aws-cn:sqs:cn-northwest-1:123456789012:MyQueue");
    testSqsUrl(
        "http://cn-north-1.queue.amazonaws.com/123456789012/MyQueue",
        "arn:aws-cn:sqs:cn-north-1:123456789012:MyQueue");
    testSqsUrl(
        "ap-south-1.queue.amazonaws.com/123412341234/MyLongerQueueNameHere",
        "arn:aws:sqs:ap-south-1:123412341234:MyLongerQueueNameHere");
    testSqsUrl(
        "https://us-gov-east-1.queue.amazonaws.com/123456789012/MyQueue",
        "arn:aws-us-gov:sqs:us-gov-east-1:123456789012:MyQueue");
  }

  @Test
  public void testSqsClientSpanNorthVirginiaLegacyUrl() {
    testSqsUrl(
        "https://queue.amazonaws.com/123456789012/MyQueue",
        "arn:aws:sqs:us-east-1:123456789012:MyQueue");
  }

  @Test
  public void testSqsClientSpanCustomUrls() {
    testSqsUrl("http://127.0.0.1:1212/123456789012/MyQueue", "::sqs::123456789012:MyQueue");
    testSqsUrl("https://127.0.0.1:1212/123412341234/RRR", "::sqs::123412341234:RRR");
    testSqsUrl("127.0.0.1:1212/123412341234/QQ", "::sqs::123412341234:QQ");
    testSqsUrl("https://amazon.com/123412341234/BB", "::sqs::123412341234:BB");
  }

  @Test
  public void testSqsClientSpanLongUrls() {
    String queueName = "a".repeat(80);
    testSqsUrl(
        "http://127.0.0.1:1212/123456789012/" + queueName, "::sqs::123456789012:" + queueName);

    String queueNameTooLong = "a".repeat(81);
    testSqsUrl("http://127.0.0.1:1212/123456789012/" + queueNameTooLong, null);
  }

  @Test
  public void testClientSpanSqsInvalidOrEmptyUrls() {
    testSqsUrl(null, null);
    testSqsUrl("", null);
    testSqsUrl("invalidUrl", null);
    testSqsUrl("https://www.amazon.com", null);
    testSqsUrl("https://sqs.us-east-1.amazonaws.com/123412341234/.", null);
    testSqsUrl("https://sqs.us-east-1.amazonaws.com/A/A", null);
    testSqsUrl("https://sqs.us-east-1.amazonaws.com/123412341234/A/ThisShouldNotBeHere", null);
  }

  private void testSqsUrl(String sqsUrl, String expectedRemoteTarget) {
    mockAttribute(AWS_QUEUE_URL, sqsUrl);
    validateRemoteTargetAttributes(AWS_REMOTE_TARGET, expectedRemoteTarget);
    mockAttribute(AWS_QUEUE_URL, null);
  }

  @Test
  public void testHttpStatusAttributeNotAwsSdk() {
    validateHttpStatusWithThrowable(new ThrowableWithMethodGetStatusCode(500), null);
  }

  @Test
  public void testHttpStatusAttributeStatusAlreadyPresent() {
    when(instrumentationScopeInfoMock.getName()).thenReturn("aws-sdk");
    mockAttribute(HTTP_STATUS_CODE, 200L);
    validateHttpStatusWithThrowable(new ThrowableWithMethodGetStatusCode(500), null);
  }

  @Test
  public void testHttpStatusAttributeGetStatusCodeException() {
    when(instrumentationScopeInfoMock.getName()).thenReturn("aws-sdk");
    validateHttpStatusWithThrowable(new ThrowableWithMethodGetStatusCode(500), 500L);
  }

  @Test
  public void testHttpStatusAttributeStatusCodeException() {
    when(instrumentationScopeInfoMock.getName()).thenReturn("aws-sdk");
    validateHttpStatusWithThrowable(new ThrowableWithMethodStatusCode(500), 500L);
  }

  @Test
  public void testHttpStatusAttributeNoStatusCodeException() {
    when(instrumentationScopeInfoMock.getName()).thenReturn("aws-sdk");
    validateHttpStatusWithThrowable(new ThrowableWithoutStatusCode(), null);
  }

  private <T> void mockAttribute(AttributeKey<T> key, T value) {
    when(attributesMock.get(key)).thenReturn(value);
  }

  private void validateAttributesProducedForNonLocalRootSpanOfKind(
      Attributes expectedAttributes, SpanKind kind) {
    when(spanDataMock.getKind()).thenReturn(kind);
    Map<String, Attributes> attributeMap =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource);
    Attributes serviceAttributes = attributeMap.get(SERVICE_METRIC);
    Attributes dependencyAttributes = attributeMap.get(DEPENDENCY_METRIC);
    if (!attributeMap.isEmpty()) {
      if (SpanKind.PRODUCER.equals(kind)
          || SpanKind.CLIENT.equals(kind)
          || SpanKind.CONSUMER.equals(kind)) {
        assertThat(serviceAttributes).isNull();
        assertThat(dependencyAttributes).isEqualTo(expectedAttributes);
        assertThat(dependencyAttributes.size()).isEqualTo(expectedAttributes.size());
      } else {
        assertThat(serviceAttributes).isEqualTo(expectedAttributes);
        assertThat(serviceAttributes.size()).isEqualTo(expectedAttributes.size());
        assertThat(dependencyAttributes).isNull();
      }
    }
  }

  private void updateResourceWithServiceName() {
    resource = Resource.builder().put(SERVICE_NAME, SERVICE_NAME_VALUE).build();
  }

  private void validateExpectedRemoteAttributes(
      String expectedRemoteService, String expectedRemoteOperation) {
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_SERVICE)).isEqualTo(expectedRemoteService);
    assertThat(actualAttributes.get(AWS_REMOTE_OPERATION)).isEqualTo(expectedRemoteOperation);

    when(spanDataMock.getKind()).thenReturn(SpanKind.PRODUCER);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_SERVICE)).isEqualTo(expectedRemoteService);
    assertThat(actualAttributes.get(AWS_REMOTE_OPERATION)).isEqualTo(expectedRemoteOperation);
  }

  private void validateAndRemoveRemoteAttributes(
      AttributeKey<String> remoteServiceKey,
      String remoteServiceValue,
      AttributeKey<String> remoteOperationKey,
      String remoteOperationValue) {
    mockAttribute(remoteServiceKey, remoteServiceValue);
    mockAttribute(remoteOperationKey, remoteOperationValue);
    validateExpectedRemoteAttributes(remoteServiceValue, remoteOperationValue);

    mockAttribute(remoteServiceKey, null);
    mockAttribute(remoteOperationKey, remoteOperationValue);
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, remoteOperationValue);

    mockAttribute(remoteServiceKey, remoteServiceValue);
    mockAttribute(remoteOperationKey, null);
    validateExpectedRemoteAttributes(remoteServiceValue, UNKNOWN_REMOTE_OPERATION);

    mockAttribute(remoteServiceKey, null);
    mockAttribute(remoteOperationKey, null);
  }

  private void validatePeerServiceDoesOverride(AttributeKey<String> remoteServiceKey) {
    mockAttribute(remoteServiceKey, "TestString");
    mockAttribute(PEER_SERVICE, "PeerService");

    // Validate that peer service value takes precedence over whatever remoteServiceKey was set
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_SERVICE)).isEqualTo("PeerService");

    mockAttribute(remoteServiceKey, null);
    mockAttribute(PEER_SERVICE, null);
  }

  private void validateRemoteTargetAttributes(
      AttributeKey<String> remoteTargetKey, String remoteTarget) {
    // Client, Producer and Consumer spans should generate the expected RemoteTarget attribute
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(remoteTargetKey)).isEqualTo(remoteTarget);

    when(spanDataMock.getKind()).thenReturn(SpanKind.PRODUCER);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);

    assertThat(actualAttributes.get(remoteTargetKey)).isEqualTo(remoteTarget);

    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(remoteTargetKey)).isEqualTo(remoteTarget);
    assertThat(actualAttributes.get(remoteTargetKey)).isEqualTo(remoteTarget);

    // Server span should not generate RemoteTarget attribute
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(SERVICE_METRIC);
    assertThat(actualAttributes.get(remoteTargetKey)).isEqualTo(null);
  }

  private void validateHttpStatusWithThrowable(Throwable throwable, Long expectedStatusCode) {
    ExceptionEventData mockEventData = mock(ExceptionEventData.class);
    when(mockEventData.getException()).thenReturn(throwable);
    List<EventData> events = new ArrayList<>(List.of(mockEventData));
    when(spanDataMock.getEvents()).thenReturn(events);
    validateHttpStatusForNonLocalRootWithThrowableForClient(SpanKind.CLIENT, expectedStatusCode);
    validateHttpStatusForNonLocalRootWithThrowableForClient(SpanKind.PRODUCER, expectedStatusCode);
    validateHttpStatusForNonLocalRootWithThrowableForClient(SpanKind.SERVER, expectedStatusCode);
    validateHttpStatusForNonLocalRootWithThrowableForClient(SpanKind.CONSUMER, expectedStatusCode);
    validateHttpStatusForNonLocalRootWithThrowableForClient(SpanKind.INTERNAL, null);
  }

  private void validateHttpStatusForNonLocalRootWithThrowableForClient(
      SpanKind spanKind, Long expectedStatusCode) {
    when(spanDataMock.getKind()).thenReturn(spanKind);
    Map<String, Attributes> attributeMap =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource);
    Attributes actualAttributes = Attributes.empty();
    if (!attributeMap.isEmpty()) {
      if (SpanKind.PRODUCER.equals(spanKind)
          || SpanKind.CLIENT.equals(spanKind)
          || SpanKind.CONSUMER.equals(spanKind)) {
        actualAttributes = attributeMap.get(DEPENDENCY_METRIC);
      } else {
        actualAttributes = attributeMap.get(SERVICE_METRIC);
      }
    }
    assertThat(actualAttributes.get(HTTP_STATUS_CODE)).isEqualTo(expectedStatusCode);
    if (expectedStatusCode == null) {
      assertThat(actualAttributes.asMap().containsKey(HTTP_STATUS_CODE)).isFalse();
    }
  }

  @Test
  public void testNormalizeServiceNameNonAwsSdkSpan() {
    String serviceName = "non aws service";
    mockAttribute(RPC_SERVICE, serviceName);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);

    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_SERVICE)).isEqualTo(serviceName);
  }

  @Test
  public void testNormalizeServiceNameAwsSdkV1Span() {
    String serviceName = "Amazon S3";
    mockAttribute(RPC_SYSTEM, "aws-api");
    mockAttribute(RPC_SERVICE, serviceName);
    when(spanDataMock.getInstrumentationScopeInfo())
        .thenReturn(InstrumentationScopeInfo.create("io.opentelemetry.aws-sdk-1.11 1.28.0-alpha"));
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);

    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_SERVICE)).isEqualTo("Amazon S3");
  }

  @Test
  public void testNormalizeServiceNameAwsSdkV2Span() {
    String serviceName = "DynamoDb";
    mockAttribute(RPC_SYSTEM, "aws-api");
    mockAttribute(RPC_SERVICE, serviceName);
    when(spanDataMock.getInstrumentationScopeInfo())
        .thenReturn(InstrumentationScopeInfo.create("io.opentelemetry.aws-sdk-2.2 1.28.0-alpha"));
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);

    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_SERVICE)).isEqualTo("AWS.SDK.DynamoDb");
  }

  @Test
  public void testNoMetricWhenConsumerProcessWithConsumerParent() {
    when(attributesMock.get(AwsAttributeKeys.AWS_CONSUMER_PARENT_SPAN_KIND))
        .thenReturn(SpanKind.CONSUMER.name());
    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn(PROCESS);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);

    Map<String, Attributes> attributeMap =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource);

    Attributes serviceAttributes = attributeMap.get(SERVICE_METRIC);
    Attributes dependencyAttributes = attributeMap.get(DEPENDENCY_METRIC);

    assertThat(serviceAttributes).isNull();
    assertThat(dependencyAttributes).isNull();
  }

  @Test
  public void testBothMetricsWhenLocalRootConsumerProcess() {
    when(attributesMock.get(AwsAttributeKeys.AWS_CONSUMER_PARENT_SPAN_KIND))
        .thenReturn(SpanKind.CONSUMER.name());
    when(attributesMock.get(MESSAGING_OPERATION)).thenReturn(PROCESS);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    when(parentSpanContextMock.isValid()).thenReturn(false);

    Map<String, Attributes> attributeMap =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource);

    Attributes serviceAttributes = attributeMap.get(SERVICE_METRIC);
    Attributes dependencyAttributes = attributeMap.get(DEPENDENCY_METRIC);

    assertThat(attributeMap.get(SERVICE_METRIC)).isEqualTo(serviceAttributes);
    assertThat(attributeMap.get(DEPENDENCY_METRIC)).isEqualTo(dependencyAttributes);
  }
}
