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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CloudWatch SEH (Sparse Exponential Histogram) implementation for Java.
 *
 * <p>This class implements the SEH1 algorithm used by AWS CloudWatch for efficient distribution
 * aggregation with ~10% relative error.
 *
 * <p>The SEH algorithm uses exponentially-spaced buckets to compress large numbers of samples into
 * a compact representation suitable for CloudWatch EMF (Embedded Metric Format).
 */
public class SehHistogram {

  // Bucket width factor: log(1.1) gives ~10% relative error per bucket
  private static final double BUCKET_FACTOR = Math.log(1.1); // ~0.0953101798043

  // Special bucket number for exact zero values
  private static final int BUCKET_FOR_ZERO = Short.MIN_VALUE; // -32768

  private final int maxBuckets;
  private final Map<Integer, Double> buckets;
  private Double minimum;
  private Double maximum;
  private double sum;
  private double count;

  /**
   * Initialize an empty SEH histogram.
   *
   * @param maxBuckets Maximum number of distinct buckets to maintain. CloudWatch EMF supports up to
   *     100 values per metric.
   */
  public SehHistogram(int maxBuckets) {
    this.maxBuckets = maxBuckets;
    this.buckets = new HashMap<>();
    this.minimum = null;
    this.maximum = null;
    this.sum = 0.0;
    this.count = 0.0;
  }

  /** Create histogram with default 100 buckets (CloudWatch EMF limit). */
  public SehHistogram() {
    this(100);
  }

  /**
   * Record a value into the histogram with optional weight.
   *
   * @param value The value to record (e.g., duration in nanoseconds)
   * @param weight Weight for this sample (default: 1.0)
   * @return True if the value was recorded, false if rejected (validation failed or bucket limit)
   */
  public synchronized boolean record(double value, double weight) {
    // Validate input
    if (!validateInput(value, weight)) {
      return false;
    }

    // Calculate bucket
    int bucketNum = getBucket(value);

    // Check bucket limit (only if adding a new bucket)
    if (!buckets.containsKey(bucketNum) && buckets.size() >= maxBuckets) {
      // Bucket limit reached - reject new distinct values
      return false;
    }

    // Update statistics
    count += weight;
    sum += value * weight;

    if (minimum == null || value < minimum) {
      minimum = value;
    }

    if (maximum == null || value > maximum) {
      maximum = value;
    }

    // Update bucket count
    buckets.merge(bucketNum, weight, Double::sum);

    return true;
  }

  /**
   * Record a value with weight of 1.0.
   *
   * @param value The value to record
   * @return True if recorded, false otherwise
   */
  public boolean record(double value) {
    return record(value, 1.0);
  }

  /**
   * Get the histogram as parallel arrays of values and counts.
   *
   * @return HistogramData containing values and counts arrays, sorted by bucket number
   */
  public synchronized HistogramData getValuesAndCounts() {
    if (buckets.isEmpty()) {
      return new HistogramData(new ArrayList<>(), new ArrayList<>());
    }

    // Sort buckets by bucket number
    TreeMap<Integer, Double> sortedBuckets = new TreeMap<>(buckets);

    List<Double> values = new ArrayList<>();
    List<Double> counts = new ArrayList<>();

    for (Map.Entry<Integer, Double> entry : sortedBuckets.entrySet()) {
      // Recover representative value from bucket number
      double value = recoverValue(entry.getKey());
      values.add(value);
      counts.add(entry.getValue());
    }

    return new HistogramData(values, counts);
  }

  /**
   * Compute a percentile value from the histogram.
   *
   * <p>Walks the sorted buckets accumulating counts until the cumulative count reaches the
   * requested percentile of the total count, then returns the representative value of that bucket.
   *
   * @param percentile The percentile to compute (0-100), e.g. 99.0 for P99
   * @return The approximate value at the given percentile, or 0.0 if the histogram is empty
   */
  public synchronized double getPercentile(double percentile) {
    if (count == 0) {
      return 0.0;
    }
    double targetCount = count * (percentile / 100.0);
    double cumulative = 0;
    TreeMap<Integer, Double> sorted = new TreeMap<>(buckets);
    for (Map.Entry<Integer, Double> entry : sorted.entrySet()) {
      cumulative += entry.getValue();
      if (cumulative >= targetCount) {
        return recoverValue(entry.getKey());
      }
    }
    return maximum != null ? maximum : 0.0;
  }

  /** Get summary statistics for the histogram. */
  public synchronized Statistics getStatistics() {
    return new Statistics(
        minimum != null ? minimum : 0.0, maximum != null ? maximum : 0.0, sum, count);
  }

  /** Check if the histogram is empty. */
  public synchronized boolean isEmpty() {
    return count == 0;
  }

  /** Get current bucket count. */
  public synchronized int getBucketCount() {
    return buckets.size();
  }

  private boolean validateInput(double value, double weight) {
    // Check for NaN
    if (Double.isNaN(value) || Double.isNaN(weight)) {
      return false;
    }

    // Check for Infinity
    if (Double.isInfinite(value) || Double.isInfinite(weight)) {
      return false;
    }

    // Check weight > 0
    return weight > 0;
  }

  private int getBucket(double value) {
    if (value == 0) {
      return BUCKET_FOR_ZERO;
    }

    // For negative values, use absolute value for bucket calculation
    double absValue = Math.abs(value);

    // Calculate bucket: floor(log(abs_value) / BUCKET_FACTOR)
    int bucketNum = (int) Math.floor(Math.log(absValue) / BUCKET_FACTOR);

    // Apply sign
    if (value < 0) {
      bucketNum = -bucketNum;
    }

    return bucketNum;
  }

  private double recoverValue(int bucketNum) {
    if (bucketNum == BUCKET_FOR_ZERO) {
      return 0.0;
    }

    // Calculate midpoint value: exp((bucket_num + 0.5) × BUCKET_FACTOR)
    return Math.exp((bucketNum + 0.5) * BUCKET_FACTOR);
  }

  @Override
  public String toString() {
    return String.format(
        "SehHistogram(count=%.0f, buckets=%d, min=%s, max=%s, sum=%.2f)",
        count, buckets.size(), minimum, maximum, sum);
  }

  /** Container for histogram values and counts. */
  public static class HistogramData {
    private final List<Double> values;
    private final List<Double> counts;

    public HistogramData(List<Double> values, List<Double> counts) {
      this.values = values;
      this.counts = counts;
    }

    public List<Double> getValues() {
      return values;
    }

    public List<Double> getCounts() {
      return counts;
    }
  }

  /** Container for histogram statistics. */
  public static class Statistics {
    private final double min;
    private final double max;
    private final double sum;
    private final double count;

    public Statistics(double min, double max, double sum, double count) {
      this.min = min;
      this.max = max;
      this.sum = sum;
      this.count = count;
    }

    public double getMin() {
      return min;
    }

    public double getMax() {
      return max;
    }

    public double getSum() {
      return sum;
    }

    public double getCount() {
      return count;
    }
  }
}
