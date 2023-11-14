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

import static java.util.Objects.requireNonNull;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public class AwsMetricAttributesSpanExporterBuilder {

  // Defaults
  private static final MetricAttributeGenerator DEFAULT_GENERATOR =
      new AwsMetricAttributeGenerator();

  // Required builder elements
  private final SpanExporter delegate;
  private final Resource resource;

  // Optional builder elements
  private MetricAttributeGenerator generator = DEFAULT_GENERATOR;

  public static AwsMetricAttributesSpanExporterBuilder create(
      SpanExporter delegate, Resource resource) {
    return new AwsMetricAttributesSpanExporterBuilder(delegate, resource);
  }

  private AwsMetricAttributesSpanExporterBuilder(SpanExporter delegate, Resource resource) {
    this.delegate = delegate;
    this.resource = resource;
  }

  /**
   * Sets the generator used to generate attributes used spancs exported by the exporter. If unset,
   * defaults to {@link #DEFAULT_GENERATOR}. Must not be null.
   */
  @CanIgnoreReturnValue
  public AwsMetricAttributesSpanExporterBuilder setGenerator(MetricAttributeGenerator generator) {
    requireNonNull(generator, "generator");
    this.generator = generator;
    return this;
  }

  public AwsMetricAttributesSpanExporter build() {
    return AwsMetricAttributesSpanExporter.create(delegate, generator, resource);
  }
}
