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

package software.amazon.opentelemetry.javaagent.bootstrap.di;

import java.util.Map;

/** Pending capture data for collection by DISnapshotCollector. */
public final class PendingCapture {

  public enum CaptureType {
    LINE,
    METHOD
  }

  private final CaptureType captureType;
  private final String instrumentationKey;
  private final int lineNumber;
  private final Map<String, SerializedValue> locals;
  private final Map<String, SerializedValue> arguments;
  private final SerializedValue returnValue;
  private final ThrowableData throwable;
  private final long timestamp;
  private final long durationNanos;
  private final String traceId;
  private final String spanId;
  private final long threadId;
  private final String threadName;
  private final StackTraceElement[] stackTrace;

  public PendingCapture(
      CaptureType captureType,
      String instrumentationKey,
      int lineNumber,
      Map<String, SerializedValue> locals,
      Map<String, SerializedValue> arguments,
      SerializedValue returnValue,
      ThrowableData throwable,
      long timestamp,
      long durationNanos,
      String traceId,
      String spanId,
      long threadId,
      String threadName,
      StackTraceElement[] stackTrace) {
    this.captureType = captureType;
    this.instrumentationKey = instrumentationKey;
    this.lineNumber = lineNumber;
    this.locals = locals;
    this.arguments = arguments;
    this.returnValue = returnValue;
    this.throwable = throwable;
    this.timestamp = timestamp;
    this.durationNanos = durationNanos;
    this.traceId = traceId;
    this.spanId = spanId;
    this.threadId = threadId;
    this.threadName = threadName;
    this.stackTrace = stackTrace;
  }

  public CaptureType getCaptureType() {
    return captureType;
  }

  public String getInstrumentationKey() {
    return instrumentationKey;
  }

  public int getLineNumber() {
    return lineNumber;
  }

  public Map<String, SerializedValue> getLocals() {
    return locals;
  }

  public Map<String, SerializedValue> getArguments() {
    return arguments;
  }

  public SerializedValue getReturnValue() {
    return returnValue;
  }

  public ThrowableData getThrowable() {
    return throwable;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public long getDurationNanos() {
    return durationNanos;
  }

  public String getTraceId() {
    return traceId;
  }

  public String getSpanId() {
    return spanId;
  }

  public long getThreadId() {
    return threadId;
  }

  public String getThreadName() {
    return threadName;
  }

  public StackTraceElement[] getStackTrace() {
    return stackTrace;
  }
}
