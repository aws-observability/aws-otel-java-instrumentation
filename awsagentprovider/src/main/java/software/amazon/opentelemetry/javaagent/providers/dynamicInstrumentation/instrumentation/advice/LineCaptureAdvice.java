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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.advice;

import java.util.Map;
import software.amazon.opentelemetry.javaagent.bootstrap.di.DIDataStore;

/**
 * Static advice class for line-level breakpoint instrumentation.
 *
 * <p>This class is called from bytecode injected by LineInstrumentationTransformer using ASM. The
 * onLineBreakpointHit method must be public static for ASM INVOKESTATIC injection.
 */
public class LineCaptureAdvice {

  /**
   * Called when a line-level instrumentation is hit. This method is invoked via ASM bytecode
   * injection at the specific target line number.
   *
   * <p>IMPORTANT: This method must be public static for ASM INVOKESTATIC injection.
   *
   * @param instrumentationKey Unique key identifying this instrumentation configuration (format:
   *     pkg.Class.method:lineNumber)
   * @param localVariables Map of local variable names to their values captured at the
   *     instrumentation line
   */
  public static void onLineBreakpointHit(
      String instrumentationKey, Map<String, Object> localVariables) {
    if (!DIDataStore.recordHit(instrumentationKey)) return;

    // Check captureLocals filter:
    // null = do not capture locals (but still produce snapshot with empty captures)
    // empty = capture all, non-empty = capture only those
    String[] captureLocalsFilter = DIDataStore.getCaptureLocals(instrumentationKey);

    int[] limits = DIDataStore.getLimits(instrumentationKey);
    Thread t = Thread.currentThread();
    String traceId = null, spanId = null;
    try {
      io.opentelemetry.api.trace.Span span = io.opentelemetry.api.trace.Span.current();
      if (span != null && span.getSpanContext().isValid()) {
        traceId = span.getSpanContext().getTraceId();
        spanId = span.getSpanContext().getSpanId();
      }
    } catch (Throwable ignored) {
      // Catch Throwable (not just Exception) because this code runs inside user methods.
      // NoClassDefFoundError is common if OTel API is not on the user's classpath.
    }

    // Apply capture filtering:
    // null = pass empty map (no locals captured, but snapshot still produced)
    // [] = pass all locals through
    // [names] = filter to specific names
    Map<String, Object> filteredLocals;
    if (captureLocalsFilter == null) {
      filteredLocals = new java.util.HashMap<>(); // empty - no locals captured
    } else if (captureLocalsFilter.length > 0 && localVariables != null) {
      filteredLocals = new java.util.HashMap<>();
      for (String name : captureLocalsFilter) {
        if (localVariables.containsKey(name)) {
          filteredLocals.put(name, localVariables.get(name));
        }
      }
    } else {
      filteredLocals = localVariables; // [] = capture all
    }

    int lineNumber = extractLineNumber(instrumentationKey);
    software.amazon.opentelemetry.javaagent.bootstrap.di.DIDataStore.captureLocals(
        instrumentationKey,
        filteredLocals,
        lineNumber,
        limits[0],
        limits[1],
        limits[2],
        limits[4],
        limits[3],
        System.currentTimeMillis(),
        traceId,
        spanId,
        t.getId(),
        t.getName());
  }

  /** Extracts line number from instrumentation key (format: pkg.Class.method:lineNumber). */
  private static int extractLineNumber(String key) {
    int colonIdx = key.lastIndexOf(':');
    if (colonIdx >= 0 && colonIdx < key.length() - 1) {
      try {
        return Integer.parseInt(key.substring(colonIdx + 1));
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return 0;
  }
}
