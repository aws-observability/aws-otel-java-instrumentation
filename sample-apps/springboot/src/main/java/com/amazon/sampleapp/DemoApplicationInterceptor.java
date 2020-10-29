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

import java.util.Random;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

@Component
public class DemoApplicationInterceptor implements HandlerInterceptor {
  static final String REQUEST_START_TIME = "requestStartTime";

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
        metricEmitter.emitBytesSentMetric(
            request.getContentLength() + mimicPayloadSize(), request.getServletPath(), statusCode);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  private static int mimicPayloadSize() {
    Random randomGenerator = new Random();
    return randomGenerator.nextInt(1000);
  }
}
