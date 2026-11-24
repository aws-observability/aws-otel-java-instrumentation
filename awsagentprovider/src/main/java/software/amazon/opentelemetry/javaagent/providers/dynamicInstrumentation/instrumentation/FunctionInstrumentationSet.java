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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationType;

/**
 * Groups instrumentation configurations for a single function (method).
 *
 * <p>A function can have multiple instrumentation types simultaneously:
 *
 * <ul>
 *   <li>Method-level PROBE (line=0) - Persistent function span
 *   <li>Method-level BREAKPOINT (line=0) - Temporary function span
 *   <li>Line-level BREAKPOINTs (line>0) - Span events within function span
 * </ul>
 *
 * <p>Decision logic for method wrapper (INTERNAL span creation):
 *
 * <ul>
 *   <li>Any instrumentation requires method wrapper for span context
 *   <li>Line-level breakpoints add span events to method span
 *   <li>PROBE takes priority over BREAKPOINT at line=0 (permanent > temporary)
 * </ul>
 */
public class FunctionInstrumentationSet {

  private final String className;
  private final String methodName;
  private final String functionKey;

  // Method-level configurations (line=0)
  private InstrumentationConfiguration methodLevelProbe; // PROBE at line=0
  private InstrumentationConfiguration methodLevelBreakpoint; // BREAKPOINT at line=0

  // Line-level configurations (line>0)
  private final Map<Integer, InstrumentationConfiguration> lineLevelBreakpoints;

  public FunctionInstrumentationSet(String className, String methodName) {
    this.className = className;
    this.methodName = methodName;
    this.functionKey = className + "." + methodName;
    this.lineLevelBreakpoints = new HashMap<>();
  }

  /** Add a configuration to this function set. Handles PROBE/BREAKPOINT priority at line=0. */
  public void addConfiguration(InstrumentationConfiguration config) {
    if (config.getLineNumber() == 0) {
      // Method-level
      if (config.getInstrumentationType() == InstrumentationType.PROBE) {
        methodLevelProbe = config;
      } else {
        methodLevelBreakpoint = config;
      }
    } else {
      // Line-level
      lineLevelBreakpoints.put(config.getLineNumber(), config);
    }
  }

  /**
   * Check if this function needs a method wrapper (INTERNAL span).
   *
   * <p>Method wrapper is needed if any instrumentation exists:
   *
   * <ul>
   *   <li>Method-level PROBE or BREAKPOINT creates function span
   *   <li>Line-level breakpoints need function span for span events
   * </ul>
   */
  public boolean needsMethodWrapper() {
    return methodLevelProbe != null
        || methodLevelBreakpoint != null
        || !lineLevelBreakpoints.isEmpty();
  }

  /**
   * Get the configuration that defines the method-level span. Returns PROBE if present (priority),
   * otherwise BREAKPOINT.
   *
   * @return Method-level config, or null if only line-level breakpoints exist
   */
  public InstrumentationConfiguration getMethodSpanConfig() {
    if (methodLevelProbe != null) {
      return methodLevelProbe;
    }
    return methodLevelBreakpoint;
  }

  /** Check if this function has method-level instrumentation (line=0). */
  public boolean hasMethodLevelInstrumentation() {
    return methodLevelProbe != null || methodLevelBreakpoint != null;
  }

  /** Check if this function has line-level breakpoints. */
  public boolean hasLineLevelBreakpoints() {
    return !lineLevelBreakpoints.isEmpty();
  }

  /**
   * Check if this function needs data capture (arguments/return values/stack trace).
   *
   * <p>Data capture via MethodCaptureAdvice is only applied for method-level configs (line=0).
   * Line-level breakpoints capture locals and stack traces independently via LineCaptureAdvice and
   * do not use MethodCaptureAdvice for return value or argument capture.
   *
   * @return true if MethodCaptureAdvice should be applied
   */
  public boolean needsDataCapture() {
    InstrumentationConfiguration methodConfig = getMethodSpanConfig();
    return methodConfig != null && methodConfig.getCaptureConfig() != null;
  }

  /** Get line numbers for line-level breakpoints. */
  public Set<Integer> getLineNumbers() {
    return lineLevelBreakpoints.keySet();
  }

  /** Get line-level breakpoint configurations. */
  public Map<Integer, InstrumentationConfiguration> getLineLevelBreakpoints() {
    return lineLevelBreakpoints;
  }

  public String getClassName() {
    return className;
  }

  public String getMethodName() {
    return methodName;
  }

  public String getFunctionKey() {
    return functionKey;
  }

  public InstrumentationConfiguration getMethodLevelProbe() {
    return methodLevelProbe;
  }

  public InstrumentationConfiguration getMethodLevelBreakpoint() {
    return methodLevelBreakpoint;
  }
}
