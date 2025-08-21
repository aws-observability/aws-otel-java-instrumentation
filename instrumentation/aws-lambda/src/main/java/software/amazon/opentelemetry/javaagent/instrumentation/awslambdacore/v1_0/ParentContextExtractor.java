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

package software.amazon.opentelemetry.javaagent.instrumentation.awslambdacore.v1_0;

import static software.amazon.opentelemetry.javaagent.instrumentation.awslambdacore.v1_0.MapUtils.lowercaseMap;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

public final class ParentContextExtractor {
  private static final String AWS_TRACE_HEADER_ENV_KEY = "_X_AMZN_TRACE_ID";
  private static final String AWS_TRACE_HEADER_PROP = "com.amazonaws.xray.traceHeader";
  // lower-case map getter used for extraction
  static final String AWS_TRACE_HEADER_PROPAGATOR_KEY = "x-amzn-trace-id";

  static Context extract(Map<String, String> headers, AwsLambdaFunctionInstrumenter instrumenter) {
    Context parentContext = null;
    String parentTraceHeader = getTraceHeader();
    if (parentTraceHeader != null) {
      parentContext =
          instrumenter.extract(
              Collections.singletonMap(AWS_TRACE_HEADER_PROPAGATOR_KEY, parentTraceHeader),
              MapGetter.INSTANCE);
    }
    if (!isValidAndSampled(parentContext)) {
      // try http
      parentContext = instrumenter.extract(lowercaseMap(headers), MapGetter.INSTANCE);
    }
    return parentContext;
  }

  private static String getTraceHeader() {
    // Lambda propagates trace header by system property instead of environment variable from java17
    String traceHeader = System.getProperty(AWS_TRACE_HEADER_PROP);
    if (traceHeader == null || traceHeader.isEmpty()) {
      return System.getenv(AWS_TRACE_HEADER_ENV_KEY);
    }
    return traceHeader;
  }

  private static boolean isValidAndSampled(Context context) {
    if (context == null) {
      return false;
    }
    Span parentSpan = Span.fromContext(context);
    SpanContext parentSpanContext = parentSpan.getSpanContext();
    return (parentSpanContext.isValid() && parentSpanContext.isSampled());
  }

  private enum MapGetter implements TextMapGetter<Map<String, String>> {
    INSTANCE;

    @Override
    public Iterable<String> keys(Map<String, String> map) {
      return map.keySet();
    }

    @Override
    public String get(Map<String, String> map, String s) {
      return map.get(s.toLowerCase(Locale.ROOT));
    }
  }

  private ParentContextExtractor() {}
}
