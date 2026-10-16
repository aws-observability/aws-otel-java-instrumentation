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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.traces;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.AbstractOtlpAwsExporterProviderTest;

class OtlpAwsSpanExporterProviderTest
    extends AbstractOtlpAwsExporterProviderTest<OtlpAwsSpanExporter> {
  OtlpAwsSpanExporterProviderTest() {
    super("traces", "xray", OtlpAwsSpanExporter.class, OtlpHttpSpanExporter.class);
  }

  @Override
  protected OtlpAwsSpanExporter createExporter(Map<String, String> properties) {
    return (OtlpAwsSpanExporter)
        ServiceLoader.load(ConfigurableSpanExporterProvider.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(provider -> provider.getName().equals("otlp/sigv4"))
            .findFirst()
            .orElseThrow()
            .createExporter(DefaultConfigProperties.createFromMap(properties));
  }

  @Override
  protected void shutdown(OtlpAwsSpanExporter exporter) {
    exporter.shutdown();
  }

  @Override
  protected void addExporterCustomizer(
      AutoConfiguredOpenTelemetrySdkBuilder builder, Consumer<Object> customizer) {
    builder.addSpanExporterCustomizer(
        (exporter, unused) -> {
          customizer.accept(exporter);
          return exporter;
        });
  }
}
