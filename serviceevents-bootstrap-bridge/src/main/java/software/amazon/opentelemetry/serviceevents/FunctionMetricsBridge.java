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
 * Bridge interface for direct OTel metric recording of sampled function calls.
 *
 * <p>Implemented by the agent classloader (see {@code FunctionMetricsBridgeImpl}). Allows {@link
 * ServiceEventsDataStore#methodExit} (running on the hot path in the application classloader) to
 * record into an OTel Exponential Histogram without depending on the OTel SDK API in bootstrap.
 *
 * <p>Only the {@code service.function.duration} Histogram is wired here. Implementations are
 * invoked only for sampled calls — non-sampled calls are filtered upstream in {@code methodEnter}
 * to keep the hot path zero-allocation when the bridge is wired but the call is dropped.
 *
 * <p>Wired only when an OTLP network endpoint is configured (not in {@code OUTPUT_FILE} mode); the
 * SEH aggregation + EMF/console fallback path remains the source of truth in file-export mode
 * because the CloudWatch metric file exporter only serializes Sum metrics.
 *
 * <p><b>IMPORTANT:</b> This interface MUST have NO external dependencies (loaded in bootstrap CL).
 */
public interface FunctionMetricsBridge {

  /**
   * Record a sampled function call into the {@code service.function.duration} Exponential
   * Histogram.
   *
   * @param functionId Function identifier (e.g., {@code "com.example.MyClass.myMethod"})
   * @param operation Operation name (e.g., {@code "GET /api/users"}); never null but may be {@code
   *     "unknown"} when context isn't set
   * @param caller Caller function id, or {@code null} if outermost frame
   * @param durationNs Measured duration in nanoseconds
   * @param exceptionType Exception class simple name if the call raised, else {@code null}
   */
  void recordFunctionCall(
      String functionId, String operation, String caller, long durationNs, String exceptionType);
}
