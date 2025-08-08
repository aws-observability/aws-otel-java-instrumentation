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

package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11;

import static io.opentelemetry.instrumentation.api.internal.ConfigPropertiesUtil.getBoolean;

import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.handlers.HandlerAfterAttemptContext;
import com.amazonaws.handlers.RequestHandler2;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

/**
 * This handler extends the AWS SDK v1.11 request handling chain to add ADOT-specific span
 * attributes. It operates at two key points in the request lifecycle:
 *
 * <p>1. Request Phase (beforeRequest):
 *
 * <ul>
 *   <li>Intercepts the request after upstream modifications
 *   <li>Extracts experimental attributes from the request in a separate AttributesBuilder
 *   <li>Adds these attributes to the current span
 * </ul>
 *
 * <p>2. Response/Error Phase (afterAttempt):
 *
 * <ul>
 *   <li>Captures final state after all upstream handlers
 *   <li>Extracts attributes from both request and response/error in a separate AttributesBuilder
 *   <li>Adds these attributes to the current span before it closes
 * </ul>
 *
 * Based on OpenTelemetry Java Instrumentation's AWS SDK v1.11 TracingRequestHandler
 * (release/v2.11.x). Adapts the base instrumentation pattern to add ADOT-specific functionality.
 *
 * <p>Source: <a
 * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/release/v2.11.x/instrumentation/aws-sdk/aws-sdk-1.11/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v1_11/TracingRequestHandler.java">...</a>
 */
public class AdotAwsSdkTracingRequestHandler extends RequestHandler2 {
  private final AwsSdkExperimentalAttributesExtractor experimentalAttributesExtractor;
  private final boolean captureExperimentalSpanAttributes =
      getBoolean("otel.instrumentation.aws-sdk.experimental-span-attributes", true);

  public AdotAwsSdkTracingRequestHandler() {
    this.experimentalAttributesExtractor = new AwsSdkExperimentalAttributesExtractor();
  }

  /**
   * This is the latest point we can obtain the Sdk Request after it is modified by the upstream
   * TracingInterceptor. It ensures upstream handles the request and applies its changes first.
   *
   * <p>Upstream's last Sdk Request modification: @see <a
   * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/aws-sdk/aws-sdk-1.11/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v1_11/TracingRequestHandler.java#L58">reference</a>
   */
  @Override
  public void beforeRequest(Request<?> request) {
    Span currentSpan = Span.current();

    if (captureExperimentalSpanAttributes
        && currentSpan != null
        && currentSpan.getSpanContext().isValid()) {
      AttributesBuilder attributes = Attributes.builder();
      experimentalAttributesExtractor.onStart(attributes, Context.current(), request);

      attributes
          .build()
          .forEach((key, value) -> currentSpan.setAttribute(key.getKey(), value.toString()));
    }
  }

  /**
   * This is the latest point to access the sdk response before the span closes in the upstream
   * afterResponse/afterError methods. This ensures we capture attributes from the final, fully
   * modified response after all upstream interceptors have processed it.
   *
   * <p>Upstream's last Sdk Response modification before span closure: @see <a
   * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/aws-sdk/aws-sdk-1.11/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v1_11/TracingRequestHandler.java#L116">reference</a>
   *
   * @see <a
   *     href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/aws-sdk/aws-sdk-1.11/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v1_11/TracingRequestHandler.java#L131">reference</a>
   */
  @Override
  public void afterAttempt(HandlerAfterAttemptContext context) {
    Span currentSpan = Span.current();

    if (captureExperimentalSpanAttributes
        && currentSpan != null
        && currentSpan.getSpanContext().isValid()) {
      Request<?> request = context.getRequest();
      Response<?> response = context.getResponse();
      Exception exception = context.getException();

      AttributesBuilder attributes = Attributes.builder();
      experimentalAttributesExtractor.onEnd(
          attributes, Context.current(), request, response, exception);

      attributes
          .build()
          .forEach((key, value) -> currentSpan.setAttribute(key.getKey(), value.toString()));
    }
  }
}
