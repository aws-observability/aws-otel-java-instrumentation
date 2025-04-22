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

import static java.util.Objects.requireNonNull;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;

public class OtlpAwsLogsExporterBuilder {
  private final OtlpHttpLogRecordExporter parentExporter;
  private final String endpoint;
  private final String logGroup;
  private final String logStream;

  public static OtlpAwsLogsExporter create(
      OtlpHttpLogRecordExporter parentExporter,
      String endpoint,
      String logGroup,
      String logStream) {
    return new OtlpAwsLogsExporter(parentExporter, endpoint, logGroup, logStream);
  }

  public static OtlpAwsLogsExporter getDefault(String endpoint, String logGroup, String logStream) {
    return OtlpAwsLogsExporter.getDefault(endpoint, logGroup, logStream);
  }

  public OtlpAwsLogsExporter build() {
    return OtlpAwsLogsExporter.create(
        this.parentExporter, this.endpoint, this.logGroup, this.logStream);
  }

  private OtlpAwsLogsExporterBuilder(
      OtlpHttpLogRecordExporter parentExporter,
      String endpoint,
      String logGroup,
      String logStream) {
    this.parentExporter = requireNonNull(parentExporter, "Must set a parentExporter");
    this.endpoint = requireNonNull(endpoint, "Must set an endpoint");
    this.logGroup = requireNonNull(logGroup, "Must set a logGroup");
    this.logStream = requireNonNull(logStream, "Must set a logStream");
  }
}
