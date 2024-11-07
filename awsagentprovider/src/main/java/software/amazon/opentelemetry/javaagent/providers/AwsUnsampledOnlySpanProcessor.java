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

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * {@link SpanProcessor} that only exports unsampled spans in a batch via a delegated @{link BatchSpanProcessor}.
 * The processor also adds an attribute to each processed span to indicate that it was sampled or not.
 */
final class AwsUnsampledOnlySpanProcessor implements SpanProcessor {

  private final SpanProcessor delegate;

  AwsUnsampledOnlySpanProcessor(SpanProcessor delegate) {
    this.delegate = delegate;
  }

  public static AwsUnsampledOnlySpanProcessorBuilder builder() {
    return new AwsUnsampledOnlySpanProcessorBuilder();
  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    if (span.getSpanContext().isSampled()) {
      span.setAttribute(AwsAttributeKeys.AWS_TRACE_FLAG_SAMPLED, true);
    } else {
      span.setAttribute(AwsAttributeKeys.AWS_TRACE_FLAG_SAMPLED, false);
    }
    delegate.onStart(parentContext, span);
  }

  @Override
  public void onEnd(ReadableSpan span) {
    if (!span.getSpanContext().isSampled()) {
      delegate.onEnd(span);
    }
  }

  @Override
  public boolean isStartRequired() {
    return delegate.isStartRequired();
  }

  @Override
  public boolean isEndRequired() {
    return delegate.isEndRequired();
  }

  @Override
  public CompletableResultCode shutdown() {
    return delegate.shutdown();
  }

  @Override
  public CompletableResultCode forceFlush() {
    return delegate.forceFlush();
  }

  @Override
  public void close() {
    delegate.close();
  }

  // Visible for testing
  SpanProcessor getDelegate() {
    return delegate;
  }
}
