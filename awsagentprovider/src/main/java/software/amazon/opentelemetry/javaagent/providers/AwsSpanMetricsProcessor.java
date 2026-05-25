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

import static io.opentelemetry.semconv.HttpAttributes.HTTP_RESPONSE_STATUS_CODE;
import static io.opentelemetry.semconv.incubating.HttpIncubatingAttributes.HTTP_STATUS_CODE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.getKeyValueWithFallback;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.trace.SpanContext;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

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
public final class AwsSpanMetricsProcessor implements SpanProcessor {

  private static final Logger logger = Logger.getLogger(AwsSpanMetricsProcessor.class.getName());

  private static final double NANOS_TO_MILLIS = 1_000_000.0;

  // Constants for deriving error and fault metrics
  private static final int ERROR_CODE_LOWER_BOUND = 400;
  private static final int ERROR_CODE_UPPER_BOUND = 499;
  private static final int FAULT_CODE_LOWER_BOUND = 500;
  private static final int FAULT_CODE_UPPER_BOUND = 599;

  // Max custom dimensions per span to prevent cardinality explosion in CloudWatch.
  // Each custom dim generates 2 metric recordings (with and without Operation).
  private static final int MAX_CUSTOM_DIMS_PER_SPAN = 10;

  // EC2 Metadata API IP Address
  // https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instancedata-data-retrieval.html#instancedata-inside-access
  private final String EC2_METADATA_API_IP = "169.254.169.254";

  // Metric instruments (standard AppSignals -> port 4315/4316)
  private final LongHistogram errorHistogram;
  private final LongHistogram faultHistogram;
  private final DoubleHistogram latencyHistogram;

  // Custom metrics histograms (-> port 4317/4318), nullable
  private final LongHistogram customErrorHistogram;
  private final LongHistogram customFaultHistogram;
  private final DoubleHistogram customLatencyHistogram;

  private final MetricAttributeGenerator generator;
  private final Resource resource;
  private final Supplier<CompletableResultCode> forceFlushAction;

  private Sampler sampler;

  // traceId -> accumulated custom dims from any span in the trace.
  // Bounded: entries are evicted after PENDING_DIMS_TTL_NANOS to prevent memory leaks
  // when local root spans end before all child spans (async frameworks).
  private static final long PENDING_DIMS_TTL_NANOS = 5 * 60 * 1_000_000_000L; // 5 minutes
  private static final int PENDING_DIMS_MAX_SIZE = 10_000;
  private static final long EVICTION_INTERVAL_NANOS = 60 * 1_000_000_000L; // 1 minute

  private final ConcurrentHashMap<String, PendingDimEntry> pendingCustomDims =
      new ConcurrentHashMap<>();
  private final AtomicLong lastEvictionNanos = new AtomicLong(System.nanoTime());

  private static final class PendingDimEntry {
    final Map<String, String> dims;
    final long createdNanos;

    PendingDimEntry(Map<String, String> dims) {
      this.dims = dims;
      this.createdNanos = System.nanoTime();
    }
  }

  /** Use {@link AwsSpanMetricsProcessorBuilder} to construct this processor. */
  static AwsSpanMetricsProcessor create(
      LongHistogram errorHistogram,
      LongHistogram faultHistogram,
      DoubleHistogram latencyHistogram,
      LongHistogram customErrorHistogram,
      LongHistogram customFaultHistogram,
      DoubleHistogram customLatencyHistogram,
      MetricAttributeGenerator generator,
      Resource resource,
      Sampler sampler,
      Supplier<CompletableResultCode> forceFlushAction) {
    return new AwsSpanMetricsProcessor(
        errorHistogram,
        faultHistogram,
        latencyHistogram,
        customErrorHistogram,
        customFaultHistogram,
        customLatencyHistogram,
        generator,
        resource,
        sampler,
        forceFlushAction);
  }

  private AwsSpanMetricsProcessor(
      LongHistogram errorHistogram,
      LongHistogram faultHistogram,
      DoubleHistogram latencyHistogram,
      LongHistogram customErrorHistogram,
      LongHistogram customFaultHistogram,
      DoubleHistogram customLatencyHistogram,
      MetricAttributeGenerator generator,
      Resource resource,
      Sampler sampler,
      Supplier<CompletableResultCode> forceFlushAction) {
    this.errorHistogram = errorHistogram;
    this.faultHistogram = faultHistogram;
    this.latencyHistogram = latencyHistogram;
    this.customErrorHistogram = customErrorHistogram;
    this.customFaultHistogram = customFaultHistogram;
    this.customLatencyHistogram = customLatencyHistogram;
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
    SpanContext ownSpanContext = spanData.getSpanContext();
    String traceId = ownSpanContext != null ? ownSpanContext.getTraceId() : null;

    // If OTEL_AWS_HTTP_OPERATION_PATHS is configured, wrap the span with the overridden name
    // so that metrics use the configured operation path instead of the original span name.
    spanData = AwsSpanProcessingUtil.applyOperationPathSpanName(spanData);

    // Extract custom dims from this span's attributes and accumulate under traceId.
    // Any span in the trace can contribute custom dims regardless of depth or kind.
    Map<String, String> ownCustomDims = CustomDimensionExtractor.extract(spanData.getAttributes());
    boolean isLocalRoot = traceId != null && AwsSpanProcessingUtil.isLocalRoot(spanData);

    if (!ownCustomDims.isEmpty() && traceId != null) {
      pendingCustomDims.compute(
          traceId,
          (key, existing) -> {
            if (existing == null) {
              return new PendingDimEntry(new HashMap<>(ownCustomDims));
            }
            Map<String, String> merged = new HashMap<>(existing.dims);
            merged.putAll(ownCustomDims);
            return new PendingDimEntry(merged);
          });
    }

    // For local root spans, atomically read-and-remove to avoid race with late child merges.
    // Uses compute() so the read and remove happen in a single atomic operation.
    Map<String, String> allCustomDims = null;
    if (isLocalRoot) {
      PendingDimEntry[] holder = new PendingDimEntry[1];
      pendingCustomDims.compute(
          traceId,
          (key, existing) -> {
            holder[0] = existing;
            return null; // remove
          });
      allCustomDims = holder[0] != null ? holder[0].dims : null;
    }

    // Periodically evict stale entries to prevent memory leaks from orphaned traces
    evictStaleEntries();

    // Always generate standard Application Signals metrics -> port 4315/4316
    Map<String, Attributes> standardAttributeMap =
        generator.generateMetricAttributeMapFromSpan(spanData, resource);
    for (Map.Entry<String, Attributes> attribute : standardAttributeMap.entrySet()) {
      recordMetrics(span, spanData, attribute.getValue());
    }

    // Generate custom dimension metrics -> port 4317/4318
    // Only for SERVICE-type spans (SERVER or local root), not DEPENDENCY
    if (allCustomDims != null && !allCustomDims.isEmpty()) {
      if (!AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanData)) {
        logger.log(
            Level.WARNING,
            "Custom dims {0} discarded: span ''{1}'' (kind={2}) does not qualify for "
                + "service metric generation. BUSINESS configs are ineffective for this span.",
            new Object[] {allCustomDims.keySet(), spanData.getName(), spanData.getKind()});
      } else {
        // Get Operation from the standard SERVICE_METRIC attributes
        Attributes serviceAttrs = standardAttributeMap.get(MetricAttributeGenerator.SERVICE_METRIC);
        String operation = serviceAttrs != null ? serviceAttrs.get(AWS_LOCAL_OPERATION) : null;

        if (allCustomDims.size() > MAX_CUSTOM_DIMS_PER_SPAN) {
          logger.log(
              Level.WARNING,
              "Span ''{0}'' has {1} custom dims, exceeding cap of {2}. Only first {2} will be processed.",
              new Object[] {spanData.getName(), allCustomDims.size(), MAX_CUSTOM_DIMS_PER_SPAN});
        }

        // For EACH custom dim separately, generate 2 recordings
        int count = 0;
        for (Map.Entry<String, String> dim : allCustomDims.entrySet()) {
          if (count >= MAX_CUSTOM_DIMS_PER_SPAN) {
            break;
          }
          count++;

          String dimName = dim.getKey();
          String dimValue = dim.getValue();

          // Set A: {Operation=<value>, <dimName>=<dimValue>}
          if (operation != null) {
            AttributesBuilder withOpBuilder = Attributes.builder();
            withOpBuilder.put("Operation", operation);
            withOpBuilder.put(dimName, dimValue);
            recordCustomMetrics(span, spanData, withOpBuilder.build());
          }

          // Set B: {<dimName>=<dimValue>}
          Attributes withoutOp =
              Attributes.of(io.opentelemetry.api.common.AttributeKey.stringKey(dimName), dimValue);
          recordCustomMetrics(span, spanData, withoutOp);
        }
      }
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
  private void recordErrorOrFault(
      SpanData spanData,
      Attributes attributes,
      LongHistogram errorHist,
      LongHistogram faultHist) {
    Long httpStatusCode =
        getKeyValueWithFallback(spanData, HTTP_RESPONSE_STATUS_CODE, HTTP_STATUS_CODE);
    StatusCode statusCode = spanData.getStatus().getStatusCode();

    if (httpStatusCode == null) {
      httpStatusCode = attributes.get(HTTP_RESPONSE_STATUS_CODE);
    }

    if (httpStatusCode == null
        || httpStatusCode < ERROR_CODE_LOWER_BOUND
        || httpStatusCode > FAULT_CODE_UPPER_BOUND) {
      if (StatusCode.ERROR.equals(statusCode)) {
        errorHist.record(0, attributes);
        faultHist.record(1, attributes);
      } else {
        errorHist.record(0, attributes);
        faultHist.record(0, attributes);
      }
    } else if (httpStatusCode >= ERROR_CODE_LOWER_BOUND
        && httpStatusCode <= ERROR_CODE_UPPER_BOUND) {
      errorHist.record(1, attributes);
      faultHist.record(0, attributes);
    } else if (httpStatusCode >= FAULT_CODE_LOWER_BOUND
        && httpStatusCode <= FAULT_CODE_UPPER_BOUND) {
      errorHist.record(0, attributes);
      faultHist.record(1, attributes);
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
      recordErrorOrFault(spanData, attributes, errorHistogram, faultHistogram);
      recordLatency(span, attributes);
    }
  }

  private void recordCustomMetrics(ReadableSpan span, SpanData spanData, Attributes attributes) {
    if (!attributes.isEmpty() && !isEc2MetadataSpan(attributes) && customErrorHistogram != null) {
      double latencyMillis = span.getLatencyNanos() / NANOS_TO_MILLIS;
      logger.log(
          Level.FINER,
          "Recording custom metric -> port 4317/4318: span=[{0}], latency={1}ms, attributes={2}",
          new Object[] {spanData.getName(), latencyMillis, attributes});
      recordErrorOrFault(spanData, attributes, customErrorHistogram, customFaultHistogram);
      recordCustomLatency(span, attributes);
    } else if (customErrorHistogram == null) {
      logger.log(
          Level.FINE,
          "Custom metric histograms not configured (null). "
              + "Custom dim metrics will be skipped for span [{0}].",
          spanData.getName());
    }
  }

  private void recordCustomLatency(ReadableSpan span, Attributes attributes) {
    long nanos = span.getLatencyNanos();
    double millis = nanos / NANOS_TO_MILLIS;
    customLatencyHistogram.record(millis, attributes);
  }

  private boolean isEc2MetadataSpan(Attributes attributes) {
    if (attributes.get(AWS_REMOTE_SERVICE) != null
        && attributes.get(AWS_REMOTE_SERVICE).equals(EC2_METADATA_API_IP)) {
      return true;
    }

    return false;
  }

  /**
   * Evict stale entries from pendingCustomDims that exceeded TTL. Also enforces a hard size cap.
   * Runs at most once per EVICTION_INTERVAL_NANOS to avoid overhead on every span.
   */
  private void evictStaleEntries() {
    long now = System.nanoTime();
    long lastEviction = lastEvictionNanos.get();
    if (now - lastEviction < EVICTION_INTERVAL_NANOS) {
      return;
    }
    if (!lastEvictionNanos.compareAndSet(lastEviction, now)) {
      return; // Another thread is handling eviction
    }

    int evicted = 0;
    Iterator<Map.Entry<String, PendingDimEntry>> it = pendingCustomDims.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, PendingDimEntry> entry = it.next();
      if (now - entry.getValue().createdNanos > PENDING_DIMS_TTL_NANOS) {
        it.remove();
        evicted++;
      }
    }

    // Hard size cap as safety net
    if (pendingCustomDims.size() > PENDING_DIMS_MAX_SIZE) {
      int excess = pendingCustomDims.size() - PENDING_DIMS_MAX_SIZE;
      Iterator<String> keyIt = pendingCustomDims.keySet().iterator();
      while (keyIt.hasNext() && excess > 0) {
        keyIt.next();
        keyIt.remove();
        excess--;
        evicted++;
      }
    }

    if (evicted > 0) {
      logger.log(
          Level.FINE,
          "Evicted {0} stale entries from pendingCustomDims, remaining: {1}",
          new Object[] {evicted, pendingCustomDims.size()});
    }
  }
}
