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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.data.Data;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramBuckets;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramData;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramPointData;
import io.opentelemetry.sdk.metrics.data.GaugeData;
import io.opentelemetry.sdk.metrics.data.HistogramData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.metrics.data.SumData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.resources.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.LogEventEmitter;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public abstract class BaseEmfExporterTest {
  private static final double PRECISION_TOLERANCE = 0.00001;
  private static final Random RANDOM = new Random();
  private static long timestampCounter = System.nanoTime();
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private MetricData metricData;
  List<Map<String, Object>> capturedLogEvents;
  MetricExporter exporter;
  LogEventEmitter mockEmitter;

  abstract MetricExporter createExporter();

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

    exporter = createExporter();
    metricData = mock(MetricData.class);
  }

  @Test
  void testExportEmptyMetrics() {
    CompletableResultCode result = exporter.export(Collections.emptyList());
    assertTrue(result.isSuccess());
    assertEquals(0, capturedLogEvents.size());
  }

  @Test
  void testExportFailureHandling() {
    doThrow(new RuntimeException("Test exception")).when(mockEmitter).emit(any());

    GaugeData gaugeData = mock(GaugeData.class);
    DoublePointData pointData = mock(DoublePointData.class);
    when(pointData.getValue()).thenReturn(10.0);
    when(pointData.getAttributes()).thenReturn(Attributes.empty());
    when(pointData.getEpochNanos()).thenReturn(timestampCounter += 1_000_000);

    when(metricData.getName()).thenReturn("test.metric");
    when(metricData.getUnit()).thenReturn("1");
    when(metricData.getData()).thenReturn(gaugeData);
    when(metricData.getResource()).thenReturn(Resource.getDefault());
    when(gaugeData.getPoints()).thenReturn(Collections.singletonList(pointData));

    CompletableResultCode result = exporter.export(Collections.singletonList(metricData));

    assertFalse(result.isSuccess());
  }

  @Test
  void testSumMetricProcessing() {
    List<Number> expectedValues = generateRandomNumbers(2);
    String name = "test.sum";

    SumData sumData = mock(SumData.class);
    List<PointData> points = new ArrayList<>();
    for (Number value : expectedValues) {
      DoublePointData pointData = mock(DoublePointData.class);
      when(pointData.getValue()).thenReturn(value.doubleValue());
      when(pointData.getAttributes()).thenReturn(Attributes.empty());
      when(pointData.getEpochNanos()).thenReturn(timestampCounter += 1_000_000);
      points.add(pointData);
    }
    when(metricData.getName()).thenReturn(name);
    when(metricData.getUnit()).thenReturn("1");
    when(metricData.getData()).thenReturn(sumData);
    when(metricData.getResource()).thenReturn(Resource.getDefault());
    when(sumData.getPoints()).thenReturn(points);

    CompletableResultCode result = exporter.export(Collections.singletonList(metricData));

    assertTrue(result.isSuccess());
    assertEquals(expectedValues.size(), capturedLogEvents.size());
    for (Map<String, Object> logEvent : capturedLogEvents) {
      Map<String, Object> emfLog = this.validateEmfStructure(logEvent, name).orElseThrow();
      double actualValue = ((Number) emfLog.get(name)).doubleValue();
      assertTrue(
          expectedValues.stream()
              .anyMatch(v -> Math.abs(v.doubleValue() - actualValue) < PRECISION_TOLERANCE),
          "Actual value " + actualValue + " not found in expected values: " + expectedValues);
    }
  }

  @Test
  void testGaugeMetricProcessing() {
    List<Number> expectedValues = generateRandomNumbers(3);
    String name = "test.gauge";

    GaugeData gaugeData = mock(GaugeData.class);
    List<PointData> points = new ArrayList<>();
    for (Number value : expectedValues) {
      DoublePointData pointData = mock(DoublePointData.class);
      when(pointData.getValue()).thenReturn(value.doubleValue());
      when(pointData.getAttributes()).thenReturn(Attributes.empty());
      when(pointData.getEpochNanos()).thenReturn(timestampCounter += 1_000_000);
      points.add(pointData);
    }
    when(metricData.getName()).thenReturn(name);
    when(metricData.getUnit()).thenReturn("1");
    when(metricData.getData()).thenReturn(gaugeData);
    when(metricData.getResource()).thenReturn(Resource.getDefault());
    when(gaugeData.getPoints()).thenReturn(points);

    CompletableResultCode result = exporter.export(Collections.singletonList(metricData));

    assertTrue(result.isSuccess());
    assertEquals(expectedValues.size(), capturedLogEvents.size());
    for (Map<String, Object> logEvent : capturedLogEvents) {
      Map<String, Object> emfLog = this.validateEmfStructure(logEvent, name).orElseThrow();
      double actualValue = ((Number) emfLog.get(name)).doubleValue();
      assertTrue(
          expectedValues.stream()
              .anyMatch(v -> Math.abs(v.doubleValue() - actualValue) < PRECISION_TOLERANCE),
          "Actual value " + actualValue + " not found in expected values: " + expectedValues);
    }
  }

  @Test
  void testHistogramMetricProcessing() {
    String name = "test.histogram";

    HistogramData histogramData = mock(HistogramData.class);
    HistogramPointData pointData = mock(HistogramPointData.class);
    when(pointData.getCount()).thenReturn(10L);
    when(pointData.getSum()).thenReturn(100.0);
    when(pointData.getMin()).thenReturn(5.0);
    when(pointData.getMax()).thenReturn(25.0);
    when(pointData.getAttributes()).thenReturn(Attributes.empty());
    when(pointData.getEpochNanos()).thenReturn(timestampCounter += 1_000_000);
    when(metricData.getName()).thenReturn(name);
    when(metricData.getUnit()).thenReturn("ms");
    when(metricData.getData()).thenReturn((Data) histogramData);
    when(metricData.getResource()).thenReturn(Resource.getDefault());
    when(histogramData.getPoints()).thenReturn(Collections.singletonList(pointData));

    CompletableResultCode result = exporter.export(Collections.singletonList(metricData));

    assertTrue(result.isSuccess());
    assertEquals(1, capturedLogEvents.size());
    Map<String, Object> emfLog =
        this.validateEmfStructure(capturedLogEvents.get(0), name).orElseThrow();
    Map<String, Object> histogramDataMap = (Map<String, Object>) emfLog.get(name);
    assertEquals(10, ((Number) histogramDataMap.get("Count")).intValue());
    assertEquals(100.0, ((Number) histogramDataMap.get("Sum")).doubleValue(), PRECISION_TOLERANCE);
    assertEquals(5.0, ((Number) histogramDataMap.get("Min")).doubleValue(), PRECISION_TOLERANCE);
    assertEquals(25.0, ((Number) histogramDataMap.get("Max")).doubleValue(), PRECISION_TOLERANCE);
  }

  @Test
  void testExponentialHistogramMetricProcessing() {
    String name = "test.exp_histogram";

    ExponentialHistogramPointData dataPoint = mock(ExponentialHistogramPointData.class);
    ExponentialHistogramBuckets positiveBuckets = mock(ExponentialHistogramBuckets.class);
    ExponentialHistogramBuckets negativeBuckets = mock(ExponentialHistogramBuckets.class);
    when(metricData.getName()).thenReturn(name);
    when(metricData.getUnit()).thenReturn("s");
    when(dataPoint.getCount()).thenReturn(10L);
    when(dataPoint.getSum()).thenReturn(50.0);
    when(dataPoint.getMin()).thenReturn(1.0);
    when(dataPoint.getMax()).thenReturn(20.0);
    when(dataPoint.getScale()).thenReturn(1);
    when(dataPoint.getZeroCount()).thenReturn(0L);
    when(dataPoint.getAttributes()).thenReturn(Attributes.builder().put("env", "test").build());
    when(dataPoint.getEpochNanos()).thenReturn(1609459200000000000L);
    when(dataPoint.getPositiveBuckets()).thenReturn(positiveBuckets);
    when(positiveBuckets.getOffset()).thenReturn(0);
    when(positiveBuckets.getBucketCounts()).thenReturn(Arrays.asList(1L, 2L, 1L));
    when(dataPoint.getNegativeBuckets()).thenReturn(negativeBuckets);
    when(negativeBuckets.getOffset()).thenReturn(0);
    when(negativeBuckets.getBucketCounts()).thenReturn(Collections.<Long>emptyList());
    ExponentialHistogramData expHistogramData = mock(ExponentialHistogramData.class);
    when(expHistogramData.getPoints()).thenReturn(Collections.singletonList(dataPoint));
    when(metricData.getData()).thenReturn((Data) expHistogramData);
    when(metricData.getResource()).thenReturn(Resource.getDefault());

    CompletableResultCode result = exporter.export(Collections.singletonList(metricData));

    assertTrue(result.isSuccess());
    assertEquals(1, capturedLogEvents.size());
    Map<String, Object> emfLog =
        this.validateEmfStructure(capturedLogEvents.get(0), name).orElseThrow();
    Map<String, Object> expHistogramRecord = (Map<String, Object>) emfLog.get(name);
    assertTrue(expHistogramRecord.containsKey("Count"));
    assertTrue(expHistogramRecord.containsKey("Sum"));
    assertTrue(expHistogramRecord.containsKey("Values"));
    assertTrue(expHistogramRecord.containsKey("Counts"));
    assertEquals(10, ((Number) expHistogramRecord.get("Count")).intValue());
    assertEquals(50.0, ((Number) expHistogramRecord.get("Sum")).doubleValue(), PRECISION_TOLERANCE);

    List<Number> values = (List<Number>) expHistogramRecord.get("Values");
    List<Number> counts = (List<Number>) expHistogramRecord.get("Counts");
    assertEquals(values.size(), counts.size());
    List<Double> expectedValues = Arrays.asList(1.2071068, 1.7071068, 2.4142136);
    List<Long> expectedCounts = Arrays.asList(1L, 2L, 1L);
    assertEquals(expectedValues.size(), values.size());
    for (int i = 0; i < expectedValues.size(); i++) {
      assertEquals(expectedValues.get(i), values.get(i).doubleValue(), PRECISION_TOLERANCE);
      assertEquals(expectedCounts.get(i).longValue(), counts.get(i).longValue());
    }
  }

  @Test
  void testGroupByAttributesAndTimestamp() {
    String name1 = "test.metric1";
    String name2 = "test.metric2";
    long timestamp = 1234567890000000L;
    Attributes attrs = Attributes.builder().put("env", "test").build();

    MetricData metric1 = mock(MetricData.class);
    GaugeData gaugeData1 = mock(GaugeData.class);
    DoublePointData pointData1 = mock(DoublePointData.class);
    when(pointData1.getValue()).thenReturn(10.0);
    when(pointData1.getAttributes()).thenReturn(attrs);
    when(pointData1.getEpochNanos()).thenReturn(timestamp);
    when(metric1.getName()).thenReturn(name1);
    when(metric1.getUnit()).thenReturn("1");
    when(metric1.getData()).thenReturn(gaugeData1);
    when(metric1.getResource()).thenReturn(Resource.getDefault());
    when(gaugeData1.getPoints()).thenReturn(Collections.singletonList(pointData1));

    MetricData metric2 = mock(MetricData.class);
    GaugeData gaugeData2 = mock(GaugeData.class);
    DoublePointData pointData2 = mock(DoublePointData.class);
    when(pointData2.getValue()).thenReturn(20.0);
    when(pointData2.getAttributes()).thenReturn(attrs);
    when(pointData2.getEpochNanos()).thenReturn(timestamp);
    when(metric2.getName()).thenReturn(name2);
    when(metric2.getUnit()).thenReturn("1");
    when(metric2.getData()).thenReturn((Data) gaugeData2);
    when(metric2.getResource()).thenReturn(Resource.getDefault());
    when(gaugeData2.getPoints()).thenReturn(Collections.singletonList(pointData2));

    CompletableResultCode result = exporter.export(Arrays.asList(metric1, metric2));

    assertTrue(result.isSuccess());
    assertEquals(1, capturedLogEvents.size());
    Map<String, Object> emfLog =
        this.validateEmfStructure(capturedLogEvents.get(0), name1).orElseThrow();
    assertTrue(emfLog.containsKey(name1));
    assertTrue(emfLog.containsKey(name2));
    assertEquals(10.0, ((Number) emfLog.get(name1)).doubleValue(), PRECISION_TOLERANCE);
    assertEquals(20.0, ((Number) emfLog.get(name2)).doubleValue(), PRECISION_TOLERANCE);
    assertEquals("test", emfLog.get("env"));
  }

  protected Optional<Map<String, Object>> validateEmfStructure(
      Map<String, Object> logEvent, String metricName) {
    assertTrue(logEvent.containsKey("message"));
    assertTrue(logEvent.containsKey("timestamp"));

    String messageJson = (String) logEvent.get("message");
    try {
      Map<String, Object> emfLog =
          objectMapper.readValue(messageJson, new TypeReference<Map<String, Object>>() {});
      assertTrue(emfLog.containsKey("_aws"));
      Map<String, Object> awsMetadata = (Map<String, Object>) emfLog.get("_aws");
      assertTrue(awsMetadata.containsKey("CloudWatchMetrics"));
      List<Map<String, Object>> cloudWatchMetrics =
          (List<Map<String, Object>>) awsMetadata.get("CloudWatchMetrics");
      assertEquals(1, cloudWatchMetrics.size());
      Map<String, Object> metricGroup = cloudWatchMetrics.get(0);
      List<Map<String, Object>> metrics = (List<Map<String, Object>>) metricGroup.get("Metrics");
      assertTrue(metrics.size() >= 1);
      boolean foundMetric = metrics.stream().anyMatch(m -> metricName.equals(m.get("Name")));
      assertTrue(foundMetric, "Expected metric " + metricName + " not found in metrics list");
      assertTrue(emfLog.containsKey(metricName));
      return Optional.of(emfLog);
    } catch (Exception e) {
      fail("Failed to parse JSON message: " + e.getMessage());
      return Optional.empty();
    }
  }

  private List<Number> generateRandomNumbers(int count) {
    List<Number> values = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      values.add(RANDOM.nextDouble() * 100);
    }
    return values;
  }
}
