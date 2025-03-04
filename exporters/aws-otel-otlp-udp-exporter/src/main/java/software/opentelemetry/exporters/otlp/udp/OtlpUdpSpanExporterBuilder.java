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

package software.opentelemetry.exporters.otlp.udp;

import static java.util.Objects.requireNonNull;

public final class OtlpUdpSpanExporterBuilder {

  private static final String DEFAULT_HOST = "127.0.0.1";
  private static final int DEFAULT_PORT = 2000;

  // The protocol header and delimiter is required for sending data to X-Ray Daemon or when running
  // in Lambda.
  // https://docs.aws.amazon.com/xray/latest/devguide/xray-api-sendingdata.html#xray-api-daemon
  private static final String PROTOCOL_HEADER = "{\"format\": \"json\", \"version\": 1}";
  private static final char PROTOCOL_DELIMITER = '\n';

  // These prefixes help the backend identify if the spans payload is sampled or not.
  private static final String FORMAT_OTEL_SAMPLED_TRACES_BINARY_PREFIX = "T1S";
  private static final String FORMAT_OTEL_UNSAMPLED_TRACES_BINARY_PREFIX = "T1U";

  private UdpSender sender;
  private String tracePayloadPrefix = FORMAT_OTEL_SAMPLED_TRACES_BINARY_PREFIX;

  public OtlpUdpSpanExporterBuilder setEndpoint(String endpoint) {
    requireNonNull(endpoint, "endpoint must not be null");
    try {
      String[] parts = endpoint.split(":");
      String host = parts[0];
      int port = Integer.parseInt(parts[1]);
      this.sender = new UdpSender(host, port);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid endpoint, must be a valid URL: " + endpoint, e);
    }
    return this;
  }

  public OtlpUdpSpanExporterBuilder setPayloadSampleDecision(TracePayloadSampleDecision decision) {
    this.tracePayloadPrefix =
        decision == TracePayloadSampleDecision.SAMPLED
            ? FORMAT_OTEL_SAMPLED_TRACES_BINARY_PREFIX
            : FORMAT_OTEL_UNSAMPLED_TRACES_BINARY_PREFIX;
    return this;
  }

  public OtlpUdpSpanExporter build() {
    if (sender == null) {
      this.sender = new UdpSender(DEFAULT_HOST, DEFAULT_PORT);
    }
    return new OtlpUdpSpanExporter(
        this.sender, PROTOCOL_HEADER + PROTOCOL_DELIMITER + tracePayloadPrefix);
  }

  // Only for testing
  OtlpUdpSpanExporterBuilder setSender(UdpSender sender) {
    this.sender = sender;
    return this;
  }
}

enum TracePayloadSampleDecision {
  SAMPLED,
  UNSAMPLED
}
