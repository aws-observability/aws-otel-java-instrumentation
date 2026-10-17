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

import static java.util.Objects.requireNonNull;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporterBuilder;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.CompressionMethod;

/** Builder for {@link OtlpAwsLogRecordExporter}. */
public class OtlpAwsLogRecordExporterBuilder {
  private final OtlpHttpLogRecordExporterBuilder parentExporterBuilder;
  private final String endpoint;
  private String compression;
  private String awsRegion;
  private String awsService = "logs";
  private AwsCredentialsProvider credentialsProvider;

  public static OtlpAwsLogRecordExporterBuilder create(
      OtlpHttpLogRecordExporter parentExporter, String endpoint) {
    return create(
        requireNonNull(parentExporter, "Must set a parentExporter").toBuilder(), endpoint);
  }

  public static OtlpAwsLogRecordExporterBuilder create(
      OtlpHttpLogRecordExporterBuilder parentExporterBuilder, String endpoint) {
    return new OtlpAwsLogRecordExporterBuilder(parentExporterBuilder, endpoint);
  }

  public static OtlpAwsLogRecordExporter getDefault(String endpoint) {
    return OtlpAwsLogRecordExporter.getDefault(endpoint);
  }

  public OtlpAwsLogRecordExporterBuilder setCompression(String compression) {
    this.compression = compression;
    return this;
  }

  /** Sets the signing region, overriding region settings and endpoint inference. */
  public OtlpAwsLogRecordExporterBuilder setAwsRegion(String awsRegion) {
    this.awsRegion = requireNonNull(awsRegion, "awsRegion");
    return this;
  }

  /** Sets the AWS signing service. Defaults to {@code logs}. */
  public OtlpAwsLogRecordExporterBuilder setAwsService(String awsService) {
    requireNonNull(awsService, "awsService");
    if (awsService.trim().isEmpty()) {
      throw new IllegalArgumentException("awsService must not be empty");
    }
    this.awsService = awsService;
    return this;
  }

  /** Sets a credential provider; otherwise uses the AWS SDK default credential chain. */
  public OtlpAwsLogRecordExporterBuilder setCredentialsProvider(
      AwsCredentialsProvider credentialsProvider) {
    this.credentialsProvider = requireNonNull(credentialsProvider, "credentialsProvider");
    return this;
  }

  public OtlpAwsLogRecordExporter build() {
    CompressionMethod compression = CompressionMethod.NONE;
    if (this.compression != null && "gzip".equalsIgnoreCase(this.compression)) {
      compression = CompressionMethod.GZIP;
    }
    if (this.compression != null) {
      this.parentExporterBuilder.setCompression(
          compression == CompressionMethod.GZIP ? "gzip" : "none");
    }
    return OtlpAwsLogRecordExporter.create(
        this.parentExporterBuilder,
        this.endpoint,
        compression,
        this.awsRegion,
        this.awsService,
        this.credentialsProvider);
  }

  private OtlpAwsLogRecordExporterBuilder(
      OtlpHttpLogRecordExporterBuilder parentExporterBuilder, String endpoint) {
    this.parentExporterBuilder =
        requireNonNull(parentExporterBuilder, "Must set a parentExporterBuilder");
    this.endpoint = requireNonNull(endpoint, "Must set an endpoint");
  }
}
