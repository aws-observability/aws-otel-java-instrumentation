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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter;

import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Local-testing metric exporter that writes canonical OTLP metrics JSON.
 *
 * <p>Each {@code export()} batch is written as ONE NDJSON line containing a full OTLP {@code
 * ExportMetricsServiceRequest} ({@code resourceMetrics[].scopeMetrics[].metrics[]}), byte-identical
 * to what the CloudWatch OTLP metrics endpoint accepts. This mirrors {@link
 * io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter} exactly — the file exporter
 * is a pure transport swap, so both {@code count} (Sum, {@code SERVICE_EVENTS_OTLP_SIGNALS_SPEC.md}
 * §7) and {@code service.function.duration} (ExponentialHistogram, §4) serialize natively with no
 * per-type special-casing.
 *
 * <p>Uses {@link MetricsRequestMarshaler} — the same serializer the OTLP HTTP exporter uses — so
 * the on-disk JSON matches the wire byte-for-byte. That class lives in {@code
 * io.opentelemetry.exporter.internal.*} (an unstable internal API), but it is already on the agent
 * runtime classpath because {@code OtlpHttpMetricExporter} (used in network mode) depends on it.
 *
 * <p>Enabled via {@code OTEL_AWS_SERVICE_EVENTS_OUTPUT_FILE}. Shares a writer singleton with {@link
 * ServiceEventsCloudWatchLogFileExporter} so log + metric lines land in the same file without
 * interleaving.
 */
public class ServiceEventsCloudWatchMetricFileExporter implements MetricExporter {

  private static final Logger logger =
      Logger.getLogger(ServiceEventsCloudWatchMetricFileExporter.class.getName());

  private final ServiceEventsFileWriter writer;
  private final AtomicBoolean shutdownCalled = new AtomicBoolean(false);

  public ServiceEventsCloudWatchMetricFileExporter(String outputFilePath) {
    this.writer = ServiceEventsFileWriter.acquire(outputFilePath);
  }

  @Override
  public CompletableResultCode export(Collection<MetricData> metrics) {
    if (shutdownCalled.get() || writer == null) {
      return CompletableResultCode.ofFailure();
    }
    if (metrics.isEmpty()) {
      return CompletableResultCode.ofSuccess();
    }
    String line;
    try {
      // Marshal the whole batch to a single OTLP/JSON ExportMetricsServiceRequest,
      // exactly as OtlpHttpMetricExporter does on the wire. One NDJSON line per batch.
      MetricsRequestMarshaler marshaler = MetricsRequestMarshaler.create(metrics);
      ByteArrayOutputStream baos = new ByteArrayOutputStream(marshaler.getBinarySerializedSize());
      marshaler.writeJsonTo(baos);
      line = new String(baos.toByteArray(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      logger.log(Level.WARNING, "ServiceEvents: failed to marshal metrics to OTLP JSON", e);
      return CompletableResultCode.ofFailure();
    }
    try {
      writer.writeLines(Collections.singletonList(line));
      writer.flush();
      return CompletableResultCode.ofSuccess();
    } catch (Exception e) {
      logger.log(Level.WARNING, "ServiceEvents: failed to write metrics to file", e);
      return CompletableResultCode.ofFailure();
    }
  }

  @Override
  public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
    return AggregationTemporality.DELTA;
  }

  @Override
  public CompletableResultCode flush() {
    if (writer == null) {
      return CompletableResultCode.ofFailure();
    }
    return writer.flush();
  }

  @Override
  public CompletableResultCode shutdown() {
    // compareAndSet gives exactly-once release semantics: concurrent shutdown() calls can't both
    // pass the guard and double-release the (potentially shared) ServiceEventsFileWriter.
    if (!shutdownCalled.compareAndSet(false, true)) {
      return CompletableResultCode.ofSuccess();
    }
    if (writer == null) {
      return CompletableResultCode.ofSuccess();
    }
    return writer.release();
  }
}
