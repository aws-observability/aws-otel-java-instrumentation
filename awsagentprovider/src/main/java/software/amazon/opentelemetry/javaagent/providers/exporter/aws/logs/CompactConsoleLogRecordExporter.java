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

import io.opentelemetry.api.common.Value;
import io.opentelemetry.exporter.logging.SystemOutLogRecordExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A compact console log exporter that changes the functionality of OpenTelemetry's {@link
 * SystemOutLogRecordExporter} by removing whitespace around JSON delimiters in the printed log
 * output.
 *
 * <p>This exporter uses the same formatting logic as {@code SystemOutLogRecordExporter} but applies
 * compact formatting by removing spaces around characters like {@code {}[]:,} to produce more
 * condensed log output.
 */
public class CompactConsoleLogRecordExporter implements LogRecordExporter {
  private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_DATE_TIME;
  private final AtomicBoolean isShutdown = new AtomicBoolean();

  private final LogRecordExporter parentExporter;

  public CompactConsoleLogRecordExporter() {
    this.parentExporter = SystemOutLogRecordExporter.create();
  }

  @Override
  public CompletableResultCode export(Collection<LogRecordData> logs) {
    if (this.isShutdown.get()) {
      return CompletableResultCode.ofFailure();
    } else {
      StringBuilder stringBuilder = new StringBuilder(60);

      for (LogRecordData logRecord : logs) {
        stringBuilder.setLength(0);
        formatLog(stringBuilder, logRecord);
        System.out.println(stringBuilder);
      }

      return CompletableResultCode.ofSuccess();
    }
  }

  @Override
  public CompletableResultCode flush() {
    return this.parentExporter.flush();
  }

  /**
   * Shuts down the exporter. This method is copied and modified from
   * SystemOutLogRecordExporter.shutdown().
   *
   * <p>See: <a
   * href="https://github.com/open-telemetry/opentelemetry-java/blob/5ab0a65675e5a06d13b293a758ef495d797e6d04/exporters/logging/src/main/java/io/opentelemetry/exporter/logging/SystemOutLogRecordExporter.java#L93">...</a>
   */
  @Override
  public CompletableResultCode shutdown() {
    if (!this.isShutdown.compareAndSet(false, true)) {
      System.out.println("Calling shutdown() multiple times.");
    }
    return CompletableResultCode.ofSuccess();
  }

  /**
   * Formats log record data into a compact string representation. This method is copied from
   * SystemOutLogRecordExporter.formatLog() and modified to apply compact formatting by removing
   * whitespace around JSON delimiters.
   *
   * <p>See: <a
   * href="https://github.com/open-telemetry/opentelemetry-java/blob/5ab0a65675e5a06d13b293a758ef495d797e6d04/exporters/logging/src/main/java/io/opentelemetry/exporter/logging/SystemOutLogRecordExporter.java#L66">...</a>
   */
  static void formatLog(StringBuilder stringBuilder, LogRecordData log) {
    InstrumentationScopeInfo instrumentationScopeInfo = log.getInstrumentationScopeInfo();
    Value<?> body = log.getBodyValue();
    stringBuilder
        .append(
            ISO_FORMAT.format(
                Instant.ofEpochMilli(TimeUnit.NANOSECONDS.toMillis(log.getTimestampEpochNanos()))
                    .atZone(ZoneOffset.UTC)))
        .append(" ")
        .append(log.getSeverity())
        .append(" '")
        .append(body == null ? "" : body.asString())
        .append("' : ")
        .append(log.getSpanContext().getTraceId())
        .append(" ")
        .append(log.getSpanContext().getSpanId())
        .append(" [scopeInfo: ")
        .append(instrumentationScopeInfo.getName())
        .append(":")
        .append(
            instrumentationScopeInfo.getVersion() == null
                ? ""
                : instrumentationScopeInfo.getVersion())
        .append("] ")
        .append(log.getAttributes());

    String compact = stringBuilder.toString().replaceAll("\\s*([{}\\[\\]:,])\\s*", "$1");
    stringBuilder.setLength(0);
    stringBuilder.append(compact);
  }
}
