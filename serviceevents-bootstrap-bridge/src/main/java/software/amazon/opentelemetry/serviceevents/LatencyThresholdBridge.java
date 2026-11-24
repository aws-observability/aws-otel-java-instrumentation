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
 * Bridge interface for resolving per-endpoint latency thresholds from the bootstrap classloader.
 *
 * <p>Implemented by the agent classloader (see {@code LatencyThresholdResolver}). Allows the
 * bootstrap-side latency-trigger check in {@code ServiceEventsDataStore.recordPotentialIncident} to
 * consult a configurable set of glob patterns keyed on {@code "METHOD /route"} without pulling the
 * pattern-compilation code into bootstrap.
 *
 * <p><b>IMPORTANT:</b> This interface MUST have NO external dependencies (loaded in bootstrap CL).
 */
public interface LatencyThresholdBridge {

  /**
   * Return the latency threshold in milliseconds for the given request, or a non-positive value
   * (including {@link Double#NaN}) to indicate that the caller should fall back to the global
   * default threshold.
   *
   * @param method HTTP method (e.g., "GET", "POST"); may be null
   * @param route URL route/path (e.g., "/api/users"); may be null
   */
  double resolveThresholdMs(String method, String route);
}
