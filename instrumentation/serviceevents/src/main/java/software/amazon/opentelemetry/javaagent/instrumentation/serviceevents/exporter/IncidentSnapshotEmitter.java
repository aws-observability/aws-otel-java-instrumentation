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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.IncidentMetadata;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.IncidentSnapshotRecordBuilder;
import software.amazon.opentelemetry.serviceevents.CallPathEntry;
import software.amazon.opentelemetry.serviceevents.IncidentSnapshotEmitterBridge;

/**
 * Agent-classloader implementation of {@link IncidentSnapshotEmitterBridge}. Invoked synchronously
 * from {@code ServiceEventsDataStore.recordPotentialIncident} when byte-instrumentation mode is
 * enabled.
 *
 * <p>Builds an IncidentSnapshot record via {@link IncidentSnapshotRecordBuilder} with the captured
 * call path (Python-parity schema) and hands it to the OTLP emitter. Relies on {@link
 * ServiceEventsOtlpEmitter} routing through the {@code BatchLogRecordProcessor} for batching, so no
 * collector-level flush loop is needed.
 */
public class IncidentSnapshotEmitter implements IncidentSnapshotEmitterBridge {

  private static final Logger logger = Logger.getLogger(IncidentSnapshotEmitter.class.getName());

  private final IncidentSnapshotRecordBuilder recordBuilder;
  private final ServiceEventsOtlpEmitter otlpEmitter;

  public IncidentSnapshotEmitter(
      IncidentSnapshotRecordBuilder recordBuilder, ServiceEventsOtlpEmitter otlpEmitter) {
    this.recordBuilder = recordBuilder;
    this.otlpEmitter = otlpEmitter;
  }

  @Override
  public void emitIncident(
      String snapshotId,
      String triggerType,
      String severity,
      String threadName,
      long startTimeNs,
      long endTimeNs,
      String route,
      String method,
      String operation,
      int statusCode,
      double durationMs,
      String exceptionType,
      String exceptionMessage,
      String stackTrace,
      String traceId,
      String spanId,
      List<CallPathEntry> callPath) {
    try {
      List<Map<String, Object>> serializedCallPath = serializeCallPath(callPath);

      IncidentSnapshotRecordBuilder.Inputs inputs =
          new IncidentSnapshotRecordBuilder.Inputs(
              snapshotId,
              severity,
              triggerType,
              operation,
              route,
              method,
              durationMs,
              statusCode,
              exceptionType,
              exceptionMessage,
              stackTrace,
              traceId,
              spanId);

      long timestamp = System.currentTimeMillis();
      Map<String, Object> record = recordBuilder.build(inputs, serializedCallPath, timestamp);

      // Adaptive sampling boost: mark this operation "hot" so subsequent
      // function-call records inside it are 100%-sampled for the next
      // hotEndpointCycles flush rounds. No-op when sampling mode != "adaptive".
      //
      // Placed AFTER recordBuilder.build() succeeds but BEFORE the emit:
      //   - build/serializeCallPath failure → caught below, no hot-mark
      //     (avoids "phantom incident" boost when no record was actually
      //     produced).
      //   - null otlpEmitter (file-export mode, test harness) → still
      //     fires (the incident was successfully recorded locally).
      //   - emit throws (network blip, serialization error in transport)
      //     → still fires (rate-limit/dedup slot was consumed upstream;
      //     in-process feedback loop should activate).
      // Fired post-rate-limit/post-dedup so suppressed incidents don't mark
      // hot — matches Python/JS placement.
      software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore.markOperationHot(
          operation);

      if (otlpEmitter != null) {
        IncidentMetadata meta =
            new IncidentMetadata(
                threadName,
                startTimeNs,
                endTimeNs,
                route,
                method,
                operation,
                statusCode,
                durationMs,
                triggerType,
                severity,
                snapshotId,
                exceptionType,
                exceptionMessage,
                stackTrace,
                traceId,
                spanId);
        otlpEmitter.emitIncidentSnapshot(record, meta);
      }
    } catch (Throwable t) {
      logger.log(Level.WARNING, "Failed to emit incident snapshot: " + t.getMessage(), t);
    }
  }

  private static List<Map<String, Object>> serializeCallPath(List<CallPathEntry> callPath) {
    if (callPath == null || callPath.isEmpty()) {
      return new ArrayList<>();
    }
    List<Map<String, Object>> out = new ArrayList<>(callPath.size());
    for (CallPathEntry entry : callPath) {
      Map<String, Object> m = new LinkedHashMap<>();
      // Substitute "" for null because downstream OTLP conversion (mapToValue) uses
      // ArrayDeque#push, which rejects null children.
      m.put("function_name", entry.functionId != null ? entry.functionId : "");
      m.put("caller_function_name", entry.caller != null ? entry.caller : "");
      m.put("duration_ns", entry.durationNs);
      m.put("error", entry.error);
      m.put("is_async", entry.isAsync);
      out.add(m);
    }
    return out;
  }
}
