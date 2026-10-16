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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.traces;

import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.StringJoiner;
import javax.annotation.Nonnull;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.BaseOtlpAwsExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.CompressionMethod;

/**
 * This exporter extends the functionality of the OtlpHttpSpanExporter to allow spans to be exported
 * to the X-Ray OTLP endpoint ({@code https://xray.[AWSRegion].amazonaws.com/v1/traces} or {@code
 * https://xray.[AWSRegion].amazonaws.com.cn/v1/traces}). Utilizes the AWS SDK library to sign and
 * directly inject SigV4 Authentication to the exported request's headers. <a
 * href="https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-OTLPEndpoint.html">...</a>
 */
public final class OtlpAwsSpanExporter extends BaseOtlpAwsExporter implements SpanExporter {
  private final OtlpHttpSpanExporterBuilder parentExporterBuilder;
  private final OtlpHttpSpanExporter parentExporter;

  static OtlpAwsSpanExporter getDefault(String endpoint) {
    return OtlpAwsSpanExporterBuilder.create(OtlpHttpSpanExporter.builder(), endpoint).build();
  }

  static OtlpAwsSpanExporter create(
      OtlpHttpSpanExporterBuilder parent,
      String endpoint,
      CompressionMethod compression,
      String awsRegion,
      String awsService,
      AwsCredentialsProvider credentialsProvider) {
    return new OtlpAwsSpanExporter(
        parent, endpoint, compression, awsRegion, awsService, credentialsProvider);
  }

  private OtlpAwsSpanExporter(
      OtlpHttpSpanExporterBuilder parentExporterBuilder,
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
  public CompletableResultCode export(@Nonnull Collection<SpanData> spans) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      TraceRequestMarshaler.create(spans).writeBinaryTo(buffer);
      this.data.set(buffer);
      return this.parentExporter.export(spans);
    } catch (IOException | IllegalStateException e) {
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
    StringJoiner joiner = new StringJoiner(", ", "OtlpAwsSpanExporter{", "}");
    joiner.add("endpoint=" + this.endpoint);
    joiner.add("awsRegion=" + this.awsRegion);
    joiner.add("awsService=" + serviceName());
    joiner.add("compression=" + getCompression());
    joiner.add("memoryMode=" + MemoryMode.IMMUTABLE_DATA);
    return joiner.toString();
  }
}
