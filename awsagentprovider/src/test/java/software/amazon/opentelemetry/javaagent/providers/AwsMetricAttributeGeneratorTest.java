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
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_BUCKET_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_DATASOURCE_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_GUARDRAIL_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_KNOWLEDGEBASE_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_QUEUE_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_QUEUE_URL;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_RESOURCE_IDENTIFIER;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_RESOURCE_TYPE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_SPAN_KIND;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_STREAM_NAME;
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

    // Validate behavior of Remote Operation from HttpTarget - with 1st api part. Also validates
    // that RemoteService is extracted from HttpUrl.
    mockAttribute(HTTP_URL, "http://www.example.com/payment/123");
    validateExpectedRemoteAttributes("www.example.com", "/payment");
    mockAttribute(HTTP_URL, null);

    // Validate behavior of Remote Operation from HttpTarget - with 1st api part. Also validates
    // that RemoteService is extracted from HttpUrl.
    mockAttribute(HTTP_URL, "http://www.example.com");
    validateExpectedRemoteAttributes("www.example.com", "/");
    mockAttribute(HTTP_URL, null);

    // Validate behavior of Remote Service from HttpUrl
    mockAttribute(HTTP_URL, "http://192.168.1.1:8000");
    validateExpectedRemoteAttributes("192.168.1.1:8000", "/");
    mockAttribute(HTTP_URL, null);

    // Validate behavior of Remote Service from HttpUrl
    mockAttribute(HTTP_URL, "http://192.168.1.1");
    validateExpectedRemoteAttributes("192.168.1.1", "/");
    mockAttribute(HTTP_URL, null);

    // Validate behavior of Remote Service from HttpUrl
    mockAttribute(HTTP_URL, "");
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, UNKNOWN_REMOTE_OPERATION);
    mockAttribute(HTTP_URL, null);

    // Validate behavior of Remote Service from HttpUrl
    mockAttribute(HTTP_URL, null);
    validateExpectedRemoteAttributes(UNKNOWN_REMOTE_SERVICE, UNKNOWN_REMOTE_OPERATION);
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
  public void testSdkClientSpanWithRemoteResourceAttributes() {
    mockAttribute(RPC_SYSTEM, "aws-api");
    // Validate behaviour of aws bucket name attribute, then remove it.
    mockAttribute(AWS_BUCKET_NAME, "aws_s3_bucket_name");
    validateRemoteResourceAttributes("AWS::S3::Bucket", "aws_s3_bucket_name");
    mockAttribute(AWS_BUCKET_NAME, null);

    // Validate behaviour of AWS_QUEUE_NAME attribute, then remove it.
    mockAttribute(AWS_QUEUE_NAME, "aws_queue_name");
    validateRemoteResourceAttributes("AWS::SQS::Queue", "aws_queue_name");
    mockAttribute(AWS_QUEUE_NAME, null);

    // Validate behaviour of having both AWS_QUEUE_NAME and AWS_QUEUE_URL attribute, then remove
    // them. Queue name is more reliable than queue URL, so we prefer to use name over URL.
    mockAttribute(AWS_QUEUE_URL, "https://sqs.us-east-2.amazonaws.com/123456789012/Queue");
    mockAttribute(AWS_QUEUE_NAME, "aws_queue_name");
    validateRemoteResourceAttributes("AWS::SQS::Queue", "aws_queue_name");
    mockAttribute(AWS_QUEUE_URL, null);
    mockAttribute(AWS_QUEUE_NAME, null);

    // Valid queue name with invalid queue URL, we should default to using the queue name.
    mockAttribute(AWS_QUEUE_URL, "invalidUrl");
    mockAttribute(AWS_QUEUE_NAME, "aws_queue_name");
    validateRemoteResourceAttributes("AWS::SQS::Queue", "aws_queue_name");
    mockAttribute(AWS_QUEUE_URL, null);
    mockAttribute(AWS_QUEUE_NAME, null);

    // Validate behaviour of AWS_STREAM_NAME attribute, then remove it.
    mockAttribute(AWS_STREAM_NAME, "aws_stream_name");
    validateRemoteResourceAttributes("AWS::Kinesis::Stream", "aws_stream_name");
    mockAttribute(AWS_STREAM_NAME, null);

    // Validate behaviour of AWS_TABLE_NAME attribute, then remove it.
    mockAttribute(AWS_TABLE_NAME, "aws_table_name");
    validateRemoteResourceAttributes("AWS::DynamoDB::Table", "aws_table_name");
    mockAttribute(AWS_TABLE_NAME, null);

    // Validate behaviour of AWS_TABLE_NAME attribute with special chars(|), then remove it.
    mockAttribute(AWS_TABLE_NAME, "aws_table|name");
    validateRemoteResourceAttributes("AWS::DynamoDB::Table", "aws_table^|name");
    mockAttribute(AWS_TABLE_NAME, null);

    // Validate behaviour of AWS_TABLE_NAME attribute with special chars(^), then remove it.
    mockAttribute(AWS_TABLE_NAME, "aws_table^name");
    validateRemoteResourceAttributes("AWS::DynamoDB::Table", "aws_table^^name");
    mockAttribute(AWS_TABLE_NAME, null);

    // Validate behaviour of AWS_BEDROCK_AGENT_ID attribute, then remove it.
    mockAttribute(AWS_AGENT_ID, "test_agent_id");
    validateRemoteResourceAttributes("AWS::Bedrock::Agent", "test_agent_id");
    mockAttribute(AWS_AGENT_ID, null);

    // Validate behaviour of AWS_BEDROCK_AGENT_ID attribute with special chars(^), then remove it.
    mockAttribute(AWS_AGENT_ID, "test_agent_^id");
    validateRemoteResourceAttributes("AWS::Bedrock::Agent", "test_agent_^^id");
    mockAttribute(AWS_AGENT_ID, null);

    // Validate behaviour of AWS_KNOWLEDGEBASE_ID attribute, then remove it.
    mockAttribute(AWS_KNOWLEDGEBASE_ID, "test_knowledgeBase_id");
    validateRemoteResourceAttributes("AWS::Bedrock::KnowledgeBase", "test_knowledgeBase_id");
    mockAttribute(AWS_KNOWLEDGEBASE_ID, null);

    // Validate behaviour of AWS_KNOWLEDGEBASE_ID attribute with special chars(^), then remove it.
    mockAttribute(AWS_KNOWLEDGEBASE_ID, "test_knowledgeBase_^id");
    validateRemoteResourceAttributes("AWS::Bedrock::KnowledgeBase", "test_knowledgeBase_^^id");
    mockAttribute(AWS_KNOWLEDGEBASE_ID, null);

    // Validate behaviour of AWS_BEDROCK_DATASOURCE_ID attribute, then remove it.
    mockAttribute(AWS_DATASOURCE_ID, "test_datasource_id");
    validateRemoteResourceAttributes("AWS::Bedrock::DataSource", "test_datasource_id");
    mockAttribute(AWS_DATASOURCE_ID, null);

    // Validate behaviour of AWS_BEDROCK_DATASOURCE_ID attribute with special chars(^), then remove
    // it.
    mockAttribute(AWS_DATASOURCE_ID, "test_datasource_^id");
    validateRemoteResourceAttributes("AWS::Bedrock::DataSource", "test_datasource_^^id");
    mockAttribute(AWS_DATASOURCE_ID, null);

    // Validate behaviour of AWS_GUARDRAIL_ID attribute, then remove it.
    mockAttribute(AWS_GUARDRAIL_ID, "test_guardrail_id");
    validateRemoteResourceAttributes("AWS::Bedrock::Guardrail", "test_guardrail_id");
    mockAttribute(AWS_GUARDRAIL_ID, null);

    // Validate behaviour of AWS_GUARDRAIL_ID attribute with special chars(^), then remove it.
    mockAttribute(AWS_GUARDRAIL_ID, "test_guardrail_^id");
    validateRemoteResourceAttributes("AWS::Bedrock::Guardrail", "test_guardrail_^^id");
    mockAttribute(AWS_GUARDRAIL_ID, null);

    // Validate behaviour of AWS_BEDROCK_RUNTIME_MODEL_ID attribute, then remove it.
    mockAttribute(GEN_AI_REQUEST_MODEL, "test.service_id");
    validateRemoteResourceAttributes("AWS::Bedrock::Model", "test.service_id");
    mockAttribute(GEN_AI_REQUEST_MODEL, null);

    // Validate behaviour of AWS_BEDROCK_RUNTIME_MODEL_ID attribute with special chars(^), then
    // remove it.
    mockAttribute(GEN_AI_REQUEST_MODEL, "test.service_^id");
    validateRemoteResourceAttributes("AWS::Bedrock::Model", "test.service_^^id");
    mockAttribute(GEN_AI_REQUEST_MODEL, null);
    mockAttribute(RPC_SYSTEM, "null");
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

  private void validateRemoteResourceAttributes(String type, String identifier) {
    // Client, Producer and Consumer spans should generate the expected remote resource attributes
    when(spanDataMock.getKind()).thenReturn(SpanKind.CLIENT);
    Attributes actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isEqualTo(type);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isEqualTo(identifier);

    when(spanDataMock.getKind()).thenReturn(SpanKind.PRODUCER);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isEqualTo(type);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isEqualTo(identifier);

    when(spanDataMock.getKind()).thenReturn(SpanKind.CONSUMER);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(DEPENDENCY_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isEqualTo(type);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isEqualTo(identifier);

    // Server span should not generate remote resource attributes
    when(spanDataMock.getKind()).thenReturn(SpanKind.SERVER);
    actualAttributes =
        GENERATOR.generateMetricAttributeMapFromSpan(spanDataMock, resource).get(SERVICE_METRIC);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_TYPE)).isEqualTo(null);
    assertThat(actualAttributes.get(AWS_REMOTE_RESOURCE_IDENTIFIER)).isEqualTo(null);
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
