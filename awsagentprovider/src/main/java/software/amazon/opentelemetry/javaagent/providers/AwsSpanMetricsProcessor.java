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

import static io.opentelemetry.semconv.SemanticAttributes.HTTP_RESPONSE_STATUS_CODE;
import static io.opentelemetry.semconv.SemanticAttributes.HTTP_STATUS_CODE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.isKeyPresent;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.contrib.awsxray.AwsXrayRemoteSampler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.concurrent.Immutable;

/**
 * This processor will generate metrics based on span data. It depends on a {@link
 * MetricAttributeGenerator} being provided on instantiation, which will provide a means to
 * determine attributes which should be used to create metrics. A {@link Resource} must also be
 * provided, which is used to generate metrics. Finally, two {@link LongHistogram}'s and a {@link
 * DoubleHistogram} must be provided, which will be used to actually create desired metrics (see
 * below)
 *
 * <p>AwsSpanMetricsProcessor produces metrics for errors (e.g. HTTP 4XX status codes), faults (e.g.
 * HTTP 5XX status codes), and latency (in Milliseconds). Errors and faults are counted, while
 * latency is measured with a histogram. Metrics are emitted with attributes derived from span
 * attributes.
 *
 * <p>For highest fidelity metrics, this processor should be coupled with the {@link
 * AlwaysRecordSampler}, which will result in 100% of spans being sent to the processor.
 */
@Immutable
public final class AwsSpanMetricsProcessor implements SpanProcessor {

  private static final double NANOS_TO_MILLIS = 1_000_000.0;

  // Constants for deriving error and fault metrics
  private static final int ERROR_CODE_LOWER_BOUND = 400;
  private static final int ERROR_CODE_UPPER_BOUND = 499;
  private static final int FAULT_CODE_LOWER_BOUND = 500;
  private static final int FAULT_CODE_UPPER_BOUND = 599;

  // EC2 Metadata API IP Address
  // https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instancedata-data-retrieval.html#instancedata-inside-access
  private final String EC2_METADATA_API_IP = "169.254.169.254";

  // Metric instruments
  private final LongHistogram errorHistogram;
  private final LongHistogram faultHistogram;
  private final DoubleHistogram latencyHistogram;

  private final MetricAttributeGenerator generator;
  private final Resource resource;
  private final Supplier<CompletableResultCode> forceFlushAction;

  private Sampler sampler;

  /** Use {@link AwsSpanMetricsProcessorBuilder} to construct this processor. */
  static AwsSpanMetricsProcessor create(
      LongHistogram errorHistogram,
      LongHistogram faultHistogram,
      DoubleHistogram latencyHistogram,
      MetricAttributeGenerator generator,
      Resource resource,
      Sampler sampler,
      Supplier<CompletableResultCode> forceFlushAction) {
    return new AwsSpanMetricsProcessor(
        errorHistogram,
        faultHistogram,
        latencyHistogram,
        generator,
        resource,
        sampler,
        forceFlushAction);
  }

  private AwsSpanMetricsProcessor(
      LongHistogram errorHistogram,
      LongHistogram faultHistogram,
      DoubleHistogram latencyHistogram,
      MetricAttributeGenerator generator,
      Resource resource,
      Sampler sampler,
      Supplier<CompletableResultCode> forceFlushAction) {
    this.errorHistogram = errorHistogram;
    this.faultHistogram = faultHistogram;
    this.latencyHistogram = latencyHistogram;
    this.generator = generator;
    this.resource = resource;
    this.sampler = sampler;
    this.forceFlushAction = forceFlushAction;
  }

  @Override
  public CompletableResultCode forceFlush() {
    return forceFlushAction.get();
  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {}

  @Override
  public boolean isStartRequired() {
    return false;
  }

  @Override
  public void onEnd(ReadableSpan span) {
    SpanData spanData = span.toSpanData();

    Map<String, Attributes> attributeMap =
        generator.generateMetricAttributeMapFromSpan(spanData, resource);

    for (Map.Entry<String, Attributes> attribute : attributeMap.entrySet()) {
      recordMetrics(span, spanData, attribute.getValue());
    }
    if (sampler != null) {
      ((AwsXrayRemoteSampler) sampler).adaptSampling(span, spanData);
    }
  }

  @Override
  public boolean isEndRequired() {
    return true;
  }

  // The logic to record error and fault should be kept in sync with the aws-xray exporter whenever
  // possible except for the throttle
  // https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/exporter/awsxrayexporter/internal/translator/cause.go#L121-L160
  private void recordErrorOrFault(SpanData spanData, Attributes attributes) {
    Long httpStatusCode = null;
    if (isKeyPresent(spanData, HTTP_RESPONSE_STATUS_CODE)) {
      httpStatusCode = spanData.getAttributes().get(HTTP_RESPONSE_STATUS_CODE);
    } else if (isKeyPresent(spanData, HTTP_STATUS_CODE)) {
      httpStatusCode = spanData.getAttributes().get(HTTP_STATUS_CODE);
    }
    StatusCode statusCode = spanData.getStatus().getStatusCode();

    if (httpStatusCode == null) {
      httpStatusCode = attributes.get(HTTP_RESPONSE_STATUS_CODE);
    }

    if (httpStatusCode == null
        || httpStatusCode < ERROR_CODE_LOWER_BOUND
        || httpStatusCode > FAULT_CODE_UPPER_BOUND) {
      if (StatusCode.ERROR.equals(statusCode)) {
        errorHistogram.record(0, attributes);
        faultHistogram.record(1, attributes);
      } else {
        errorHistogram.record(0, attributes);
        faultHistogram.record(0, attributes);
      }
    } else if (httpStatusCode >= ERROR_CODE_LOWER_BOUND
        && httpStatusCode <= ERROR_CODE_UPPER_BOUND) {
      errorHistogram.record(1, attributes);
      faultHistogram.record(0, attributes);
    } else if (httpStatusCode >= FAULT_CODE_LOWER_BOUND
        && httpStatusCode <= FAULT_CODE_UPPER_BOUND) {
      errorHistogram.record(0, attributes);
      faultHistogram.record(1, attributes);
    }
  }

  private void recordLatency(ReadableSpan span, Attributes attributes) {
    long nanos = span.getLatencyNanos();
    double millis = nanos / NANOS_TO_MILLIS;
    latencyHistogram.record(millis, attributes);
  }

  private void recordMetrics(ReadableSpan span, SpanData spanData, Attributes attributes) {
    // Only record metrics if non-empty attributes are returned.
    if (!attributes.isEmpty() && !isEc2MetadataSpan((attributes))) {
      recordErrorOrFault(spanData, attributes);
      recordLatency(span, attributes);
    }
  }

  private boolean isEc2MetadataSpan(Attributes attributes) {
    if (attributes.get(AWS_REMOTE_SERVICE) != null
        && attributes.get(AWS_REMOTE_SERVICE).equals(EC2_METADATA_API_IP)) {
      return true;
    }

    return false;
  }
}
