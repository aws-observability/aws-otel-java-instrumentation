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
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Utilities class to validate ADOT environment variable configuration. */
public final class AwsApplicationSignalsConfigUtils {
  private static final Logger logger =
      Logger.getLogger(AwsApplicationSignalsCustomizerProvider.class.getName());

  /**
   * Removes "awsemf" from OTEL_METRICS_EXPORTER if present to prevent validation errors from OTel
   * dependencies which would try to load metric exporters. We will contribute emf exporter to
   * upstream for supporting OTel metrics in SDK
   *
   * @param configProps the configuration properties
   * @return Optional containing string with "awsemf" removed if the original OTEL_METRICS_EXPORTER
   *     contains "awsemf", otherwise empty Optional if "awsemf" is not found
   */
  static Optional<String> removeEmfExporterIfEnabled(ConfigProperties configProps) {
    String metricExporters = configProps.getString(OTEL_METRICS_EXPORTER);

    if (metricExporters == null || !metricExporters.contains("awsemf")) {
      return Optional.empty();
    }

    String[] exporters = metricExporters.split(",");
    List<String> filtered =
        Arrays.stream(exporters)
            .map(String::trim)
            .filter(exp -> !exp.equals("awsemf"))
            .collect(java.util.stream.Collectors.toList());

    // Return empty string instead of "none" because upstream will not call
    // customizeMetricExporter if OTEL_METRICS_EXPORTER is set to "none" as it assumes
    // no metrics exporter is configured
    return Optional.of(filtered.isEmpty() ? "" : String.join(",", filtered));
  }

  /**
   * Is the given configuration correct to enable SigV4 for Logs?
   *
   * <ul>
   *   <li><code>OTEL_EXPORTER_OTLP_LOGS_ENDPOINT</code>
   *       =https://logs.[AWS-REGION].amazonaws.com/v1/logs
   *   <li><code>OTEL_AWS_LOG_GROUP</code>=[CW-LOG-GROUP-NAME]
   *   <li><code>OTEL_AWS_LOG_STREAM</code>=[CW-LOG-STREAM-NAME]
   *   <li><code>OTEL_EXPORTER_OTLP_LOGS_PROTOCOL</code>=http/protobuf
   *   <li><code>OTEL_LOGS_EXPORTER</code>=otlp
   * </ul>
   *
   * <p>NOTE: ** indicates that the environment variable must exactly match this value or must not
   * be set at all.
   */
  static boolean isSigV4EnabledLogs(ConfigProperties config) {
    String logsEndpoint = config.getString(OTEL_EXPORTER_OTLP_LOGS_ENDPOINT);
    String logsExporter = config.getString(OTEL_LOGS_EXPORTER);
    String logsProtocol = config.getString(OTEL_EXPORTER_OTLP_LOGS_PROTOCOL);
    String logsHeaders = config.getString(OTEL_EXPORTER_OTLP_LOGS_HEADERS);

    if (!isSigv4ValidConfig(
        logsEndpoint,
        AWS_OTLP_LOGS_ENDPOINT_PATTERN,
        OTEL_LOGS_EXPORTER,
        logsExporter,
        OTEL_EXPORTER_OTLP_LOGS_PROTOCOL,
        logsProtocol)) {
      return false;
    }

    if (logsHeaders == null || logsHeaders.isEmpty()) {
      logger.warning(
          String.format(
              "Improper configuration: Please configure the environment variable OTEL_EXPORTER_OTLP_LOGS_HEADERS to include %s and %s",
              AWS_OTLP_LOGS_GROUP_HEADER, AWS_OTLP_LOGS_STREAM_HEADER));

      return false;
    }
    Map<String, String> parsedHeaders =
        AwsApplicationSignalsConfigUtils.parseOtlpHeaders(logsHeaders);

    if (!(parsedHeaders.containsKey(AWS_OTLP_LOGS_GROUP_HEADER)
        && parsedHeaders.containsKey(AWS_OTLP_LOGS_STREAM_HEADER))) {
      logger.warning(
          String.format(
              "Improper configuration: Please configure the environment variable OTEL_EXPORTER_OTLP_LOGS_HEADERS to have values for %s and %s",
              AWS_OTLP_LOGS_GROUP_HEADER, AWS_OTLP_LOGS_STREAM_HEADER));
      return false;
    }

    return true;
  }

  /**
   * Is the given configuration correct to enable SigV4 for Traces?
   *
   * <ul>
   *   <li><code>OTEL_EXPORTER_OTLP_TRACES_ENDPOINT</code>
   *       =https://xray.[AWS-REGION].amazonaws.com/v1/traces
   *   <li><code>OTEL_EXPORTER_OTLP_TRACES_PROTOCOL</code>=http/protobuf **
   *   <li><code>OTEL_TRACES_EXPORTER</code>=otlp **
   * </ul>
   *
   * <p>NOTE: ** indicates that the environment variable must exactly match this value or must not
   * be set at all.
   */
  static boolean isSigV4EnabledTraces(ConfigProperties config) {
    String tracesEndpoint = config.getString(OTEL_EXPORTER_OTLP_TRACES_ENDPOINT);
    String tracesExporter = config.getString(OTEL_TRACES_EXPORTER);
    String tracesProtocol = config.getString(OTEL_EXPORTER_OTLP_TRACES_PROTOCOL);

    return isSigv4ValidConfig(
        tracesEndpoint,
        AWS_OTLP_TRACES_ENDPOINT_PATTERN,
        OTEL_TRACES_EXPORTER,
        tracesExporter,
        OTEL_EXPORTER_OTLP_TRACES_PROTOCOL,
        tracesProtocol);
  }

  /**
   * Determines if the required configurations for the signal type is correct. These environment
   * variables must exactly match this value or must not be set at all.
   *
   * <ul>
   *   <li><code>OTEL_EXPORTER_OTLP_{SIGNAL}_ENDPOINT</code>=[AWS OTLP LOGS or TRACES endpoint]
   *   <li><code>OTEL_EXPORTER_OTLP_{SIGNAL}_PROTOCOL</code>=http/protobuf
   *   <li><code>OTEL_{SIGNAL}_EXPORTER</code>=otlp
   * </ul>
   */
  private static boolean isSigv4ValidConfig(
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
        logger.log(Level.INFO, String.format("Detected using AWS OTLP Endpoint: %s.", endpoint));

        if (exporter != null && !exporter.equals("otlp")) {
          logger.warning(
              String.format(
                  "Improper configuration: Please configure your environment variables and export/set %s=otlp",
                  exporterType));
          return false;
        }

        if (protocol != null && !protocol.equals(OTEL_EXPORTER_HTTP_PROTOBUF_PROTOCOL)) {
          logger.warning(
              String.format(
                  "Improper configuration: Please configure your environment variables and export/set %s=%s",
                  protocolConfig, OTEL_EXPORTER_HTTP_PROTOBUF_PROTOCOL));
          return false;
        }

        return true;
      }
    } catch (Exception e) {
      logger.warning(
          String.format(
              "Caught error while attempting to validate configuration to export to %s: %s",
              endpoint, e.getMessage()));
    }

    return false;
  }

  /**
   * Parse OTLP headers and return a map of header key to value. See: <a
   * href="https://opentelemetry.io/docs/languages/sdk-configuration/otlp-exporter/#otel_exporter_otlp_headers">...</a>
   *
   * @param headersString the headers string in format "key1=value1,key2=value2"
   * @return map of header keys to values
   */
  static Map<String, String> parseOtlpHeaders(String headersString) {
    Map<String, String> headers = new HashMap<>();
    if (headersString == null || headersString.isEmpty()) {
      return headers;
    }

    for (String pair : headersString.split(",")) {
      if (pair.contains("=")) {
        String[] keyValue = pair.split("=", 2);
        headers.put(keyValue[0].trim(), keyValue[1].trim());
      }
    }
    return headers;
  }
}
