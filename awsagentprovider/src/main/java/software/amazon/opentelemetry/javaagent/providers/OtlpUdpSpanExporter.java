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

import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.Immutable;

/**
 * Exports spans via UDP, using OpenTelemetry's protobuf model. The protobuf modelled spans are
 * Base64 encoded and prefixed with AWS X-Ray specific information before being sent over to {@link
 * UdpSender}.
 *
 * <p>This exporter is NOT meant for generic use since the payload is prefixed with AWS X-Ray
 * specific information.
 */
@Immutable
class OtlpUdpSpanExporter implements SpanExporter {

  private static final Logger logger = Logger.getLogger(OtlpUdpSpanExporter.class.getName());

  // The protocol header and delimiter is required for sending data to X-Ray Daemon or when running
  // in Lambda.
  // https://docs.aws.amazon.com/xray/latest/devguide/xray-api-sendingdata.html#xray-api-daemon
  private static final String PROTOCOL_HEADER = "{\"format\": \"json\", \"version\": 1}";
  private static final char PROTOCOL_DELIMITER = '\n';

  // These prefixes help the backend identify if the spans payload is sampled or not.
  private static final String FORMAT_OTEL_SAMPLED_TRACES_BINARY_PREFIX = "T1S";
  private static final String FORMAT_OTEL_UNSAMPLED_TRACES_BINARY_PREFIX = "T1U";

  private final AtomicBoolean isShutdown = new AtomicBoolean();

  private final UdpSender sender;
  private final boolean sampled;

  OtlpUdpSpanExporter(UdpSender sender, boolean sampled) {
    this.sender = sender;
    this.sampled = sampled;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    if (isShutdown.get()) {
      return CompletableResultCode.ofFailure();
    }

    TraceRequestMarshaler exportRequest = TraceRequestMarshaler.create(spans);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try {
      exportRequest.writeBinaryTo(baos);
      String payload =
          PROTOCOL_HEADER
              + PROTOCOL_DELIMITER
              + (sampled
                  ? FORMAT_OTEL_SAMPLED_TRACES_BINARY_PREFIX
                  : FORMAT_OTEL_UNSAMPLED_TRACES_BINARY_PREFIX)
              + Base64.getEncoder().encodeToString(baos.toByteArray());
      sender.send(payload.getBytes(StandardCharsets.UTF_8));
      return CompletableResultCode.ofSuccess();
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Failed to export spans. Error: " + e.getMessage(), e);
      return CompletableResultCode.ofFailure();
    }
  }

  @Override
  public CompletableResultCode flush() {
    // TODO: implement
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    if (!isShutdown.compareAndSet(false, true)) {
      logger.log(Level.INFO, "Calling shutdown() multiple times.");
      return CompletableResultCode.ofSuccess();
    }
    return sender.shutdown();
  }

  // Visible for testing
  UdpSender getSender() {
    return sender;
  }

  // Visible for testing
  boolean isSampled() {
    return sampled;
  }
}
