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
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
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
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.common.emitter.LogEventEmitter;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public abstract class BaseEmfExporterTest<T> {
  private static final double PRECISION_TOLERANCE = 0.00001;
  private static final Random RANDOM = new Random();
  private static long timestampCounter = System.nanoTime();
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private MetricData metricData;
  static final String NAMESPACE = "test-namespace";
  List<Map<String, Object>> capturedLogEvents;
  MetricExporter exporter;
  LogEventEmitter<T> mockEmitter;

  abstract LogEventEmitter<T> createEmitter();

  abstract MetricExporter buildExporter(boolean shouldAddAppSignals);

  @BeforeEach
  void setup() {
    capturedLogEvents = new ArrayList<>();
    mockEmitter = createEmitter();
    exporter = buildExporter(false);
    metricData = mock(MetricData.class);

    doAnswer(
            invocation -> {
              capturedLogEvents.add(invocation.getArgument(0));
              return null;
            })
        .when(mockEmitter)
        .emit(any());

    doNothing().when(mockEmitter).flushEvents();
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

  @ParameterizedTest
  @MethodSource("applicationSignalsDimensionsProvider")
  void testApplicationSignalsDimensions(
      Map<String, String> resourceAttrs, String expectedService, String expectedEnvironment) {
    MetricExporter exporterWithAppSignals = buildExporter(true);

    Resource resource = Resource.empty();
    for (Map.Entry<String, String> entry : resourceAttrs.entrySet()) {
      resource = resource.toBuilder().put(entry.getKey(), entry.getValue()).build();
    }

    GaugeData gaugeData = mock(GaugeData.class);
    DoublePointData pointData = mock(DoublePointData.class);
    when(pointData.getValue()).thenReturn(10.0);
    when(pointData.getAttributes()).thenReturn(Attributes.empty());
    when(pointData.getEpochNanos()).thenReturn(timestampCounter += 1_000_000);

    when(metricData.getName()).thenReturn("test.metric");
    when(metricData.getUnit()).thenReturn("1");
    when(metricData.getData()).thenReturn(gaugeData);
    when(metricData.getResource()).thenReturn(resource);
    when(gaugeData.getPoints()).thenReturn(Collections.singletonList(pointData));

    CompletableResultCode result =
        exporterWithAppSignals.export(Collections.singletonList(metricData));

    assertTrue(result.isSuccess());
    assertEquals(1, capturedLogEvents.size());

    Map<String, Object> emfLog =
        validateEmfStructure(capturedLogEvents.get(0), "test.metric").orElseThrow();

    assertEquals(expectedService, emfLog.get("Service"));
    assertEquals(expectedEnvironment, emfLog.get("Environment"));

    Map<String, Object> awsMetadata = (Map<String, Object>) emfLog.get("_aws");
    List<Map<String, Object>> cloudWatchMetrics =
        (List<Map<String, Object>>) awsMetadata.get("CloudWatchMetrics");
    Map<String, Object> metricGroup = cloudWatchMetrics.get(0);
    List<List<String>> dimensions = (List<List<String>>) metricGroup.get("Dimensions");

    List<String> dimensionNames = dimensions.get(0);
    assertTrue(dimensionNames.contains("Service"));
    assertTrue(dimensionNames.contains("Environment"));
  }

  @ParameterizedTest
  @MethodSource("metricsGroupingProvider")
  void testGroupByAttributesAndTimestamp(List<MetricData> metrics, int expectedLogCount) {
    CompletableResultCode result = exporter.export(metrics);
    assertTrue(result.isSuccess());
    assertEquals(expectedLogCount, capturedLogEvents.size());
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
      assertEquals(NAMESPACE, metricGroup.get("Namespace"));
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

  protected Map<String, Object> createLogEvent(String message, long timestamp) {
    Map<String, Object> logEvent = new HashMap<>();
    logEvent.put("message", message);
    logEvent.put("timestamp", timestamp);
    return logEvent;
  }

  private static MetricData createMetric(
      String name,
      double value,
      Attributes attrs,
      long timestamp,
      Resource resource,
      InstrumentationScopeInfo scope) {
    MetricData metric = mock(MetricData.class);
    GaugeData gaugeData = mock(GaugeData.class);
    DoublePointData pointData = mock(DoublePointData.class);
    when(pointData.getValue()).thenReturn(value);
    when(pointData.getAttributes()).thenReturn(attrs);
    when(pointData.getEpochNanos()).thenReturn(timestamp);
    when(metric.getName()).thenReturn(name);
    when(metric.getUnit()).thenReturn("1");
    when(metric.getData()).thenReturn(gaugeData);
    when(metric.getResource()).thenReturn(resource);
    when(metric.getInstrumentationScopeInfo()).thenReturn(scope);
    when(gaugeData.getPoints()).thenReturn(Collections.singletonList(pointData));
    return metric;
  }

  private List<Number> generateRandomNumbers(int count) {
    List<Number> values = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      values.add(RANDOM.nextDouble() * 100);
    }
    return values;
  }

  static List<Arguments> applicationSignalsDimensionsProvider() {
    return Arrays.asList(
        // Both service.name and deployment.environment.name provided
        Arguments.of(
            Map.of("service.name", "test-service", "deployment.environment.name", "prod"),
            "test-service",
            "prod"),
        // Only service.name provided, environment defaults to generic:default
        Arguments.of(Map.of("service.name", "test-service"), "test-service", "generic:default"),
        // No service.name, defaults to UnknownService
        Arguments.of(Map.of("deployment.environment.name", "staging"), "UnknownService", "staging"),
        // Empty service.name, defaults to UnknownService
        Arguments.of(
            Map.of("service.name", "", "deployment.environment.name", "dev"),
            "UnknownService",
            "dev"),
        // No attributes, both default
        Arguments.of(Map.of(), "UnknownService", "generic:default"),
        // cloud.platform=aws_ec2, environment defaults to ec2:default
        Arguments.of(
            Map.of("service.name", "ec2-service", "cloud.platform", "aws_ec2"),
            "ec2-service",
            "ec2:default"),
        // cloud.platform=aws_ecs, environment defaults to ecs:default
        Arguments.of(
            Map.of("service.name", "ecs-service", "cloud.platform", "aws_ecs"),
            "ecs-service",
            "ecs:default"),
        // cloud.platform=aws_eks, environment defaults to eks:default
        Arguments.of(
            Map.of("service.name", "eks-service", "cloud.platform", "aws_eks"),
            "eks-service",
            "eks:default"),
        // cloud.platform=aws_lambda, environment defaults to lambda:default
        Arguments.of(
            Map.of("service.name", "lambda-service", "cloud.platform", "aws_lambda"),
            "lambda-service",
            "lambda:default"),
        // deployment.environment.name takes precedence over cloud.platform
        Arguments.of(
            Map.of(
                "service.name",
                "override-service",
                "deployment.environment.name",
                "custom-env",
                "cloud.platform",
                "aws_ec2"),
            "override-service",
            "custom-env"));
  }

  static List<Arguments> metricsGroupingProvider() {
    Attributes attrs1 = Attributes.builder().put("env", "prod").build();
    Attributes attrs2 = Attributes.builder().put("env", "dev").build();
    Attributes attrs3 = Attributes.builder().put("region", "us-east-1").build();
    Attributes attrs4 = Attributes.builder().put("region", "prod").build();
    Attributes attrsSameValue = Attributes.builder().put("env", "prod").build();
    Resource resourceAttrs1 = Resource.builder().put("service.name", "svc1").build();
    Resource resourceAttrs2 = Resource.builder().put("service.name", "svc2").build();
    InstrumentationScopeInfo scope1 = InstrumentationScopeInfo.create("scope1");
    InstrumentationScopeInfo scope2 = InstrumentationScopeInfo.create("scope2");

    return Arrays.asList(
        // Should group when resource attributes, scope, point attributes, and timestamp all match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m2", 20.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m3", 30.0, attrs1, 1000000L, resourceAttrs1, scope1)),
            1),
        // Should NOT group if timestamps differ but resource attributes, scope, and point
        // attributes match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m2", 20.0, attrs1, 2000000L, resourceAttrs1, scope1),
                createMetric("m3", 30.0, attrs1, 3000000L, resourceAttrs1, scope1)),
            3),
        // Should NOT group if point attributes differ but resource attributes, scope, and timestamp
        // match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m2", 20.0, attrs2, 1000000L, resourceAttrs1, scope1),
                createMetric("m3", 30.0, attrs3, 1000000L, resourceAttrs1, scope1)),
            3),
        // Should NOT group if resource attributes differ but point attributes, scope, and timestamp
        // match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m2", 20.0, attrs1, 1000000L, resourceAttrs2, scope1),
                createMetric("m3", 30.0, attrs1, 1000000L, Resource.empty(), scope1)),
            3),
        // Should NOT group if scopes differ but resource attributes, point attributes, and
        // timestamp match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m2", 20.0, attrs1, 1000000L, resourceAttrs1, scope2),
                createMetric("m3", 30.0, attrs1, 1000000L, resourceAttrs1, null)),
            3),
        // Should group when both scopes are null and resource attributes, point attributes, and
        // timestamp match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, resourceAttrs1, null),
                createMetric("m2", 20.0, attrs1, 1000000L, resourceAttrs1, null),
                createMetric("m3", 30.0, attrs1, 1000000L, resourceAttrs1, null)),
            1),
        // Should NOT group if scopes differ but resource attributes, point attributes, and
        // timestamp
        // match
        Arguments.of(
            Arrays.asList(
                createMetric(
                    "m1",
                    10.0,
                    attrs1,
                    1000000L,
                    resourceAttrs1,
                    InstrumentationScopeInfo.create("scope3")),
                createMetric("m2", 20.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m3", 30.0, attrs1, 1000000L, resourceAttrs1, scope2)),
            3),
        // Should group when both point attributes are empty and resource attributes, scope, and
        // timestamp match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, Attributes.empty(), 1000000L, resourceAttrs1, scope1),
                createMetric("m2", 20.0, Attributes.empty(), 1000000L, resourceAttrs1, scope1),
                createMetric("m3", 30.0, Attributes.empty(), 1000000L, resourceAttrs1, scope1)),
            1),
        // Should NOT group if one has empty point attributes and other does not but resource
        // attributes, scope, and timestamp match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, Attributes.empty(), 1000000L, resourceAttrs1, scope1),
                createMetric("m2", 20.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m3", 30.0, attrs2, 1000000L, resourceAttrs1, scope1)),
            3),
        // Should group when both resource attributes are empty and point attributes, scope, and
        // timestamp match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, Resource.empty(), scope1),
                createMetric("m2", 20.0, attrs1, 1000000L, Resource.empty(), scope1),
                createMetric("m3", 30.0, attrs1, 1000000L, Resource.empty(), scope1)),
            1),
        // Should NOT group if one has empty resource attributes and other does not but point
        // attributes, scope, and timestamp match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, Resource.empty(), scope1),
                createMetric("m2", 20.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m3", 30.0, attrs1, 1000000L, resourceAttrs2, scope1)),
            3),
        // Should NOT group if attribute values differ for same key but resource attributes, scope,
        // and timestamp match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m2", 20.0, attrs2, 1000000L, resourceAttrs1, scope1),
                createMetric(
                    "m3",
                    30.0,
                    Attributes.builder().put("env", "staging").build(),
                    1000000L,
                    resourceAttrs1,
                    scope1)),
            3),
        // Should NOT group if attribute keys differ but resource attributes, scope, and timestamp
        // match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m2", 20.0, attrs3, 1000000L, resourceAttrs1, scope1),
                createMetric("m3", 30.0, attrs4, 1000000L, resourceAttrs1, scope1)),
            3),

        // Should group when attributes have same content regardless of object identity and resource
        // attributes, scope, and timestamp match
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m2", 20.0, attrsSameValue, 1000000L, resourceAttrs1, scope1),
                createMetric("m3", 30.0, attrs1, 1000000L, resourceAttrs1, scope1)),
            1),
        // Should partially group when some metrics match all dimensions but others differ in point
        // attributes
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m2", 20.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m3", 30.0, attrs2, 1000000L, resourceAttrs1, scope1)),
            2),

        // Should NOT group when all dimensions differ (resource attributes, scope, point
        // attributes, and timestamp)
        Arguments.of(
            Arrays.asList(
                createMetric("m1", 10.0, attrs1, 1000000L, resourceAttrs1, scope1),
                createMetric("m2", 20.0, attrs2, 2000000L, resourceAttrs2, scope2),
                createMetric("m3", 30.0, attrs3, 3000000L, Resource.empty(), null)),
            3));
  }
}
