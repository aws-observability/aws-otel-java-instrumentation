package com.amazon.sampleapp;

import java.util.concurrent.ThreadLocalRandom;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

@Component
public class DemoApplicationInterceptor implements HandlerInterceptor {
  static final String REQUEST_START_TIME = "requestStartTime";
  static int mimicQueueSize;

  private static MetricEmitter buildMetricEmitter() {
    return new MetricEmitter();
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {

    request.setAttribute(REQUEST_START_TIME, System.currentTimeMillis());
    return true;
  }

  @Override
  public void postHandle(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler,
      ModelAndView modelAndView) {
    // only emit metric data for /outgoing-http-call path
    if (request.getServletPath().equals("/outgoing-http-call")) {
      try {
        MetricEmitter metricEmitter = buildMetricEmitter();
        String statusCode = String.valueOf(response.getStatus());

        // calculate return time
        Long requestStartTime = (Long) request.getAttribute(REQUEST_START_TIME);
        metricEmitter.emitReturnTimeMetric(
            System.currentTimeMillis() - requestStartTime, request.getServletPath(), statusCode);

        // emit http request load size
        int loadSize = request.getContentLength() + mimicPayloadSize();
        metricEmitter.emitBytesSentMetric(loadSize, request.getServletPath(), statusCode);
        metricEmitter.updateTotalBytesSentMetric(loadSize, request.getServletPath(), statusCode);
        // mimic a queue size reporter
        int queueSizeChange = mimicQueueSizeChange();
        metricEmitter.emitQueueSizeChangeMetric(
            queueSizeChange, request.getServletPath(), statusCode);
        metricEmitter.updateActualQueueSizeMetric(
            queueSizeChange, request.getServletPath(), statusCode);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  private static int mimicPayloadSize() {
    return ThreadLocalRandom.current().nextInt(1000);
  }

  private static int mimicQueueSizeChange() {
    int newQueueSize = ThreadLocalRandom.current().nextInt(100);
    int queueSizeChange = newQueueSize - mimicQueueSize;
    mimicQueueSize = newQueueSize;
    return queueSizeChange;
  }
}
