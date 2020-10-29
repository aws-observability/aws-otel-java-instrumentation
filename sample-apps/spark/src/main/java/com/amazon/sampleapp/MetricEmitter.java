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

package com.amazon.sampleapp;

import io.opentelemetry.OpenTelemetry;
import io.opentelemetry.common.Labels;
import io.opentelemetry.metrics.LongCounter;
import io.opentelemetry.metrics.LongValueRecorder;
import io.opentelemetry.metrics.Meter;

public class MetricEmitter {

  static final String DIMENSION_API_NAME = "apiName";
  static final String DIMENSION_STATUS_CODE = "statusCode";

  static String API_COUNTER_METRIC = "apiBytesSent";
  static String API_LATENCY_METRIC = "latency";

  LongCounter apiBytesSentCounter;
  LongValueRecorder apiLatencyRecorder;

  public MetricEmitter() {
    Meter meter = OpenTelemetry.getMeter("cloudwatch-otel", "1.0");

    // give a instanceId appending to the metricname so that we can check the metric for each round
    // of integ-test

    String latencyMetricName = API_LATENCY_METRIC;
    String apiBytesSentMetricName = API_COUNTER_METRIC;
    String instanceId = System.getenv("INSTANCE_ID");
    if (instanceId != null && !instanceId.trim().equals("")) {
      latencyMetricName = API_LATENCY_METRIC + "_" + instanceId;
      apiBytesSentMetricName = API_COUNTER_METRIC + "_" + instanceId;
    }

    apiBytesSentCounter =
        meter
            .longCounterBuilder(apiBytesSentMetricName)
            .setDescription("API request load sent in bytes")
            .setUnit("one")
            .build();

    apiLatencyRecorder =
        meter
            .longValueRecorderBuilder(latencyMetricName)
            .setDescription("API latency time")
            .setUnit("ms")
            .build();
  }

  /**
   * emit http request latency metrics with summary metric type
   *
   * @param returnTime
   * @param apiName
   * @param statusCode
   */
  public void emitReturnTimeMetric(Long returnTime, String apiName, String statusCode) {
    System.out.println(
        "emit metric with return time " + returnTime + "," + apiName + "," + statusCode);
    apiLatencyRecorder.record(
        returnTime, Labels.of(DIMENSION_API_NAME, apiName, DIMENSION_STATUS_CODE, statusCode));
  }

  /**
   * emit http request load size with counter metrics type
   *
   * @param bytes
   * @param apiName
   * @param statusCode
   */
  public void emitBytesSentMetric(int bytes, String apiName, String statusCode) {
    System.out.println("emit metric with http request size " + bytes + " byte, " + apiName);
    apiBytesSentCounter.add(
        bytes, Labels.of(DIMENSION_API_NAME, apiName, DIMENSION_STATUS_CODE, statusCode));
  }
}
