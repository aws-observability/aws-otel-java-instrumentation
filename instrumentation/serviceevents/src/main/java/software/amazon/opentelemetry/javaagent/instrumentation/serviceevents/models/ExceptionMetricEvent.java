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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exception metric EMF event.
 *
 * <p>Represents a single exception metric in CloudWatch Embedded Metric Format (EMF). Each event
 * produces one CloudWatch metric with dimensions: service_name, environment, operation, exception.
 */
public class ExceptionMetricEvent {

  private static final String EMF_NAMESPACE = "ServiceEvents";
  private static final int MAX_DIMENSION_VALUE_LENGTH = 1024;

  private final String serviceName;
  private final String environment;
  private final String operation;
  private final String exception;
  private final int count;
  private final long timestampMs;

  private ExceptionMetricEvent(Builder builder) {
    this.serviceName = builder.serviceName;
    this.environment = builder.environment;
    this.operation = builder.operation;
    this.exception = formatExceptionValue(builder.functionId, builder.errorType);
    this.count = builder.count;
    this.timestampMs = builder.timestampMs;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Format the exception dimension value from function_id and error_type.
   *
   * <p>Primary format: "com.amazon.service.Handler.process java.lang.NullPointerException" Fallback
   * (if > 1024 chars): "Handler.process NullPointerException" (class.method + simple exception
   * name)
   */
  static String formatExceptionValue(String functionId, String errorType) {
    String safeFunction = functionId != null ? functionId : "unknown";
    String safeError = errorType != null ? errorType : "unknown";

    String fullValue = safeFunction + " " + safeError;
    if (fullValue.length() <= MAX_DIMENSION_VALUE_LENGTH) {
      return fullValue;
    }

    // Fallback: strip packages from both parts
    String shortFunction = getShortClassName(safeFunction);
    String shortError = getSimpleClassName(safeError);
    String fallbackValue = shortFunction + " " + shortError;

    // If still too long, truncate
    if (fallbackValue.length() > MAX_DIMENSION_VALUE_LENGTH) {
      return fallbackValue.substring(0, MAX_DIMENSION_VALUE_LENGTH);
    }
    return fallbackValue;
  }

  /**
   * Extract "ClassName.methodName" from a fully qualified function id like
   * "com.amazon.service.Handler.process".
   */
  private static String getShortClassName(String fqn) {
    if (fqn == null || fqn.isEmpty()) {
      return fqn;
    }
    // Split by dots, take last two parts (ClassName.methodName)
    String[] parts = fqn.split("\\.");
    if (parts.length >= 2) {
      return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
    return fqn;
  }

  /** Extract simple class name from fully qualified like "java.lang.NullPointerException". */
  private static String getSimpleClassName(String fqn) {
    if (fqn == null || fqn.isEmpty()) {
      return fqn;
    }
    int lastDot = fqn.lastIndexOf('.');
    if (lastDot >= 0 && lastDot < fqn.length() - 1) {
      return fqn.substring(lastDot + 1);
    }
    return fqn;
  }

  /** Convert to EMF-compliant map for JSON serialization. */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();

    map.put("telemetry_type", "EndpointErrorMetric");

    // Omit the environment dimension entirely when unset (no sentinel default). The root-level
    // member and the EMF Dimensions entry must be dropped together to stay EMF-valid.
    boolean hasEnvironment = environment != null && !environment.isEmpty();

    // Root-level dimension target members (must be strings)
    map.put("sdk_lang", "java");
    map.put("service_name", serviceName);
    if (hasEnvironment) {
      map.put("environment", environment);
    }
    map.put("operation", operation);
    map.put("exception", exception);

    // Root-level metric target member (must be numeric)
    map.put("count", count);

    // _aws EMF metadata
    Map<String, Object> aws = new LinkedHashMap<>();
    aws.put("Timestamp", timestampMs);

    Map<String, Object> metricDirective = new LinkedHashMap<>();
    metricDirective.put("Namespace", EMF_NAMESPACE);
    List<String> dimensionKeys = new ArrayList<>();
    dimensionKeys.add("service_name");
    if (hasEnvironment) {
      dimensionKeys.add("environment");
    }
    dimensionKeys.add("operation");
    dimensionKeys.add("exception");
    metricDirective.put("Dimensions", Arrays.asList(dimensionKeys));

    Map<String, String> countMetric = new LinkedHashMap<>();
    countMetric.put("Name", "count");
    countMetric.put("Unit", "Count");
    metricDirective.put("Metrics", Arrays.asList(countMetric));

    aws.put("CloudWatchMetrics", Arrays.asList(metricDirective));
    map.put("_aws", aws);

    return map;
  }

  // Getters
  public String getServiceName() {
    return serviceName;
  }

  public String getEnvironment() {
    return environment;
  }

  public String getOperation() {
    return operation;
  }

  public String getException() {
    return exception;
  }

  public int getCount() {
    return count;
  }

  public long getTimestampMs() {
    return timestampMs;
  }

  /** Builder for ExceptionMetricEvent. */
  public static class Builder {
    private String serviceName;
    private String environment;
    private String operation;
    private String functionId;
    private String errorType;
    private int count;
    private long timestampMs;

    public Builder serviceName(String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    public Builder environment(String environment) {
      this.environment = environment;
      return this;
    }

    public Builder operation(String operation) {
      this.operation = operation;
      return this;
    }

    public Builder functionId(String functionId) {
      this.functionId = functionId;
      return this;
    }

    public Builder errorType(String errorType) {
      this.errorType = errorType;
      return this;
    }

    public Builder count(int count) {
      this.count = count;
      return this;
    }

    public Builder timestampMs(long timestampMs) {
      this.timestampMs = timestampMs;
      return this;
    }

    public ExceptionMetricEvent build() {
      return new ExceptionMetricEvent(this);
    }
  }
}
