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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import org.junit.jupiter.api.Test;

public class AwsUnsampledOnlySpanProcessorTest {

  @Test
  public void testIsStartRequired() {
    SpanProcessor processor = AwsUnsampledOnlySpanProcessorBuilder.create().build();
    assertThat(processor.isStartRequired()).isTrue();
  }

  @Test
  public void testIsEndRequired() {
    SpanProcessor processor = AwsUnsampledOnlySpanProcessorBuilder.create().build();
    assertThat(processor.isEndRequired()).isTrue();
  }

  @Test
  public void testDefaultSpanProcessor() {
    AwsUnsampledOnlySpanProcessorBuilder builder = AwsUnsampledOnlySpanProcessorBuilder.create();
    AwsUnsampledOnlySpanProcessor unsampledSP = builder.build();

    assertThat(builder.getSpanExporter()).isInstanceOf(OtlpUdpSpanExporter.class);
    SpanProcessor delegate = unsampledSP.getDelegate();
    assertThat(delegate).isInstanceOf(BatchSpanProcessor.class);
    BatchSpanProcessor delegateBsp = (BatchSpanProcessor) delegate;
    String delegateBspString = delegateBsp.toString();
    assertThat(delegateBspString)
        .contains(
            "spanExporter=software.amazon.opentelemetry.javaagent.providers.OtlpUdpSpanExporter");
    assertThat(delegateBspString).contains("exportUnsampledSpans=true");
  }

  @Test
  public void testSpanProcessorWithExporter() {
    AwsUnsampledOnlySpanProcessorBuilder builder =
        AwsUnsampledOnlySpanProcessorBuilder.create()
            .setSpanExporter(InMemorySpanExporter.create());
    AwsUnsampledOnlySpanProcessor unsampledSP = builder.build();

    assertThat(builder.getSpanExporter()).isInstanceOf(InMemorySpanExporter.class);
    SpanProcessor delegate = unsampledSP.getDelegate();
    assertThat(delegate).isInstanceOf(BatchSpanProcessor.class);
    BatchSpanProcessor delegateBsp = (BatchSpanProcessor) delegate;
    String delegateBspString = delegateBsp.toString();
    assertThat(delegateBspString)
        .contains("spanExporter=io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter");
    assertThat(delegateBspString).contains("exportUnsampledSpans=true");
  }

  @Test
  public void testStartAddsAttributeToSampledSpan() {
    SpanContext mockSpanContext = mock(SpanContext.class);
    when(mockSpanContext.isSampled()).thenReturn(true);
    Context parentContextMock = mock(Context.class);
    ReadWriteSpan spanMock = mock(ReadWriteSpan.class);
    when(spanMock.getSpanContext()).thenReturn(mockSpanContext);

    AwsUnsampledOnlySpanProcessor processor = AwsUnsampledOnlySpanProcessorBuilder.create().build();
    processor.onStart(parentContextMock, spanMock);

    // verify setAttribute was never called
    verify(spanMock, never()).setAttribute(any(), anyBoolean());
  }

  @Test
  public void testStartAddsAttributeToUnsampledSpan() {
    SpanContext mockSpanContext = mock(SpanContext.class);
    when(mockSpanContext.isSampled()).thenReturn(false);
    Context parentContextMock = mock(Context.class);
    ReadWriteSpan spanMock = mock(ReadWriteSpan.class);
    when(spanMock.getSpanContext()).thenReturn(mockSpanContext);

    AwsUnsampledOnlySpanProcessor processor = AwsUnsampledOnlySpanProcessorBuilder.create().build();
    processor.onStart(parentContextMock, spanMock);

    // verify setAttribute was called with the correct arguments
    verify(spanMock, times(1)).setAttribute(AwsAttributeKeys.AWS_TRACE_FLAG_SAMPLED, false);
  }

  @Test
  public void testExportsOnlyUnsampledSpans() {
    SpanExporter mockExporter = mock(SpanExporter.class);
    when(mockExporter.export(anyCollection())).thenReturn(CompletableResultCode.ofSuccess());

    TestDelegateProcessor delegate = new TestDelegateProcessor();
    AwsUnsampledOnlySpanProcessor processor = new AwsUnsampledOnlySpanProcessor(delegate);

    // unsampled span
    SpanContext mockSpanContextUnsampled = mock(SpanContext.class);
    when(mockSpanContextUnsampled.isSampled()).thenReturn(false);
    ReadableSpan mockSpanUnsampled = mock(ReadableSpan.class);
    when(mockSpanUnsampled.getSpanContext()).thenReturn(mockSpanContextUnsampled);

    // sampled span
    SpanContext mockSpanContextSampled = mock(SpanContext.class);
    when(mockSpanContextSampled.isSampled()).thenReturn(true);
    ReadableSpan mockSpanSampled = mock(ReadableSpan.class);
    when(mockSpanSampled.getSpanContext()).thenReturn(mockSpanContextSampled);

    processor.onEnd(mockSpanSampled);
    processor.onEnd(mockSpanUnsampled);

    // validate that only the unsampled span was delegated
    assertThat(delegate.getEndedSpans()).containsExactly(mockSpanUnsampled);
  }

  private static class TestDelegateProcessor implements SpanProcessor {
    // keep a queue of Readable spans added when onEnd is called
    Collection<ReadableSpan> endedSpans = new ArrayList<>();

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {}

    @Override
    public boolean isStartRequired() {
      return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
      endedSpans.add(span);
    }

    @Override
    public boolean isEndRequired() {
      return false;
    }

    public Collection<ReadableSpan> getEndedSpans() {
      return endedSpans;
    }
  }
}
