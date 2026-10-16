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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.logs;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.logs.ConfigurableLogRecordExporterProvider;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.AbstractOtlpAwsExporterProviderTest;

class OtlpAwsLogRecordExporterProviderTest
    extends AbstractOtlpAwsExporterProviderTest<OtlpAwsLogRecordExporter> {
  OtlpAwsLogRecordExporterProviderTest() {
    super("logs", "logs", OtlpAwsLogRecordExporter.class, OtlpHttpLogRecordExporter.class);
  }

  @BeforeEach
  void setup() {
    OtlpAwsLogRecordExporterBuilder builder =
        OtlpAwsLogRecordExporterBuilder.create(OtlpHttpLogRecordExporter.builder(), endpoint)
            .setAwsRegion("us-east-1");
    setAwsService = builder::setAwsService;
    buildExporter = builder::build;
  }

  @Override
  protected OtlpAwsLogRecordExporter createExporter(Map<String, String> properties) {
    return (OtlpAwsLogRecordExporter)
        ServiceLoader.load(ConfigurableLogRecordExporterProvider.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(provider -> provider.getName().equals("otlp/sigv4"))
            .findFirst()
            .orElseThrow()
            .createExporter(DefaultConfigProperties.createFromMap(properties));
  }

  @Override
  protected void shutdown(OtlpAwsLogRecordExporter exporter) {
    exporter.shutdown();
  }

  @Override
  protected void addExporterCustomizer(
      AutoConfiguredOpenTelemetrySdkBuilder builder, Consumer<Object> customizer) {
    builder.addLogRecordExporterCustomizer(
        (exporter, unused) -> {
          customizer.accept(exporter);
          return exporter;
        });
  }
}
