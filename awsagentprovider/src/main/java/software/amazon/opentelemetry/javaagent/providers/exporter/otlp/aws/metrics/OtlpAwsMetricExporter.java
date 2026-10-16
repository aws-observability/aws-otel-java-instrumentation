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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.metrics;

import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.StringJoiner;
import javax.annotation.Nonnull;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.BaseOtlpAwsExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.CompressionMethod;

/**
 * OTLP/HTTP metrics exporter that signs requests to the CloudWatch OTLP metrics endpoint with SigV4
 * service {@code monitoring}.
 */
public final class OtlpAwsMetricExporter extends BaseOtlpAwsExporter implements MetricExporter {
  private final OtlpHttpMetricExporterBuilder parentExporterBuilder;
  private final OtlpHttpMetricExporter parentExporter;

  static OtlpAwsMetricExporter getDefault(String endpoint) {
    return OtlpAwsMetricExporterBuilder.create(OtlpHttpMetricExporter.builder(), endpoint).build();
  }

  static OtlpAwsMetricExporter create(
      OtlpHttpMetricExporterBuilder parent,
      String endpoint,
      CompressionMethod compression,
      String awsRegion,
      String awsService,
      AwsCredentialsProvider credentialsProvider) {
    return new OtlpAwsMetricExporter(
        parent, endpoint, compression, awsRegion, awsService, credentialsProvider);
  }

  private OtlpAwsMetricExporter(
      OtlpHttpMetricExporterBuilder parentExporterBuilder,
      String endpoint,
      CompressionMethod compression,
      String awsRegion,
      String awsService,
      AwsCredentialsProvider credentialsProvider) {
    super(endpoint, compression, awsRegion, awsService, credentialsProvider);

    // IMMUTABLE_DATA is required for correctness, not chosen for performance. export() signs the
    // bytes produced by MetricsRequestMarshaler, while the parent exporter serializes the metrics
    // again for the actual request. Under the default REUSABLE_DATA the parent uses
    // LowAllocationMetricsRequestMarshaler, which groups metrics by instrumentation scope in a
    // differently sized identity-keyed map and so can emit the scope blocks in a different order.
    // The payloads stay semantically equal but differ byte-wise, the signed SHA-256 no longer
    // matches the request body, and CloudWatch rejects the export with 403 SignatureDoesNotMatch.
    // Pinning IMMUTABLE_DATA puts both serializations on the same marshaler. The span and log
    // exporters pin it for the same reason.
    this.parentExporterBuilder =
        parentExporterBuilder
            .setMemoryMode(MemoryMode.IMMUTABLE_DATA)
            .setEndpoint(endpoint)
            .setHeaders(this.headerSupplier);
    this.parentExporter = this.parentExporterBuilder.build();
  }

  @Override
  public CompletableResultCode export(@Nonnull Collection<MetricData> metrics) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      MetricsRequestMarshaler.create(metrics).writeBinaryTo(buffer);
      this.data.set(buffer);
      return this.parentExporter.export(metrics);
    } catch (IOException | IllegalStateException e) {
      return CompletableResultCode.ofFailure();
    }
  }

  @Override
  public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
    return this.parentExporter.getAggregationTemporality(instrumentType);
  }

  @Override
  public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
    return this.parentExporter.getDefaultAggregation(instrumentType);
  }

  @Override
  public MemoryMode getMemoryMode() {
    return this.parentExporter.getMemoryMode();
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
    StringJoiner joiner = new StringJoiner(", ", "OtlpAwsMetricExporter{", "}");
    joiner.add("endpoint=" + this.endpoint);
    joiner.add("awsRegion=" + this.awsRegion);
    joiner.add("awsService=" + serviceName());
    joiner.add("compression=" + getCompression());
    joiner.add("memoryMode=" + getMemoryMode());
    return joiner.toString();
  }
}
