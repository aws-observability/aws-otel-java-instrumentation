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
    assertThat(attrs.get(AttributeKey.stringKey("aws.otel.extension.lib.version")))
        .isEqualTo(SpanMetricsProcessor.LIB_VERSION);
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
}
