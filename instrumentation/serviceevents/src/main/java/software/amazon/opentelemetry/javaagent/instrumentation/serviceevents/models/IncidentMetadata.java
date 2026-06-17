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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models;

/** Incident metadata record: attribute fields plus trace context for an incident snapshot. */
public class IncidentMetadata {
  public final String threadName;
  public final long startNs;
  public final long endNs;
  public final String route;
  public final String method;
  public final String operation;
  public final int statusCode;
  public final double durationMs;
  public final String triggerType;
  public final String severity;
  public final String snapshotId;
  public final String exceptionType;
  public final String exceptionMessage;
  public final String stackTrace;
  public final String traceId;
  public final String spanId;

  /**
   * True when at least one captured call_path frame lacks timing (durationNs == 0) — i.e. an
   * unsampled frame or the truncation sentinel. Computed from the call path (matches the Python/JS
   * distros' {@code any(duration_ns == 0)} rule) rather than hardcoded, so a fully-timed snapshot
   * (the {@code always}-mode default) correctly reports {@code false}.
   */
  public final boolean isPartial;

  public IncidentMetadata(
      String threadName,
      long startNs,
      long endNs,
      String route,
      String method,
      String operation,
      int statusCode,
      double durationMs,
      String triggerType,
      String severity,
      String snapshotId,
      String exceptionType,
      String exceptionMessage,
      String stackTrace,
      String traceId,
      String spanId,
      boolean isPartial) {
    this.threadName = threadName;
    this.startNs = startNs;
    this.endNs = endNs;
    this.route = route;
    this.method = method;
    this.operation = operation;
    this.statusCode = statusCode;
    this.durationMs = durationMs;
    this.triggerType = triggerType;
    this.severity = severity;
    this.snapshotId = snapshotId;
    this.exceptionType = exceptionType;
    this.exceptionMessage = exceptionMessage;
    this.stackTrace = stackTrace;
    this.traceId = traceId;
    this.spanId = spanId;
    this.isPartial = isPartial;
  }
}
