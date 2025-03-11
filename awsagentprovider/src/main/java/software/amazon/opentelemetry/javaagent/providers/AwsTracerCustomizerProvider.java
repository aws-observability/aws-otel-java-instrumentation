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

import io.opentelemetry.contrib.awsxray.AwsXrayIdGenerator;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

public class AwsTracerCustomizerProvider implements AutoConfigurationCustomizerProvider {
  static {
    if (System.getProperty("otel.aws.imds.endpointOverride") == null) {
      String overrideFromEnv = System.getenv("OTEL_AWS_IMDS_ENDPOINT_OVERRIDE");
      if (overrideFromEnv != null) {
        System.setProperty("otel.aws.imds.endpointOverride", overrideFromEnv);
      }
    }

    // Set OpenTelemetry environment variables based on env variables first
    setSystemPropertyFromEnvOrDefault("otel.aws.application.signals.enabled", "false"); // Default to false
    if ("true".equals(System.getProperty("otel.aws.application.signals.enabled"))) {
      setSystemPropertyFromEnvOrDefault("otel.metrics.exporter", "none");
      setSystemPropertyFromEnvOrDefault("otel.logs.export", "none");
      setSystemPropertyFromEnvOrDefault("otel.aws.application.signals.exporter.endpoint", "http://localhost:4316/v1/metrics");
      setSystemPropertyFromEnvOrDefault("otel.exporter.otlp.protocol", "http/protobuf");
      setSystemPropertyFromEnvOrDefault("otel.exporter.otlp.traces.endpoint", "http://localhost:4316/v1/traces");
      setSystemPropertyFromEnvOrDefault("otel.traces.sampler", "xray");
      setSystemPropertyFromEnvOrDefault("otel.traces.sampler.arg", "endpoint=http://localhost:2000");
    }
  }

  private static void setSystemPropertyFromEnvOrDefault(String key, String defaultValue) {
    String envValue = System.getenv(key.toUpperCase().replace('.', '_'));
    if (envValue != null) {
      System.setProperty(key, envValue);
    } else if (System.getProperty(key) == null) {
      System.setProperty(key, defaultValue);
    }
  }

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    autoConfiguration.addTracerProviderCustomizer(
            (tracerProviderBuilder, configProps) ->
                    tracerProviderBuilder.setIdGenerator(AwsXrayIdGenerator.getInstance()));
  }
}
