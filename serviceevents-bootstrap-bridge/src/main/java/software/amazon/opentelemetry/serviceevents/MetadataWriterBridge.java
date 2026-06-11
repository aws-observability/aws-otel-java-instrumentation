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

/**
 * Bridge interface for routing incident metadata from the bootstrap classloader.
 *
 * <p>Implemented by {@code LiteIncidentDrainer} in the agent classloader. Allows the incident
 * recording path (running in the app/bootstrap classloader) to hand off incident metadata via
 * {@link ServiceEventsDataStore}.
 *
 * <p><b>IMPORTANT:</b> This interface MUST have NO external dependencies (loaded in bootstrap CL).
 */
public interface MetadataWriterBridge {

  /**
   * Write an incident metadata record.
   *
   * @param threadName The thread that served the request
   * @param startTimeNs Request start time (epoch nanoseconds)
   * @param endTimeNs Request end time (epoch nanoseconds)
   * @param route The request route/path
   * @param method HTTP method
   * @param statusCode HTTP status code
   * @param durationMs Request duration in milliseconds
   * @param triggerType Incident trigger type ("exception" or "latency")
   * @param severity Incident severity
   * @param snapshotId Unique snapshot identifier
   * @param exceptionType Exception class name (nullable)
   * @param exceptionMessage Exception message (nullable)
   * @param stackTrace Exception stack trace (nullable)
   * @param traceId OpenTelemetry trace ID (nullable)
   * @param spanId OpenTelemetry span ID (nullable)
   * @param operation Operation name from AwsSpanProcessingUtil (nullable, falls back to
   *     method+route)
   */
  void writeIncident(
      String threadName,
      long startTimeNs,
      long endTimeNs,
      String route,
      String method,
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
      String operation);
}
