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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.bootstrap.di.DIDataStore;
import software.amazon.opentelemetry.javaagent.bootstrap.di.PendingCapture;
import software.amazon.opentelemetry.javaagent.bootstrap.di.SerializedValue;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.config.DynamicInstrumentationConfig;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.InstrumentationRegistry;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.CapturedContext;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.CapturedThrowable;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.CapturedValue;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.Captures;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.Snapshot;

/** Background daemon thread that drains DIDataStore and writes snapshots. */
public final class DISnapshotCollector implements Runnable {

  private static final Logger logger = Logger.getLogger(DISnapshotCollector.class.getName());

  private final DISnapshotOtlpEmitter otlpEmitter;
  private final DynamicInstrumentationConfig diConfig;
  private volatile boolean shutdown = false;
  private Thread thread;

  public DISnapshotCollector(
      DISnapshotOtlpEmitter otlpEmitter, DynamicInstrumentationConfig diConfig) {
    this.otlpEmitter = otlpEmitter;
    this.diConfig = diConfig;
  }

  public void start() {
    if (thread == null) {
      thread = new Thread(this, "di-snapshot-collector");
      thread.setDaemon(true);
      thread.start();
    }
  }

  public void stop() {
    shutdown = true;
    if (thread != null) {
      thread.interrupt();
    }
  }

  @Override
  public void run() {
    boolean exitedDueToError = false;
    try {
      while (!shutdown) {
        try {
          List<PendingCapture> captures = DIDataStore.drain();
          if (captures != null) {
            logger.fine("Drained " + captures.size() + " captures");
            for (PendingCapture capture : captures) {
              processCapture(capture);
            }
          }
          Thread.sleep(10);
        } catch (InterruptedException e) {
          if (shutdown) {
            break;
          }
        } catch (Exception e) {
          logger.log(Level.WARNING, "Error in snapshot collector loop", e);
        } catch (Throwable t) {
          // Fatal error (e.g., OutOfMemoryError during serialization).
          // Log and stop gracefully — snapshot collection will cease.
          logger.log(Level.SEVERE, "DI: Fatal error in snapshot collector thread, DI will stop", t);
          exitedDueToError = true;
          break;
        }
      }
    } finally {
      // Best-effort final drain on normal shutdown only.
      // Skip if exiting due to fatal Error — further processing would likely fail too.
      if (!exitedDueToError) {
        try {
          List<PendingCapture> remaining = DIDataStore.drain();
          if (remaining != null && !remaining.isEmpty()) {
            logger.log(
                Level.FINE, "Processing {0} remaining captures during shutdown", remaining.size());
            for (PendingCapture capture : remaining) {
              processCapture(capture);
            }
          }
        } catch (Throwable e) {
          logger.log(Level.WARNING, "Error during final snapshot drain", e);
        }
      }
    }
  }

  private void processCapture(PendingCapture capture) {
    try {
      InstrumentationConfiguration config =
          InstrumentationRegistry.get(capture.getInstrumentationKey());
      if (config == null) {
        return;
      }

      Captures captures;
      if (capture.getCaptureType() == PendingCapture.CaptureType.METHOD) {
        captures = buildMethodCaptures(capture, config);
      } else {
        captures = buildLineCaptures(capture);
      }

      Long duration =
          capture.getDurationNanos() > 0 ? capture.getDurationNanos() / 1_000_000L : null;

      StackTraceElement[] stackTrace =
          config.getCaptureConfig().isCaptureStackTrace() ? capture.getStackTrace() : null;

      Snapshot snapshot =
          Snapshot.create(
              config.getInstrumentationKey(),
              config.getCodeUnit(),
              config.getClassName(),
              config.getMethodName(),
              config.getFilePath(),
              config.getLineNumber(),
              config.getLocationHash(),
              config.getCaptureConfig().getMaxStackFrames(),
              duration,
              diConfig.getServiceName(),
              diConfig.getDeploymentEnvironment(),
              capture.getTraceId(),
              capture.getSpanId(),
              captures,
              stackTrace);

      otlpEmitter.emitSnapshot(snapshot, config);

    } catch (Throwable e) {
      // Catch Throwable: individual capture failures (including Error subclasses like
      // StackOverflowError during serialization) should skip this capture, not abort
      // processing of remaining captures in the queue.
      logger.log(Level.WARNING, "Error processing capture", e);
    }
  }

  private Captures buildMethodCaptures(
      PendingCapture capture, InstrumentationConfiguration config) {
    Captures.Builder builder = Captures.builder();

    Map<String, CapturedValue> arguments = convertMap(capture.getArguments());
    if (!arguments.isEmpty()) {
      builder.entry(CapturedContext.builder().arguments(arguments).build());
    }

    CapturedContext.Builder retBuilder = CapturedContext.builder();
    boolean hasReturnData = false;
    if (capture.getReturnValue() != null) {
      retBuilder.returnValue(toCapturedValue(capture.getReturnValue()));
      hasReturnData = true;
    }
    if (capture.getThrowable() != null && config.getCaptureConfig() != null) {
      CaptureConfiguration captureConfig = config.getCaptureConfig();
      CapturedThrowable throwable =
          CapturedThrowable.fromThrowableData(
              capture.getThrowable(),
              captureConfig.getMaxStringLength(),
              captureConfig.getMaxStackFrames());
      if (throwable != null) {
        retBuilder.throwable(throwable);
        hasReturnData = true;
      }
    }
    if (hasReturnData) {
      builder.methodReturn(retBuilder.build());
    }

    return builder.build();
  }

  private Captures buildLineCaptures(PendingCapture capture) {
    Map<String, CapturedValue> locals = convertMap(capture.getLocals());
    CapturedContext lineContext = CapturedContext.builder().locals(locals).build();
    return Captures.builder().addLine(capture.getLineNumber(), lineContext).build();
  }

  private Map<String, CapturedValue> convertMap(Map<String, SerializedValue> map) {
    if (map == null) {
      return new HashMap<>();
    }
    Map<String, CapturedValue> result = new HashMap<>();
    for (Map.Entry<String, SerializedValue> entry : map.entrySet()) {
      result.put(entry.getKey(), toCapturedValue(entry.getValue()));
    }
    return result;
  }

  private static CapturedValue toCapturedValue(SerializedValue sv) {
    if (sv == null) {
      return CapturedValue.ofNull("unknown");
    }

    if (sv.isNull()) {
      return CapturedValue.ofNull(sv.getType());
    }

    if (sv.getNotCapturedReason() != null) {
      CapturedValue.NotCapturedReason reason;
      try {
        reason = CapturedValue.NotCapturedReason.valueOf(sv.getNotCapturedReason());
      } catch (IllegalArgumentException e) {
        reason = CapturedValue.NotCapturedReason.TIMEOUT;
      }
      return CapturedValue.notCaptured(sv.getType(), reason);
    }

    if (sv.getValue() != null) {
      if ("java.lang.String".equals(sv.getType())) {
        return CapturedValue.ofString(
            sv.getValue(),
            sv.isTruncated(),
            sv.getSize() != null ? sv.getSize() : sv.getValue().length());
      } else {
        return CapturedValue.ofPrimitive(sv.getType(), sv.getValue());
      }
    }

    if (sv.getFields() != null) {
      Map<String, CapturedValue> fields = new HashMap<>();
      for (Map.Entry<String, SerializedValue> entry : sv.getFields().entrySet()) {
        fields.put(entry.getKey(), toCapturedValue(entry.getValue()));
      }
      return CapturedValue.ofObject(sv.getType(), fields);
    }

    if (sv.getElements() != null) {
      List<CapturedValue> elements = new ArrayList<>();
      for (SerializedValue element : sv.getElements()) {
        elements.add(toCapturedValue(element));
      }
      return CapturedValue.ofCollection(
          sv.getType(), elements, sv.getSize() != null ? sv.getSize() : elements.size());
    }

    if (sv.getEntries() != null) {
      List<CapturedValue.MapEntry> entries = new ArrayList<>();
      for (SerializedValue[] entry : sv.getEntries()) {
        if (entry.length >= 2) {
          entries.add(
              new CapturedValue.MapEntry(toCapturedValue(entry[0]), toCapturedValue(entry[1])));
        }
      }
      return CapturedValue.ofMap(
          sv.getType(), entries, sv.getSize() != null ? sv.getSize() : entries.size());
    }

    return CapturedValue.ofNull(sv.getType());
  }
}
