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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common.AbstractOtlpAwsExporterTest;

class OtlpAwsMetricExporterTest extends AbstractOtlpAwsExporterTest<MetricData> {
  private static final String METRICS_OTLP_ENDPOINT =
      "https://monitoring.us-east-1.amazonaws.com/v1/metrics";

  @Mock private OtlpHttpMetricExporterBuilder mockBuilder;
  @Mock private OtlpHttpMetricExporter mockExporter;
  private MetricExporter exporter;

  OtlpAwsMetricExporterTest() {
    super(METRICS_OTLP_ENDPOINT, "us-east-1", "monitoring");
  }

  @BeforeEach
  @Override
  protected void setup() {
    lenient().when(this.mockExporter.toBuilder()).thenReturn(mockBuilder);
    lenient().when(this.mockBuilder.setEndpoint(any())).thenReturn(mockBuilder);
    lenient().when(this.mockBuilder.setMemoryMode(any())).thenReturn(this.mockBuilder);
    lenient().when(this.mockBuilder.setHeaders(any())).thenReturn(mockBuilder);
    lenient().when(this.mockBuilder.build()).thenReturn(this.mockExporter);
    super.setup();
  }

  @Override
  protected MetricData createOtlpData(int index) {
    return ImmutableMetricData.createDoubleGauge(
        TEST_RESOURCE,
        InstrumentationScopeInfo.builder("test.scope." + index).setVersion("1.2.3").build(),
        "test.metric." + index,
        "test metric description",
        "1",
        ImmutableGaugeData.create(
            List.of(
                ImmutableDoublePointData.create(
                    1L,
                    2L,
                    Attributes.builder()
                        .put("test.name", "metric")
                        .put("test.count", (long) index)
                        .put("test.enabled", true)
                        .build(),
                    index + 0.5))));
  }

  @Override
  protected void validateOtlpPayload(byte[] payload, Collection<MetricData> data) throws Exception {
    ExportMetricsServiceRequest decoded = ExportMetricsServiceRequest.parseFrom(payload);
    assertEquals(1, decoded.getResourceMetricsCount());
    ResourceMetrics resource = decoded.getResourceMetrics(0);
    assertResource(resource.getResource());
    assertEquals(data.size(), resource.getScopeMetricsCount());
    Map<String, MetricData> expected =
        data.stream()
            .collect(
                Collectors.toMap(
                    expectedData -> expectedData.getInstrumentationScopeInfo().getName(),
                    expectedData -> expectedData));
    for (ScopeMetrics scope : resource.getScopeMetricsList()) {
      MetricData expectedData = expected.remove(scope.getScope().getName());
      assertNotNull(expectedData, "unexpected or repeated scope " + scope.getScope().getName());
      assertScope(expectedData.getInstrumentationScopeInfo(), scope.getScope());
      assertEquals(1, scope.getMetricsCount());
      Metric metric = scope.getMetrics(0);
      assertEquals(expectedData.getName(), metric.getName());
      assertEquals(expectedData.getDescription(), metric.getDescription());
      assertEquals(expectedData.getUnit(), metric.getUnit());
      assertTrue(metric.hasGauge());
      assertEquals(1, metric.getGauge().getDataPointsCount());
      NumberDataPoint point = metric.getGauge().getDataPoints(0);
      DoublePointData expectedPoint =
          expectedData.getDoubleGaugeData().getPoints().iterator().next();
      assertEquals(NumberDataPoint.ValueCase.AS_DOUBLE, point.getValueCase());
      assertEquals(expectedPoint.getValue(), point.getAsDouble());
      assertEquals(expectedPoint.getStartEpochNanos(), point.getStartTimeUnixNano());
      assertEquals(expectedPoint.getEpochNanos(), point.getTimeUnixNano());
      assertAttributes(expectedPoint.getAttributes(), point.getAttributesList());
      assertEquals(0, point.getFlags());
      assertEquals(expectedPoint.getExemplars().size(), point.getExemplarsCount());
    }
    assertTrue(expected.isEmpty());
  }

  @Override
  protected CompletableResultCode export(Collection<MetricData> data) {
    if (exporter == null) {
      exporter =
          OtlpAwsMetricExporterBuilder.create(
                  OtlpHttpMetricExporter.builder()
                      .setComponentLoader(componentLoader)
                      .setEndpoint(endpoint)
                      .setCompression(otlpCompression)
                      .build(),
                  endpoint)
              .setCompression(compression)
              .build();
    }
    return exporter.export(data);
  }

  @Override
  protected void shutdownExporter() {
    if (exporter != null) {
      exporter.shutdown();
    }
  }

  @Test
  void testMetricsExporterDelegatesMetricSettings() {
    Aggregation aggregation = Aggregation.sum();
    when(this.mockExporter.getAggregationTemporality(InstrumentType.COUNTER))
        .thenReturn(AggregationTemporality.DELTA);
    when(this.mockExporter.getDefaultAggregation(InstrumentType.COUNTER)).thenReturn(aggregation);

    OtlpAwsMetricExporter exporter =
        OtlpAwsMetricExporterBuilder.create(this.mockExporter, METRICS_OTLP_ENDPOINT).build();

    assertEquals(
        AggregationTemporality.DELTA, exporter.getAggregationTemporality(InstrumentType.COUNTER));
    assertSame(aggregation, exporter.getDefaultAggregation(InstrumentType.COUNTER));
  }

  @Test
  void testMetricsExporterDelegatesFlushAndShutdown() {
    CompletableResultCode flushResult = CompletableResultCode.ofSuccess();
    CompletableResultCode shutdownResult = CompletableResultCode.ofSuccess();
    when(this.mockExporter.flush()).thenReturn(flushResult);
    when(this.mockExporter.shutdown()).thenReturn(shutdownResult);

    OtlpAwsMetricExporter exporter =
        OtlpAwsMetricExporterBuilder.create(this.mockExporter, METRICS_OTLP_ENDPOINT).build();

    assertSame(flushResult, exporter.flush());
    assertSame(shutdownResult, exporter.shutdown());
  }
}
