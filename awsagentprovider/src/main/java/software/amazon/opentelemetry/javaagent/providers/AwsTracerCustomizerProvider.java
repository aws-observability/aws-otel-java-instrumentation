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

    // Set default OpenTelemetry environment variables if they are not already set
    setDefaultSystemProperty("otel.metrics.exporter", "none");
    setDefaultSystemProperty("otel.logs.export", "none");
    setDefaultSystemProperty("otel.aws.application.signals.enabled", "false"); // Default to false
    setDefaultSystemProperty("otel.aws.application.signals.exporter.endpoint", "http://localhost:4316/v1/metrics");
    setDefaultSystemProperty("otel.exporter.otlp.protocol", "http/protobuf");
    setDefaultSystemProperty("otel.exporter.otlp.traces.endpoint", "http://localhost:4316/v1/traces");
    setDefaultSystemProperty("otel.traces.sampler", "xray");
    setDefaultSystemProperty("otel.traces.sampler.arg", "endpoint=http://localhost:2000");
  }

  private static void setDefaultSystemProperty(String key, String defaultValue) {
    if (System.getProperty(key) == null && System.getenv(key.toUpperCase().replace('.', '_')) == null) {
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
