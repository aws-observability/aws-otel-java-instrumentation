package com.amazon.sampleapp;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.GlobalMeterProvider;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

public class MetricEmitter {

  static final AttributeKey<String> DIMENSION_API_NAME = AttributeKey.stringKey("apiName");
  static final AttributeKey<String> DIMENSION_STATUS_CODE = AttributeKey.stringKey("statusCode");

  static String API_COUNTER_METRIC = "apiBytesSent";
  static String API_LATENCY_METRIC = "latency";
  static String API_SUM_METRIC = "totalApiBytesSent";
  static String API_LAST_LATENCY_METRIC = "lastLatency";
  static String API_UP_DOWN_COUNTER_METRIC = "queueSizeChange";
  static String API_UP_DOWN_SUM_METRIC = "actualQueueSize";

  DoubleHistogram apiLatencyRecorder;
  LongCounter totalBytesSentObserver;

  long apiBytesSent;
  long queueSizeChange;

  long totalBytesSent;
  long apiLastLatency;
  long actualQueueSize;

  // The below API name and status code dimensions are currently shared by all metrics observer in
  // this class.
  String apiNameValue = "";
  String statusCodeValue = "";

  public MetricEmitter() {
    Meter meter =
        GlobalMeterProvider.get().meterBuilder("aws-otel").setInstrumentationVersion("1.0").build();

    // give a instanceId appending to the metricname so that we can check the metric for each round
    // of integ-test

    String latencyMetricName = API_LATENCY_METRIC;
    String apiBytesSentMetricName = API_COUNTER_METRIC;
    String totalApiBytesSentMetricName = API_SUM_METRIC;
    String lastLatencyMetricName = API_LAST_LATENCY_METRIC;
    String queueSizeChangeMetricName = API_UP_DOWN_COUNTER_METRIC;
    String actualQueueSizeMetricName = API_UP_DOWN_SUM_METRIC;

    String instanceId = System.getenv("INSTANCE_ID");
    if (instanceId != null && !instanceId.trim().equals("")) {
      latencyMetricName = API_LATENCY_METRIC + "_" + instanceId;
      apiBytesSentMetricName = API_COUNTER_METRIC + "_" + instanceId;
      totalApiBytesSentMetricName = API_SUM_METRIC + "_" + instanceId;
      lastLatencyMetricName = API_LAST_LATENCY_METRIC + "_" + instanceId;
      queueSizeChangeMetricName = API_UP_DOWN_COUNTER_METRIC + "_" + instanceId;
      actualQueueSizeMetricName = API_UP_DOWN_SUM_METRIC + "_" + instanceId;
    }

    meter
        .counterBuilder(apiBytesSentMetricName)
        .setDescription("API request load sent in bytes")
        .setUnit("one")
        .buildWithCallback(
            measurement ->
                measurement.observe(
                    apiBytesSent,
                    Attributes.of(
                        DIMENSION_API_NAME, apiNameValue, DIMENSION_STATUS_CODE, statusCodeValue)));

    apiLatencyRecorder =
        meter
            .histogramBuilder(latencyMetricName)
            .setDescription("API latency time")
            .setUnit("ms")
            .build();

    meter
        .upDownCounterBuilder(queueSizeChangeMetricName)
        .setDescription("Queue Size change")
        .setUnit("one")
        .buildWithCallback(
            measurement ->
                measurement.observe(
                    queueSizeChange,
                    Attributes.of(
                        DIMENSION_API_NAME, apiNameValue, DIMENSION_STATUS_CODE, statusCodeValue)));

    meter
        .gaugeBuilder(totalApiBytesSentMetricName)
        .setDescription("Total API request load sent in bytes")
        .setUnit("one")
        .ofLongs()
        .buildWithCallback(
            measurement -> {
              measurement.observe(
                  totalBytesSent,
                  Attributes.of(
                      DIMENSION_API_NAME, apiNameValue, DIMENSION_STATUS_CODE, statusCodeValue));
            });

    meter
        .gaugeBuilder(lastLatencyMetricName)
        .setDescription("The last API latency observed at collection interval")
        .setUnit("ms")
        .ofLongs()
        .buildWithCallback(
            measurement -> {
              measurement.observe(
                  apiLastLatency,
                  Attributes.of(
                      DIMENSION_API_NAME, apiNameValue, DIMENSION_STATUS_CODE, statusCodeValue));
            });
    meter
        .gaugeBuilder(actualQueueSizeMetricName)
        .setDescription("The actual queue size observed at collection interval")
        .setUnit("one")
        .ofLongs()
        .buildWithCallback(
            measurement -> {
              measurement.observe(
                  actualQueueSize,
                  Attributes.of(
                      DIMENSION_API_NAME, apiNameValue, DIMENSION_STATUS_CODE, statusCodeValue));
            });
  }

  /**
   * emit http request latency metrics with summary metric type
   *
   * @param returnTime
   * @param apiName
   * @param statusCode
   */
  public void emitReturnTimeMetric(Long returnTime, String apiName, String statusCode) {
    apiLatencyRecorder.record(
        returnTime, Attributes.of(DIMENSION_API_NAME, apiName, DIMENSION_STATUS_CODE, statusCode));
  }

  /**
   * emit http request load size with counter metrics type
   *
   * @param bytes
   * @param apiName
   * @param statusCode
   */
  public void emitBytesSentMetric(int bytes, String apiName, String statusCode) {
    apiBytesSent += bytes;
  }

  /**
   * emit queue size change metrics with UpDownCounter metric type
   *
   * @param queueSizeChange
   * @param apiName
   * @param statusCode
   */
  public void emitQueueSizeChangeMetric(int queueSizeChange, String apiName, String statusCode) {
    queueSizeChange += queueSizeChange;
  }

  /**
   * update total http request load size, it will be collected as summary metrics type
   *
   * @param bytes
   * @param apiName
   * @param statusCode
   */
  public void updateTotalBytesSentMetric(int bytes, String apiName, String statusCode) {
    totalBytesSent += bytes;
    apiNameValue = apiName;
    statusCodeValue = statusCode;
  }

  /**
   * update last api latency, it will be collected by value observer
   *
   * @param returnTime
   * @param apiName
   * @param statusCode
   */
  public void updateLastLatencyMetric(Long returnTime, String apiName, String statusCode) {
    apiLastLatency = returnTime;
    apiNameValue = apiName;
    statusCodeValue = statusCode;
  }
  /**
   * update actual queue size, it will be collected by UpDownSumObserver
   *
   * @param queueSizeChange
   * @param apiName
   * @param statusCode
   */
  public void updateActualQueueSizeMetric(int queueSizeChange, String apiName, String statusCode) {
    actualQueueSize += queueSizeChange;
    apiNameValue = apiName;
    statusCodeValue = statusCode;
  }
}
