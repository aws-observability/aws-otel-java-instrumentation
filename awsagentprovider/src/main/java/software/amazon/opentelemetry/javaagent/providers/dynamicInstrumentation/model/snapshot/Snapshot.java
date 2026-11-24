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
import java.util.UUID;

/** Top-level snapshot container for dynamic instrumentation captures. */
public final class Snapshot {

  private final String id;
  private final long timestamp;
  private final Long duration;
  private final String service;
  private final String environment;
  private final String locationHash;
  private final InstrumentationDetails instrumentation;
  private final TraceContext trace;
  private final ThreadInfo thread;
  private final List<CapturedThrowable.StackFrame> stack;
  private final Captures captures;

  private Snapshot(
      String id,
      long timestamp,
      Long duration,
      String service,
      String environment,
      String locationHash,
      InstrumentationDetails instrumentation,
      TraceContext trace,
      ThreadInfo thread,
      List<CapturedThrowable.StackFrame> stack,
      Captures captures) {
    this.id = id;
    this.timestamp = timestamp;
    this.duration = duration;
    this.service = service;
    this.environment = environment;
    this.locationHash = locationHash;
    this.instrumentation = instrumentation;
    this.trace = trace;
    this.thread = thread;
    this.stack = Collections.unmodifiableList(stack);
    this.captures = captures;
  }

  public static Snapshot create(
      String instrumentationId,
      String codeUnit,
      String className,
      String methodName,
      String filePath,
      int lineNumber,
      String locationHash,
      Long duration,
      String traceId,
      String spanId,
      Captures captures) {
    return create(
        instrumentationId,
        codeUnit,
        className,
        methodName,
        filePath,
        lineNumber,
        locationHash,
        20,
        duration,
        null,
        null,
        traceId,
        spanId,
        captures,
        null);
  }

  public static Snapshot create(
      String instrumentationId,
      String codeUnit,
      String className,
      String methodName,
      String filePath,
      int lineNumber,
      String locationHash,
      int maxStackFrames,
      Long duration,
      String service,
      String environment,
      String traceId,
      String spanId,
      Captures captures,
      StackTraceElement[] preCapturedStackTrace) {
    String id = UUID.randomUUID().toString();
    long timestamp = System.currentTimeMillis();

    Thread currentThread = Thread.currentThread();
    ThreadInfo threadInfo = new ThreadInfo(currentThread.getId(), currentThread.getName());

    List<CapturedThrowable.StackFrame> stackFrames = new ArrayList<>();
    if (preCapturedStackTrace != null) {
      for (int i = 0;
          i < preCapturedStackTrace.length && stackFrames.size() < maxStackFrames;
          i++) {
        if (InternalFrameFilter.isInternal(preCapturedStackTrace[i])) {
          continue;
        }
        stackFrames.add(
            new CapturedThrowable.StackFrame(
                preCapturedStackTrace[i].getFileName(),
                preCapturedStackTrace[i].getMethodName(),
                preCapturedStackTrace[i].getLineNumber()));
      }
    }

    InstrumentationLocation location =
        new InstrumentationLocation(codeUnit, className, methodName, lineNumber, filePath, "java");
    InstrumentationDetails instrumentation = new InstrumentationDetails(location);

    TraceContext trace = null;
    if (traceId != null && spanId != null) {
      trace = new TraceContext(traceId, spanId);
    }

    return new Snapshot(
        id,
        timestamp,
        duration,
        service,
        environment,
        locationHash,
        instrumentation,
        trace,
        threadInfo,
        stackFrames,
        captures);
  }

  public String getId() {
    return id;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public Long getDuration() {
    return duration;
  }

  public String getService() {
    return service;
  }

  public String getEnvironment() {
    return environment;
  }

  public String getLocationHash() {
    return locationHash;
  }

  public InstrumentationDetails getInstrumentation() {
    return instrumentation;
  }

  public TraceContext getTrace() {
    return trace;
  }

  public ThreadInfo getThread() {
    return thread;
  }

  public List<CapturedThrowable.StackFrame> getStack() {
    return stack;
  }

  public Captures getCaptures() {
    return captures;
  }

  public static final class InstrumentationDetails {
    private final InstrumentationLocation location;

    public InstrumentationDetails(InstrumentationLocation location) {
      this.location = location;
    }

    public InstrumentationLocation getLocation() {
      return location;
    }
  }

  public static final class InstrumentationLocation {
    private final String codeUnit;
    private final String className;
    private final String methodName;
    private final int lineNumber;
    private final String filePath;
    private final String language;

    public InstrumentationLocation(
        String codeUnit,
        String className,
        String methodName,
        int lineNumber,
        String filePath,
        String language) {
      this.codeUnit = codeUnit;
      this.className = className;
      this.methodName = methodName;
      this.lineNumber = lineNumber;
      this.filePath = filePath;
      this.language = language;
    }

    public String getCodeUnit() {
      return codeUnit;
    }

    public String getClassName() {
      return className;
    }

    public String getMethodName() {
      return methodName;
    }

    public int getLineNumber() {
      return lineNumber;
    }

    public String getFilePath() {
      return filePath;
    }

    public String getLanguage() {
      return language;
    }
  }

  public static final class TraceContext {
    private final String traceId;
    private final String spanId;

    public TraceContext(String traceId, String spanId) {
      this.traceId = traceId;
      this.spanId = spanId;
    }

    public String getTraceId() {
      return traceId;
    }

    public String getSpanId() {
      return spanId;
    }
  }

  public static final class ThreadInfo {
    private final long id;
    private final String name;

    public ThreadInfo(long id, String name) {
      this.id = id;
      this.name = name;
    }

    public long getId() {
      return id;
    }

    public String getName() {
      return name;
    }
  }
}
