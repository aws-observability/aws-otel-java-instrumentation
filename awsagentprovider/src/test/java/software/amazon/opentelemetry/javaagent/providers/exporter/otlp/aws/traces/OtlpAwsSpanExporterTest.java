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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.traces;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.AbstractOtlpAwsExporterTest;

class OtlpAwsSpanExporterTest extends AbstractOtlpAwsExporterTest<SpanData> {
  private static final String XRAY_OTLP_ENDPOINT = "https://xray.us-east-1.amazonaws.com/v1/traces";

  private SpanExporter exporter;

  OtlpAwsSpanExporterTest() {
    super(XRAY_OTLP_ENDPOINT, "us-east-1", "xray");
  }

  @Override
  protected SpanData createOtlpData(int index) {
    return TestSpanData.builder()
        .setSpanContext(
            SpanContext.create(
                "0123456789abcdef0123456789abcdef",
                String.format("%016x", index + 1),
                TraceFlags.getSampled(),
                TraceState.builder().put("test", "state").build()))
        .setParentSpanContext(
            SpanContext.create(
                "0123456789abcdef0123456789abcdef",
                "fedcba9876543210",
                TraceFlags.getSampled(),
                TraceState.getDefault()))
        .setName("test.span." + index)
        .setKind(SpanKind.SERVER)
        .setStartEpochNanos(1L)
        .setEndEpochNanos(2L)
        .setHasEnded(true)
        .setStatus(StatusData.create(StatusCode.ERROR, "test error"))
        .setAttributes(
            Attributes.builder()
                .put("test.name", "span")
                .put("test.count", (long) index)
                .put("test.enabled", true)
                .build())
        .setTotalAttributeCount(3)
        .setResource(TEST_RESOURCE)
        .setInstrumentationScopeInfo(
            InstrumentationScopeInfo.builder("test.scope." + index).setVersion("1.2.3").build())
        .build();
  }

  @Override
  protected void validateOtlpPayload(byte[] payload, Collection<SpanData> signals)
      throws Exception {
    ExportTraceServiceRequest decoded = ExportTraceServiceRequest.parseFrom(payload);
    assertEquals(1, decoded.getResourceSpansCount());
    ResourceSpans resource = decoded.getResourceSpans(0);
    assertResource(resource.getResource());
    assertEquals(signals.size(), resource.getScopeSpansCount());
    Map<String, SpanData> expected =
        signals.stream()
            .collect(
                Collectors.toMap(
                    signal -> signal.getInstrumentationScopeInfo().getName(), signal -> signal));
    for (ScopeSpans scope : resource.getScopeSpansList()) {
      SpanData signal = expected.remove(scope.getScope().getName());
      assertNotNull(signal, "unexpected or repeated scope " + scope.getScope().getName());
      assertScope(signal.getInstrumentationScopeInfo(), scope.getScope());
      assertEquals(1, scope.getSpansCount());
      Span span = scope.getSpans(0);
      assertEquals(signal.getName(), span.getName());
      assertEquals(signal.getKind().ordinal() + 1, span.getKindValue());
      assertArrayEquals(signal.getSpanContext().getTraceIdBytes(), span.getTraceId().toByteArray());
      assertArrayEquals(signal.getSpanContext().getSpanIdBytes(), span.getSpanId().toByteArray());
      assertArrayEquals(
          signal.getParentSpanContext().getSpanIdBytes(), span.getParentSpanId().toByteArray());
      assertEquals("test=state", span.getTraceState());
      assertEquals(signal.getStartEpochNanos(), span.getStartTimeUnixNano());
      assertEquals(signal.getEndEpochNanos(), span.getEndTimeUnixNano());
      assertEquals(signal.getStatus().getStatusCode().ordinal(), span.getStatus().getCodeValue());
      assertEquals(signal.getStatus().getDescription(), span.getStatus().getMessage());
      assertAttributes(signal.getAttributes(), span.getAttributesList());
      assertEquals(0, span.getDroppedAttributesCount());
      assertEquals(signal.getEvents().size(), span.getEventsCount());
      assertEquals(signal.getLinks().size(), span.getLinksCount());
    }
    assertTrue(expected.isEmpty());
  }

  @Override
  protected CompletableResultCode export(Collection<SpanData> signals) {
    if (exporter == null) {
      exporter =
          OtlpAwsSpanExporterBuilder.create(
                  OtlpHttpSpanExporter.builder()
                      .setComponentLoader(componentLoader)
                      .setEndpoint(endpoint)
                      .setCompression(otlpCompression)
                      .build(),
                  endpoint)
              .setCompression(compression)
              .build();
    }
    return exporter.export(signals);
  }

  @Override
  protected void shutdownExporter() {
    if (exporter != null) {
      exporter.shutdown();
    }
  }
}
