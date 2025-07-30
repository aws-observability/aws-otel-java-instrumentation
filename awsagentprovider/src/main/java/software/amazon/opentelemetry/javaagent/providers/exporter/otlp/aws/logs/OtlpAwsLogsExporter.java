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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.logs;

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
import java.util.StringJoiner;
import javax.annotation.Nonnull;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.BaseOtlpAwsExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.CompressionMethod;

/**
 * This exporter extends the functionality of the OtlpHttpLogsRecordExporter to allow logs to be
 * exported to the CloudWatch Logs OTLP endpoint https://logs.[AWSRegion].amazonaws.com/v1/logs.
 * Utilizes the AWSSDK library to sign and directly inject SigV4 Authentication to the exported
 * request's headers. Also injects x-aws-log-group and x-aws-log-stream headers as per
 * documentation: "<a
 * href="https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-OTLPEndpoint.html">...</a>">
 */
public final class OtlpAwsLogsExporter extends BaseOtlpAwsExporter implements LogRecordExporter {
  private final OtlpHttpLogRecordExporterBuilder parentExporterBuilder;
  private final OtlpHttpLogRecordExporter parentExporter;
  private final CompressionMethod compression;

  static OtlpAwsLogsExporter getDefault(String endpoint) {
    return new OtlpAwsLogsExporter(
        OtlpHttpLogRecordExporter.getDefault(), endpoint, CompressionMethod.NONE);
  }

  static OtlpAwsLogsExporter create(
      OtlpHttpLogRecordExporter parent, String endpoint, CompressionMethod compression) {
    return new OtlpAwsLogsExporter(parent, endpoint, compression);
  }

  private OtlpAwsLogsExporter(
      OtlpHttpLogRecordExporter parentExporter, String endpoint, CompressionMethod compression) {
    super(endpoint);

    this.parentExporterBuilder =
        parentExporter.toBuilder()
            .setMemoryMode(MemoryMode.IMMUTABLE_DATA)
            .setEndpoint(endpoint)
            .setHeaders(this.headerSupplier);

    this.parentExporter = this.parentExporterBuilder.build();
    this.compression = compression;
  }

  /**
   * Overrides the upstream implementation of export. All behaviors are the same except if the
   * endpoint is an CloudWatch Logs OTLP endpoint, we will sign the request with SigV4 in headers
   * before sending it to the endpoint. Otherwise, we will skip signing.
   */
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
  public String toString() {
    StringJoiner joiner = new StringJoiner(", ", "OtlpAwsLogsExporter{", "}");
    joiner.add(this.parentExporterBuilder.toString());
    joiner.add("memoryMode=" + MemoryMode.IMMUTABLE_DATA);
    return joiner.toString();
  }

  @Override
  public String serviceName() {
    return "logs";
  }

  @Override
  public CompressionMethod getCompression() {
    return this.compression;
  }
}
