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
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.sdk.resources.Resource;

/** A builder for {@link AwsSpanMetricsProcessor} */
public final class AwsSpanMetricsProcessorBuilder {

  // Metric instrument configuration constants
  private static final String ERROR = "Error";
  private static final String FAULT = "Fault";
  private static final String LATENCY = "Latency";
  private static final String LATENCY_UNITS = "Milliseconds";

  // Defaults
  private static final MetricAttributeGenerator DEFAULT_GENERATOR =
      new AwsMetricAttributeGenerator();
  private static final String DEFAULT_SCOPE_NAME = "AwsSpanMetricsProcessor";

  // Required builder elements
  private final MeterProvider meterProvider;
  private final Resource resource;

  // Optional builder elements
  private MetricAttributeGenerator generator = DEFAULT_GENERATOR;
  private String scopeName = DEFAULT_SCOPE_NAME;

  public static AwsSpanMetricsProcessorBuilder create(
      MeterProvider meterProvider, Resource resource) {
    return new AwsSpanMetricsProcessorBuilder(meterProvider, resource);
  }

  private AwsSpanMetricsProcessorBuilder(MeterProvider meterProvider, Resource resource) {
    this.meterProvider = meterProvider;
    this.resource = resource;
  }

  /**
   * Sets the generator used to generate attributes used in metrics produced by span metrics
   * processor. If unset, defaults to {@link #DEFAULT_GENERATOR}. Must not be null.
   */
  @CanIgnoreReturnValue
  public AwsSpanMetricsProcessorBuilder setGenerator(MetricAttributeGenerator generator) {
    requireNonNull(generator, "generator");
    this.generator = generator;
    return this;
  }

  /**
   * Sets the scope name used in the creation of metrics by the span metrics processor. If unset,
   * defaults to {@link #DEFAULT_SCOPE_NAME}. Must not be null.
   */
  @CanIgnoreReturnValue
  public AwsSpanMetricsProcessorBuilder setScopeName(String scopeName) {
    requireNonNull(scopeName, "scopeName");
    this.scopeName = scopeName;
    return this;
  }

  public AwsSpanMetricsProcessor build() {
    Meter meter = meterProvider.get(scopeName);
    LongHistogram errorHistogram = meter.histogramBuilder(ERROR).ofLongs().build();
    LongHistogram faultHistogram = meter.histogramBuilder(FAULT).ofLongs().build();
    DoubleHistogram latencyHistogram =
        meter.histogramBuilder(LATENCY).setUnit(LATENCY_UNITS).build();

    return AwsSpanMetricsProcessor.create(
        errorHistogram, faultHistogram, latencyHistogram, generator, resource);
  }
}
