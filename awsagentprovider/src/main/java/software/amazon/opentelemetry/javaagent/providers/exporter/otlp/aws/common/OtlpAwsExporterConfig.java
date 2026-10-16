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

import io.opentelemetry.exporter.otlp.internal.OtlpConfigUtil;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

/** Shared configuration for the {@code otlp/sigv4} exporter providers. */
public final class OtlpAwsExporterConfig {
  public static final String EXPORTER_NAME = "otlp/sigv4";
  public static final String SIGV4_SERVICE = "otel.exporter.otlp.sigv4.service";

  private static final Pattern AWS_ENDPOINT_REGION =
      Pattern.compile(
          "(?:[a-z0-9-]+\\.)*([a-z]+(?:-[a-z]+)+-\\d+)(?:\\.[a-z0-9-]+)*\\."
              + "(?:amazonaws\\.com(?:\\.cn)?|api\\.aws|c2s\\.ic\\.gov|sc2s\\.sgov\\.gov"
              + "|cloud\\.adc-e\\.uk|csp\\.hci\\.ic\\.gov)",
          Pattern.CASE_INSENSITIVE);

  private OtlpAwsExporterConfig() {}

  public static void validate(String signal, ConfigProperties config) {
    String protocol = OtlpConfigUtil.getOtlpProtocol(signal, config);
    if (!OtlpConfigUtil.PROTOCOL_HTTP_PROTOBUF.equals(protocol)) {
      throw new ConfigurationException(
          "otlp/sigv4 requires OTEL_EXPORTER_OTLP_"
              + signal.toUpperCase(java.util.Locale.ROOT)
              + "_PROTOCOL=http/protobuf (or OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf).");
    }
    Map<String, String> headers = config.getMap("otel.exporter.otlp." + signal + ".headers");
    if (headers.isEmpty()) {
      headers = config.getMap("otel.exporter.otlp.headers");
    }
    if (headers.keySet().stream().anyMatch("Authorization"::equalsIgnoreCase)) {
      throw new ConfigurationException(
          "otlp/sigv4 cannot be combined with an OTLP Authorization header; "
              + "remove the header to use AWS SigV4 authentication.");
    }
  }

  public static String service(String signal, ConfigProperties config, String defaultService) {
    return config.getString(
        "otel.exporter.otlp." + signal + ".sigv4.service",
        config.getString(SIGV4_SERVICE, defaultService));
  }

  public static String region(ConfigProperties config, String endpoint) {
    return resolveRegion(
        config.getString("aws.region", config.getString("aws.default.region")), endpoint);
  }

  public static String compression(String signal, ConfigProperties config) {
    String compression =
        config.getString(
            "otel.exporter.otlp." + signal + ".compression",
            config.getString("otel.exporter.otlp.compression", "none"));
    if (!"none".equals(compression) && !"gzip".equals(compression)) {
      throw new ConfigurationException("otlp/sigv4 supports only none or gzip compression.");
    }
    return compression;
  }

  /** Explicit region, Java/AWS region settings, AWS hostname, then the AWS SDK region chain. */
  public static String resolveRegion(String explicitRegion, String endpoint) {
    String region = explicitRegion;
    if (region == null || region.isEmpty()) {
      region = System.getProperty("aws.region");
    }
    if (region == null || region.isEmpty()) {
      region = System.getenv("AWS_REGION");
    }
    if (region == null || region.isEmpty()) {
      region = System.getenv("AWS_DEFAULT_REGION");
    }
    if (region != null && !region.isEmpty()) {
      return region;
    }

    String host = URI.create(endpoint).getHost();
    if (host != null) {
      Matcher matcher = AWS_ENDPOINT_REGION.matcher(host);
      if (matcher.matches()) {
        return matcher.group(1).toLowerCase(java.util.Locale.ROOT);
      }
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
}
