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

import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.handlers.HandlerAfterAttemptContext;
import com.amazonaws.handlers.RequestHandler2;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

public class AdotTracingRequestHandler extends RequestHandler2 {
  private final AwsSdkExperimentalAttributesExtractor experimentalAttributesExtractor;

  public AdotTracingRequestHandler() {
    this.experimentalAttributesExtractor = new AwsSdkExperimentalAttributesExtractor();
  }

  // This is the latest point where the sdk request can be obtained after it is modified by the
  // upstream aws-sdk v1.11 handler. This ensures the upstream handles the request and applies its
  // changes first.
  @Override
  public void beforeRequest(Request<?> request) {
    Span currentSpan = Span.current();
    if (currentSpan != null && currentSpan.getSpanContext().isValid()) {

      AttributesBuilder attributes = Attributes.builder();
      experimentalAttributesExtractor.onStart(attributes, Context.current(), request);

      attributes
          .build()
          .forEach(
              (key, value) -> currentSpan.setAttribute((AttributeKey<String>) key, (String) value));
    }
  }

  // This is the latest point to access the sdk response before the span closes in the upstream
  // afterResponse/afterError methods. This ensures we capture attributes from the final, fully
  // modified response after all upstream handlers have processed it.
  @Override
  public void afterAttempt(HandlerAfterAttemptContext context) {
    Span currentSpan = Span.current();
    if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
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
