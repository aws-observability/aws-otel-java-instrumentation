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
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.BaseOtlpAwsExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.CompressionMethod;

/**
 * This exporter extends the functionality of the OtlpHttpLogsRecordExporter to allow logs to be
 * exported to the CloudWatch Logs OTLP endpoint ({@code
 * https://logs.[AWSRegion].amazonaws.com/v1/logs} or {@code
 * https://logs.[AWSRegion].amazonaws.com.cn/v1/logs}). Utilizes the AWS SDK library to sign and
 * directly inject SigV4 Authentication to the exported request's headers. Also injects
 * x-aws-log-group and x-aws-log-stream headers as per documentation: "<a
 * href="https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-OTLPEndpoint.html">...</a>">
 */
public final class OtlpAwsLogRecordExporter extends BaseOtlpAwsExporter
    implements LogRecordExporter {
  private final OtlpHttpLogRecordExporterBuilder parentExporterBuilder;
  private final OtlpHttpLogRecordExporter parentExporter;

  static OtlpAwsLogRecordExporter getDefault(String endpoint) {
    return OtlpAwsLogRecordExporterBuilder.create(OtlpHttpLogRecordExporter.builder(), endpoint)
        .build();
  }

  static OtlpAwsLogRecordExporter create(
      OtlpHttpLogRecordExporterBuilder parent,
      String endpoint,
      CompressionMethod compression,
      String awsRegion,
      String awsService,
      AwsCredentialsProvider credentialsProvider) {
    return new OtlpAwsLogRecordExporter(
        parent, endpoint, compression, awsRegion, awsService, credentialsProvider);
  }

  private OtlpAwsLogRecordExporter(
      OtlpHttpLogRecordExporterBuilder parentExporterBuilder,
      String endpoint,
      CompressionMethod compression,
      String awsRegion,
      String awsService,
      AwsCredentialsProvider credentialsProvider) {
    super(endpoint, compression, awsRegion, awsService, credentialsProvider);

    this.parentExporterBuilder =
        parentExporterBuilder
            .setMemoryMode(MemoryMode.IMMUTABLE_DATA)
            .setEndpoint(endpoint)
            .setHeaders(this.headerSupplier);

    this.parentExporter = this.parentExporterBuilder.build();
  }

  /** Signs each OTLP/HTTP request with AWS SigV4 before exporting it. */
  @Override
  public CompletableResultCode export(@Nonnull Collection<LogRecordData> logs) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      LogsRequestMarshaler.create(logs).writeBinaryTo(buffer);
      this.data.set(buffer);
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
    joiner.add("endpoint=" + this.endpoint);
    joiner.add("awsRegion=" + this.awsRegion);
    joiner.add("awsService=" + serviceName());
    joiner.add("compression=" + getCompression());
    joiner.add("memoryMode=" + MemoryMode.IMMUTABLE_DATA);
    return joiner.toString();
  }
}
