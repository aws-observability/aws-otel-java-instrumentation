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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model;

/**
 * Types of instrumentation points supported by the dynamic instrumentation system.
 *
 * <p>This enum distinguishes between two types of instrumentation:
 *
 * <ul>
 *   <li>PROBE: Permanent instrumentation that persists until explicitly deleted. Always applies at
 *       method-level (lineNumber = 0).
 *   <li>BREAKPOINT: Temporary instrumentation with expiration and hit limits. Can be method-level
 *       or line-level.
 * </ul>
 */
public enum InstrumentationType {
  /**
   * Probe: Permanent, always-on instrumentation at method boundaries. Used for continuous
   * monitoring and metrics collection. Does not expire and has no hit limits.
   */
  PROBE,

  /**
   * Breakpoint: Temporary instrumentation for debugging and troubleshooting. Can capture detailed
   * snapshots including local variables. Has expiration time and hit limits for safety.
   */
  BREAKPOINT
}
