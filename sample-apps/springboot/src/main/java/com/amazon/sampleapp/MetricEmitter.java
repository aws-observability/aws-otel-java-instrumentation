package com.amazon.sampleapp;

import io.opentelemetry.api.metrics.GlobalMeterProvider;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongSumObserver;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.LongUpDownSumObserver;
import io.opentelemetry.api.metrics.LongValueObserver;
import io.opentelemetry.api.metrics.LongValueRecorder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.common.Labels;

public class MetricEmitter {

  static final String DIMENSION_API_NAME = "apiName";
  static final String DIMENSION_STATUS_CODE = "statusCode";

  static String API_COUNTER_METRIC = "apiBytesSent";
  static String API_LATENCY_METRIC = "latency";
  static String API_SUM_METRIC = "totalApiBytesSent";
  static String API_LAST_LATENCY_METRIC = "lastLatency";
  static String API_UP_DOWN_COUNTER_METRIC = "queueSizeChange";
  static String API_UP_DOWN_SUM_METRIC = "actualQueueSize";

  LongCounter apiBytesSentCounter;
  LongValueRecorder apiLatencyRecorder;
  LongSumObserver totalBytesSentObserver;
  LongValueObserver apiLastLatencyObserver;
  LongUpDownCounter queueSizeCounter;
  LongUpDownSumObserver actualQueueSizeObserver;

  long totalBytesSent;
  long apiLastLatency;
  long actualQueueSize;

  // The below API name and status code dimensions are currently shared by all metrics observer in
  // this class.
  String apiNameValue = "";
  String statusCodeValue = "";

  public MetricEmitter() {
    Meter meter = GlobalMeterProvider.getMeter("aws-otel", "1.0");

    // give a instanceId appending to the metricname so that we can check the metric for each round
    // of integ-test

    System.out.println("OTLP port is: " + System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"));

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

    queueSizeCounter =
        meter
            .longUpDownCounterBuilder(queueSizeChangeMetricName)
            .setDescription("Queue Size change")
            .setUnit("one")
            .build();

    totalBytesSentObserver =
        meter
            .longSumObserverBuilder(totalApiBytesSentMetricName)
            .setDescription("Total API request load sent in bytes")
            .setUnit("one")
            .setUpdater(
                longResult -> {
                  System.out.println(
                      "emit total http request size "
                          + totalBytesSent
                          + " byte, "
                          + apiNameValue
                          + ","
                          + statusCodeValue);
                  longResult.observe(
                      totalBytesSent,
                      Labels.of(
                          DIMENSION_API_NAME,
                          apiNameValue,
                          DIMENSION_STATUS_CODE,
                          statusCodeValue));
                })
            .build();

    apiLastLatencyObserver =
        meter
            .longValueObserverBuilder(lastLatencyMetricName)
            .setDescription("The last API latency observed at collection interval")
            .setUnit("ms")
            .setUpdater(
                longResult -> {
                  System.out.println(
                      "emit last api latency "
                          + apiLastLatency
                          + ","
                          + apiNameValue
                          + ","
                          + statusCodeValue);
                  longResult.observe(
                      apiLastLatency,
                      Labels.of(
                          DIMENSION_API_NAME,
                          apiNameValue,
                          DIMENSION_STATUS_CODE,
                          statusCodeValue));
                })
            .build();
    actualQueueSizeObserver =
        meter
            .longUpDownSumObserverBuilder(actualQueueSizeMetricName)
            .setDescription("The actual queue size observed at collection interval")
            .setUnit("one")
            .setUpdater(
                longResult -> {
                  System.out.println(
                      "emit actual queue size "
                          + actualQueueSize
                          + ","
                          + apiNameValue
                          + ","
                          + statusCodeValue);
                  longResult.observe(
                      actualQueueSize,
                      Labels.of(
                          DIMENSION_API_NAME,
                          apiNameValue,
                          DIMENSION_STATUS_CODE,
                          statusCodeValue));
                })
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

  /**
   * emit queue size change metrics with UpDownCounter metric type
   *
   * @param queueSizeChange
   * @param apiName
   * @param statusCode
   */
  public void emitQueueSizeChangeMetric(int queueSizeChange, String apiName, String statusCode) {
    System.out.println(
        "emit metric with queue size change " + queueSizeChange + "," + apiName + "," + statusCode);
    queueSizeCounter.add(
        queueSizeChange, Labels.of(DIMENSION_API_NAME, apiName, DIMENSION_STATUS_CODE, statusCode));
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
    System.out.println(
        "update total http request size "
            + totalBytesSent
            + " byte, "
            + apiName
            + ","
            + statusCode);
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
    System.out.println(
        "update last latency value " + totalBytesSent + ", " + apiName + "," + statusCode);
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
    System.out.println(
        "update actual queue size " + actualQueueSize + ", " + apiName + "," + statusCode);
  }
}
