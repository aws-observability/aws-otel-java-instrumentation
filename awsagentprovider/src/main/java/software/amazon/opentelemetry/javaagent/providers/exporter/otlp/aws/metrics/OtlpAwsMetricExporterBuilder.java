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

import static java.util.Objects.requireNonNull;

import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.CompressionMethod;

/** Builder for {@link OtlpAwsMetricExporter}. */
public final class OtlpAwsMetricExporterBuilder {
  private final OtlpHttpMetricExporterBuilder parentExporterBuilder;
  private final String endpoint;
  private String compression;
  private String awsRegion;
  private String awsService = "monitoring";
  private AwsCredentialsProvider credentialsProvider;

  public static OtlpAwsMetricExporterBuilder create(
      OtlpHttpMetricExporter parentExporter, String endpoint) {
    return create(
        requireNonNull(parentExporter, "Must set a parentExporter").toBuilder(), endpoint);
  }

  public static OtlpAwsMetricExporterBuilder create(
      OtlpHttpMetricExporterBuilder parentExporterBuilder, String endpoint) {
    return new OtlpAwsMetricExporterBuilder(parentExporterBuilder, endpoint);
  }

  public static OtlpAwsMetricExporter getDefault(String endpoint) {
    return OtlpAwsMetricExporter.getDefault(endpoint);
  }

  public OtlpAwsMetricExporterBuilder setCompression(String compression) {
    this.compression = compression;
    return this;
  }

  /** Sets the signing region, overriding region settings and endpoint inference. */
  public OtlpAwsMetricExporterBuilder setAwsRegion(String awsRegion) {
    this.awsRegion = requireNonNull(awsRegion, "awsRegion");
    return this;
  }

  /** Sets the AWS signing service. Defaults to {@code monitoring}. */
  public OtlpAwsMetricExporterBuilder setAwsService(String awsService) {
    requireNonNull(awsService, "awsService");
    if (awsService.trim().isEmpty()) {
      throw new IllegalArgumentException("awsService must not be empty");
    }
    this.awsService = awsService;
    return this;
  }

  /** Sets a credential provider; otherwise uses the AWS SDK default credential chain. */
  public OtlpAwsMetricExporterBuilder setCredentialsProvider(
      AwsCredentialsProvider credentialsProvider) {
    this.credentialsProvider = requireNonNull(credentialsProvider, "credentialsProvider");
    return this;
  }

  public OtlpAwsMetricExporter build() {
    CompressionMethod compression = CompressionMethod.NONE;
    if (this.compression != null && "gzip".equalsIgnoreCase(this.compression)) {
      compression = CompressionMethod.GZIP;
    }
    if (this.compression != null) {
      this.parentExporterBuilder.setCompression(
          compression == CompressionMethod.GZIP ? "gzip" : "none");
    }
    return OtlpAwsMetricExporter.create(
        this.parentExporterBuilder,
        this.endpoint,
        compression,
        this.awsRegion,
        this.awsService,
        this.credentialsProvider);
  }

  private OtlpAwsMetricExporterBuilder(
      OtlpHttpMetricExporterBuilder parentExporterBuilder, String endpoint) {
    this.parentExporterBuilder =
        requireNonNull(parentExporterBuilder, "Must set a parentExporterBuilder");
    this.endpoint = requireNonNull(endpoint, "Must set an endpoint");
  }
}
