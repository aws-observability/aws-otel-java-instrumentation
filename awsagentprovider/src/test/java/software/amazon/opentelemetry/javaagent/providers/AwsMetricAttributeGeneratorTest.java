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
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_AGENT_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_AUTH_ACCESS_KEY;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_AUTH_REGION;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_BUCKET_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_CLOUDFORMATION_PRIMARY_IDENTIFIER;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_DATA_SOURCE_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_GUARDRAIL_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_GUARDRAIL_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_KNOWLEDGE_BASE_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LAMBDA_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LAMBDA_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LAMBDA_RESOURCE_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_QUEUE_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_QUEUE_URL;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_DB_USER;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_ENVIRONMENT;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_RESOURCE_ACCESS_KEY;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_RESOURCE_ACCOUNT_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_RESOURCE_IDENTIFIER;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_RESOURCE_REGION;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_RESOURCE_TYPE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_SECRET_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_SNS_TOPIC_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_SPAN_KIND;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_STATE_MACHINE_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_STEP_FUNCTIONS_ACTIVITY_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_STREAM_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_STREAM_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_TABLE_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_TABLE_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.GEN_AI_REQUEST_MODEL;
import static software.amazon.opentelemetry.javaagent.providers.MetricAttributeGenerator.DEPENDENCY_METRIC;
import static software.amazon.opentelemetry.javaagent.providers.MetricAttributeGenerator.SERVICE_METRIC;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.ExceptionEventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private static final String MOCK_ACCESS_KEY = "MockAccessKey";
  private static final String MOCK_REGION = "us-east-1";

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
    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND, SpanKind.SERVER.name(),
            AWS_LOCAL_SERVICE, SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION, UNKNOWN_OPERATION);
    // Validate the span with http.method.
    mockAttribute(HTTP_METHOD, "GET");
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.SERVER);
    mockAttribute(HTTP_METHOD, null);
    // Validate the span with http.request.method.
    mockAttribute(HTTP_REQUEST_METHOD, "GET");
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.SERVER);
    mockAttribute(HTTP_REQUEST_METHOD, null);
  }

  @Test
  public void testServerSpanWithSpanNameWithHttpTarget() {
    updateResourceWithServiceName();
    when(spanDataMock.getName()).thenReturn("POST");
    Attributes expectedAttributes =
        Attributes.of(
            AWS_SPAN_KIND,
            SpanKind.SERVER.name(),
            AWS_LOCAL_SERVICE,
            SERVICE_NAME_VALUE,
            AWS_LOCAL_OPERATION,
            "POST /payment");

    // Validate the span with http.method and http.target.
    mockAttribute(HTTP_METHOD, "POST");
    mockAttribute(HTTP_TARGET, "/payment/123");
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.SERVER);
    mockAttribute(HTTP_METHOD, null);
    mockAttribute(HTTP_TARGET, null);

    // Validate the span with http.request.method and url.path.
    mockAttribute(HTTP_REQUEST_METHOD, "POST");
    mockAttribute(URL_PATH, "/payment/123");
    validateAttributesProducedForNonLocalRootSpanOfKind(expectedAttributes, SpanKind.SERVER);
    mockAttribute(HTTP_REQUEST_METHOD, null);
    mockAttribute(URL_PATH, null);
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
    mockAttribute(DB_STATEMENT, "TestString");
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

    // Validate db.operation not exist, but db.statement exist, where SpanAttributes.DB_STATEMENT is
    // invalid
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_STATEMENT, "invalid DB statement");
    mockAttribute(DB_OPERATION, null);
    validateAndRemoveRemoteAttributes(
        DB_SYSTEM, "DB system", DB_OPERATION, UNKNOWN_REMOTE_OPERATION);

    // Validate both db.operation and db.statement not exist.
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_OPERATION, null);
    mockAttribute(DB_STATEMENT, null);
    validateAndRemoveRemoteAttributes(
        DB_SYSTEM, "DB system", DB_OPERATION, UNKNOWN_REMOTE_OPERATION);

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

    // Validate behaviour of extracting Remote Service from service.address
    mockAttribute(SERVER_ADDRESS, "www.example.com");
    validateExpectedRemoteAttributes("www.example.com", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(SERVER_ADDRESS, null);

    // Validate behaviour of extracting Remote Service from net.peer.name and net.peer.port
    mockAttribute(NET_PEER_NAME, "192.168.0.0");
    mockAttribute(NET_PEER_PORT, 8081L);
    validateExpectedRemoteAttributes("192.168.0.0:8081", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(NET_PEER_NAME, null);
    mockAttribute(NET_PEER_PORT, null);

    // Validate behaviour of extracting Remote Service from service.address and service.port
    mockAttribute(SERVER_ADDRESS, "192.168.0.0");
    mockAttribute(SERVER_PORT, 8081L);
    validateExpectedRemoteAttributes("192.168.0.0:8081", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(SERVER_ADDRESS, null);
    mockAttribute(SERVER_PORT, null);

    // Validate behaviour of extracting Remote Service from net.peer.socket.addr
    mockAttribute(NET_SOCK_PEER_ADDR, "www.example.com");
    validateExpectedRemoteAttributes("www.example.com", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(NET_SOCK_PEER_ADDR, null);

    // Validate behaviour of extracting Remote Service from net.peer.socket.addr
    mockAttribute(NETWORK_PEER_ADDRESS, "www.example.com");
    validateExpectedRemoteAttributes("www.example.com", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(NETWORK_PEER_ADDRESS, null);

    // Validate behaviour of extracting Remote Service from net.peer.socket.addr and
    // net.sock.peer.port
    mockAttribute(NET_SOCK_PEER_ADDR, "192.168.0.0");
    mockAttribute(NET_SOCK_PEER_PORT, 8081L);
    validateExpectedRemoteAttributes("192.168.0.0:8081", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(NET_SOCK_PEER_ADDR, null);
    mockAttribute(NET_SOCK_PEER_PORT, null);

    // Validate behaviour of extracting Remote Service from net.peer.socket.addr and
    // net.sock.peer.port
    mockAttribute(NETWORK_PEER_ADDRESS, "192.168.0.0");
    mockAttribute(NETWORK_PEER_PORT, 8081L);
    validateExpectedRemoteAttributes("192.168.0.0:8081", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(NETWORK_PEER_ADDRESS, null);
    mockAttribute(NETWORK_PEER_PORT, null);

    // Validate behavior of Remote Operation from HttpTarget - with 1st api part. Also validates
    // that RemoteService is extracted from HttpUrl.
    mockAttribute(HTTP_URL, "http://www.example.com/payment/123");
    validateExpectedRemoteAttributes("www.example.com", "/payment");
    mockAttribute(HTTP_URL, null);

    // that RemoteService is extracted from url.full.
    mockAttribute(URL_FULL, "http://www.example.com/payment/123");
    validateExpectedRemoteAttributes("www.example.com", "/payment");
    mockAttribute(URL_FULL, null);

    // Validate behavior of Remote Operation from HttpTarget - with 1st api part. Also validates
    // that RemoteService is extracted from HttpUrl.
    mockAttribute(HTTP_URL, "http://www.example.com");
    validateExpectedRemoteAttributes("www.example.com", "/");
    mockAttribute(HTTP_URL, null);

    // that RemoteService is extracted from url.full.
    mockAttribute(URL_FULL, "http://www.example.com");
    validateExpectedRemoteAttributes("www.example.com", "/");
    mockAttribute(URL_FULL, null);

    // Validate behavior of Remote Service from HttpUrl
    mockAttribute(HTTP_URL, "http://192.168.1.1:8000");
    validateExpectedRemoteAttributes("192.168.1.1:8000", "/");
    mockAttribute(HTTP_URL, null);

    // Validate behavior of Remote Service from url.full
    mockAttribute(URL_FULL, "http://192.168.1.1:8000");
    validateExpectedRemoteAttributes("192.168.1.1:8000", "/");
    mockAttribute(URL_FULL, null);

    // Validate behavior of Remote Service from HttpUrl
    mockAttribute(HTTP_URL, "http://192.168.1.1");
    validateExpectedRemoteAttributes("192.168.1.1", "/");
    mockAttribute(HTTP_URL, null);

    // Validate behavior of Remote Service from url.full
    mockAttribute(URL_FULL, "http://192.168.1.1");
    validateExpectedRemoteAttributes("192.168.1.1", "/");
    mockAttribute(URL_FULL, null);

    // Validate behavior of Remote Service from HttpUrl
    mockAttribute(HTTP_URL, "");
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, UNKNOWN_REMOTE_OPERATION);
    mockAttribute(HTTP_URL, null);

    // Validate behavior of Remote Service from url.full
    mockAttribute(URL_FULL, "");
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, UNKNOWN_REMOTE_OPERATION);
    mockAttribute(URL_FULL, null);

    // Validate behavior of Remote Service from HttpUrl
    mockAttribute(HTTP_URL, null);
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, UNKNOWN_REMOTE_OPERATION);
    mockAttribute(HTTP_URL, null);

    // Validate behavior of Remote Service from url.full
    mockAttribute(URL_FULL, null);
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, UNKNOWN_REMOTE_OPERATION);
    mockAttribute(URL_FULL, null);

    // Validate behavior of Remote Operation from HttpTarget - invalid url, then remove it
    mockAttribute(HTTP_URL, "abc");
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, UNKNOWN_REMOTE_OPERATION);
    mockAttribute(HTTP_URL, null);

    // Validate behavior of Remote Operation from url.full - invalid url, then remove it
    mockAttribute(URL_FULL, "abc");
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, UNKNOWN_REMOTE_OPERATION);
    mockAttribute(URL_FULL, null);

    // Validate behaviour of Peer service attribute, then remove it.
    mockAttribute(PEER_SERVICE, "Peer service");
    validateExpectedRemoteAttributes("Peer service", UNKNOWN_REMOTE_OPERATION);
    mockAttribute(PEER_SERVICE, null);

    // Once we have removed all usable metrics, we only have "unknown" attributes, which are unused.
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, UNKNOWN_REMOTE_OPERATION);
  }

  // Validate behaviour of various combinations of DB attributes.
  @Test
  public void testGetDBStatementRemoteOperation() {
    // Set all expected fields to a test string, we will overwrite them in descending order to test
    mockAttribute(DB_SYSTEM, "TestString");
    mockAttribute(DB_OPERATION, "TestString");
    mockAttribute(DB_STATEMENT, "TestString");

    // Validate SpanAttributes.DB_OPERATION not exist, but SpanAttributes.DB_STATEMENT exist,
    // where SpanAttributes.DB_STATEMENT is valid
    // Case 1: Only 1 valid keywords match
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_STATEMENT, "SELECT DB statement");
    mockAttribute(DB_OPERATION, null);
    validateExpectedRemoteAttributes("DB system", "SELECT");

    // Case 2: More than 1 valid keywords match, we want to pick the longest match
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_STATEMENT, "DROP VIEW DB statement");
    mockAttribute(DB_OPERATION, null);
    validateExpectedRemoteAttributes("DB system", "DROP VIEW");

    // Case 3: More than 1 valid keywords match, but the other keywords is not
    // at the start of the SpanAttributes.DB_STATEMENT. We want to only pick start match
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_STATEMENT, "SELECT data FROM domains");
    mockAttribute(DB_OPERATION, null);
    validateExpectedRemoteAttributes("DB system", "SELECT");

    // Case 4: Have valid keywords，but it is not at the start of SpanAttributes.DB_STATEMENT
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_STATEMENT, "invalid SELECT DB statement");
    mockAttribute(DB_OPERATION, null);
    validateExpectedRemoteAttributes("DB system", UNKNOWN_REMOTE_OPERATION);

    // Case 5: Have valid keywords, match the longest word
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_STATEMENT, "UUID");
    mockAttribute(DB_OPERATION, null);
    validateExpectedRemoteAttributes("DB system", "UUID");

    // Case 6: Have valid keywords, match with first word
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_STATEMENT, "FROM SELECT *");
    mockAttribute(DB_OPERATION, null);
    validateExpectedRemoteAttributes("DB system", "FROM");

    // Case 7: Have valid keyword, match with first word
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_STATEMENT, "SELECT FROM *");
    mockAttribute(DB_OPERATION, null);
    validateExpectedRemoteAttributes("DB system", "SELECT");

    // Case 8: Have valid keywords, match with upper case
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_STATEMENT, "seLeCt *");
    mockAttribute(DB_OPERATION, null);
    validateExpectedRemoteAttributes("DB system", "SELECT");

    // Case 9: Both DB_OPERATION and DB_STATEMENT are set but the former takes precedence
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_STATEMENT, "SELECT FROM *");
    mockAttribute(DB_OPERATION, "DB operation");
    validateExpectedRemoteAttributes("DB system", "DB operation");

    // Case 10: Duplicate of case 1 with leading whitespace
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_STATEMENT, "    SELECT DB statement");
    mockAttribute(DB_OPERATION, null);
    validateExpectedRemoteAttributes("DB system", "SELECT");

    // Case 11: Duplicate of case 2 with leading whitespace. Test if whitespace affects longest
    // match
    mockAttribute(DB_SYSTEM, "DB system");
    mockAttribute(DB_STATEMENT, "     DROP VIEW DB statement");
    mockAttribute(DB_OPERATION, null);
    validateExpectedRemoteAttributes("DB system", "DROP VIEW");
  }

  @Test
  public void testPeerServiceDoesOverrideOtherRemoteServices() {
    validatePeerServiceDoesOverride(RPC_SERVICE);
    validatePeerServiceDoesOverride(DB_SYSTEM);
    validatePeerServiceDoesOverride(FAAS_INVOKED_PROVIDER);
    validatePeerServiceDoesOverride(MESSAGING_SYSTEM);
    validatePeerServiceDoesOverride(GRAPHQL_OPERATION_TYPE);
    validatePeerServiceDoesOverride(NET_PEER_NAME);
    validatePeerServiceDoesOverride(SERVER_ADDRESS);
    validatePeerServiceDoesOverride(NET_SOCK_PEER_ADDR);
    validatePeerServiceDoesOverride(NETWORK_PEER_ADDRESS);
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
  public void testSdkClientSpanWithRemoteResourceAttributes() {
    mockAttribute(RPC_SYSTEM, "aws-api");
    mockAttribute(AWS_AUTH_ACCESS_KEY, MOCK_ACCESS_KEY);
    mockAttribute(AWS_AUTH_REGION, MOCK_REGION);
    // Validate behaviour of aws bucket name attribute, then remove it.
    mockAttribute(AWS_BUCKET_NAME, "aws_s3_bucket_name");
    validateRemoteResourceAttributes("AWS::S3::Bucket", "aws_s3_bucket_name");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_BUCKET_NAME, null);

    // Validate behaviour of AWS_QUEUE_NAME attribute, then remove it.
    mockAttribute(AWS_QUEUE_NAME, "aws_queue_name");
    validateRemoteResourceAttributes("AWS::SQS::Queue", "aws_queue_name");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_QUEUE_NAME, null);

    // Validate behaviour of having both AWS_QUEUE_NAME and AWS_QUEUE_URL attribute, then remove
    // them. Queue name is more reliable than queue URL, so we prefer to use name over URL.
    mockAttribute(AWS_QUEUE_URL, "https://sqs.us-east-2.amazonaws.com/123456789012/Queue");
    mockAttribute(AWS_QUEUE_NAME, "aws_queue_name");
    validateRemoteResourceAttributes("AWS::SQS::Queue", "aws_queue_name");
    validateRemoteResourceAccountIdAndRegion(
        Optional.of("123456789012"), Optional.empty(), Optional.of("us-east-2"));
    mockAttribute(AWS_QUEUE_URL, null);
    mockAttribute(AWS_QUEUE_NAME, null);

    // Valid queue name with invalid queue URL, we should default to using the queue name.
    mockAttribute(AWS_QUEUE_URL, "invalidUrl");
    mockAttribute(AWS_QUEUE_NAME, "aws_queue_name");
    validateRemoteResourceAttributes("AWS::SQS::Queue", "aws_queue_name");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_QUEUE_URL, null);
    mockAttribute(AWS_QUEUE_NAME, null);

    // Validate behaviour of AWS_STREAM_NAME attribute, then remove it.
    mockAttribute(AWS_STREAM_NAME, "aws_stream_name");
    validateRemoteResourceAttributes("AWS::Kinesis::Stream", "aws_stream_name");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_STREAM_NAME, null);

    // Validate behaviour of AWS_STREAM_ARN attribute, then remove it.
    mockAttribute(AWS_STREAM_ARN, "arn:aws:kinesis:us-east-1:123456789012:stream/test_stream");
    validateRemoteResourceAttributes("AWS::Kinesis::Stream", "test_stream");
    validateRemoteResourceAccountIdAndRegion(
        Optional.of("123456789012"), Optional.empty(), Optional.of("us-east-1"));
    mockAttribute(AWS_STREAM_ARN, null);

    // Validate behaviour of AWS_TABLE_NAME attribute, then remove it.
    mockAttribute(AWS_TABLE_NAME, "aws_table_name");
    validateRemoteResourceAttributes("AWS::DynamoDB::Table", "aws_table_name");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_TABLE_NAME, null);

    // Validate behaviour of AWS_TABLE_NAME attribute with special chars(|), then remove it.
    mockAttribute(AWS_TABLE_NAME, "aws_table|name");
    validateRemoteResourceAttributes("AWS::DynamoDB::Table", "aws_table^|name");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_TABLE_NAME, null);

    // Validate behaviour of AWS_TABLE_NAME attribute with special chars(^), then remove it.
    mockAttribute(AWS_TABLE_NAME, "aws_table^name");
    validateRemoteResourceAttributes("AWS::DynamoDB::Table", "aws_table^^name");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_TABLE_NAME, null);

    // Validate behaviour of AWS_TABLE_ARN attribute, then remove it.
    mockAttribute(AWS_TABLE_ARN, "arn:aws:dynamodb:us-east-1:123456789012:table/test_table");
    validateRemoteResourceAttributes("AWS::DynamoDB::Table", "test_table");
    validateRemoteResourceAccountIdAndRegion(
        Optional.of("123456789012"), Optional.empty(), Optional.of("us-east-1"));
    mockAttribute(AWS_TABLE_ARN, null);

    // Validate behaviour of AWS_BEDROCK_AGENT_ID attribute, then remove it.
    mockAttribute(AWS_AGENT_ID, "test_agent_id");
    validateRemoteResourceAttributes("AWS::Bedrock::Agent", "test_agent_id");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_AGENT_ID, null);

    // Validate behaviour of AWS_BEDROCK_AGENT_ID attribute with special chars(^), then remove it.
    mockAttribute(AWS_AGENT_ID, "test_agent_^id");
    validateRemoteResourceAttributes("AWS::Bedrock::Agent", "test_agent_^^id");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_AGENT_ID, null);

    // Validate behaviour of AWS_KNOWLEDGE_BASE_ID attribute, then remove it.
    mockAttribute(AWS_KNOWLEDGE_BASE_ID, "test_knowledgeBase_id");
    validateRemoteResourceAttributes("AWS::Bedrock::KnowledgeBase", "test_knowledgeBase_id");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_KNOWLEDGE_BASE_ID, null);

    // Validate behaviour of AWS_KNOWLEDGE_BASE_ID attribute with special chars(^), then remove it.
    mockAttribute(AWS_KNOWLEDGE_BASE_ID, "test_knowledgeBase_^id");
    validateRemoteResourceAttributes("AWS::Bedrock::KnowledgeBase", "test_knowledgeBase_^^id");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_KNOWLEDGE_BASE_ID, null);

    // Validate behaviour of AWS_DATA_SOURCE_ID attribute, then remove it.
    mockAttribute(AWS_DATA_SOURCE_ID, "test_datasource_id");
    validateRemoteResourceAttributes("AWS::Bedrock::DataSource", "test_datasource_id");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_DATA_SOURCE_ID, null);

    // Validate behaviour of AWS_DATA_SOURCE_ID attribute with special chars(^), then remove
    // it.
    mockAttribute(AWS_DATA_SOURCE_ID, "test_datasource_^id");
    validateRemoteResourceAttributes("AWS::Bedrock::DataSource", "test_datasource_^^id");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_DATA_SOURCE_ID, null);

    // Validate behaviour of AWS_GUARDRAIL_ID attribute, then remove it.
    mockAttribute(AWS_GUARDRAIL_ID, "test_guardrail_id");
    mockAttribute(AWS_AUTH_ACCESS_KEY, MOCK_ACCESS_KEY);
    mockAttribute(AWS_AUTH_REGION, MOCK_REGION);
    // Also test with ARN to verify cloudformationPrimaryIdentifier uses ARN
    mockAttribute(
        AWS_GUARDRAIL_ARN, "arn:aws:bedrock:us-east-1:123456789012:guardrail/test_guardrail_id");
    validateRemoteResourceAttributes(
        "AWS::Bedrock::Guardrail",
        "test_guardrail_id",
        "arn:aws:bedrock:us-east-1:123456789012:guardrail/test_guardrail_id");
    validateRemoteResourceAccountIdAndRegion(
          Optional.of("123456789012"), Optional.empty(), Optional.of("us-east-1"));
    mockAttribute(AWS_GUARDRAIL_ID, null);
    mockAttribute(AWS_GUARDRAIL_ARN, null);

    // Validate behaviour of AWS_GUARDRAIL_ID attribute with special chars(^), then remove it.
    mockAttribute(AWS_GUARDRAIL_ID, "test_guardrail_^id");
    validateRemoteResourceAttributes("AWS::Bedrock::Guardrail", "test_guardrail_^^id");
    // Also test with ARN containing special chars to verify delimiter escaping in
    // cloudformationPrimaryIdentifier
    mockAttribute(
        AWS_GUARDRAIL_ARN, "arn:aws:bedrock:us-east-1:123456789012:guardrail/test_guardrail_^id");
    validateRemoteResourceAttributes(
        "AWS::Bedrock::Guardrail",
        "test_guardrail_^^id",
        "arn:aws:bedrock:us-east-1:123456789012:guardrail/test_guardrail_^^id");
    validateRemoteResourceAccountIdAndRegion(
          Optional.of("123456789012"), Optional.empty(), Optional.of("us-east-1"));
    mockAttribute(AWS_GUARDRAIL_ID, null);
    mockAttribute(AWS_AUTH_REGION, null);
    mockAttribute(AWS_AUTH_ACCESS_KEY, null);
    mockAttribute(AWS_GUARDRAIL_ARN, null);

    // Validate behaviour of GEN_AI_REQUEST_MODEL attribute, then remove it.
    mockAttribute(GEN_AI_REQUEST_MODEL, "test.service_id");
    validateRemoteResourceAttributes("AWS::Bedrock::Model", "test.service_id");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(GEN_AI_REQUEST_MODEL, null);

    // Validate behaviour of GEN_AI_REQUEST_MODEL attribute with special chars(^), then
    // remove it.
    mockAttribute(GEN_AI_REQUEST_MODEL, "test.service_^id");
    validateRemoteResourceAttributes("AWS::Bedrock::Model", "test.service_^^id");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(GEN_AI_REQUEST_MODEL, null);

    // Validate behaviour of AWS_STATE_MACHINE_ARN attribute, then remove it.
    mockAttribute(
        AWS_STATE_MACHINE_ARN,
        "arn:aws:states:us-east-1:123456789012:stateMachine:test_state_machine");
    mockAttribute(AWS_AUTH_ACCESS_KEY, MOCK_ACCESS_KEY);
    mockAttribute(AWS_AUTH_REGION, MOCK_REGION);
    validateRemoteResourceAccountIdAndRegion(
        Optional.of("123456789012"), Optional.empty(), Optional.of("us-east-1"));
    validateRemoteResourceAttributes(
        "AWS::StepFunctions::StateMachine",
        "test_state_machine",
        "arn:aws:states:us-east-1:123456789012:stateMachine:test_state_machine");
    mockAttribute(AWS_STATE_MACHINE_ARN, null);

    // Validate behaviour of AWS_STEPFUNCTIONS_ACTIVITY_ARN, then remove it.
    mockAttribute(
        AWS_STEP_FUNCTIONS_ACTIVITY_ARN,
        "arn:aws:states:us-east-1:123456789012:activity:testActivity");
    mockAttribute(AWS_AUTH_ACCESS_KEY, MOCK_ACCESS_KEY);
    mockAttribute(AWS_AUTH_REGION, MOCK_REGION);
    validateRemoteResourceAccountIdAndRegion(
        Optional.of("123456789012"), Optional.empty(), Optional.of("us-east-1"));
    validateRemoteResourceAttributes(
        "AWS::StepFunctions::Activity",
        "testActivity",
        "arn:aws:states:us-east-1:123456789012:activity:testActivity");
    mockAttribute(AWS_STEP_FUNCTIONS_ACTIVITY_ARN, null);

    // Validate behaviour of AWS_SNS_TOPIC_ARN, then remove it.
    mockAttribute(AWS_SNS_TOPIC_ARN, "arn:aws:sns:us-west-2:012345678901:testTopic");
    mockAttribute(AWS_AUTH_ACCESS_KEY, MOCK_ACCESS_KEY);
    mockAttribute(AWS_AUTH_REGION, MOCK_REGION);
    validateRemoteResourceAccountIdAndRegion(
        Optional.of("012345678901"), Optional.empty(), Optional.of("us-west-2"));
    validateRemoteResourceAttributes(
        "AWS::SNS::Topic", "testTopic", "arn:aws:sns:us-west-2:012345678901:testTopic");
    mockAttribute(AWS_SNS_TOPIC_ARN, null);

    // Validate behaviour of AWS_SECRET_ARN, then remove it.
    mockAttribute(
        AWS_SECRET_ARN, "arn:aws:secretsmanager:us-east-1:123456789012:secret:secretName");
    mockAttribute(AWS_AUTH_ACCESS_KEY, MOCK_ACCESS_KEY);
    mockAttribute(AWS_AUTH_REGION, MOCK_REGION);
    validateRemoteResourceAccountIdAndRegion(
        Optional.of("123456789012"), Optional.empty(), Optional.of("us-east-1"));
    validateRemoteResourceAttributes(
        "AWS::SecretsManager::Secret",
        "secretName",
        "arn:aws:secretsmanager:us-east-1:123456789012:secret:secretName");
    mockAttribute(AWS_SECRET_ARN, null);

    // Validate behaviour of AWS_LAMBDA_NAME for non-Invoke operations (treated as resource)
    mockAttribute(RPC_SERVICE, "Lambda");
    mockAttribute(RPC_METHOD, "GetFunction");
    mockAttribute(AWS_LAMBDA_NAME, "testLambdaName");
    mockAttribute(AWS_LAMBDA_ARN, "arn:aws:lambda:us-east-1:123456789012:function:testLambdaName");
    validateRemoteResourceAttributes(
        "AWS::Lambda::Function",
        "testLambdaName",
        "arn:aws:lambda:us-east-1:123456789012:function:testLambdaName");
    mockAttribute(RPC_SERVICE, null);
    mockAttribute(RPC_METHOD, null);
    mockAttribute(AWS_LAMBDA_NAME, null);
    mockAttribute(AWS_LAMBDA_ARN, null);

    // Validate behaviour of AWS_LAMBDA_NAME containing ARN for non-Invoke operations
    mockAttribute(RPC_SERVICE, "Lambda");
    mockAttribute(RPC_METHOD, "ListFunctions");
    mockAttribute(AWS_LAMBDA_NAME, "arn:aws:lambda:us-east-1:123456789012:function:testLambdaName");
    mockAttribute(AWS_LAMBDA_ARN, "arn:aws:lambda:us-east-1:123456789012:function:testLambdaName");
    validateRemoteResourceAttributes(
        "AWS::Lambda::Function",
        "testLambdaName",
        "arn:aws:lambda:us-east-1:123456789012:function:testLambdaName");
    mockAttribute(RPC_SERVICE, null);
    mockAttribute(RPC_METHOD, null);
    mockAttribute(AWS_LAMBDA_NAME, null);
    mockAttribute(AWS_LAMBDA_ARN, null);

    // Validate that Lambda Invoke with function name treats Lambda as a service, not a resource
    mockAttribute(RPC_SERVICE, "Lambda");
    mockAttribute(RPC_METHOD, "Invoke");
    mockAttribute(AWS_LAMBDA_NAME, "testLambdaName");
    validateRemoteResourceAttributes(null, null);
    mockAttribute(RPC_SERVICE, null);
    mockAttribute(RPC_METHOD, null);
    mockAttribute(AWS_LAMBDA_NAME, null);

    // Validate behaviour of AWS_LAMBDA_NAME containing ARN for Invoke operations
    mockAttribute(RPC_SERVICE, "Lambda");
    mockAttribute(RPC_METHOD, "Invoke");
    mockAttribute(AWS_LAMBDA_NAME, "arn:aws:lambda:us-east-1:123456789012:function:testLambdaName");
    validateRemoteResourceAttributes(null, null);
    mockAttribute(RPC_SERVICE, null);
    mockAttribute(RPC_METHOD, null);
    mockAttribute(AWS_LAMBDA_NAME, null);

    // Validate behaviour of AWS_LAMBDA_RESOURCE_ID
    mockAttribute(AWS_LAMBDA_RESOURCE_ID, "eventSourceId");
    validateRemoteResourceAttributes("AWS::Lambda::EventSourceMapping", "eventSourceId");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_LAMBDA_RESOURCE_ID, null);

    // Validate behaviour of AWS_LAMBDA_FUNCTION_NAME
    mockAttribute(AWS_LAMBDA_RESOURCE_ID, "eventSourceId");
    validateRemoteResourceAttributes("AWS::Lambda::EventSourceMapping", "eventSourceId");
    validateRemoteResourceAccountIdAndRegion(
        Optional.empty(), Optional.of(MOCK_ACCESS_KEY), Optional.of(MOCK_REGION));
    mockAttribute(AWS_LAMBDA_RESOURCE_ID, null);

    // Cross account support
    // Invalid arn but account access key is available
    mockAttribute(AWS_SECRET_ARN, "invalid_arn");
    validateRemoteResourceAccountIdAndRegion(Optional.empty(), Optional.empty(), Optional.empty());
    mockAttribute(AWS_SECRET_ARN, null);

    // Invalid arn and no account access key
    mockAttribute(AWS_SECRET_ARN, "invalid_arn");
    validateRemoteResourceAccountIdAndRegion(Optional.empty(), Optional.empty(), Optional.empty());
    mockAttribute(AWS_SECRET_ARN, null);

    // Both account access key and account id are not available
    mockAttribute(AWS_AUTH_REGION, null);
    mockAttribute(AWS_AUTH_ACCESS_KEY, null);
    mockAttribute(AWS_BUCKET_NAME, "aws_s3_bucket_name");
    validateRemoteResourceAttributes("AWS::S3::Bucket", "aws_s3_bucket_name");
    validateRemoteResourceAccountIdAndRegion(Optional.empty(), Optional.empty(), Optional.empty());
    mockAttribute(AWS_BUCKET_NAME, null);

    // Account access key is not available
    mockAttribute(
        AWS_SECRET_ARN, "arn:aws:secretsmanager:us-east-1:123456789012:secret:secretName");
    validateRemoteResourceAttributes("AWS::SecretsManager::Secret", "secretName");
    validateRemoteResourceAccountIdAndRegion(
        Optional.of("123456789012"), Optional.empty(), Optional.of("us-east-1"));
    mockAttribute(AWS_SECRET_ARN, null);

    // Arn with invalid account id
    mockAttribute(
        AWS_SECRET_ARN, "arn:aws:secretsmanager:us-east-1:invalid_account_id:secret:secretName");
    validateRemoteResourceAttributes("AWS::SecretsManager::Secret", "secretName");
    validateRemoteResourceAccountIdAndRegion(
        Optional.of("invalid_account_id"), Optional.empty(), Optional.of("us-east-1"));
    mockAttribute(AWS_SECRET_ARN, null);

    // Arn with invalid region
    mockAttribute(
        AWS_SECRET_ARN, "arn:aws:secretsmanager:invalid_region:123456789012:secret:secretName");
    validateRemoteResourceAttributes("AWS::SecretsManager::Secret", "secretName");
    validateRemoteResourceAccountIdAndRegion(
        Optional.of("123456789012"), Optional.empty(), Optional.of("invalid_region"));
    mockAttribute(AWS_SECRET_ARN, null);

    mockAttribute(RPC_SYSTEM, "null");
  }

  @Test
  public void testCloudFormationPrimaryIdentifierFallbackToRemoteResourceIdentifier() {
    // Test that when cloudformationPrimaryIdentifier is not explicitly set,
    // it falls back to use the same value as remoteResourceIdentifier

    mockAttribute(RPC_SYSTEM, "aws-api");
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);

    // Test case 1: S3 Bucket (no ARN available, should use bucket name for both)
    mockAttribute(AWS_BUCKET_NAME, "my-test-bucket");
    validateRemoteResourceAttributes("AWS::S3::Bucket", "my-test-bucket");

    // Test S3 bucket with special characters
    mockAttribute(AWS_BUCKET_NAME, "my-test|bucket^name");
    validateRemoteResourceAttributes("AWS::S3::Bucket", "my-test^|bucket^^name");
    mockAttribute(AWS_BUCKET_NAME, null);

    // Test case 2: SQS Queue by name (no ARN, should use queue name for both)
    mockAttribute(AWS_QUEUE_NAME, "my-test-queue");
    validateRemoteResourceAttributes("AWS::SQS::Queue", "my-test-queue");

    // Test SQS queue with special characters
    mockAttribute(AWS_QUEUE_NAME, "my^queue|name");
    validateRemoteResourceAttributes("AWS::SQS::Queue", "my^^queue^|name");
    mockAttribute(AWS_QUEUE_NAME, null);

    // Test case 3: DynamoDB Table (no ARN, should use table name for both)
    mockAttribute(AWS_TABLE_NAME, "my-test-table");
    validateRemoteResourceAttributes("AWS::DynamoDB::Table", "my-test-table");

    // Test DynamoDB table with special characters
    mockAttribute(AWS_TABLE_NAME, "my|test^table");
    validateRemoteResourceAttributes("AWS::DynamoDB::Table", "my^|test^^table");
    mockAttribute(AWS_TABLE_NAME, null);

    // Test case 4: Kinesis Stream
    mockAttribute(AWS_STREAM_NAME, "my-test-stream");
    validateRemoteResourceAttributes("AWS::Kinesis::Stream", "my-test-stream");

    // Test Kinesis stream with special characters
    mockAttribute(AWS_STREAM_NAME, "my-stream^with|chars");
    validateRemoteResourceAttributes("AWS::Kinesis::Stream", "my-stream^^with^|chars");
    mockAttribute(AWS_STREAM_NAME, null);

    // Test case 5: Lambda Function (non-invoke operation, no ARN)
    mockAttribute(RPC_METHOD, "GetFunction"); // Non-invoke operation
    mockAttribute(AWS_LAMBDA_NAME, "my-test-function");
    validateRemoteResourceAttributes("AWS::Lambda::Function", "my-test-function");

    // Test Lambda function with special characters
    mockAttribute(AWS_LAMBDA_NAME, "my-function|with^chars");
    validateRemoteResourceAttributes("AWS::Lambda::Function", "my-function^|with^^chars");
    mockAttribute(AWS_LAMBDA_NAME, null);
    mockAttribute(RPC_METHOD, null);

    mockAttribute(RPC_SYSTEM, null);
  }

  @Test
  public void testDBClientSpanWithRemoteResourceAttributes() {
    mockAttribute(DB_SYSTEM, "mysql");
    // Validate behaviour of DB_NAME, SERVER_ADDRESS and SERVER_PORT exist, then remove it.
    mockAttribute(DB_NAME, "db_name");
    mockAttribute(SERVER_ADDRESS, "abc.com");
    mockAttribute(SERVER_PORT, 3306L);
    validateRemoteResourceAttributes("DB::Connection", "db_name|abc.com|3306");
    mockAttribute(DB_NAME, null);
    mockAttribute(SERVER_ADDRESS, null);
    mockAttribute(SERVER_PORT, null);

    // Validate behaviour of DB_NAME with '|' char, SERVER_ADDRESS and SERVER_PORT exist, then
    // remove it.
    mockAttribute(DB_NAME, "db_name|special");
    mockAttribute(SERVER_ADDRESS, "abc.com");
    mockAttribute(SERVER_PORT, 3306L);
    validateRemoteResourceAttributes("DB::Connection", "db_name^|special|abc.com|3306");
    mockAttribute(DB_NAME, null);
    mockAttribute(SERVER_ADDRESS, null);
    mockAttribute(SERVER_PORT, null);

    // Validate behaviour of DB_NAME with '^' char, SERVER_ADDRESS and SERVER_PORT exist, then
    // remove it.
    mockAttribute(DB_NAME, "db_name^special");
    mockAttribute(SERVER_ADDRESS, "abc.com");
    mockAttribute(SERVER_PORT, 3306L);
    validateRemoteResourceAttributes("DB::Connection", "db_name^^special|abc.com|3306");
    mockAttribute(DB_NAME, null);
    mockAttribute(SERVER_ADDRESS, null);
    mockAttribute(SERVER_PORT, null);

    // Validate behaviour of DB_NAME, SERVER_ADDRESS exist, then remove it.
    mockAttribute(DB_NAME, "db_name");
    mockAttribute(SERVER_ADDRESS, "abc.com");
    validateRemoteResourceAttributes("DB::Connection", "db_name|abc.com");
    mockAttribute(DB_NAME, null);
    mockAttribute(SERVER_ADDRESS, null);

    // Validate behaviour of SERVER_ADDRESS exist, then remove it.
    mockAttribute(SERVER_ADDRESS, "abc.com");
    validateRemoteResourceAttributes("DB::Connection", "abc.com");
    mockAttribute(SERVER_ADDRESS, null);

    // Validate behaviour of SERVER_PORT exist, then remove it.
    mockAttribute(SERVER_PORT, 3306L);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isNull();
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isNull();
    mockAttribute(SERVER_PORT, null);

    // Validate behaviour of DB_NAME, NET_PEER_NAME and NET_PEER_PORT exist, then remove it.
    mockAttribute(DB_NAME, "db_name");
    mockAttribute(NET_PEER_NAME, "abc.com");
    mockAttribute(NET_PEER_PORT, 3306L);
    validateRemoteResourceAttributes("DB::Connection", "db_name|abc.com|3306");
    mockAttribute(DB_NAME, null);
    mockAttribute(NET_PEER_NAME, null);
    mockAttribute(NET_PEER_PORT, null);

    // Validate behaviour of DB_NAME, NET_PEER_NAME exist, then remove it.
    mockAttribute(DB_NAME, "db_name");
    mockAttribute(NET_PEER_NAME, "abc.com");
    validateRemoteResourceAttributes("DB::Connection", "db_name|abc.com");
    mockAttribute(DB_NAME, null);
    mockAttribute(NET_PEER_NAME, null);

    // Validate behaviour of NET_PEER_NAME exist, then remove it.
    mockAttribute(NET_PEER_NAME, "abc.com");
    validateRemoteResourceAttributes("DB::Connection", "abc.com");
    mockAttribute(NET_PEER_NAME, null);

    // Validate behaviour of NET_PEER_PORT exist, then remove it.
    mockAttribute(NET_PEER_PORT, 3306L);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isNull();
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isNull();
    mockAttribute(NET_PEER_PORT, null);

    // Validate behaviour of DB_NAME, SERVER_SOCKET_ADDRESS and SERVER_SOCKET_PORT exist, then
    // remove it.
    mockAttribute(DB_NAME, "db_name");
    mockAttribute(SERVER_SOCKET_ADDRESS, "abc.com");
    mockAttribute(SERVER_SOCKET_PORT, 3306L);
    validateRemoteResourceAttributes("DB::Connection", "db_name|abc.com|3306");
    mockAttribute(DB_NAME, null);
    mockAttribute(SERVER_SOCKET_ADDRESS, null);
    mockAttribute(SERVER_SOCKET_PORT, null);

    // Validate behaviour of DB_NAME, SERVER_SOCKET_ADDRESS exist, then remove it.
    mockAttribute(DB_NAME, "db_name");
    mockAttribute(SERVER_SOCKET_ADDRESS, "abc.com");
    validateRemoteResourceAttributes("DB::Connection", "db_name|abc.com");
    mockAttribute(DB_NAME, null);
    mockAttribute(SERVER_SOCKET_ADDRESS, null);

    // Validate behaviour of SERVER_SOCKET_PORT exist, then remove it.
    mockAttribute(SERVER_SOCKET_PORT, 3306L);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isNull();
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isNull();
    mockAttribute(SERVER_SOCKET_PORT, null);

    // Validate behaviour of only DB_NAME exist, then remove it.
    mockAttribute(DB_NAME, "db_name");
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isNull();
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isNull();
    mockAttribute(DB_NAME, null);

    // Validate behaviour of DB_NAME and DB_CONNECTION_STRING exist, then remove it.
    mockAttribute(DB_NAME, "db_name");
    mockAttribute(
        DB_CONNECTION_STRING,
        "mysql://test-apm.cluster-cnrw3s3ddo7n.us-east-1.rds.amazonaws.com:3306/petclinic");
    validateRemoteResourceAttributes(
        "DB::Connection", "db_name|test-apm.cluster-cnrw3s3ddo7n.us-east-1.rds.amazonaws.com|3306");
    mockAttribute(DB_NAME, null);
    mockAttribute(DB_CONNECTION_STRING, null);

    // Validate behaviour of DB_CONNECTION_STRING exist, then remove it.
    mockAttribute(
        DB_CONNECTION_STRING,
        "mysql://test-apm.cluster-cnrw3s3ddo7n.us-east-1.rds.amazonaws.com:3306/petclinic");
    validateRemoteResourceAttributes(
        "DB::Connection", "test-apm.cluster-cnrw3s3ddo7n.us-east-1.rds.amazonaws.com|3306");
    mockAttribute(DB_CONNECTION_STRING, null);

    // Validate behaviour of DB_CONNECTION_STRING exist without port, then remove it.
    mockAttribute(DB_CONNECTION_STRING, "http://dbserver");
    validateRemoteResourceAttributes("DB::Connection", "dbserver");
    mockAttribute(DB_CONNECTION_STRING, null);

    // Validate behaviour of DB_NAME and invalid DB_CONNECTION_STRING exist, then remove it.
    mockAttribute(DB_NAME, "db_name");
    mockAttribute(DB_CONNECTION_STRING, "hsqldb:mem:");
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isNull();
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isNull();
    mockAttribute(DB_NAME, null);
    mockAttribute(DB_CONNECTION_STRING, null);

    mockAttribute(DB_SYSTEM, null);
  }

  @Test
  public void testHttpStatusAttributeNotAwsSdk() {
    validateHttpStatusWithThrowable(new ThrowableWithMethodGetStatusCode(500), null);
  }

  @Test
  public void testHttpStatusAttributeStatusAlreadyPresent() {
    when(instrumentationScopeInfoMock.getName()).thenReturn("aws-sdk");
    mockAttribute(HTTP_RESPONSE_STATUS_CODE, 200L);
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

  private void validateRemoteResourceAccountIdAndRegion(
      Optional<String> accountId, Optional<String> accessKey, Optional<String> region) {
    SpanKind[] spanKinds = {SpanKind.CLIENT, SpanKind.PRODUCER, SpanKind.CONSUMER};

    for (SpanKind spanKind : spanKinds) {
      when(spanDataMock.getKind()).thenReturn(spanKind);
      Attributes actualAttributes =
          GENERATOR
              .generateMetricAttributeMapFromSpan(spanDataMock, resource)
              .get(DEPENDENCY_METRIC);

      if (region.isPresent()) {
        assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_REGION)).isEqualTo(region.get());
      } else {
        assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_REGION)).isEqualTo(null);
      }

      if (accountId.isPresent()) {
        assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_ACCOUNT_ID)).isEqualTo(accountId.get());
        assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_ACCESS_KEY)).isEqualTo(null);
      } else {
        assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_ACCOUNT_ID)).isEqualTo(null);
      }

      if (accessKey.isPresent()) {
        assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_ACCESS_KEY)).isEqualTo(accessKey.get());
        assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_ACCOUNT_ID)).isEqualTo(null);
      } else {
        assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_ACCESS_KEY)).isEqualTo(null);
      }
    }

    // Server span should not generate remote resource attributes
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(SERVICE_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_ACCESS_KEY)).isNull();
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_ACCOUNT_ID)).isNull();
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_REGION)).isNull();
  }

  private void validateRemoteResourceAttributes(String type, String identifier) {
    validateRemoteResourceAttributes(type, identifier, identifier);
  }

  private void validateRemoteResourceAttributes(
      String type, String identifier, String cloudformationPrimaryIdentifier) {
    // Client, Producer and Consumer spans should generate the expected remote resource attributes
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isEqualTo(type);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isEqualTo(identifier);
    assertThat(actualAttributes.get(AWS_CLOUDFORMATION_PRIMARY_IDENTIFIER))
        .isEqualTo(cloudformationPrimaryIdentifier);

    when(spanDataMock.getKind()).thenReturn(SpanKind.PRODUCER);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isEqualTo(type);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isEqualTo(identifier);
    assertThat(actualAttributes.get(AWS_CLOUDFORMATION_PRIMARY_IDENTIFIER))
        .isEqualTo(cloudformationPrimaryIdentifier);

    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isEqualTo(type);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isEqualTo(identifier);
    assertThat(actualAttributes.get(AWS_CLOUDFORMATION_PRIMARY_IDENTIFIER))
        .isEqualTo(cloudformationPrimaryIdentifier);

    // Server span should not generate remote resource attributes
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(SERVICE_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isEqualTo(null);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isEqualTo(null);
    assertThat(actualAttributes.get(AWS_CLOUDFORMATION_PRIMARY_IDENTIFIER)).isEqualTo(null);
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
    assertThat(actualAttributes.get(HTTP_RESPONSE_STATUS_CODE)).isEqualTo(expectedStatusCode);
    if (expectedStatusCode == null) {
      assertThat(actualAttributes.asMap().containsKey(HTTP_RESPONSE_STATUS_CODE)).isFalse();
    }
  }

  @Test
  public void testDBUserAttribute() {
    mockAttribute(DB_OPERATION, "db_operation");
    mockAttribute(DB_USER, "db_user");
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);

    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_OPERATION)).isEqualTo("db_operation");
    assertThat(actualAttributes.get(AWS_REMOTE_DB_USER)).isEqualTo("db_user");
  }

  @Test
  public void testDBUserAttributeAbsent() {
    mockAttribute(DB_SYSTEM, "db_system");
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);

    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_DB_USER)).isNull();
  }

  @Test
  public void testDBUserAttributeWithDifferentValues() {
    mockAttribute(DB_OPERATION, "db_operation");
    mockAttribute(DB_USER, "non_db_user");
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);

    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_DB_USER)).isEqualTo("non_db_user");
  }

  @Test
  public void testDBUserAttributeNotPresentInServiceMetricForServerSpan() {
    mockAttribute(DB_USER, "db_user");
    mockAttribute(DB_SYSTEM, "db_system");
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);

    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(SERVICE_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_DB_USER)).isNull();
  }

  @Test
  public void testDbUserPresentAndIsDbSpanFalse() {
    mockAttribute(DB_USER, "DB user");
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);

    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_DB_USER)).isNull();
  }

  @Test
  public void testNormalizeRemoteServiceName_NoNormalization() {
    String serviceName = "non aws service";
    mockAttribute(RPC_SERVICE, serviceName);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);

    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_SERVICE)).isEqualTo(serviceName);
  }

  @Test
  public void testNormalizeRemoteServiceName_AwsSdk() {
    // AWS SDK V1
    testAwsSdkServiceNormalization("AmazonDynamoDBv2", "AWS::DynamoDB");
    testAwsSdkServiceNormalization("AmazonKinesis", "AWS::Kinesis");
    testAwsSdkServiceNormalization("Amazon S3", "AWS::S3");
    testAwsSdkServiceNormalization("AmazonSQS", "AWS::SQS");
    testAwsSdkServiceNormalization("Bedrock", "AWS::Bedrock");
    testAwsSdkServiceNormalization("AWSBedrockAgentRuntime", "AWS::Bedrock");
    testAwsSdkServiceNormalization("AWSBedrockAgent", "AWS::Bedrock");
    testAwsSdkServiceNormalization("AmazonBedrockRuntime", "AWS::BedrockRuntime");
    testAwsSdkServiceNormalization("AWSStepFunctions", "AWS::StepFunctions");

    // AWS SDK V1 Lambda tests
    testAwsSdkServiceNormalization("Lambda", "AWS::Lambda");
    mockAttribute(RPC_METHOD, "Invoke");
    mockAttribute(AWS_LAMBDA_NAME, "testFunction");
    testAwsSdkServiceNormalization("Lambda", "testFunction");
    // Test Lambda Invoke without AWS_LAMBDA_NAME - should fall back to UnknownRemoteService
    mockAttribute(AWS_LAMBDA_NAME, null);
    testAwsSdkServiceNormalization("Lambda", "UnknownRemoteService");
    mockAttribute(RPC_METHOD, null);

    testAwsSdkServiceNormalization("AWSLambda", "AWS::Lambda");
    mockAttribute(RPC_METHOD, "Invoke");
    mockAttribute(AWS_LAMBDA_NAME, "testFunction");
    testAwsSdkServiceNormalization("AWSLambda", "testFunction");
    // Test Lambda Invoke without AWS_LAMBDA_NAME - should fall back to UnknownRemoteService
    mockAttribute(AWS_LAMBDA_NAME, null);
    testAwsSdkServiceNormalization("AWSLambda", "UnknownRemoteService");
    mockAttribute(RPC_METHOD, null);

    // AWS SDK V2
    testAwsSdkServiceNormalization("DynamoDb", "AWS::DynamoDB");
    testAwsSdkServiceNormalization("Kinesis", "AWS::Kinesis");
    testAwsSdkServiceNormalization("S3", "AWS::S3");
    testAwsSdkServiceNormalization("Sqs", "AWS::SQS");
    testAwsSdkServiceNormalization("Bedrock", "AWS::Bedrock");
    testAwsSdkServiceNormalization("BedrockAgentRuntime", "AWS::Bedrock");
    testAwsSdkServiceNormalization("BedrockAgent", "AWS::Bedrock");
    testAwsSdkServiceNormalization("BedrockRuntime", "AWS::BedrockRuntime");
  }

  private void testAwsSdkServiceNormalization(String serviceName, String expectedRemoteService) {
    mockAttribute(RPC_SYSTEM, "aws-api");
    mockAttribute(RPC_SERVICE, serviceName);
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);

    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_SERVICE)).isEqualTo(expectedRemoteService);
  }

  @Test
  public void testSetRemoteEnvironment() {
    // Test 1: Setting remote environment when all relevant attributes are present
    mockAttribute(RPC_SYSTEM, "aws-api");
    mockAttribute(RPC_SERVICE, "Lambda");
    mockAttribute(RPC_METHOD, "Invoke");
    mockAttribute(AWS_LAMBDA_NAME, "testFunction");
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);

    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_ENVIRONMENT)).isEqualTo("lambda:default");

    // Test 2: NOT setting it when RPC_SYSTEM is missing
    mockAttribute(RPC_SYSTEM, null);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_ENVIRONMENT)).isNull();
    mockAttribute(RPC_SYSTEM, "aws-api");

    // Test 3: NOT setting it when RPC_METHOD is missing
    mockAttribute(RPC_METHOD, null);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_ENVIRONMENT)).isNull();
    mockAttribute(RPC_METHOD, "Invoke");

    // Test 4: Still setting it to lambda:default when AWS_LAMBDA_NAME is missing
    mockAttribute(AWS_LAMBDA_NAME, null);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_ENVIRONMENT)).isEqualTo("lambda:default");
    mockAttribute(AWS_LAMBDA_NAME, "testFunction");

    // Test 5: NOT setting it for non-Lambda services
    mockAttribute(RPC_SERVICE, "S3");
    mockAttribute(RPC_METHOD, "GetObject");
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_ENVIRONMENT)).isNull();

    // Test 6: NOT setting it for Lambda non-Invoke operations
    mockAttribute(RPC_SERVICE, "Lambda");
    mockAttribute(RPC_METHOD, "GetFunction");
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_ENVIRONMENT)).isNull();
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
