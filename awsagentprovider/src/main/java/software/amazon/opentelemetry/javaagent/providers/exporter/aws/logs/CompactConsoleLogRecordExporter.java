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

package software.amazon.opentelemetry.javaagent.providers.exporter.aws.logs;

/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modifications Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.opentelemetry.exporter.internal.otlp.IncubatingUtil;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.resources.Resource;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link LogRecordExporter} that prints {@link LogRecordData} to standard out based on upstream's
 * implementation of SystemOutLogRecordExporter, see: <a
 * href="https://github.com/open-telemetry/opentelemetry-java/blob/5ab0a65675e5a06d13b293a758ef495d797e6d04/exporters/logging/src/main/java/io/opentelemetry/exporter/logging/SystemOutLogRecordExporter.java#L66">...</a>
 */
@SuppressWarnings("SystemOut")
public class CompactConsoleLogRecordExporter implements LogRecordExporter {
  private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_INSTANT;
  private static final ObjectMapper MAPPER =
      new ObjectMapper().disable(SerializationFeature.INDENT_OUTPUT);
  private final AtomicBoolean isShutdown = new AtomicBoolean();
  private final PrintStream printStream;

  public CompactConsoleLogRecordExporter() {
    this(System.out);
  }

  public CompactConsoleLogRecordExporter(PrintStream printStream) {
    this.printStream = printStream;
  }

  @Override
  public CompletableResultCode export(Collection<LogRecordData> logs) {
    if (isShutdown.get()) {
      return CompletableResultCode.ofFailure();
    }

    for (LogRecordData log : logs) {
      this.printStream.println(this.toCompactJson(log));
      this.printStream.flush();
    }
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode flush() {
    this.printStream.flush();
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    if (!this.isShutdown.compareAndSet(false, true)) {
      this.printStream.println("Calling shutdown() multiple times.");
    }
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public String toString() {
    return "CompactConsoleLogRecordExporter{}";
  }

  /**
   * Converts OpenTelemetry log data to compact JSON format. OTel Java's SystemOutLogRecordExporter
   * uses a concise text format, this implementation outputs a compact JSON representation based on
   * OTel JavaScript's _exportInfo: <a
   * href="https://github.com/open-telemetry/opentelemetry-js/blob/09bf31eb966bab627e76a6c5c05c6e51ccd2f387/experimental/packages/sdk-logs/src/export/ConsoleLogRecordExporter.ts#L58">...</a>
   *
   * <p>Example output:
   *
   * <pre>
   *     {"body":"This is a test log","severityNumber":9,"severityText":"INFO","attributes":{},"droppedAttributes":0,"timestamp":"2025-09-30T22:37:56.724Z","observedTimestamp":"2025-09-30T22:37:56.724Z","traceId":"","spanId":"","traceFlags":0,"resource":{}}
   * </pre>
   *
   * @param log the log record data to convert
   * @return compact JSON string representation of the log record
   */
  private String toCompactJson(LogRecordData log) {
    LogRecordDataTemplate template = LogRecordDataTemplate.parse(log);

    try {
      return MAPPER.writeValueAsString(template);
    } catch (Exception e) {
      this.printStream.println("Error serializing log record: " + e.getMessage());
      return "{}";
    }
  }

  /** Data object that structures OTel log record data for JSON serialization. */
  @SuppressWarnings("unused")
  private static final class LogRecordDataTemplate {
    @JsonProperty("resource")
    private final ResourceTemplate resourceTemplate;

    @JsonProperty("body")
    private final String body;

    @JsonProperty("severityNumber")
    private final int severityNumber;

    @JsonProperty("severityText")
    private final String severityText;

    @JsonProperty("attributes")
    private final Map<String, String> attributes;

    @JsonProperty("droppedAttributes")
    private final int droppedAttributes;

    @JsonProperty("timestamp")
    private final String timestamp;

    @JsonProperty("observedTimestamp")
    private final String observedTimestamp;

    @JsonProperty("traceId")
    private final String traceId;

    @JsonProperty("spanId")
    private final String spanId;

    @JsonProperty("traceFlags")
    private final int traceFlags;

    @JsonProperty("instrumentationScope")
    private final InstrumentationScopeTemplate instrumentationScope;

    private LogRecordDataTemplate(
        String body,
        int severityNumber,
        String severityText,
        Map<String, String> attributes,
        int droppedAttributes,
        String timestamp,
        String observedTimestamp,
        String traceId,
        String spanId,
        int traceFlags,
        ResourceTemplate resourceTemplate,
        InstrumentationScopeTemplate instrumentationScope) {
      this.resourceTemplate = resourceTemplate;
      this.body = body;
      this.severityNumber = severityNumber;
      this.severityText = severityText;
      this.attributes = attributes;
      this.droppedAttributes = droppedAttributes;
      this.timestamp = timestamp;
      this.observedTimestamp = observedTimestamp;
      this.traceId = traceId;
      this.spanId = spanId;
      this.traceFlags = traceFlags;
      this.instrumentationScope = instrumentationScope;
    }

    private static LogRecordDataTemplate parse(LogRecordData log) {
      // https://github.com/open-telemetry/opentelemetry-java/blob/48684d6d33048030b133b4f6479d45afddcdc313/exporters/otlp/common/src/main/java/io/opentelemetry/exporter/internal/otlp/logs/LogMarshaler.java#L59
      Map<String, String> attributes = new HashMap<>();
      log.getAttributes()
          .forEach((key, value) -> attributes.put(key.getKey(), String.valueOf(value)));

      int attributeSize =
          IncubatingUtil.isExtendedLogRecordData(log)
              ? IncubatingUtil.extendedAttributesSize(log)
              : log.getAttributes().size();

      return new LogRecordDataTemplate(
          log.getBodyValue() != null ? log.getBodyValue().asString() : null,
          log.getSeverity().getSeverityNumber(),
          log.getSeverity().name(),
          attributes,
          log.getTotalAttributeCount() - attributeSize,
          formatTimestamp(log.getTimestampEpochNanos()),
          formatTimestamp(log.getObservedTimestampEpochNanos()),
          log.getSpanContext().isValid() ? log.getSpanContext().getTraceId() : "",
          log.getSpanContext().isValid() ? log.getSpanContext().getSpanId() : "",
          log.getSpanContext().getTraceFlags().asByte(),
          log.getResource() != null
              ? ResourceTemplate.parse(log.getResource())
              : new ResourceTemplate(new HashMap<>(), ""),
          log.getInstrumentationScopeInfo() != null
              ? InstrumentationScopeTemplate.parse(log.getInstrumentationScopeInfo())
              : new InstrumentationScopeTemplate("", "", ""));
    }

    private static String formatTimestamp(long nanos) {
      return nanos != 0
          ? ISO_FORMAT.format(
              Instant.ofEpochMilli(TimeUnit.NANOSECONDS.toMillis(nanos)).atZone(ZoneOffset.UTC))
          : null;
    }
  }

  @SuppressWarnings("unused")
  private static final class ResourceTemplate {
    @JsonProperty("attributes")
    private final Map<String, String> attributes;

    @JsonProperty("schemaUrl")
    private final String schemaUrl;

    private ResourceTemplate(Map<String, String> attributes, String schemaUrl) {
      this.attributes = attributes;
      this.schemaUrl = schemaUrl != null ? schemaUrl : "";
    }

    private static ResourceTemplate parse(Resource resource) {
      Map<String, String> attributes = new HashMap<>();
      if (resource == null) {
        return new ResourceTemplate(attributes, "");
      }
      resource
          .getAttributes()
          .forEach((key, value) -> attributes.put(key.getKey(), String.valueOf(value)));
      return new ResourceTemplate(attributes, resource.getSchemaUrl());
    }
  }

  @SuppressWarnings("unused")
  private static final class InstrumentationScopeTemplate {
    @JsonProperty("name")
    private final String name;

    @JsonProperty("version")
    private final String version;

    @JsonProperty("schemaUrl")
    private final String schemaUrl;

    private InstrumentationScopeTemplate(String name, String version, String schemaUrl) {
      this.name = name != null ? name : "";
      this.version = version != null ? version : "";
      this.schemaUrl = schemaUrl != null ? schemaUrl : "";
    }

    private static InstrumentationScopeTemplate parse(InstrumentationScopeInfo scope) {
      if (scope == null) {
        return new InstrumentationScopeTemplate("", "", "");
      }
      return new InstrumentationScopeTemplate(
          scope.getName(), scope.getVersion(), scope.getSchemaUrl());
    }
  }
}
