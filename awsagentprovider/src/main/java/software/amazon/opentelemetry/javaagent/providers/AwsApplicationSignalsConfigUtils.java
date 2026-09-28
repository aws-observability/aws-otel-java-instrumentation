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
import java.util.stream.Collectors;

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
   * @return Optional string containing the updated metrics exporter config with "awsemf" removed if
   *     "awsemf" was one of the registered exporters, otherwise empty Optional if "awsemf" was not
   *     a part of the registered exporters.
   */
  static Optional<String> removeEmfExporterIfEnabled(ConfigProperties configProps) {
    String metricExporters = configProps.getString(OTEL_METRICS_EXPORTER);

    if (metricExporters == null || !metricExporters.contains("awsemf")) {
      return Optional.empty();
    }

    // Remove "awsemf" from exporters list. If "awsemf" is the only exporter, return empty
    // string instead of "none". While OTel's behavior when given an empty string for the exporter
    // is to default to the "otlp" exporter, we will deviate from this
    // because upstream will not call customizeMetricExporter if OTEL_METRICS_EXPORTER is set to
    // "none", which would prevent EMF exporter registration
    String filtered =
        Arrays.stream(metricExporters.split(","))
            .map(String::trim)
            .filter(exp -> !exp.equals("awsemf"))
            .collect(Collectors.joining(","));

    return Optional.of(filtered);
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
   *
   * <p>An explicit Authorization header selects bearer authentication (a CloudWatch Logs API key)
   * and takes precedence over SigV4.
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

    if (hasExplicitAuthorizationHeader(config, OTEL_EXPORTER_OTLP_LOGS_HEADERS)) {
      logger.info(
          "Detected an explicit OTLP logs Authorization header; preserving configured authentication instead of applying SigV4.");
      return false;
    }

    return true;
  }

  /**
   * Is the given configuration correct to enable SigV4 for Metrics?
   *
   * <ul>
   *   <li><code>OTEL_EXPORTER_OTLP_METRICS_ENDPOINT</code>
   *       =https://monitoring.[AWS-REGION].amazonaws.com/v1/metrics
   *   <li><code>OTEL_EXPORTER_OTLP_METRICS_PROTOCOL</code>=http/protobuf **
   *   <li><code>OTEL_METRICS_EXPORTER</code>=otlp **
   * </ul>
   *
   * <p>An explicit Authorization header in {@code OTEL_EXPORTER_OTLP_METRICS_HEADERS} selects
   * bearer authentication and takes precedence over SigV4. The global {@code
   * OTEL_EXPORTER_OTLP_HEADERS} is not considered; see {@link #hasExplicitAuthorizationHeader}.
   */
  static boolean isSigV4EnabledMetrics(ConfigProperties config) {
    String metricsEndpoint = config.getString(OTEL_EXPORTER_OTLP_METRICS_ENDPOINT);
    String metricsExporter = config.getString(OTEL_METRICS_EXPORTER);
    String metricsProtocol = config.getString(OTEL_EXPORTER_OTLP_METRICS_PROTOCOL);

    if (!isSigv4ValidConfig(
        metricsEndpoint,
        AWS_OTLP_METRICS_ENDPOINT_PATTERN,
        OTEL_METRICS_EXPORTER,
        metricsExporter,
        OTEL_EXPORTER_OTLP_METRICS_PROTOCOL,
        metricsProtocol)) {
      return false;
    }

    if (hasExplicitAuthorizationHeader(config, OTEL_EXPORTER_OTLP_METRICS_HEADERS)) {
      logger.info(OTLP_CONFIGURED_AUTH_EXPORTER_SELECTED_LOG);
      return false;
    }

    return true;
  }

  static boolean isAwsOtlpMetricsEndpoint(ConfigProperties config) {
    return endpointMatches(
        config.getString(OTEL_EXPORTER_OTLP_METRICS_ENDPOINT), AWS_OTLP_METRICS_ENDPOINT_PATTERN);
  }

  /**
   * Does the effective configuration for the given signal contain an explicit {@code Authorization}
   * header?
   *
   * <p>An explicit {@code Authorization} header means the user selected their own authentication
   * mode, typically a CloudWatch API key (bearer token). In that case ADOT must not also apply
   * SigV4: upstream includes values from both constant headers and the header supplier, so the
   * request would carry two {@code Authorization} values and neither mode would cleanly apply.
   *
   * <p>Only the signal-specific header map is consulted. The global {@code
   * OTEL_EXPORTER_OTLP_HEADERS} is deliberately <strong>not</strong> considered: a global {@code
   * Authorization} is not a statement about this signal's AWS endpoint, so it must not silently
   * disable SigV4. Selecting bearer authentication for an AWS endpoint requires the signal-specific
   * variable.
   *
   * <p>Known consequence, accepted: upstream falls back to the global map when the signal-specific
   * map is empty, so a global-only {@code Authorization} still reaches the exporter as a constant
   * header while SigV4 also applies, producing two {@code Authorization} values. Resolving that
   * ambiguous configuration is out of scope here and is tracked as a follow-up.
   *
   * <p>Matching is case-insensitive because header names are case-insensitive.
   */
  static boolean hasExplicitAuthorizationHeader(ConfigProperties config, String signalHeadersKey) {
    return config.getMap(signalHeadersKey).keySet().stream()
        .anyMatch("authorization"::equalsIgnoreCase);
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
   *
   * <p>An explicit Authorization header takes precedence over SigV4. The X-Ray OTLP endpoint
   * currently documents SigV4 only, so this path is primarily for forward compatibility and to
   * avoid silently overriding explicit user configuration.
   */
  static boolean isSigV4EnabledTraces(ConfigProperties config) {
    String tracesEndpoint = config.getString(OTEL_EXPORTER_OTLP_TRACES_ENDPOINT);
    String tracesExporter = config.getString(OTEL_TRACES_EXPORTER);
    String tracesProtocol = config.getString(OTEL_EXPORTER_OTLP_TRACES_PROTOCOL);

    if (!isSigv4ValidConfig(
        tracesEndpoint,
        AWS_OTLP_TRACES_ENDPOINT_PATTERN,
        OTEL_TRACES_EXPORTER,
        tracesExporter,
        OTEL_EXPORTER_OTLP_TRACES_PROTOCOL,
        tracesProtocol)) {
      return false;
    }

    if (hasExplicitAuthorizationHeader(config, OTEL_EXPORTER_OTLP_TRACES_HEADERS)) {
      logger.warning(
          "Detected an explicit OTLP traces Authorization header; preserving configured authentication instead of applying SigV4. Note that the X-Ray OTLP endpoint currently documents SigV4 authentication only.");
      return false;
    }

    return true;
  }

  private static boolean endpointMatches(String endpoint, String endpointPattern) {
    return endpoint != null
        && Pattern.compile(endpointPattern).matcher(endpoint.toLowerCase()).matches();
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
      isValidOtlpEndpoint = endpointMatches(endpoint, endpointPattern);

      if (isValidOtlpEndpoint) {
        logger.log(Level.INFO, String.format("Detected using AWS OTLP Endpoint: %s.", endpoint));

        if (exporter != null && !exporter.contains("otlp")) {
          logger.warning(
              String.format(
                  "Improper configuration: Please configure your environment variables and export/set %s to include otlp",
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
