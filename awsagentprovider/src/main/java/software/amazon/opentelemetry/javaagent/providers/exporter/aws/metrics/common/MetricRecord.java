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

package software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.common;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramBuckets;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramPointData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/** The metric data unified representation of all OTel metrics for OTel to CW EMF conversion. */
public class MetricRecord {
  private static final Logger logger = Logger.getLogger(MetricRecord.class.getName());

  // CloudWatch EMF supported units
  // Ref: https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_MetricDatum.html
  private static final Set<String> EMF_SUPPORTED_UNITS =
      new HashSet<>(
          Arrays.asList(
              "Seconds",
              "Microseconds",
              "Milliseconds",
              "Bytes",
              "Kilobytes",
              "Megabytes",
              "Gigabytes",
              "Terabytes",
              "Bits",
              "Kilobits",
              "Megabits",
              "Gigabits",
              "Terabits",
              "Percent",
              "Count",
              "Bytes/Second",
              "Kilobytes/Second",
              "Megabytes/Second",
              "Gigabytes/Second",
              "Terabytes/Second",
              "Bits/Second",
              "Kilobits/Second",
              "Megabits/Second",
              "Gigabits/Second",
              "Terabits/Second",
              "Count/Second",
              "None"));

  // OTel to CloudWatch unit mapping
  // Ref: opentelemetry-collector-contrib/blob/main/exporter/awsemfexporter/grouped_metric.go#L188
  private static final Map<String, String> UNIT_MAPPING = new HashMap<>();

  static {
    UNIT_MAPPING.put("1", "");
    UNIT_MAPPING.put("ns", "");
    UNIT_MAPPING.put("ms", "Milliseconds");
    UNIT_MAPPING.put("s", "Seconds");
    UNIT_MAPPING.put("us", "Microseconds");
    UNIT_MAPPING.put("By", "Bytes");
    UNIT_MAPPING.put("bit", "Bits");
  }

  // Instrument metadata
  private final String name;
  private final String unit;

  @Nullable private Long timestamp;
  private Attributes attributes = Attributes.empty();
  private Attributes resourceAttributes = Attributes.empty();

  // Different metric type data - only one will be set per record
  @Nullable private Double value;
  @Nullable private Map<String, Number> histogramData;
  @Nullable private Map<String, Object> expHistogramData;

  /**
   * Initialize metric record.
   *
   * @param metricName Name of the metric
   * @param metricUnit Unit of the metric
   */
  public MetricRecord(String metricName, String metricUnit) {
    this.name = metricName;
    this.unit = metricUnit;
  }

  /**
   * Create EMF log dictionary from metric records.
   *
   * @param metricRecords List of metric records grouped by attributes
   * @param resource Resource attributes
   * @param namespace CloudWatch namespace
   * @param timestamp Optional timestamp
   * @return EMF log as Map
   */
  static Map<String, Object> createEmfLog(
      List<MetricRecord> metricRecords,
      Attributes resource,
      String namespace,
      @Nullable Long timestamp) {
    Map<String, Object> emfLog = new HashMap<>();

    // Base structure
    List<Object> cloudWatchMetrics = new ArrayList<>();
    Map<String, Object> aws = new HashMap<>();
    aws.put("Timestamp", timestamp != null ? timestamp : System.currentTimeMillis());
    aws.put("CloudWatchMetrics", cloudWatchMetrics);
    emfLog.put("_aws", aws);
    emfLog.put("Version", "1");

    // Add resource attributes to EMF log but not as dimensions
    // OTel collector EMF Exporter has a resource_to_telemetry_conversion flag that will convert
    // resource attributes
    // as regular metric attributes(potential dimensions). However, for this SDK EMF implementation,
    // we align with the OpenTelemetry concept that all metric attributes are treated as dimensions.
    // And have resource attributes as just additional metadata in EMF, added otel.resource as
    // prefix to distinguish.
    if (resource != null) {
      resource.forEach(
          (key, value) -> emfLog.put("otel.resource." + key.getKey(), value.toString()));
    }

    List<Map<String, Object>> metricDefinitions = new ArrayList<>();
    // Collect attributes from all records (they should be the same for all records in the group)
    // Only collect once from the first record and apply to all records
    Attributes allAttributes =
        !metricRecords.isEmpty() ? metricRecords.get(0).getAttributes() : Attributes.empty();

    // Process each metric record
    for (MetricRecord record : metricRecords) {
      String metricName = record.getName();
      if (metricName == null || metricName.isEmpty()) {
        continue;
      }

      Map<String, Object> metricData = new HashMap<>();
      metricData.put("Name", metricName);

      String unit = MetricRecord.convertUnit(record);
      if (!unit.isEmpty()) {
        metricData.put("Unit", unit);
      }

      boolean hasMetricData = false;
      if (record.getExpHistogramData() != null) {
        emfLog.put(metricName, record.getExpHistogramData());
        hasMetricData = true;
      }
      if (record.getHistogramData() != null) {
        emfLog.put(metricName, record.getHistogramData());
        hasMetricData = true;
      }
      if (record.getValue() != null) {
        emfLog.put(metricName, record.getValue());
        hasMetricData = true;
      }
      if (!hasMetricData) {
        logger.fine("Skipping metric " + metricName + " as it does not have valid metric value");
        continue;
      }

      metricDefinitions.add(metricData);
    }

    // Add attribute values to EMF log
    allAttributes.forEach((key, value) -> emfLog.put(key.getKey(), value.toString()));

    // Add CloudWatch Metrics
    if (!metricDefinitions.isEmpty()) {
      Map<String, Object> cloudwatchMetric = new HashMap<>();
      cloudwatchMetric.put("Namespace", namespace);
      cloudwatchMetric.put("Metrics", metricDefinitions);

      List<String> dimensionNames = new ArrayList<>();
      allAttributes.forEach((key, value) -> dimensionNames.add(key.getKey()));

      if (!dimensionNames.isEmpty()) {
        cloudwatchMetric.put("Dimensions", Collections.singletonList(dimensionNames));
      }

      cloudWatchMetrics.add(cloudwatchMetric);
    }

    return emfLog;
  }

  static MetricRecord convertHistogram(MetricData metric, HistogramPointData dataPoint) {
    MetricRecord record = new MetricRecord(metric.getName(), metric.getUnit());
    long timestampMs =
        dataPoint.getEpochNanos() != 0
            ? MetricRecord.normalizeTimestamp(dataPoint.getEpochNanos())
            : System.currentTimeMillis();
    Map<String, Number> histogramMap = new HashMap<>();
    histogramMap.put("Count", dataPoint.getCount());
    histogramMap.put("Sum", dataPoint.getSum());
    histogramMap.put("Min", dataPoint.getMin());
    histogramMap.put("Max", dataPoint.getMax());

    record.setTimestamp(timestampMs);
    record.setAttributes(dataPoint.getAttributes());
    record.setResourceAttributes(metric.getResource().getAttributes());
    record.setHistogramData(histogramMap);

    return record;
  }

  static MetricRecord convertExponentialHistogram(
      MetricData metric, ExponentialHistogramPointData dataPoint) {
    MetricRecord record = new MetricRecord(metric.getName(), metric.getUnit());
    long timestampMs =
        dataPoint.getEpochNanos() != 0
            ? MetricRecord.normalizeTimestamp(dataPoint.getEpochNanos())
            : System.currentTimeMillis();
    List<Float> values = new ArrayList<>();
    List<Float> counts = new ArrayList<>();

    double base = Math.pow(2, Math.pow(2, -1 * dataPoint.getScale()));

    record.setTimestamp(timestampMs);
    record.setAttributes(dataPoint.getAttributes());
    record.setResourceAttributes(metric.getResource().getAttributes());

    // Process positive buckets
    ExponentialHistogramBuckets positiveBuckets = dataPoint.getPositiveBuckets();
    if (positiveBuckets != null && !positiveBuckets.getBucketCounts().isEmpty()) {
      int positiveOffset = positiveBuckets.getOffset();
      List<Long> positiveBucketCounts = positiveBuckets.getBucketCounts();

      double bucketBegin = 0;
      double bucketEnd = 0;

      for (int bucketIndex = 0; bucketIndex < positiveBucketCounts.size(); bucketIndex++) {
        long count = positiveBucketCounts.get(bucketIndex);
        if (count > 0) {
          int index = bucketIndex + positiveOffset;

          if (bucketBegin == 0) {
            bucketBegin = Math.pow(base, index);
          } else {
            bucketBegin = bucketEnd;
          }

          bucketEnd = Math.pow(base, index + 1);

          // Calculate midpoint value of the bucket
          double metricVal = (bucketBegin + bucketEnd) / 2;

          values.add((float) metricVal);
          counts.add((float) count);
        }
      }
    }

    long zeroCount = dataPoint.getZeroCount();
    if (zeroCount > 0) {
      values.add(0f);
      counts.add((float) zeroCount);
    }

    // Process negative buckets
    ExponentialHistogramBuckets negativeBuckets = dataPoint.getNegativeBuckets();
    if (negativeBuckets != null && !negativeBuckets.getBucketCounts().isEmpty()) {
      int negativeOffset = negativeBuckets.getOffset();
      List<Long> negativeBucketCounts = negativeBuckets.getBucketCounts();

      double bucketBegin = 0;
      double bucketEnd = 0;

      for (int bucketIndex = 0; bucketIndex < negativeBucketCounts.size(); bucketIndex++) {
        long count = negativeBucketCounts.get(bucketIndex);
        if (count > 0) {
          int index = bucketIndex + negativeOffset;

          if (bucketEnd == 0) {
            bucketEnd = -Math.pow(base, index);
          } else {
            bucketEnd = bucketBegin;
          }

          bucketBegin = -Math.pow(base, index + 1);

          // Calculate midpoint value of the bucket
          double metricVal = (bucketBegin + bucketEnd) / 2;

          values.add((float) metricVal);
          counts.add((float) count);
        }
      }
    }

    Map<String, Object> expHistogramMap = new HashMap<>();
    expHistogramMap.put("Values", values);
    expHistogramMap.put("Counts", counts);
    expHistogramMap.put("Count", dataPoint.getCount());
    expHistogramMap.put("Sum", dataPoint.getSum());
    expHistogramMap.put("Max", dataPoint.getMax());
    expHistogramMap.put("Min", dataPoint.getMin());
    record.setExpHistogramData(expHistogramMap);

    return record;
  }

  /**
   * Convert a Gauge or Sum metric datapoint to a metric record.
   *
   * @param metric The metric object
   * @param dataPoint The datapoint to convert
   * @return MetricRecord with populated timestamp, attributes, and value
   */
  static MetricRecord convertGaugeAndSum(MetricData metric, PointData dataPoint) {
    MetricRecord record = new MetricRecord(metric.getName(), metric.getUnit());

    long timestampMs =
        dataPoint.getEpochNanos() != 0
            ? MetricRecord.normalizeTimestamp(dataPoint.getEpochNanos())
            : System.currentTimeMillis();

    record.setTimestamp(timestampMs);
    record.setAttributes(dataPoint.getAttributes());
    record.setResourceAttributes(metric.getResource().getAttributes());

    if (dataPoint instanceof DoublePointData) {
      record.setValue(((DoublePointData) dataPoint).getValue());
    }
    if (dataPoint instanceof LongPointData) {
      record.setValue((double) ((LongPointData) dataPoint).getValue());
    }

    return record;
  }

  private static long normalizeTimestamp(long timestampNs) {
    return timestampNs / 1_000_000;
  }

  /**
   * Converts OTel unit to equivalent CloudWatch unit.
   *
   * @param record The metric record
   * @return CloudWatch-compatible unit or empty string.
   */
  private static String convertUnit(MetricRecord record) {
    String unit = record.getUnit();

    if (unit == null || unit.isEmpty()) {
      return "";
    }

    if (EMF_SUPPORTED_UNITS.contains(unit)) {
      return unit;
    }

    // Convert non-units that use curly braces to annotate a quantity to Count
    // See: https://opentelemetry.io/docs/specs/semconv/general/metrics/#instrument-units
    if (unit.startsWith("{") && unit.endsWith("}")) {
      return "Count";
    }

    String mappedUnit = UNIT_MAPPING.get(unit);
    return mappedUnit != null ? mappedUnit : "";
  }

  String getName() {
    return this.name;
  }

  String getUnit() {
    return this.unit;
  }

  @Nullable
  Long getTimestamp() {
    return this.timestamp;
  }

  void setTimestamp(@Nullable Long timestamp) {
    this.timestamp = timestamp;
  }

  Attributes getAttributes() {
    return this.attributes;
  }

  void setAttributes(Attributes attributes) {
    this.attributes = attributes;
  }

  @Nullable
  Double getValue() {
    return this.value;
  }

  void setValue(@Nullable Double value) {
    this.value = value;
  }

  @Nullable
  Map<String, Number> getHistogramData() {
    return this.histogramData;
  }

  void setHistogramData(Map<String, Number> histogramData) {
    this.histogramData = histogramData;
  }

  @Nullable
  Map<String, Object> getExpHistogramData() {
    return this.expHistogramData;
  }

  void setExpHistogramData(@Nullable Map<String, Object> expHistogramData) {
    this.expHistogramData = expHistogramData;
  }

  Attributes getResourceAttributes() {
    return this.resourceAttributes;
  }

  void setResourceAttributes(Attributes resourceAttributes) {
    this.resourceAttributes = resourceAttributes;
  }
}
