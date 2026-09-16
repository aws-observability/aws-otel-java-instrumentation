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

package software.amazon.opentelemetry.cloudwatch.spanmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpanMetricsAttributesBuilderTest {

  private static TestSpanData.Builder span(SpanKind kind, Attributes attributes) {
    return TestSpanData.builder()
        .setName("op")
        .setKind(kind)
        .setStatus(StatusData.unset())
        .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "svc")))
        .setAttributes(attributes)
        .setSpanContext(
            SpanContext.create(
                "00000000000000000000000000000001",
                "0000000000000001",
                TraceFlags.getDefault(),
                TraceState.getDefault()))
        .setStartEpochNanos(0)
        .setEndEpochNanos(1_000_000)
        .setHasEnded(true);
  }

  @Test
  void baseAttributesUseShortForms() {
    Attributes attrs =
        SpanMetricsAttributesBuilder.build(
            span(SpanKind.SERVER, Attributes.empty())
                .setStatus(StatusData.create(StatusCode.ERROR, ""))
                .build());
    assertThat(attrs.get(AttributeKey.stringKey("span.name"))).isEqualTo("op");
    assertThat(attrs.get(AttributeKey.stringKey("span.kind"))).isEqualTo("SERVER");
    assertThat(attrs.get(AttributeKey.stringKey("status.code"))).isEqualTo("ERROR");
  }

  @Test
  void serviceNameNeverEmittedAsDatapointAttribute() {
    // service.name lives on the metric's resource (the host MeterProvider's resource), so the
    // datapoint must not duplicate it — even when the span's resource carries it.
    Attributes withResource =
        SpanMetricsAttributesBuilder.build(span(SpanKind.SERVER, Attributes.empty()).build());
    assertThat(withResource.get(AttributeKey.stringKey("service.name"))).isNull();
    Attributes withoutResource =
        SpanMetricsAttributesBuilder.build(
            span(SpanKind.INTERNAL, Attributes.empty()).setResource(Resource.empty()).build());
    assertThat(withoutResource.get(AttributeKey.stringKey("service.name"))).isNull();
  }

  @Test
  void httpAttributesCopiedWhenPresent() {
    Attributes span =
        Attributes.builder()
            .put("http.request.method", "GET")
            .put("http.response.status_code", 200L)
            .put("http.route", "/vets")
            .put("url.query", "x=1") // not allowlisted
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.SERVER, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("http.request.method"))).isEqualTo("GET");
    assertThat(attrs.get(AttributeKey.longKey("http.response.status_code"))).isEqualTo(200L);
    assertThat(attrs.get(AttributeKey.stringKey("http.route"))).isEqualTo("/vets");
    assertThat(attrs.get(AttributeKey.stringKey("url.query"))).isNull();
  }

  @Test
  void rpcAttributesCopiedWhenPresent() {
    Attributes span =
        Attributes.builder()
            .put("rpc.system.name", "grpc")
            .put("rpc.service", "orders.OrderService")
            .put("rpc.method", "GetOrder")
            .put("error.type", "UNAVAILABLE")
            .put("rpc.request.body", "secret") // not allowlisted
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CLIENT, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("rpc.system.name"))).isEqualTo("grpc");
    assertThat(attrs.get(AttributeKey.stringKey("rpc.service"))).isEqualTo("orders.OrderService");
    assertThat(attrs.get(AttributeKey.stringKey("rpc.method"))).isEqualTo("GetOrder");
    assertThat(attrs.get(AttributeKey.stringKey("error.type"))).isEqualTo("UNAVAILABLE");
    assertThat(attrs.get(AttributeKey.stringKey("rpc.request.body"))).isNull();
  }

  @Test
  void legacyRpcSystemPassesThroughUnderLegacyKey() {
    // Instrumentation still emitting rpc.system (legacy): pass it through unchanged under its own
    // key; do not re-home it under rpc.system.name.
    Attributes span = Attributes.builder().put("rpc.system", "grpc").build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CLIENT, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("rpc.system"))).isEqualTo("grpc");
    assertThat(attrs.get(AttributeKey.stringKey("rpc.system.name"))).isNull();
  }

  @Test
  void currentRpcSystemNameWinsOverLegacy() {
    Attributes span =
        Attributes.builder().put("rpc.system.name", "grpc").put("rpc.system", "legacy").build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CLIENT, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("rpc.system.name"))).isEqualTo("grpc");
    assertThat(attrs.get(AttributeKey.stringKey("rpc.system"))).isNull();
  }

  @Test
  void legacyHttpAttributesPassThroughUnderLegacyKeys() {
    // Instrumentation still emitting the legacy HTTP keys: pass them through unchanged, no value
    // translation and no current-key emission. http.status_code stays a long.
    Attributes span =
        Attributes.builder().put("http.method", "POST").put("http.status_code", 404L).build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.SERVER, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("http.method"))).isEqualTo("POST");
    assertThat(attrs.get(AttributeKey.longKey("http.status_code"))).isEqualTo(404L);
    assertThat(attrs.get(AttributeKey.stringKey("http.request.method"))).isNull();
    assertThat(attrs.get(AttributeKey.longKey("http.response.status_code"))).isNull();
  }

  @Test
  void currentHttpAttributesWinOverLegacy() {
    Attributes span =
        Attributes.builder()
            .put("http.request.method", "GET")
            .put("http.method", "POST")
            .put("http.response.status_code", 200L)
            .put("http.status_code", 500L)
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.SERVER, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("http.request.method"))).isEqualTo("GET");
    assertThat(attrs.get(AttributeKey.longKey("http.response.status_code"))).isEqualTo(200L);
    // Legacy keys not added when the current keys are present.
    assertThat(attrs.get(AttributeKey.stringKey("http.method"))).isNull();
    assertThat(attrs.get(AttributeKey.longKey("http.status_code"))).isNull();
  }

  @Test
  void databaseAttributesCopied() {
    Attributes span =
        Attributes.builder()
            .put("db.system.name", "postgresql")
            .put("db.operation.name", "SELECT")
            .put("db.collection.name", "vets")
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CLIENT, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("db.system.name"))).isEqualTo("postgresql");
    assertThat(attrs.get(AttributeKey.stringKey("db.operation.name"))).isEqualTo("SELECT");
    assertThat(attrs.get(AttributeKey.stringKey("db.collection.name"))).isEqualTo("vets");
  }

  @Test
  void legacyDatabaseAttributesPassThroughUnderLegacyKeys() {
    // Instrumentation still emitting legacy keys: pass the legacy key + value through unchanged,
    // no value translation and no current-key emission.
    Attributes span =
        Attributes.builder()
            .put("db.system", "mssql")
            .put("db.operation", "SELECT")
            .put("db.sql.table", "orders")
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CLIENT, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("db.system"))).isEqualTo("mssql");
    assertThat(attrs.get(AttributeKey.stringKey("db.operation"))).isEqualTo("SELECT");
    assertThat(attrs.get(AttributeKey.stringKey("db.sql.table"))).isEqualTo("orders");
    // The value is never re-homed under the current key.
    assertThat(attrs.get(AttributeKey.stringKey("db.system.name"))).isNull();
  }

  @Test
  void currentKeyWinsAndLegacyIgnoredWhenBothPresent() {
    Attributes span =
        Attributes.builder().put("db.system.name", "postgresql").put("db.system", "h2").build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CLIENT, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("db.system.name"))).isEqualTo("postgresql");
    // Legacy key not added when the current key is present.
    assertThat(attrs.get(AttributeKey.stringKey("db.system"))).isNull();
  }

  @Test
  void schemaAndLibVersionAlwaysPresent() {
    Attributes attrs =
        SpanMetricsAttributesBuilder.build(span(SpanKind.SERVER, Attributes.empty()).build());
    assertThat(attrs.get(AttributeKey.stringKey("aws.otel.span.metrics.schema"))).isEqualTo("v1");
    // A real version, not the constant itself: comparing to LIB_VERSION would pass even if
    // resolution broke (it once shipped as "unknown" in javaagent mode).
    assertThat(attrs.get(AttributeKey.stringKey("aws.otel.extension.lib.version")))
        .matches("\\d+\\.\\d+\\.\\d+.*");
  }

  @Test
  void namedMessagingDestinationCopied() {
    Attributes span =
        Attributes.builder()
            .put("messaging.system", "kafka")
            .put("messaging.destination.name", "orders")
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CONSUMER, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("messaging.destination.name"))).isEqualTo("orders");
  }

  @Test
  void temporaryMessagingDestinationOmitted() {
    Attributes span =
        Attributes.builder()
            .put("messaging.system", "jms")
            .put("messaging.destination.name", "temp-abc123")
            .put("messaging.destination.temporary", true)
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.PRODUCER, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("messaging.destination.name"))).isNull();
    assertThat(attrs.get(AttributeKey.stringKey("messaging.system"))).isEqualTo("jms");
  }

  @Test
  void anonymousMessagingDestinationOmitted() {
    Attributes span =
        Attributes.builder()
            .put("messaging.destination.name", "amq.gen-xyz")
            .put("messaging.destination.anonymous", true)
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CONSUMER, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("messaging.destination.name"))).isNull();
  }

  @Test
  void messagingOperationAndConsumerGroupCopied() {
    Attributes span =
        Attributes.builder()
            .put("messaging.system", "kafka")
            .put("messaging.operation.type", "receive")
            .put("messaging.consumer.group.name", "order-processors")
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CONSUMER, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("messaging.operation.type"))).isEqualTo("receive");
    assertThat(attrs.get(AttributeKey.stringKey("messaging.consumer.group.name")))
        .isEqualTo("order-processors");
  }

  @Test
  void peerAttributesCopied() {
    Attributes span =
        Attributes.builder()
            .put("server.address", "payments.example.com")
            .put("server.port", 8443L)
            .put("network.peer.address", "10.0.0.1") // not allowlisted
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CLIENT, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("server.address")))
        .isEqualTo("payments.example.com");
    // server.port is an int per semconv (emitted as a long attribute), not a string dimension.
    assertThat(attrs.get(AttributeKey.longKey("server.port"))).isEqualTo(8443L);
    assertThat(attrs.get(AttributeKey.stringKey("network.peer.address"))).isNull();
  }

  @Test
  void legacyPeerAttributesPassThroughUnderLegacyKeys() {
    // Instrumentation still emitting the legacy network keys: pass them through unchanged under
    // their own keys; do not re-home to server.address/server.port. net.peer.* is the client-span
    // spelling.
    Attributes span =
        Attributes.builder()
            .put("net.peer.name", "payments.example.com")
            .put("net.peer.port", 8443L)
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CLIENT, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("net.peer.name")))
        .isEqualTo("payments.example.com");
    assertThat(attrs.get(AttributeKey.longKey("net.peer.port"))).isEqualTo(8443L);
    assertThat(attrs.get(AttributeKey.stringKey("server.address"))).isNull();
    assertThat(attrs.get(AttributeKey.longKey("server.port"))).isNull();
  }

  @Test
  void legacyServerSpanPeerAttributesPassThroughUnderLegacyKeys() {
    // net.host.* is the server-span spelling of the same peer/host attributes.
    Attributes span =
        Attributes.builder()
            .put("net.host.name", "api.example.com")
            .put("net.host.port", 443L)
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.SERVER, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("net.host.name"))).isEqualTo("api.example.com");
    assertThat(attrs.get(AttributeKey.longKey("net.host.port"))).isEqualTo(443L);
    assertThat(attrs.get(AttributeKey.stringKey("server.address"))).isNull();
  }

  @Test
  void currentPeerAttributesWinOverLegacy() {
    Attributes span =
        Attributes.builder()
            .put("server.address", "current.example.com")
            .put("net.peer.name", "legacy.example.com")
            .put("net.host.name", "legacy-host.example.com")
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CLIENT, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("server.address")))
        .isEqualTo("current.example.com");
    // No legacy key added when the current key is present.
    assertThat(attrs.get(AttributeKey.stringKey("net.peer.name"))).isNull();
    assertThat(attrs.get(AttributeKey.stringKey("net.host.name"))).isNull();
  }

  @Test
  void genAiAttributesCopied() {
    Attributes span =
        Attributes.builder()
            .put("gen_ai.request.model", "claude-sonnet-4")
            .put("gen_ai.provider.name", "aws.bedrock")
            .put("gen_ai.operation.name", "chat")
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CLIENT, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("gen_ai.request.model")))
        .isEqualTo("claude-sonnet-4");
    assertThat(attrs.get(AttributeKey.stringKey("gen_ai.provider.name"))).isEqualTo("aws.bedrock");
    assertThat(attrs.get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("chat");
  }

  @Test
  void awsResourceIdentityAttributesCopied() {
    Attributes span =
        Attributes.builder()
            .put("aws.s3.bucket", "my-bucket")
            .put(
                AttributeKey.stringArrayKey("aws.dynamodb.table_names"), List.of("orders", "items"))
            .put("aws.lambda.invoked_arn", "arn:aws:lambda:us-east-1:123:function:fn")
            .put("aws.sns.topic.arn", "arn:aws:sns:us-east-1:123:topic")
            .put("aws.sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123/queue")
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CLIENT, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("aws.s3.bucket"))).isEqualTo("my-bucket");
    // table_names stays a string array per semconv; copied through unchanged, not normalized.
    assertThat(attrs.get(AttributeKey.stringArrayKey("aws.dynamodb.table_names")))
        .containsExactly("orders", "items");
    assertThat(attrs.get(AttributeKey.stringKey("aws.lambda.invoked_arn")))
        .isEqualTo("arn:aws:lambda:us-east-1:123:function:fn");
    assertThat(attrs.get(AttributeKey.stringKey("aws.sns.topic.arn")))
        .isEqualTo("arn:aws:sns:us-east-1:123:topic");
    assertThat(attrs.get(AttributeKey.stringKey("aws.sqs.queue.url")))
        .isEqualTo("https://sqs.us-east-1.amazonaws.com/123/queue");
  }

  @Test
  void faasAttributesCopied() {
    Attributes span =
        Attributes.builder()
            .put("faas.invoked_name", "my-function")
            .put("faas.invoked_provider", "aws")
            .put("faas.invoked_region", "us-east-1")
            .put("faas.trigger", "http")
            .build();
    Attributes attrs = SpanMetricsAttributesBuilder.build(span(SpanKind.CLIENT, span).build());
    assertThat(attrs.get(AttributeKey.stringKey("faas.invoked_name"))).isEqualTo("my-function");
    assertThat(attrs.get(AttributeKey.stringKey("faas.invoked_provider"))).isEqualTo("aws");
    assertThat(attrs.get(AttributeKey.stringKey("faas.invoked_region"))).isEqualTo("us-east-1");
    assertThat(attrs.get(AttributeKey.stringKey("faas.trigger"))).isEqualTo("http");
  }
}
