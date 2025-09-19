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

package software.amazon.opentelemetry.javaagent.providers.exporter.aws.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.Data;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramData;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramPointData;
import io.opentelemetry.sdk.metrics.data.GaugeData;
import io.opentelemetry.sdk.metrics.data.HistogramData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.metrics.data.SumData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

public abstract class BaseEmfExporter implements MetricExporter {
  private static final Logger logger = Logger.getLogger(BaseEmfExporter.class.getName());
  private final String namespace;

  protected BaseEmfExporter(String namespace) {
    this.namespace = namespace != null ? namespace : "default";
  }

  @Override
  public CompletableResultCode export(Collection<MetricData> metricsData) {
    try {
      if (metricsData.isEmpty()) {
        return CompletableResultCode.ofSuccess();
      }

      // Group metrics by attributes and timestamp
      Map<String, List<MetricRecord>> groupedMetrics = new HashMap<>();

      for (MetricData metric : metricsData) {
        Data<? extends PointData> metricData = metric.getData();
        if (metricData == null || metricData.getPoints().isEmpty()) {
          continue;
        }

        for (PointData point : metricData.getPoints()) {
          MetricRecord record = null;

          if (metricData instanceof GaugeData || metricData instanceof SumData) {
            record = MetricRecord.convertGaugeAndSum(metric, point);
          }
          if (metricData instanceof HistogramData && point instanceof HistogramPointData) {
            record = MetricRecord.convertHistogram(metric, (HistogramPointData) point);
          }
          if (metricData instanceof ExponentialHistogramData
              && point instanceof ExponentialHistogramPointData) {
            record =
                MetricRecord.convertExponentialHistogram(
                    metric, (ExponentialHistogramPointData) point);
          }

          if (record == null) {
            logger.fine("Unsupported metric data type: " + metricData.getClass().getSimpleName());
            continue;
          }

          String groupKey = this.groupByAttributesAndTimestamp(record);
          groupedMetrics.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(record);
        }
      }

      // Process each group to create EMF logs
      for (List<MetricRecord> records : groupedMetrics.values()) {
        if (!records.isEmpty()) {
          MetricRecord firstRecord = records.get(0);
          // Get resource from first metric in the collection
          MetricData firstMetric = metricsData.iterator().next();
          Map<String, Object> emfLog =
              MetricRecord.createEmfLog(
                  records,
                  firstMetric.getResource().getAttributes(),
                  this.namespace,
                  firstRecord.getTimestamp());

          // Create log event with message and timestamp like Python implementation
          Map<String, Object> logEvent = new HashMap<>();
          logEvent.put("message", new ObjectMapper().writeValueAsString(emfLog));
          logEvent.put("timestamp", firstRecord.getTimestamp());
          this.emit(logEvent);
        }
      }

      return CompletableResultCode.ofSuccess();
    } catch (Exception e) {
      logger.severe("Failed to export metrics: " + e.getMessage());
      return CompletableResultCode.ofFailure();
    }
  }

  @Override
  public abstract CompletableResultCode flush();

  @Override
  public abstract CompletableResultCode shutdown();

  @Override
  public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
    return AggregationTemporality.DELTA;
  }

  @Override
  public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
    if (instrumentType == InstrumentType.HISTOGRAM) {
      return Aggregation.base2ExponentialBucketHistogram();
    }
    return Aggregation.defaultAggregation();
  }

  /**
   * Export a log event.
   *
   * <p>This method must be implemented by subclasses to define where the EMF logs are sent.
   *
   * @param logEvent The log event to send
   */
  protected abstract void emit(Map<String, Object> logEvent);

  private String groupByAttributesAndTimestamp(MetricRecord record) {
    // Java doesn't have built-in, hashable tuples, so we
    // concatenate the attributes key and timestamp into a single string to create a unique
    // grouping key for the HashMap.
    String attrsKey = getAttributesKey(record.getAttributes());
    return attrsKey + "_" + record.getTimestamp();
  }

  private String getAttributesKey(Attributes attributes) {
    // Sort the attributes to ensure consistent keys
    // Using TreeMap: The map is sorted
    // according to the natural ordering of its keys, or by a Comparator provided at map creation
    // time, depending on which constructor is used.
    // https://docs.oracle.com/javase/8/docs/api/java/util/TreeMap.html
    Map<String, Object> sortedAttrs = new TreeMap<>();
    attributes.forEach((key, value) -> sortedAttrs.put(key.getKey(), value));
    return sortedAttrs.toString();
  }
}
