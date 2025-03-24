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

package software.amazon.distro.opentelemetry.exporter.xray.lambda;

import static java.util.Objects.requireNonNull;

import java.util.Map;

public final class AWSXRayLambdaExporterBuilder {

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
  private Map<String, String> environmentVariables = System.getenv();

  private static final String AWS_LAMBDA_FUNCTION_NAME_CONFIG = "AWS_LAMBDA_FUNCTION_NAME";
  private static final String AWS_XRAY_DAEMON_ADDRESS_CONFIG = "AWS_XRAY_DAEMON_ADDRESS";

  public AWSXRayLambdaExporterBuilder setEndpoint(String endpoint) {
    requireNonNull(endpoint, "endpoint must not be null");
    try {
      this.sender = createSenderFromEndpoint(endpoint);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid endpoint, must be a valid URL: " + endpoint, e);
    }
    return this;
  }

  public AWSXRayLambdaExporterBuilder setPayloadSampleDecision(TracePayloadSampleDecision decision) {
    this.tracePayloadPrefix =
        decision == TracePayloadSampleDecision.SAMPLED
            ? FORMAT_OTEL_SAMPLED_TRACES_BINARY_PREFIX
            : FORMAT_OTEL_UNSAMPLED_TRACES_BINARY_PREFIX;
    return this;
  }

  private UdpSender createSenderFromEndpoint(String endpoint) {
        String[] parts = endpoint.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        return new UdpSender(host, port);
    }

  // For testing purposes
  AWSXRayLambdaExporterBuilder withEnvironmentVariables(Map<String, String> env) {
    this.environmentVariables = env;
    return this;
  }

  // NEW: Added getter for testing
  Map<String, String> getEnvironmentVariables() {
    return environmentVariables;
  }

  public AWSXRayLambdaExporter build() {
    if (sender == null) {
      String endpoint = null;

      // If in Lambda environment, try to get X-Ray daemon address
      if (isLambdaEnvironment()) {
        endpoint = environmentVariables.get(AWS_XRAY_DAEMON_ADDRESS_CONFIG);
        if (endpoint != null && !endpoint.isEmpty()) {
          try {
            this.sender = createSenderFromEndpoint(endpoint);
            return new AWSXRayLambdaExporter(
                this.sender, PROTOCOL_HEADER + PROTOCOL_DELIMITER + tracePayloadPrefix);
          } catch (Exception e) {
            // Fallback to defaults if parsing fails
            this.sender = new UdpSender(DEFAULT_HOST, DEFAULT_PORT);
          }
        }
      }

      // Use defaults if not in Lambda or if daemon address is invalid/unavailable
      this.sender = new UdpSender(DEFAULT_HOST, DEFAULT_PORT);
    }
    return new AWSXRayLambdaExporter(
        this.sender, PROTOCOL_HEADER + PROTOCOL_DELIMITER + tracePayloadPrefix);
  }

  private boolean isLambdaEnvironment() {
    String functionName = environmentVariables.get(AWS_LAMBDA_FUNCTION_NAME_CONFIG);
    return functionName != null && !functionName.isEmpty();
  }

  // Only for testing
  AWSXRayLambdaExporterBuilder setSender(UdpSender sender) {
    this.sender = sender;
    return this;
  }
}

enum TracePayloadSampleDecision {
  SAMPLED,
  UNSAMPLED
}
