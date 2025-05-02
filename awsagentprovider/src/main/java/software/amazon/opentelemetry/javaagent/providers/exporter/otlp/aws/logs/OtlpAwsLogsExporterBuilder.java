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

public class OtlpAwsLogsExporterBuilder {
  private final OtlpHttpLogRecordExporter parentExporter;
  private final String endpoint;

  public static OtlpAwsLogsExporterBuilder create(
      OtlpHttpLogRecordExporter parentExporter, String endpoint) {
    return new OtlpAwsLogsExporterBuilder(parentExporter, endpoint);
  }

  public static OtlpAwsLogsExporter getDefault(String endpoint) {
    return OtlpAwsLogsExporter.getDefault(endpoint);
  }

  public OtlpAwsLogsExporter build() {
    return OtlpAwsLogsExporter.create(this.parentExporter, this.endpoint);
  }

  private OtlpAwsLogsExporterBuilder(OtlpHttpLogRecordExporter parentExporter, String endpoint) {
    this.parentExporter = requireNonNull(parentExporter, "Must set a parentExporter");
    this.endpoint = requireNonNull(endpoint, "Must set an endpoint");
  }
}
