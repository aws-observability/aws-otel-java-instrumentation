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

package software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.jmh;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Minimal ReadableSpan that returns a fixed SpanData. Hand-written (not a mock) so the benchmark
 * measures the processor's work, not a mocking framework's proxy overhead. Only the methods the
 * processor calls ({@code toSpanData}) need real behavior.
 */
final class FixedReadableSpan implements ReadableSpan {

  private final SpanData data;

  FixedReadableSpan(SpanData data) {
    this.data = data;
  }

  @Override
  public SpanData toSpanData() {
    return data;
  }

  @Override
  public SpanContext getSpanContext() {
    return data.getSpanContext();
  }

  @Override
  public SpanContext getParentSpanContext() {
    return data.getParentSpanContext();
  }

  @Override
  public String getName() {
    return data.getName();
  }

  @Override
  public InstrumentationScopeInfo getInstrumentationScopeInfo() {
    return data.getInstrumentationScopeInfo();
  }

  @Override
  @SuppressWarnings("deprecation")
  public io.opentelemetry.sdk.common.InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
    return data.getInstrumentationLibraryInfo();
  }

  @Override
  public boolean hasEnded() {
    return true;
  }

  @Override
  public long getLatencyNanos() {
    return data.getEndEpochNanos() - data.getStartEpochNanos();
  }

  @Override
  public SpanKind getKind() {
    return data.getKind();
  }

  @Override
  public <T> T getAttribute(AttributeKey<T> key) {
    return data.getAttributes().get(key);
  }
}
