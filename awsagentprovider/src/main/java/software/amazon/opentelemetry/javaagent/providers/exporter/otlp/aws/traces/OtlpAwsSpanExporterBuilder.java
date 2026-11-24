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

import static java.util.Objects.requireNonNull;
import static software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.OtlpAwsConfigUtil.DEFAULT_TRACES_AWS_SERVICE;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.CompressionMethod;

/** Builder for {@link OtlpAwsSpanExporter}. */
public class OtlpAwsSpanExporterBuilder {
  private final OtlpHttpSpanExporterBuilder parentExporterBuilder;
  private final String endpoint;
  private String compression;
  private String awsRegion;
  private String awsService = DEFAULT_TRACES_AWS_SERVICE;
  private AwsCredentialsProvider credentialsProvider;

  public static OtlpAwsSpanExporterBuilder create(
      OtlpHttpSpanExporter parentExporter, String endpoint) {
    return create(
        requireNonNull(parentExporter, "Must set a parentExporter").toBuilder(), endpoint);
  }

  public static OtlpAwsSpanExporterBuilder create(
      OtlpHttpSpanExporterBuilder parentExporterBuilder, String endpoint) {
    return new OtlpAwsSpanExporterBuilder(parentExporterBuilder, endpoint);
  }

  public static OtlpAwsSpanExporter getDefault(String endpoint) {
    return OtlpAwsSpanExporter.getDefault(endpoint);
  }

  public OtlpAwsSpanExporterBuilder setCompression(String compression) {
    this.compression = compression;
    return this;
  }

  /** Sets the signing region, overriding region settings and endpoint inference. */
  public OtlpAwsSpanExporterBuilder setAwsRegion(String awsRegion) {
    this.awsRegion = requireNonNull(awsRegion, "awsRegion");
    return this;
  }

  /** Sets the AWS signing service. Defaults to {@code xray}. */
  public OtlpAwsSpanExporterBuilder setAwsService(String awsService) {
    requireNonNull(awsService, "awsService");
    if (awsService.trim().isEmpty()) {
      throw new IllegalArgumentException("awsService must not be empty");
    }
    this.awsService = awsService;
    return this;
  }

  /** Sets a credential provider; otherwise uses the AWS SDK default credential chain. */
  public OtlpAwsSpanExporterBuilder setCredentialsProvider(
      AwsCredentialsProvider credentialsProvider) {
    this.credentialsProvider = requireNonNull(credentialsProvider, "credentialsProvider");
    return this;
  }

  public OtlpAwsSpanExporter build() {
    CompressionMethod compression = CompressionMethod.NONE;
    if (this.compression != null && "gzip".equalsIgnoreCase(this.compression)) {
      compression = CompressionMethod.GZIP;
    }
    if (this.compression != null) {
      this.parentExporterBuilder.setCompression(
          compression == CompressionMethod.GZIP ? "gzip" : "none");
    }
    return OtlpAwsSpanExporter.create(
        this.parentExporterBuilder,
        this.endpoint,
        compression,
        this.awsRegion,
        this.awsService,
        this.credentialsProvider);
  }

  private OtlpAwsSpanExporterBuilder(
      OtlpHttpSpanExporterBuilder parentExporterBuilder, String endpoint) {
    this.parentExporterBuilder =
        requireNonNull(parentExporterBuilder, "Must set a parentExporterBuilder");
    this.endpoint = requireNonNull(endpoint, "Must set an endpoint");
  }
}
