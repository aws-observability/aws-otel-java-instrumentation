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

package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2;

import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsExperimentalAttributes.GEN_AI_SYSTEM;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkRequestType.BEDROCKRUNTIME;

import io.opentelemetry.api.trace.Span;
import software.amazon.awssdk.auth.signer.AwsSignerExecutionAttribute;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.*;

public class TracingExecutionInterceptor implements ExecutionInterceptor {

  private static final String GEN_AI_SYSTEM_BEDROCK = "aws.bedrock";
  private final FieldMapper fieldMapper = new FieldMapper();

  private static final ExecutionAttribute<io.opentelemetry.context.Context> OtelContextAttribute =
      new ExecutionAttribute<>("otel-context");

  // to capture context early
  @Override
  public void beforeExecution(
      Context.BeforeExecution context, ExecutionAttributes executionAttributes) {
    executionAttributes.putAttribute(
        OtelContextAttribute, io.opentelemetry.context.Context.current());
  }

  @Override
  public SdkRequest modifyRequest(
      Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
    // This is the latest point where we can start the span, since we might need to inject
    // it into the request payload. This means that HTTP attributes need to be captured later.

    System.out.println("ADOT in TracingExection Modify Request !!!!!!!");
    SdkRequest request = context.request();

    // Ignore presign request. These requests don't run all interceptor methods and the span
    // created
    // here would never be ended and scope closed.
    if (executionAttributes.getAttribute(AwsSignerExecutionAttribute.PRESIGNER_EXPIRATION)
        != null) {
      return request;
    }

    io.opentelemetry.context.Context otelContext =
        executionAttributes.getAttribute(OtelContextAttribute);

    if (otelContext == null) {
      return request;
    }

    Span currentSpan = Span.fromContext(otelContext);

    if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
      AwsSdkRequest awsSdkRequest = AwsSdkRequest.ofSdkRequest(request);
      if (awsSdkRequest != null) {
        // Apply field mappings with patched logic
        fieldMapper.mapToAttributes(request, awsSdkRequest, currentSpan);

        // Add Bedrock-specific attributes
        if (awsSdkRequest.type() == BEDROCKRUNTIME) {
          currentSpan.setAttribute(GEN_AI_SYSTEM, GEN_AI_SYSTEM_BEDROCK);
        }
      }
    }

    System.out.println(currentSpan);
    return request;
  }
}
