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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Duration metrics in EMF histogram format.
 *
 * <p>Represents aggregated duration measurements for multiple function calls. Uses CloudWatch EMF
 * histogram format with Values and Counts arrays.
 */
public class DurationMetrics {

  @JsonProperty("values")
  private List<Double> values;

  @JsonProperty("counts")
  private List<Double> counts;

  @JsonProperty("max")
  private double max;

  @JsonProperty("min")
  private double min;

  @JsonProperty("count")
  private double count;

  @JsonProperty("sum")
  private double sum;

  public DurationMetrics() {
    this.values = new ArrayList<>();
    this.counts = new ArrayList<>();
    this.max = 0;
    this.min = 0;
    this.count = 0;
    this.sum = 0;
  }

  public DurationMetrics(
      List<Double> values, List<Double> counts, double max, double min, double count, double sum) {
    this.values = values != null ? values : new ArrayList<>();
    this.counts = counts != null ? counts : new ArrayList<>();
    this.max = max;
    this.min = min;
    this.count = count;
    this.sum = sum;
  }

  // Getters and setters
  public List<Double> getValues() {
    return values;
  }

  public void setValues(List<Double> values) {
    this.values = values;
  }

  public List<Double> getCounts() {
    return counts;
  }

  public void setCounts(List<Double> counts) {
    this.counts = counts;
  }

  public double getMax() {
    return max;
  }

  public void setMax(double max) {
    this.max = max;
  }

  public double getMin() {
    return min;
  }

  public void setMin(double min) {
    this.min = min;
  }

  public double getCount() {
    return count;
  }

  public void setCount(double count) {
    this.count = count;
  }

  public double getSum() {
    return sum;
  }

  public void setSum(double sum) {
    this.sum = sum;
  }

  /** Convert to EMF-compliant map with uppercase keys. */
  public Map<String, Object> toEmfMap() {
    Map<String, Object> map = new HashMap<>();
    map.put("Values", values);
    map.put("Counts", counts);
    map.put("Max", max);
    map.put("Min", min);
    map.put("Count", count);
    map.put("Sum", sum);
    return map;
  }
}
