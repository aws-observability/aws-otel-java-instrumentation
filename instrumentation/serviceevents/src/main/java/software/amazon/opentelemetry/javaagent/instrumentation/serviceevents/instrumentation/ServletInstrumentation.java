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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.instrumentation;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servlet instrumentation utilities for ServiceEvents.
 *
 * <p>Provides helper methods and data classes used by ByteBuddy advice (ServletAdvice) for tracking
 * HTTP requests. The actual advice logic is in ServiceEventsInstrumentationModule.
 */
public class ServletInstrumentation {

  // Lazy logger: this class may be loaded during agent init as a helper class.
  private static volatile Logger logger;

  private static Logger logger() {
    Logger local = logger;
    if (local == null) {
      local = Logger.getLogger(ServletInstrumentation.class.getName());
      logger = local;
    }
    return local;
  }

  private ServletInstrumentation() {
    // Utility class
  }

  /** Extract headers from servlet request via reflection. */
  @SuppressWarnings("unchecked")
  public static Map<String, String> extractHeaders(Object request) {
    Map<String, String> headers = new HashMap<>();
    try {
      java.lang.reflect.Method getHeaderNames = request.getClass().getMethod("getHeaderNames");
      Enumeration<String> headerNames = (Enumeration<String>) getHeaderNames.invoke(request);

      java.lang.reflect.Method getHeader = request.getClass().getMethod("getHeader", String.class);
      while (headerNames.hasMoreElements()) {
        String name = headerNames.nextElement();
        String value = (String) getHeader.invoke(request, name);
        headers.put(name, value);
      }
    } catch (Exception e) {
      logger().log(Level.FINE, "Could not extract headers from request", e);
    }
    return headers;
  }

  /** Extract query parameters from servlet request via reflection. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> extractQueryParams(Object request) {
    Map<String, Object> params = new HashMap<>();
    try {
      java.lang.reflect.Method getParameterMap = request.getClass().getMethod("getParameterMap");
      Map<String, String[]> parameterMap = (Map<String, String[]>) getParameterMap.invoke(request);

      for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
        String[] values = entry.getValue();
        if (values.length == 1) {
          params.put(entry.getKey(), values[0]);
        } else {
          params.put(entry.getKey(), values);
        }
      }
    } catch (Exception e) {
      logger().log(Level.FINE, "Could not extract query params from request", e);
    }
    return params;
  }

  /** Container for request tracking context. */
  public static class RequestContext {
    public final String method;
    public final String route;

    /** Monotonic nanoTime at request entry — used for precise duration calculation. */
    public final long startTimeNs;

    /**
     * Epoch nanoseconds at request entry — used for JFR sample correlation. Computed as {@code
     * System.currentTimeMillis() * 1_000_000L} at entry time. This anchors the request to
     * wall-clock time with millisecond precision, while the duration (computed from monotonic
     * nanoTime) adds nanosecond precision for the end time.
     */
    public final long epochStartTimeNs;

    public RequestContext(String method, String route, long startTimeNs, long epochStartTimeNs) {
      this.method = method;
      this.route = route;
      this.startTimeNs = startTimeNs;
      this.epochStartTimeNs = epochStartTimeNs;
    }
  }
}
