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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsOtlpEmitter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.IncidentSnapshotRecordBuilder;
import software.amazon.opentelemetry.serviceevents.MetadataWriterBridge;

/**
 * Lite-mode IncidentSnapshot drainer.
 *
 * <p>In lite mode (bytecode instrumentation off) there is no synchronous emitter path to drive
 * incident emission and no synchronous {@code IncidentSnapshotEmitter} installed by the bytecode
 * path. This drainer fills that gap: it implements {@link MetadataWriterBridge} so {@code
 * ServiceEventsDataStore.recordPotentialIncident} dispatches into an in-memory bounded queue, then
 * a background thread (inherited from {@link BaseCollector}) drains the queue on the configured
 * flush interval and emits each record via the existing OTLP path.
 *
 * <p>The lite snapshot omits {@code call_path} (no bytecode advice ran). Other fields — exception
 * type/message/stack trace, route, status, trace correlation — are populated from the servlet
 * advice and the incident recording path.
 */
public final class LiteIncidentDrainer extends BaseCollector implements MetadataWriterBridge {

  private static final Logger logger = Logger.getLogger(LiteIncidentDrainer.class.getName());

  /** Bounds the in-memory queue. Upstream rate limit caps fault rate, but defend in depth. */
  private static final int MAX_QUEUE_SIZE = 4096;

  /** Bounds the work done per drain tick so a backlog can't pin the daemon thread. */
  private static final int MAX_DRAIN_BATCH = 512;

  private final ConcurrentLinkedQueue<QueuedIncident> queue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger queueSize = new AtomicInteger();
  private final AtomicInteger droppedCount = new AtomicInteger();

  private final IncidentSnapshotRecordBuilder recordBuilder;

  public LiteIncidentDrainer(
      int flushIntervalMs,
      IncidentSnapshotRecordBuilder recordBuilder,
      ServiceEventsOtlpEmitter otlpEmitter) {
    super(flushIntervalMs, "LiteIncidentDrainer", otlpEmitter);
    this.recordBuilder = recordBuilder;
  }

  @Override
  public void writeIncident(
      String threadName,
      long startTimeNs,
      long endTimeNs,
      String route,
      String method,
      int statusCode,
      double durationMs,
      String triggerType,
      String severity,
      String snapshotId,
      String exceptionType,
      String exceptionMessage,
      String stackTrace,
      String traceId,
      String spanId,
      String operation) {
    // Approximate-size bound. Race-tolerant: a few overshoots are fine.
    if (queueSize.get() >= MAX_QUEUE_SIZE) {
      droppedCount.incrementAndGet();
      return;
    }
    queue.offer(
        new QueuedIncident(
            snapshotId,
            severity,
            triggerType,
            operation != null ? operation : method + " " + route,
            route,
            method,
            durationMs,
            statusCode,
            exceptionType,
            exceptionMessage,
            stackTrace,
            traceId,
            spanId,
            startTimeNs,
            endTimeNs));
    queueSize.incrementAndGet();
  }

  /** Drain up to {@link #MAX_DRAIN_BATCH} records and emit each one. Called by BaseCollector. */
  protected void collect() {
    if (queue.isEmpty()) {
      return;
    }

    int dropped = droppedCount.getAndSet(0);
    if (dropped > 0) {
      logger.log(
          Level.WARNING,
          "{0}: dropped {1} incidents because the queue was full (cap={2})",
          new Object[] {name, dropped, MAX_QUEUE_SIZE});
    }

    List<QueuedIncident> batch = new ArrayList<>(Math.min(queue.size(), MAX_DRAIN_BATCH));
    QueuedIncident item;
    while (batch.size() < MAX_DRAIN_BATCH && (item = queue.poll()) != null) {
      queueSize.decrementAndGet();
      batch.add(item);
    }

    long timestamp = System.currentTimeMillis();
    int emitted = 0;
    for (QueuedIncident inc : batch) {
      try {
        IncidentSnapshotRecordBuilder.Inputs inputs =
            new IncidentSnapshotRecordBuilder.Inputs(
                inc.snapshotId,
                inc.severity,
                inc.triggerType,
                inc.operation,
                inc.route,
                inc.method,
                inc.durationMs,
                inc.statusCode,
                inc.exceptionType,
                inc.exceptionMessage,
                inc.stackTrace,
                inc.traceId,
                inc.spanId);
        // Lite mode: no call_path (no bytecode advice).
        Map<String, Object> record = recordBuilder.build(inputs, null, timestamp);
        if (otlpEmitter != null) {
          otlpEmitter.emitIncidentSnapshot(record, inc);
        }
        emitted++;
      } catch (Throwable t) {
        logger.log(Level.WARNING, "LiteIncidentDrainer: failed to emit incident", t);
      }
    }

    if (emitted > 0) {
      logger.fine(name + " emitted " + emitted + " IncidentSnapshot records");
    }
  }

  /** Visible for tests. */
  int queueSize() {
    return queueSize.get();
  }

  /** Visible for tests. */
  int droppedCount() {
    return droppedCount.get();
  }

  /**
   * Captured fields for a queued incident. Mirrors the {@link
   * software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.IncidentMetadata}
   * shape so {@link ServiceEventsOtlpEmitter#emitIncidentSnapshot} can use it as the {@code meta}
   * argument (duck-typed via reflection on field names).
   */
  static final class QueuedIncident
      extends software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models
          .IncidentMetadata {
    QueuedIncident(
        String snapshotId,
        String severity,
        String triggerType,
        String operation,
        String route,
        String method,
        double durationMs,
        int statusCode,
        String exceptionType,
        String exceptionMessage,
        String stackTrace,
        String traceId,
        String spanId,
        long startNs,
        long endNs) {
      super(
          /* threadName= */ "",
          startNs,
          endNs,
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
          spanId,
          /* isPartial= */ false); // lite mode carries no call_path, so timing is never partial
    }
  }
}
