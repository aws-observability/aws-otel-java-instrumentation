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
import java.util.Arrays;
import java.util.List;

/**
 * CloudWatch EMF metadata structure.
 *
 * <p>This is the `_aws` field in EMF format that tells CloudWatch Logs how to extract metrics from
 * the log event.
 */
public class CloudWatchMetadata {

  @JsonProperty("CloudWatchMetrics")
  private List<CloudWatchMetricSet> cloudWatchMetrics;

  @JsonProperty("Timestamp")
  private long timestamp;

  public CloudWatchMetadata() {
    this.cloudWatchMetrics = new ArrayList<>();
    this.timestamp = System.currentTimeMillis();
  }

  public CloudWatchMetadata(List<CloudWatchMetricSet> cloudWatchMetrics, long timestamp) {
    this.cloudWatchMetrics = cloudWatchMetrics;
    this.timestamp = timestamp;
  }

  // Getters and setters
  public List<CloudWatchMetricSet> getCloudWatchMetrics() {
    return cloudWatchMetrics;
  }

  public void setCloudWatchMetrics(List<CloudWatchMetricSet> cloudWatchMetrics) {
    this.cloudWatchMetrics = cloudWatchMetrics;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }

  /** CloudWatch metric set with namespace, dimensions, and metrics. */
  public static class CloudWatchMetricSet {

    @JsonProperty("Namespace")
    private String namespace;

    @JsonProperty("Dimensions")
    private List<List<String>> dimensions;

    @JsonProperty("Metrics")
    private List<CloudWatchMetricDefinition> metrics;

    public CloudWatchMetricSet() {
      this.dimensions = new ArrayList<>();
      this.metrics = new ArrayList<>();
    }

    public CloudWatchMetricSet(
        String namespace, List<List<String>> dimensions, List<CloudWatchMetricDefinition> metrics) {
      this.namespace = namespace;
      this.dimensions = dimensions;
      this.metrics = metrics;
    }

    // Getters and setters
    public String getNamespace() {
      return namespace;
    }

    public void setNamespace(String namespace) {
      this.namespace = namespace;
    }

    public List<List<String>> getDimensions() {
      return dimensions;
    }

    public void setDimensions(List<List<String>> dimensions) {
      this.dimensions = dimensions;
    }

    public List<CloudWatchMetricDefinition> getMetrics() {
      return metrics;
    }

    public void setMetrics(List<CloudWatchMetricDefinition> metrics) {
      this.metrics = metrics;
    }
  }

  /** Definition of a single CloudWatch metric. */
  public static class CloudWatchMetricDefinition {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Unit")
    private String unit;

    public CloudWatchMetricDefinition() {}

    public CloudWatchMetricDefinition(String name, String unit) {
      this.name = name;
      this.unit = unit;
    }

    // Getters and setters
    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getUnit() {
      return unit;
    }

    public void setUnit(String unit) {
      this.unit = unit;
    }
  }

  /**
   * Create metadata for function call metrics. The {@code environment} dimension is included only
   * when {@code includeEnvironment} is true — when environment is unset it is omitted entirely (no
   * sentinel default) to stay EMF-valid against the root-level member dropped in {@link
   * FunctionCallMetrics#toEmfMap()}.
   */
  public static CloudWatchMetadata forFunctionCallMetrics(
      long timestamp, boolean includeEnvironment) {
    CloudWatchMetricDefinition durationMetric =
        new CloudWatchMetricDefinition("duration", "Microseconds");

    List<String> dimensionList = new ArrayList<>();
    if (includeEnvironment) {
      dimensionList.add("environment");
    }
    dimensionList.add("service_name");
    dimensionList.add("function_id");
    dimensionList.add("operation");

    CloudWatchMetricSet metricSet =
        new CloudWatchMetricSet(
            "ApplicationObservability/Functions",
            Arrays.asList(dimensionList),
            Arrays.asList(durationMetric));

    return new CloudWatchMetadata(Arrays.asList(metricSet), timestamp);
  }
}
