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

import static software.amazon.opentelemetry.javaagent.providers.AwsApplicationSignalsCustomizerProvider.*;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class ConfigValidator {
  private static final Logger logger =
      Logger.getLogger(AwsApplicationSignalsCustomizerProvider.class.getName());

  public static boolean isSigV4EnabledLogs(ConfigProperties config) {
    String logsEndpoint = config.getString(OTEL_EXPORTER_OTLP_LOGS_ENDPOINT);
    String logsExporter = config.getString(OTEL_LOGS_EXPORTER);
    String logsProtocol = config.getString(OTEL_EXPORTER_OTLP_LOGS_PROTOCOL);
    String logGroup = config.getString(OTEL_AWS_SIGV4_LOG_GROUP);
    String logStream = config.getString(OTEL_AWS_SIGV4_LOG_STREAM);

    boolean isValidConfig =
        isValidConfig(
            logsEndpoint,
            AWS_OTLP_LOGS_ENDPOINT_PATTERN,
            OTEL_LOGS_EXPORTER,
            logsExporter,
            OTEL_EXPORTER_OTLP_LOGS_PROTOCOL,
            logsProtocol);

    if (isValidConfig) {
      if (logGroup == null) {
        logger.log(
            Level.INFO,
            String.format(
                "Improper configuration: Please configure your environment variables and export/set %s",
                OTEL_AWS_SIGV4_LOG_GROUP));
        return false;
      }

      if (logStream == null) {
        logger.log(
            Level.INFO,
            String.format(
                "Improper configuration: Please configure your environment variables and export/set %s",
                OTEL_AWS_SIGV4_LOG_STREAM));
        return false;
      }

      return true;
    }

    return false;
  }

  public static boolean isSigV4EnabledTraces(ConfigProperties config) {
    String tracesEndpoint = config.getString(OTEL_EXPORTER_OTLP_TRACES_ENDPOINT);
    String tracesExporter = config.getString(OTEL_TRACES_EXPORTER);
    String tracesProtocol = config.getString(OTEL_EXPORTER_OTLP_TRACES_PROTOCOL);

    return isValidConfig(
        tracesEndpoint,
        AWS_OTLP_TRACES_ENDPOINT_PATTERN,
        OTEL_TRACES_EXPORTER,
        tracesExporter,
        OTEL_EXPORTER_OTLP_TRACES_PROTOCOL,
        tracesProtocol);
  }

  private static boolean isValidConfig(
      String endpoint,
      String endpointPattern,
      String exporterType,
      String exporter,
      String protocolConfig,
      String protocol) {
    boolean isValidOtlpEndpoint;
    try {
      isValidOtlpEndpoint =
          endpoint != null
              && Pattern.compile(endpointPattern).matcher(endpoint.toLowerCase()).matches();

      if (isValidOtlpEndpoint) {
        logger.log(Level.INFO, "Detected using AWS OTLP Endpoint.");

        if (exporter != null && !exporter.equals("otlp")) {
          logger.log(
              Level.INFO,
              String.format(
                  "Improper configuration: Please configure your environment variables and export/set %s=otlp",
                  exporterType));
          return false;
        }

        if (protocol != null && !protocol.equals(OTEL_EXPORTER_HTTP_PROTOBUF_PROTOCOL)) {
          logger.info(
              String.format(
                  "Improper configuration: Please configure your environment variables and export/set %s=%s",
                  protocolConfig, OTEL_EXPORTER_HTTP_PROTOBUF_PROTOCOL));
          return false;
        }

        return true;
      }
    } catch (Exception e) {
      logger.log(
          Level.WARNING,
          String.format(
              "Caught error while attempting to validate configuration to export to OTLP endpoint: %s",
              e.getMessage()));
    }

    return false;
  }
}
