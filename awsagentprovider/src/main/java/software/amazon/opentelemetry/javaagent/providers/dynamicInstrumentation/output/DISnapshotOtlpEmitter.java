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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.output;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.CapturedContext;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.CapturedThrowable;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.CapturedValue;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.Captures;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.Snapshot;

/**
 * Emits DI snapshot data as OTLP LogRecords via a dedicated SdkLoggerProvider.
 *
 * <p>Converts {@link Snapshot} objects into structured OTLP LogRecords with:
 *
 * <ul>
 *   <li>Flat attributes (queryable in CloudWatch Logs Insights)
 *   <li>Structured body (stack + captures as nested AnyValue maps/arrays)
 *   <li>Trace context correlation (TraceId/SpanId)
 * </ul>
 *
 * <p>Uses a dedicated, isolated SdkLoggerProvider — DI snapshots do not mix with application logs
 * or Application Signals.
 */
public class DISnapshotOtlpEmitter {

  private static final String INSTRUMENTATION_SCOPE = "aws.dynamic_instrumentation";
  private static final String INSTRUMENTATION_VERSION = "1.0";
  private static final String EVENT_NAME = "aws.dynamic_instrumentation.snapshot";
  private static final java.util.logging.Logger jlogger =
      java.util.logging.Logger.getLogger(DISnapshotOtlpEmitter.class.getName());

  private final Supplier<SdkLoggerProvider> loggerProviderFactory;
  private volatile Logger logger;
  private volatile SdkLoggerProvider ownedLoggerProvider;
  private volatile boolean initFailed = false;

  /**
   * Primary constructor for production use with lazy provider creation.
   *
   * <p>The provider is created on the first emit call, allowing ResourceHolder to be populated by
   * OTel autoconfiguration before the Resource is read.
   */
  public DISnapshotOtlpEmitter(Supplier<SdkLoggerProvider> loggerProviderFactory) {
    this.loggerProviderFactory = loggerProviderFactory;
  }

  /**
   * Constructor for tests where the provider is pre-built (e.g., InMemory exporters).
   *
   * <p>The provider is initialized eagerly — no lazy init needed.
   */
  public DISnapshotOtlpEmitter(SdkLoggerProvider loggerProvider) {
    this.loggerProviderFactory = null;
    this.ownedLoggerProvider = loggerProvider;
    this.logger =
        loggerProvider
            .loggerBuilder(INSTRUMENTATION_SCOPE)
            .setInstrumentationVersion(INSTRUMENTATION_VERSION)
            .build();
  }

  private boolean ensureInitialized() {
    if (logger != null) {
      return true;
    }
    if (initFailed) {
      return false;
    }
    synchronized (this) {
      if (logger != null) {
        return true;
      }
      if (initFailed) {
        return false;
      }
      try {
        ownedLoggerProvider = loggerProviderFactory.get();
        logger =
            ownedLoggerProvider
                .loggerBuilder(INSTRUMENTATION_SCOPE)
                .setInstrumentationVersion(INSTRUMENTATION_VERSION)
                .build();
        return true;
      } catch (Throwable e) {
        jlogger.log(
            java.util.logging.Level.SEVERE,
            "AWS DI: Failed to initialize OTLP LoggerProvider, snapshots will not be exported",
            e);
        initFailed = true;
        return false;
      }
    }
  }

  /** Get the owned LoggerProvider (for shutdown). May be null if not yet initialized. */
  public SdkLoggerProvider getLoggerProvider() {
    return ownedLoggerProvider;
  }

  /**
   * Emit a DI snapshot as an OTLP LogRecord.
   *
   * @param snapshot the captured snapshot data
   * @param config the instrumentation configuration that triggered this snapshot
   */
  public void emitSnapshot(Snapshot snapshot, InstrumentationConfiguration config) {
    if (!ensureInitialized()) {
      return;
    }

    try {
      emitSnapshotInternal(snapshot, config);
    } catch (Throwable e) {
      jlogger.log(
          java.util.logging.Level.WARNING, "AWS DI: Error emitting snapshot as OTLP LogRecord", e);
    }
  }

  private void emitSnapshotInternal(Snapshot snapshot, InstrumentationConfiguration config) {
    Snapshot.InstrumentationLocation location = snapshot.getInstrumentation().getLocation();

    // Build attributes
    AttributesBuilder attrs = Attributes.builder();
    attrs.put("event.name", EVENT_NAME);
    attrs.put("aws.di.snapshot_id", snapshot.getId());
    attrs.put("aws.di.location_hash", snapshot.getLocationHash());

    boolean isLineLevel = location.getLineNumber() > 0;
    attrs.put("aws.di.instrumentation_level", isLineLevel ? "line" : "method");

    if (snapshot.getDuration() != null) {
      attrs.put(AttributeKey.longKey("aws.di.duration_ms"), snapshot.getDuration());
    }
    if (location.getCodeUnit() != null) {
      attrs.put("aws.di.code_unit", location.getCodeUnit());
    }
    if (location.getClassName() != null) {
      attrs.put("aws.di.class_name", location.getClassName());
    }
    if (location.getMethodName() != null) {
      attrs.put("aws.di.method_name", location.getMethodName());
    }
    if (location.getClassName() != null) {
      attrs.put("aws.di.file_path", deriveFilePath(location.getClassName()));
    }
    if (isLineLevel) {
      attrs.put(AttributeKey.longKey("aws.di.line_number"), (long) location.getLineNumber());
    }
    if (config != null && config.getInstrumentationType() != null) {
      attrs.put("aws.di.instrumentation_type", config.getInstrumentationType().name());
    }

    // Build structured body
    Value<?> body = snapshotBodyToValue(snapshot);

    // Build trace context
    Context context = null;
    Snapshot.TraceContext trace = snapshot.getTrace();
    if (trace != null && trace.getTraceId() != null && trace.getSpanId() != null) {
      SpanContext spanContext =
          SpanContext.create(
              trace.getTraceId(),
              trace.getSpanId(),
              TraceFlags.getSampled(),
              TraceState.getDefault());
      context = Context.root().with(Span.wrap(spanContext));
    }

    // Build and emit the LogRecord
    LogRecordBuilder builder =
        logger
            .logRecordBuilder()
            .setTimestamp(snapshot.getTimestamp(), TimeUnit.MILLISECONDS)
            .setAllAttributes(attrs.build());

    if (body != null) {
      builder.setBody(body);
    }
    if (context != null) {
      builder.setContext(context);
    }

    builder.emit();
  }

  /** Convert snapshot stack + captures into a structured Value body. */
  private Value<?> snapshotBodyToValue(Snapshot snapshot) {
    Map<String, Value<?>> bodyMap = new LinkedHashMap<>();

    // Stack frames
    List<CapturedThrowable.StackFrame> stack = snapshot.getStack();
    if (stack != null && !stack.isEmpty()) {
      bodyMap.put("stack", stackToValue(stack));
    }

    // Captures
    if (snapshot.getCaptures() != null) {
      bodyMap.put("captures", capturesToValue(snapshot.getCaptures()));
    }

    return Value.of(bodyMap);
  }

  /** Convert stack frames to a Value array. */
  private Value<?> stackToValue(List<CapturedThrowable.StackFrame> frames) {
    List<Value<?>> frameList = new ArrayList<>();
    for (CapturedThrowable.StackFrame frame : frames) {
      Map<String, Value<?>> frameMap = new LinkedHashMap<>();
      if (frame.getFileName() != null) {
        frameMap.put("file_path", Value.of(frame.getFileName()));
      }
      if (frame.getFunction() != null) {
        frameMap.put("function", Value.of(frame.getFunction()));
      }
      frameMap.put("line_number", Value.of((long) frame.getLineNumber()));
      frameList.add(Value.of(frameMap));
    }
    return Value.of(frameList);
  }

  /** Convert Captures to a Value map with entry/return/lines. */
  private Value<?> capturesToValue(Captures captures) {
    Map<String, Value<?>> capturesMap = new LinkedHashMap<>();

    if (captures.getEntry() != null) {
      capturesMap.put("entry", capturedContextToValue(captures.getEntry()));
    }
    if (captures.getMethodReturn() != null) {
      capturesMap.put("return", capturedContextToValue(captures.getMethodReturn()));
    }
    if (captures.getLines() != null && !captures.getLines().isEmpty()) {
      Map<String, Value<?>> linesMap = new LinkedHashMap<>();
      for (Map.Entry<Integer, CapturedContext> entry : captures.getLines().entrySet()) {
        linesMap.put(String.valueOf(entry.getKey()), capturedContextToValue(entry.getValue()));
      }
      capturesMap.put("lines", Value.of(linesMap));
    }

    return Value.of(capturesMap);
  }

  /** Convert CapturedContext to a Value map with arguments/locals/return_value/throwable. */
  private Value<?> capturedContextToValue(CapturedContext ctx) {
    Map<String, Value<?>> ctxMap = new LinkedHashMap<>();

    if (ctx.getArguments() != null && !ctx.getArguments().isEmpty()) {
      ctxMap.put("arguments", capturedValueMapToValue(ctx.getArguments()));
    }
    if (ctx.getLocals() != null && !ctx.getLocals().isEmpty()) {
      ctxMap.put("locals", capturedValueMapToValue(ctx.getLocals()));
    }
    if (ctx.getReturnValue() != null) {
      ctxMap.put("return_value", capturedValueToValue(ctx.getReturnValue()));
    }
    if (ctx.getThrowable() != null) {
      ctxMap.put("throwable", throwableToValue(ctx.getThrowable()));
    }

    return Value.of(ctxMap);
  }

  /** Convert a map of named CapturedValues to a Value map. */
  private Value<?> capturedValueMapToValue(Map<String, CapturedValue> map) {
    Map<String, Value<?>> result = new LinkedHashMap<>();
    for (Map.Entry<String, CapturedValue> entry : map.entrySet()) {
      result.put(entry.getKey(), capturedValueToValue(entry.getValue()));
    }
    return Value.of(result);
  }

  /**
   * Recursively convert a CapturedValue to a Value.
   *
   * <p>Each CapturedValue has a type and exactly one of: value, fields, elements, entries, is_null,
   * or not_captured_reason.
   */
  private Value<?> capturedValueToValue(CapturedValue cv) {
    Map<String, Value<?>> valueMap = new LinkedHashMap<>();
    valueMap.put("type", Value.of(cv.getType()));

    if (cv.isNull()) {
      valueMap.put("is_null", Value.of(true));
    } else if (cv.getNotCapturedReason() != null) {
      valueMap.put("not_captured_reason", Value.of(cv.getNotCapturedReason().name()));
    } else if (cv.getValue() != null) {
      valueMap.put("value", Value.of(cv.getValue()));
      if (cv.isTruncated()) {
        valueMap.put("truncated", Value.of(true));
      }
      if (cv.getSize() != null) {
        valueMap.put("size", Value.of((long) cv.getSize()));
      }
    } else if (cv.getFields() != null) {
      valueMap.put("fields", capturedValueMapToValue(cv.getFields()));
    } else if (cv.getElements() != null) {
      List<Value<?>> elementList = new ArrayList<>();
      for (CapturedValue element : cv.getElements()) {
        elementList.add(capturedValueToValue(element));
      }
      valueMap.put("elements", Value.of(elementList));
      if (cv.getSize() != null) {
        valueMap.put("size", Value.of((long) cv.getSize()));
      }
    } else if (cv.getEntries() != null) {
      List<Value<?>> entryList = new ArrayList<>();
      for (CapturedValue.MapEntry entry : cv.getEntries()) {
        Map<String, Value<?>> entryMap = new LinkedHashMap<>();
        entryMap.put("key", capturedValueToValue(entry.getKey()));
        entryMap.put("value", capturedValueToValue(entry.getValue()));
        entryList.add(Value.of(entryMap));
      }
      valueMap.put("entries", Value.of(entryList));
      if (cv.getSize() != null) {
        valueMap.put("size", Value.of((long) cv.getSize()));
      }
    }

    return Value.of(valueMap);
  }

  /** Convert CapturedThrowable to a Value map. */
  private Value<?> throwableToValue(CapturedThrowable throwable) {
    Map<String, Value<?>> throwableMap = new LinkedHashMap<>();
    throwableMap.put("type", Value.of(throwable.getType() != null ? throwable.getType() : ""));
    throwableMap.put(
        "message", Value.of(throwable.getMessage() != null ? throwable.getMessage() : ""));

    if (throwable.getStacktrace() != null && !throwable.getStacktrace().isEmpty()) {
      throwableMap.put("stacktrace", stackToValue(throwable.getStacktrace()));
    }

    return Value.of(throwableMap);
  }

  /**
   * Derive a conventional source file path from a fully qualified Java class name.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code "com.amazon.sampleapp.BasicMethods"} → {@code
   *       "com/amazon/sampleapp/BasicMethods.java"}
   *   <li>{@code "com.amazon.Foo$Bar"} → {@code "com/amazon/Foo.java"} (inner class stripped)
   * </ul>
   */
  static String deriveFilePath(String className) {
    if (className == null || className.isEmpty()) {
      return null;
    }
    // Strip inner class suffix (e.g., "Foo$Bar" → "Foo")
    int dollarIdx = className.indexOf('$');
    String outerClass = dollarIdx >= 0 ? className.substring(0, dollarIdx) : className;
    return outerClass.replace('.', '/') + ".java";
  }
}
