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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

/** Configuration utilities for the AWS SigV4 OTLP exporters. */
public final class OtlpAwsConfigUtil {
  public static final String DATA_TYPE_TRACES = "traces";
  public static final String DATA_TYPE_METRICS = "metrics";
  public static final String DATA_TYPE_LOGS = "logs";

  public static final String DEFAULT_TRACES_ENDPOINT = "http://localhost:4318/v1/traces";
  public static final String DEFAULT_METRICS_ENDPOINT = "http://localhost:4318/v1/metrics";
  public static final String DEFAULT_LOGS_ENDPOINT = "http://localhost:4318/v1/logs";

  public static final String DEFAULT_TRACES_AWS_SERVICE = "xray";
  public static final String DEFAULT_METRICS_AWS_SERVICE = "monitoring";
  public static final String DEFAULT_LOGS_AWS_SERVICE = "logs";

  private static final Pattern AWS_ENDPOINT_REGION =
      Pattern.compile(
          "(?:[a-z0-9-]+\\.)*([a-z]+(?:-[a-z]+)+-\\d+)(?:\\.[a-z0-9-]+)*\\."
              + "(?:amazonaws\\.com(?:\\.cn)?|api\\.aws|c2s\\.ic\\.gov|sc2s\\.sgov\\.gov"
              + "|cloud\\.adc-e\\.uk|csp\\.hci\\.ic\\.gov)",
          Pattern.CASE_INSENSITIVE);

  private OtlpAwsConfigUtil() {}

  /** Per-data-type AWS exporter service, global service, then the default AWS service. */
  public static String getAwsService(String dataType, ConfigProperties config) {
    String defaultAwsService;
    switch (dataType) {
      case DATA_TYPE_TRACES:
        defaultAwsService = DEFAULT_TRACES_AWS_SERVICE;
        break;
      case DATA_TYPE_METRICS:
        defaultAwsService = DEFAULT_METRICS_AWS_SERVICE;
        break;
      case DATA_TYPE_LOGS:
        defaultAwsService = DEFAULT_LOGS_AWS_SERVICE;
        break;
      default:
        throw new IllegalArgumentException("Unsupported OTLP data type: " + dataType);
    }
    return config.getString(
        "aws." + dataType + ".exporter.service",
        config.getString("aws.exporter.service", defaultAwsService));
  }

  /**
   * Determine the signing region from the resolved AWS endpoint, then region configuration, then
   * the AWS SDK region provider chain. OTel normalizes {@code AWS_REGION} and {@code
   * AWS_DEFAULT_REGION} to the corresponding dotted property keys. Standalone builders without
   * configuration use Java/AWS region settings directly.
   */
  public static String getAwsRegion(@Nullable ConfigProperties config, String endpoint) {
    String host = URI.create(endpoint).getHost();
    if (host != null) {
      Matcher matcher = AWS_ENDPOINT_REGION.matcher(host);
      if (matcher.matches()) {
        return matcher.group(1).toLowerCase(Locale.ROOT);
      }
    }

    String region;
    if (config != null) {
      region = config.getString("aws.region", config.getString("aws.default.region"));
    } else {
      region = System.getProperty("aws.region");
      if (region == null || region.isEmpty()) {
        region = System.getenv("AWS_REGION");
      }
      if (region == null || region.isEmpty()) {
        region = System.getenv("AWS_DEFAULT_REGION");
      }
    }
    if (region != null && !region.isEmpty()) {
      return region;
    }
    try {
      return DefaultAwsRegionProviderChain.builder().build().getRegion().id();
    } catch (SdkClientException e) {
      throw new ConfigurationException(
          "otlp/sigv4 requires an AWS endpoint region, aws.region, AWS_REGION, "
              + "AWS_DEFAULT_REGION, or a region in the AWS SDK region provider chain.",
          e);
    }
  }

  /** Determine the configured compression for the {@code dataType}. */
  public static String getCompression(String dataType, ConfigProperties config) {
    String compression =
        config.getString(
            "otel.exporter.otlp." + dataType + ".compression",
            config.getString("otel.exporter.otlp.compression", "none"));
    if (!"none".equals(compression) && !"gzip".equals(compression)) {
      throw new ConfigurationException("otlp/sigv4 supports only none or gzip compression.");
    }
    return compression;
  }
}
