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

package software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.GaugeData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.SumData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.resources.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.LogEventEmitter;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AwsCloudWatchEmfExporterTest {
  private static final String NAMESPACE = "test-namespace";
  private MetricExporter exporter;
  private LogEventEmitter mockEmitter;
  private List<Map<String, Object>> capturedLogEvents;

  @BeforeEach
  void setup() {
    mockEmitter = mock(LogEventEmitter.class);
    capturedLogEvents = new ArrayList<>();

    doAnswer(
            invocation -> {
              capturedLogEvents.add(invocation.getArgument(0));
              return null;
            })
        .when(mockEmitter)
        .emit(any());

    exporter = new AwsCloudWatchEmfExporter(NAMESPACE, mockEmitter);
  }

  @Test
  void testExporterCreation() {}

  @Test
  void testGaugeAndSumMetricProcessing() {
    MetricData gaugeMetric = createGaugeMetricWithPoint("test.gauge", 42.0);
    MetricData sumMetric = createSumMetricWithPoint("test.sum", 100L);

    exporter.export(List.of(gaugeMetric, sumMetric));

    // Validate that log events were emitted
    assertEquals(2, capturedLogEvents.size());

    for (Map<String, Object> logEvent : capturedLogEvents) {
      assertNotNull(logEvent.get("message"));
      assertNotNull(logEvent.get("timestamp"));
    }
  }

  private MetricData createGaugeMetricWithPoint(String name, double value) {
    MetricData metricData = mock(MetricData.class);
    GaugeData gaugeData = mock(GaugeData.class);

    DoublePointData pointData = mock(DoublePointData.class);

    when(metricData.getName()).thenReturn(name);
    when(metricData.getUnit()).thenReturn("1");
    when(metricData.getData()).thenReturn(gaugeData);
    when(metricData.getResource()).thenReturn(Resource.getDefault());
    when(gaugeData.getPoints()).thenReturn(List.of(pointData));

    when(pointData.getValue()).thenReturn(value);
    when(pointData.getAttributes()).thenReturn(Attributes.empty());
    when(pointData.getEpochNanos()).thenReturn(System.nanoTime());

    return metricData;
  }

  private MetricData createSumMetricWithPoint(String name, long value) {
    MetricData metricData = mock(MetricData.class);
    SumData sumData = mock(SumData.class);
    DoublePointData pointData = mock(DoublePointData.class);

    when(metricData.getName()).thenReturn(name);
    when(metricData.getUnit()).thenReturn("1");
    when(metricData.getData()).thenReturn(sumData);
    when(metricData.getResource()).thenReturn(Resource.getDefault());
    when(sumData.getPoints()).thenReturn(List.of(pointData));
    when(pointData.getValue()).thenReturn((double) value);
    when(pointData.getAttributes()).thenReturn(Attributes.empty());
    when(pointData.getEpochNanos()).thenReturn(System.nanoTime());

    return metricData;
  }
}
