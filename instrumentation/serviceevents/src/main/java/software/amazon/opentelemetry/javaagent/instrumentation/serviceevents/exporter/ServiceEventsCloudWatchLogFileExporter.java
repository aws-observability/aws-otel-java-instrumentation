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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.common.ValueType;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CloudWatch-faithful local-testing log-record exporter.
 *
 * <p>Writes one NDJSON line per {@link LogRecordData} to the configured output file. Shape matches
 * what users see in CloudWatch Logs Insights (not the OTLP wire envelope): top-level {@code
 * eventName}, {@code timeUnixNano}, {@code attributes}, {@code body}, plus a nested {@code
 * resource} map.
 *
 * <p>Enabled via {@code OTEL_AWS_SERVICE_EVENTS_OUTPUT_FILE}. See {@code
 * SERVICE_EVENTS_LOCAL_FILE_FORMAT.md} at the monorepo root for the full spec.
 */
public class ServiceEventsCloudWatchLogFileExporter implements LogRecordExporter {

  private static final Logger logger =
      Logger.getLogger(ServiceEventsCloudWatchLogFileExporter.class.getName());
  private static final ObjectMapper MAPPER =
      new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

  private final ServiceEventsFileWriter writer;
  private final AtomicBoolean shutdownCalled = new AtomicBoolean(false);

  public ServiceEventsCloudWatchLogFileExporter(String outputFilePath) {
    this.writer = ServiceEventsFileWriter.acquire(outputFilePath);
  }

  @Override
  public CompletableResultCode export(Collection<LogRecordData> batch) {
    if (shutdownCalled.get() || writer == null) {
      return CompletableResultCode.ofFailure();
    }
    if (batch.isEmpty()) {
      return CompletableResultCode.ofSuccess();
    }
    List<String> lines = new ArrayList<>(batch.size());
    for (LogRecordData record : batch) {
      try {
        lines.add(MAPPER.writeValueAsString(serializeLogRecord(record)));
      } catch (Exception e) {
        logger.log(Level.WARNING, "ServiceEvents: failed to serialize LogRecord to JSON", e);
        return CompletableResultCode.ofFailure();
      }
    }
    try {
      writer.writeLines(lines);
      writer.flush();
      return CompletableResultCode.ofSuccess();
    } catch (Exception e) {
      logger.log(Level.WARNING, "ServiceEvents: failed to write log records to file", e);
      return CompletableResultCode.ofFailure();
    }
  }

  @Override
  public CompletableResultCode flush() {
    if (writer == null) {
      return CompletableResultCode.ofFailure();
    }
    return writer.flush();
  }

  @Override
  public CompletableResultCode shutdown() {
    // compareAndSet gives exactly-once release semantics: concurrent shutdown() calls can't both
    // pass the guard and double-release the (potentially shared) ServiceEventsFileWriter.
    if (!shutdownCalled.compareAndSet(false, true)) {
      return CompletableResultCode.ofSuccess();
    }
    if (writer == null) {
      return CompletableResultCode.ofSuccess();
    }
    return writer.release();
  }

  /**
   * Build the flat CloudWatch-shape JSON representation for one log record. Package-private for
   * test access.
   */
  static Map<String, Object> serializeLogRecord(LogRecordData record) {
    Map<String, Object> out = new LinkedHashMap<>();
    String eventName = record.getEventName();
    out.put("eventName", eventName == null ? "" : eventName);
    out.put("timeUnixNano", record.getTimestampEpochNanos());

    // attributes
    Map<String, Object> attrs = new LinkedHashMap<>();
    record
        .getAttributes()
        .forEach((AttributeKey<?> key, Object value) -> attrs.put(key.getKey(), value));
    out.put("attributes", attrs);

    // body — unwrap the AnyValue tree into plain JSON-serializable primitives
    Value<?> body = record.getBodyValue();
    Object unwrappedBody = body == null ? new LinkedHashMap<>() : unwrapValue(body);
    if (unwrappedBody == null) {
      unwrappedBody = new LinkedHashMap<>();
    }
    out.put("body", unwrappedBody);

    // resource — nested map of resource attributes
    Map<String, Object> resource = new LinkedHashMap<>();
    record
        .getResource()
        .getAttributes()
        .forEach((AttributeKey<?> key, Object value) -> resource.put(key.getKey(), value));
    out.put("resource", resource);

    // trace context — present only when the LogRecord carries a valid SpanContext
    // (e.g., IncidentSnapshot correlated to an active trace).
    if (record.getSpanContext() != null && record.getSpanContext().isValid()) {
      out.put("traceId", record.getSpanContext().getTraceId());
      out.put("spanId", record.getSpanContext().getSpanId());
      out.put("flags", record.getSpanContext().getTraceFlags().asByte() & 0xff);
    }

    // Severity — kept out of the flat shape since ServiceEvents is event-only
    // (no severity). Read it here just to avoid the unused-method warning
    // and make the intent explicit.
    Severity ignored = record.getSeverity();
    if (ignored == null) {
      // Intentionally ignored — ServiceEvents signals don't carry severity.
    }

    return out;
  }

  /** Recursively unwrap OTel {@link Value} into plain Java objects. */
  static Object unwrapValue(Value<?> value) {
    if (value == null) {
      return null;
    }
    ValueType type = value.getType();
    switch (type) {
      case STRING:
      case BOOLEAN:
      case LONG:
      case DOUBLE:
      case BYTES:
        return value.getValue();
      case ARRAY:
        {
          @SuppressWarnings("unchecked")
          List<Value<?>> list = (List<Value<?>>) value.getValue();
          List<Object> out = new ArrayList<>(list.size());
          for (Value<?> element : list) {
            out.add(unwrapValue(element));
          }
          return out;
        }
      case KEY_VALUE_LIST:
        {
          @SuppressWarnings("unchecked")
          List<io.opentelemetry.api.common.KeyValue> kvList =
              (List<io.opentelemetry.api.common.KeyValue>) value.getValue();
          Map<String, Object> map = new LinkedHashMap<>();
          for (io.opentelemetry.api.common.KeyValue kv : kvList) {
            map.put(kv.getKey(), unwrapValue(kv.getValue()));
          }
          return map;
        }
      default:
        return value.asString();
    }
  }

  /** Only here to suppress a "never used" lint on {@link Locale}; imports kept stable. */
  @SuppressWarnings("unused")
  private static final Locale _LOCALE = Locale.ROOT;
}
