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
import com.amazonaws.handlers.RequestHandler2;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

public class AdotTracingRequestHandler extends RequestHandler2 {
  private final AwsSdkExperimentalAttributesExtractor experimentalAttributesExtractor;

  public AdotTracingRequestHandler() {
    //    System.out.println("Starting AdotTracingRequestHandler constructor");

    this.experimentalAttributesExtractor = new AwsSdkExperimentalAttributesExtractor();

    //    System.out.println("Successfully created experimentalAttributesExtractor");
  }

  @Override
  public void beforeRequest(Request<?> request) {
    System.out.println("HERE BEFORE REQUEST");
    Span currentSpan = Span.current();
    if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
      // Create attributes builder
      AttributesBuilder attributes = Attributes.builder();

      // Extract experimental attributes
      experimentalAttributesExtractor.onStart(attributes, Context.current(), request);

      // Add all built attributes to the span
      attributes
          .build()
          .forEach(
              (key, value) -> currentSpan.setAttribute((AttributeKey<String>) key, (String) value));
    }
  }

  @Override
  public void afterResponse(Request<?> request, Response<?> response) {
    Span currentSpan = Span.current();
    if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
      // Create attributes builder
      AttributesBuilder attributes = Attributes.builder();

      // Extract experimental response attributes
      experimentalAttributesExtractor.onEnd(
          attributes, Context.current(), request, response, null // no error
          );

      // Add all built attributes to the span
      attributes
          .build()
          .forEach(
              (key, value) -> currentSpan.setAttribute((AttributeKey<String>) key, (String) value));
    }
  }
}
