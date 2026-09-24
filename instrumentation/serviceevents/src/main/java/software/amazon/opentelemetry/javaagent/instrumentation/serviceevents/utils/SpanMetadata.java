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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils;

/**
 * Per-request correlation record used by the profiler scanner to attach an operation (and, when the
 * trace was sampled, a trace {@code Link}) to JFR wall/alloc samples.
 *
 * <p>These records are decoded from {@code profiler.Span} JFR events (async-profiler 4.5 {@code
 * one.profiler.Span} markers) — the request thread, {@code [startNs, startNs+timeSpan]} interval,
 * and decoded {@link ProfilerSpanTag} (operation / traceId / spanId) come straight out of the
 * single JFR clock.
 */
public final class SpanMetadata {
  public final String threadName;
  public final String operation;
  public final long startNs;
  public final long endNs;
  public final String traceId;
  public final String spanId;

  public SpanMetadata(
      String threadName,
      String operation,
      long startNs,
      long endNs,
      String traceId,
      String spanId) {
    this.threadName = threadName;
    this.operation = operation;
    this.startNs = startNs;
    this.endNs = endNs;
    this.traceId = traceId;
    this.spanId = spanId;
  }
}
