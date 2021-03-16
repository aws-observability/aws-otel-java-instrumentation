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

package software.amazon.opentelemetry.javaagent.instrumentation.log4j_2_13_2;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.Collections;
import java.util.Map;
import org.apache.logging.log4j.core.util.ContextDataProvider;

/**
 * A {@link ContextDataProvider} which injects the trace and span ID of the current {@link Span} in
 * a format for consumption by AWS X-Ray and related services.
 */
public class AwsXrayContextDataProvider implements ContextDataProvider {
  private static final String TRACE_ID_KEY = "AWS-XRAY-TRACE-ID";

  @Override
  public Map<String, String> supplyContextData() {
    Span currentSpan = Span.current();
    SpanContext spanContext = currentSpan.getSpanContext();
    if (!spanContext.isValid()) {
      return Collections.emptyMap();
    }

    String value =
        TRACE_ID_KEY
            + ": 1-"
            + spanContext.getTraceId().substring(0, 8)
            + "-"
            + spanContext.getTraceId().substring(8)
            + "@"
            + spanContext.getSpanId();
    return Collections.singletonMap(TRACE_ID_KEY, value);
  }
}
