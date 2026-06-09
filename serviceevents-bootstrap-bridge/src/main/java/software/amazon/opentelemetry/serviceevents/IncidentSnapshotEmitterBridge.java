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

package software.amazon.opentelemetry.serviceevents;

import java.util.List;

/**
 * Bridge interface for emitting incident snapshots directly from the bootstrap classloader.
 *
 * <p>Implemented by the agent classloader. Allows byte-instrumentation-mode code paths to emit
 * IncidentSnapshot telemetry synchronously at request end, bypassing the JFR rotation sidecar.
 *
 * <p><b>IMPORTANT:</b> This interface MUST have NO external dependencies (loaded in bootstrap CL).
 */
public interface IncidentSnapshotEmitterBridge {

  /**
   * Emit an incident snapshot with a fully-captured byte-instrumentation call path.
   *
   * @param snapshotId Unique snapshot identifier
   * @param triggerType "exception" or "latency"
   * @param severity "critical" | "high" | "medium"
   * @param threadName Thread name at incident capture time
   * @param startTimeNs Request start time (epoch nanoseconds)
   * @param endTimeNs Request end time (epoch nanoseconds)
   * @param route Request route/path
   * @param method HTTP method
   * @param operation Operation name (e.g. "GET /api/users")
   * @param statusCode HTTP status code
   * @param durationMs Request duration in milliseconds
   * @param exceptionType Exception class name (nullable)
   * @param exceptionMessage Exception message (nullable)
   * @param stackTrace Exception stack trace (nullable)
   * @param traceId OpenTelemetry trace ID (nullable)
   * @param spanId OpenTelemetry span ID (nullable)
   * @param callPath Ordered call path captured from InvestigationData (never null; may be empty)
   */
  void emitIncident(
      String snapshotId,
      String triggerType,
      String severity,
      String threadName,
      long startTimeNs,
      long endTimeNs,
      String route,
      String method,
      String operation,
      int statusCode,
      double durationMs,
      String exceptionType,
      String exceptionMessage,
      String stackTrace,
      String traceId,
      String spanId,
      List<CallPathEntry> callPath);
}
