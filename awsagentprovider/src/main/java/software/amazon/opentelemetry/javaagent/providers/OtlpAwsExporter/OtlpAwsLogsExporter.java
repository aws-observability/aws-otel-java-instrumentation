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

package software.amazon.opentelemetry.javaagent.providers.OtlpAwsExporter;

import io.opentelemetry.exporter.internal.otlp.logs.LogsRequestMarshaler;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporterBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

public class OtlpAwsLogsExporter extends AbstractOtlpAwsExporter<LogRecordData>
    implements LogRecordExporter {
  private static final String SERVICE_NAME = "logs";

  private final OtlpHttpLogRecordExporterBuilder parentExporterBuilder;
  private final OtlpHttpLogRecordExporter parentExporter;
  private final String logGroup;
  private final String logStream;

  static OtlpAwsLogsExporter getDefault(String endpoint, String logGroup, String logStream) {
    return new OtlpAwsLogsExporter(endpoint, logGroup, logStream);
  }

  static OtlpAwsLogsExporter create(
      OtlpHttpLogRecordExporter parent, String endpoint, String logGroup, String logStream) {
    return new OtlpAwsLogsExporter(parent, endpoint, logGroup, logStream);
  }

  OtlpAwsLogsExporter(String endpoint, String logGroup, String logStream) {
    this(null, endpoint, logGroup, logStream);
  }

  OtlpAwsLogsExporter(
      OtlpHttpLogRecordExporter parentExporter,
      String endpoint,
      String logGroup,
      String logStream) {
    super(endpoint);

    if (logGroup == null || logStream == null) {
      throw new IllegalArgumentException("logGroup and logStream must not be null");
    }

    Supplier<Map<String, String>> logsHeader = new LogsHeaderSupplier();

    this.logGroup = logGroup;
    this.logStream = logStream;

    if (parentExporter == null) {
      this.parentExporterBuilder =
          OtlpHttpLogRecordExporter.builder()
              .setMemoryMode(MemoryMode.IMMUTABLE_DATA)
              .setEndpoint(endpoint)
              .setHeaders(logsHeader);
      this.parentExporter = this.parentExporterBuilder.build();
      return;
    }

    this.parentExporterBuilder =
        parentExporter.toBuilder()
            .setMemoryMode(MemoryMode.IMMUTABLE_DATA)
            .setEndpoint(endpoint)
            .setHeaders(logsHeader);

    this.parentExporter = this.parentExporterBuilder.build();
  }

  @Override
  public CompletableResultCode export(@Nonnull Collection<LogRecordData> logs) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      LogsRequestMarshaler.create(logs).writeBinaryTo(buffer);
      this.data.set(buffer.toByteArray());
      return this.parentExporter.export(logs);
    } catch (IOException e) {
      return CompletableResultCode.ofFailure();
    }
  }

  @Override
  public CompletableResultCode flush() {
    return this.parentExporter.flush();
  }

  @Override
  public CompletableResultCode shutdown() {
    return this.parentExporter.shutdown();
  }

  @Override
  String serviceName() {
    return SERVICE_NAME;
  }

  private final class LogsHeaderSupplier implements Supplier<Map<String, String>> {
    private static final String LOG_GROUP_HEADER = "x-aws-log-group";
    private static final String LOG_STREAM_HEADER = "x-aws-log-stream";

    @Override
    public Map<String, String> get() {
      Map<String, String> headers = OtlpAwsLogsExporter.this.authSupplier.get();
      headers.put(LOG_GROUP_HEADER, OtlpAwsLogsExporter.this.logGroup);
      headers.put(LOG_STREAM_HEADER, OtlpAwsLogsExporter.this.logStream);

      return headers;
    }
  }
}
