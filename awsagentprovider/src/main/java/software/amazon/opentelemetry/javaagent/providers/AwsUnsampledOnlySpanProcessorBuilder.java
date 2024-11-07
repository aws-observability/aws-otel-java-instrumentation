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

package software.amazon.opentelemetry.javaagent.providers;

import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessorBuilder;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import static java.util.Objects.requireNonNull;

final class AwsUnsampledOnlySpanProcessorBuilder {

  // Default exporter is OtlpUdpSpanExporter with unsampled payload prefix
  private SpanExporter exporter = new OtlpUdpSpanExporterBuilder()
          .setPayloadSampleDecision(TracePayloadSampleDecision.UNSAMPLED)
          .build();

  public AwsUnsampledOnlySpanProcessorBuilder setSpanExporter(SpanExporter exporter) {
    requireNonNull(exporter, "exporter cannot be null");
    this.exporter = exporter;
    return this;
  }

  public AwsUnsampledOnlySpanProcessorBuilder setMaxQueueSize(int maxQueueSize) {

    return this;
  }

  public AwsUnsampledOnlySpanProcessor build() {
  BatchSpanProcessor bsp =
        BatchSpanProcessor.builder(exporter).setExportUnsampledSpans(true).build();
    return new AwsUnsampledOnlySpanProcessor(bsp);
  }

  SpanExporter getSpanExporter() {
    return exporter;
  }
}
