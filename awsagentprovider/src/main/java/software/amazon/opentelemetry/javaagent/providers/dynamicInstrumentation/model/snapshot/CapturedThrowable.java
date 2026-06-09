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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import software.amazon.opentelemetry.javaagent.bootstrap.di.ThrowableData;

/** Structured exception representation for captured throwables in dynamic instrumentation. */
public final class CapturedThrowable {

  private final String type;
  private final String message;
  private final List<StackFrame> stacktrace;

  private CapturedThrowable(String type, String message, List<StackFrame> stacktrace) {
    this.type = type;
    this.message = message;
    this.stacktrace = Collections.unmodifiableList(stacktrace);
  }

  public static CapturedThrowable fromThrowable(
      Throwable throwable, int maxMessageLength, int maxStackFrames) {
    if (throwable == null) {
      return null;
    }

    String type = throwable.getClass().getName();
    String message = throwable.getMessage();
    if (message != null && message.length() > maxMessageLength) {
      message = message.substring(0, maxMessageLength);
    }

    List<StackFrame> stackFrames = buildFilteredFrames(throwable.getStackTrace(), maxStackFrames);
    return new CapturedThrowable(type, message, stackFrames);
  }

  /**
   * Construct a CapturedThrowable from pre-extracted ThrowableData. Filters internal frames and
   * enforces stack frame limits.
   */
  public static CapturedThrowable fromThrowableData(
      ThrowableData data, int maxMessageLength, int maxStackFrames) {
    if (data == null) {
      return null;
    }

    String type = data.getType();
    String message = data.getMessage();
    if (message != null && message.length() > maxMessageLength) {
      message = message.substring(0, maxMessageLength);
    }

    List<StackFrame> stackFrames = buildFilteredFrames(data.getStackTrace(), maxStackFrames);
    return new CapturedThrowable(type, message, stackFrames);
  }

  private static List<StackFrame> buildFilteredFrames(
      StackTraceElement[] elements, int maxStackFrames) {
    List<StackFrame> stackFrames = new ArrayList<>();
    if (elements == null) {
      return stackFrames;
    }
    for (StackTraceElement element : elements) {
      if (stackFrames.size() >= maxStackFrames) {
        break;
      }
      if (InternalFrameFilter.isInternal(element)) {
        continue;
      }
      stackFrames.add(
          new StackFrame(element.getFileName(), element.getMethodName(), element.getLineNumber()));
    }
    return stackFrames;
  }

  public String getType() {
    return type;
  }

  public String getMessage() {
    return message;
  }

  public List<StackFrame> getStacktrace() {
    return stacktrace;
  }

  public static final class StackFrame {
    private final String fileName;
    private final String function;
    private final int lineNumber;

    public StackFrame(String fileName, String function, int lineNumber) {
      this.fileName = fileName;
      this.function = function;
      this.lineNumber = lineNumber;
    }

    public String getFileName() {
      return fileName;
    }

    public String getFunction() {
      return function;
    }

    public int getLineNumber() {
      return lineNumber;
    }
  }
}
