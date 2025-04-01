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

package software.amazon.opentelemetry.javaagent.providers.AwsSpanExporter;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;

public class OtlpAwsSpanExporterBuilder {
  private final OtlpHttpSpanExporter parentExporter;
  private final String endpoint;

  public static OtlpAwsSpanExporterBuilder create(
      OtlpHttpSpanExporter parentExporter, String endpoint) {
    return new OtlpAwsSpanExporterBuilder(parentExporter, endpoint);
  }

  public static OtlpAwsSpanExporter getDefault(String endpoint) {
    return OtlpAwsSpanExporter.getDefault(endpoint);
  }

  private OtlpAwsSpanExporterBuilder(OtlpHttpSpanExporter parentExporter, String endpoint) {
    this.parentExporter = parentExporter;
    this.endpoint = endpoint;
  }

  public OtlpAwsSpanExporter build() {
    return OtlpAwsSpanExporter.create(this.parentExporter, this.endpoint);
  }
}
