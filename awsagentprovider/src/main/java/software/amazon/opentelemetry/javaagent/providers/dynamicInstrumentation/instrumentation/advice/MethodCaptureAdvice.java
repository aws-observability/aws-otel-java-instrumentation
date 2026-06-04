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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import software.amazon.opentelemetry.javaagent.bootstrap.di.DIDataStore;

/** ByteBuddy Advice class that captures method arguments and return values via DIDataStore. */
public class MethodCaptureAdvice {

  public static final ThreadLocal<Deque<EntryData>> entryDataThreadLocal =
      ThreadLocal.withInitial(ArrayDeque::new);

  public static class EntryData {
    public final long startTime;
    public final String methodKey;
    public final int[] limits;
    public final boolean captureReturn;

    public EntryData(long startTime, String methodKey, int[] limits, boolean captureReturn) {
      this.startTime = startTime;
      this.methodKey = methodKey;
      this.limits = limits;
      this.captureReturn = captureReturn;
    }
  }

  /** Executed at method entry to capture arguments. */
  @Advice.OnMethodEnter
  public static void onMethodEnter(
      @Advice.Origin String method, @Advice.AllArguments Object[] args) {

    try {
      String methodKey = extractMethodKey(method);
      if (!DIDataStore.recordHit(methodKey)) {
        return;
      }

      int[] limits = DIDataStore.getLimits(methodKey);
      boolean captureReturn = DIDataStore.shouldCaptureReturn(methodKey);

      // Check captureArguments filter:
      // null = do not capture, empty = capture all, non-empty = capture only those
      String[] captureArgsFilter = DIDataStore.getCaptureArguments(methodKey);

      Map<String, Object> arguments = null;
      if (captureArgsFilter != null && args != null && args.length > 0) {
        arguments = new HashMap<>();
        String[] paramNames = DIDataStore.getParameterNames(methodKey);
        for (int i = 0; i < args.length; i++) {
          String name =
              (paramNames != null
                      && i < paramNames.length
                      && paramNames[i] != null
                      && !paramNames[i].isEmpty())
                  ? paramNames[i]
                  : "arg" + i;
          // If filter is non-empty, only include named arguments
          if (captureArgsFilter.length > 0) {
            boolean found = false;
            for (String filterName : captureArgsFilter) {
              if (name.equals(filterName)) {
                found = true;
                break;
              }
            }
            if (!found) continue;
          }
          arguments.put(name, args[i]);
        }
      }

      Thread t = Thread.currentThread();
      String traceId = null, spanId = null;
      try {
        io.opentelemetry.api.trace.Span span = io.opentelemetry.api.trace.Span.current();
        if (span != null && span.getSpanContext().isValid()) {
          traceId = span.getSpanContext().getTraceId();
          spanId = span.getSpanContext().getSpanId();
        }
      } catch (Exception ignored) {
      }

      // Only call captureMethodEntry if there are arguments to capture
      if (arguments != null && !arguments.isEmpty()) {
        software.amazon.opentelemetry.javaagent.bootstrap.di.DIDataStore.captureMethodEntry(
            methodKey,
            arguments,
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

      entryDataThreadLocal
          .get()
          .push(new EntryData(System.nanoTime(), methodKey, limits, captureReturn));

    } catch (Throwable t) {
      System.err.println("[MethodCaptureAdvice] onMethodEnter ERROR: " + t);
      t.printStackTrace(System.err);
    }
  }

  /** Executed at method exit to capture return value. */
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onMethodExit(
      @Advice.Origin String method,
      @Advice.Return(
              readOnly = false,
              typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC)
          Object returnValue,
      @Advice.Thrown Throwable throwable) {

    try {
      Deque<EntryData> stack = entryDataThreadLocal.get();
      EntryData entryData = stack.peekFirst();
      String methodKey = extractMethodKey(method);
      if (entryData == null || !methodKey.equals(entryData.methodKey)) {
        return;
      }
      stack.pollFirst();

      long durationNanos = System.nanoTime() - entryData.startTime;

      Thread t = Thread.currentThread();
      String traceId = null, spanId = null;
      try {
        io.opentelemetry.api.trace.Span span = io.opentelemetry.api.trace.Span.current();
        if (span != null && span.getSpanContext().isValid()) {
          traceId = span.getSpanContext().getTraceId();
          spanId = span.getSpanContext().getSpanId();
        }
      } catch (Exception ignored) {
      }

      software.amazon.opentelemetry.javaagent.bootstrap.di.DIDataStore.captureMethodExit(
          entryData.methodKey,
          entryData.captureReturn ? returnValue : null,
          throwable,
          entryData.limits[0],
          entryData.limits[1],
          entryData.limits[2],
          entryData.limits[4],
          entryData.limits[3],
          durationNanos,
          System.currentTimeMillis(),
          traceId,
          spanId,
          t.getId(),
          t.getName());

    } catch (Throwable t) {
      System.err.println("[MethodCaptureAdvice] onMethodExit ERROR: " + t);
      t.printStackTrace(System.err);
    }
  }

  /**
   * Extract method key from ByteBuddy origin for registry lookup. Must be public because Advice
   * code is inlined and called from target classes. Returns: "com.example.ClassName.methodName"
   */
  public static String extractMethodKey(String origin) {
    // Origin: "public ReturnType com.example.Class.method(Args)"
    int openParen = origin.indexOf('(');
    if (openParen < 0) {
      return origin;
    }

    // Find last space before class.method
    int lastSpace = origin.lastIndexOf(' ', openParen);
    if (lastSpace < 0) {
      return origin;
    }

    // Extract "com.example.Class.method"
    return origin.substring(lastSpace + 1, openParen);
  }
}
