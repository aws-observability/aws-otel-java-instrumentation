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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.AbstractOtlpAwsExporterProviderTest;

class OtlpAwsMetricExporterProviderTest
    extends AbstractOtlpAwsExporterProviderTest<OtlpAwsMetricExporter> {
  OtlpAwsMetricExporterProviderTest() {
    super("metrics", "monitoring", OtlpAwsMetricExporter.class, OtlpHttpMetricExporter.class);
  }

  @Test
  void testOtlpAwsExporterShouldPreserveTemporalityAndAggregation() {
    Map<String, String> properties = properties();
    properties.put("otel.exporter.otlp.metrics.temporality.preference", "DELTA");
    properties.put(
        "otel.exporter.otlp.metrics.default.histogram.aggregation",
        "base2_exponential_bucket_histogram");
    OtlpAwsMetricExporter exporter = createExporter(properties);
    try {
      assertThat(exporter.getAggregationTemporality(InstrumentType.COUNTER))
          .isEqualTo(AggregationTemporality.DELTA);
      assertThat(exporter.getDefaultAggregation(InstrumentType.HISTOGRAM).toString())
          .contains("Base2ExponentialHistogram");
    } finally {
      exporter.shutdown();
    }
  }

  @Override
  protected OtlpAwsMetricExporter createExporter(Map<String, String> properties) {
    return (OtlpAwsMetricExporter)
        ServiceLoader.load(ConfigurableMetricExporterProvider.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(provider -> provider.getName().equals("otlp/sigv4"))
            .findFirst()
            .orElseThrow()
            .createExporter(DefaultConfigProperties.createFromMap(properties));
  }

  @Override
  protected void shutdown(OtlpAwsMetricExporter exporter) {
    exporter.shutdown();
  }

  @Override
  protected void addExporterCustomizer(
      AutoConfiguredOpenTelemetrySdkBuilder builder, Consumer<Object> customizer) {
    builder.addMetricExporterCustomizer(
        (exporter, unused) -> {
          customizer.accept(exporter);
          return exporter;
        });
  }
}
