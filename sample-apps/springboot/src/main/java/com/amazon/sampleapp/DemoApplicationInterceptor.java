/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
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
