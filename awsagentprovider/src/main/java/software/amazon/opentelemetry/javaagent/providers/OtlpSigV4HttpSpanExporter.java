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

import io.opentelemetry.exporter.internal.FailedExportException;
import io.opentelemetry.exporter.internal.http.HttpSender.Response;
import io.opentelemetry.exporter.internal.marshal.CodedInputStream;
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * TODO: Exports spans via HTTP, using OpenTelemetry's ..., sent over to {@link
 * OtlpSigV4HttpSender}.
 *
 * <p>This exporter is NOT meant for generic use since the payload is prefixed with AWS X-Ray
 * specific information.
 */
@Immutable
class OtlpSigV4HttpSpanExporter implements SpanExporter {
  private static final Logger logger = Logger.getLogger(OtlpSigV4HttpSpanExporter.class.getName());
  private final AtomicBoolean isShutdown = new AtomicBoolean();

  private final String type;
  private final OtlpSigV4HttpSender sender;

  OtlpSigV4HttpSpanExporter(OtlpSigV4HttpSender sender) {
    this.sender = sender;
    this.type = "span";
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    if (isShutdown.get()) {
      return CompletableResultCode.ofFailure();
    }

    TraceRequestMarshaler exportRequest = TraceRequestMarshaler.create(spans);

    CompletableResultCode result = new CompletableResultCode();

    logger.info("Exporting spans to AWS X-Ray");
    sender.send(
        exportRequest,
        exportRequest.getBinarySerializedSize(),
        httpResponse -> onResponse(result, httpResponse),
        throwable -> onError(result, throwable));

    return result;
  }

  private void onResponse(CompletableResultCode result, Response httpResponse) {
    logger.info("ON RESPONSE");
    int statusCode = httpResponse.statusCode();

    if (statusCode >= 200 && statusCode < 300) {
      // exporterMetrics.addSuccess(numItems);
      result.succeed();
      return;
    }

    // exporterMetrics.addFailed(numItems);

    byte[] body = null;
    try {
      body = httpResponse.responseBody();
    } catch (IOException ex) {
      logger.log(Level.FINE, "Unable to obtain response body", ex);
    }

    String status = extractErrorStatus(httpResponse.statusMessage(), body);

    logger.log(
        Level.WARNING,
        "Failed to export "
            + type
            + "s. Server responded with HTTP status code "
            + statusCode
            + ". Error message: "
            + status);

    result.failExceptionally(FailedExportException.httpFailedWithResponse(httpResponse));
  }

  private void onError(CompletableResultCode result, Throwable e) {
    logger.info("ON ERROR");
    // exporterMetrics.addFailed(numItems);
    logger.log(
        Level.SEVERE,
        "Failed to export "
            + type
            + "s. The request could not be executed. Full error message: "
            + e.getMessage(),
        e);
    result.failExceptionally(FailedExportException.httpFailedExceptionally(e));
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

  private static String extractErrorStatus(String statusMessage, @Nullable byte[] responseBody) {
    if (responseBody == null) {
      return "Response body missing, HTTP status message: " + statusMessage;
    }
    try {
      return getStatusMessage(responseBody);
    } catch (IOException e) {
      return "Unable to parse response body, HTTP status message: " + statusMessage;
    }
  }

  /** Parses the message out of a serialized gRPC Status. */
  private static String getStatusMessage(byte[] serializedStatus) throws IOException {
    CodedInputStream input = CodedInputStream.newInstance(serializedStatus);
    boolean done = false;
    while (!done) {
      int tag = input.readTag();
      switch (tag) {
        case 0:
          done = true;
          break;
        case 18:
          return input.readStringRequireUtf8();
        default:
          input.skipField(tag);
          break;
      }
    }
    // Serialized Status proto had no message, proto always defaults to empty string when not found.
    return "";
  }

  // Visible for testing
  OtlpSigV4HttpSender getSender() {
    return sender;
  }
}
