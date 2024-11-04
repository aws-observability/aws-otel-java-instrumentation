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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

public class UdpExporterTest {
  private static final boolean CONTAINS_ATTRIBUTES = true;

  @Test
  public void testExporter() { // TODO: only for testing. remove.
    //    Tracer tracer = GlobalOpenTelemetry.getTracer("My application");
    Tracer tracer = OpenTelemetrySdk.builder().build().getTracer("hello");

    Span mySpan = tracer.spanBuilder("DoTheLoop_3").startSpan();
    int numIterations = 5;
    mySpan.setAttribute("NumIterations", numIterations);
    mySpan.setAttribute(AttributeKey.stringArrayKey("foo"), Arrays.asList("bar1", "bar2"));
    try (var scope = mySpan.makeCurrent()) {
      for (int i = 1; i <= numIterations; i++) {
        System.out.println("i = " + i);
      }
    } finally {
      mySpan.end();
    }

    OtlpUdpSpanExporter exporter = new OtlpUdpSpanExporterBuilder().build();

    ReadableSpan readableSpan = (ReadableSpan) mySpan;
    SpanData spanData = readableSpan.toSpanData();

    exporter.export(Collections.singletonList(spanData));
  }

  @Test
  public void testUdpExporter() {
    // Add your test logic here
    OtlpUdpSpanExporter exporter = new OtlpUdpSpanExporterBuilder().build();

    // build test span
    SpanContext parentSpanContextMock = mock(SpanContext.class);
    Attributes spanAttributes = buildSpanAttributes(CONTAINS_ATTRIBUTES);
    SpanData spanDataMock = buildSpanDataMock(spanAttributes);
    when(spanDataMock.getParentSpanContext()).thenReturn(parentSpanContextMock);
    SpanContext spanContextMock = mock(SpanContext.class);
    when(spanContextMock.isValid()).thenReturn(true);
    when(spanDataMock.getSpanContext()).thenReturn(spanContextMock);
    TraceState traceState = TraceState.builder().build();
    when(spanContextMock.getTraceState()).thenReturn(traceState);
    StatusData statusData = StatusData.unset();
    when(spanDataMock.getStatus()).thenReturn(statusData);
    when(spanDataMock.getInstrumentationScopeInfo())
        .thenReturn(InstrumentationScopeInfo.create("Dummy Scope"));

    Resource testResource = Resource.empty();
    when(spanDataMock.getResource()).thenReturn(testResource);

    exporter.export(Collections.singletonList(spanDataMock));
  }

  private static Attributes buildSpanAttributes(boolean containsAttribute) {
    if (containsAttribute) {
      return Attributes.of(AttributeKey.stringKey("original key"), "original value");
    } else {
      return Attributes.empty();
    }
  }

  private static SpanData buildSpanDataMock(Attributes spanAttributes) {
    // Configure spanData
    SpanData mockSpanData = mock(SpanData.class);
    when(mockSpanData.getAttributes()).thenReturn(spanAttributes);
    when(mockSpanData.getTotalAttributeCount()).thenReturn(spanAttributes.size());
    when(mockSpanData.getKind()).thenReturn(SpanKind.SERVER);
    when(mockSpanData.getParentSpanContext()).thenReturn(null);
    return mockSpanData;
  }
}
