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
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.*;

public class AdotTracingExecutionInterceptor implements ExecutionInterceptor {

  private static final String GEN_AI_SYSTEM_BEDROCK = "aws.bedrock";

  private static final ExecutionAttribute<AwsSdkRequest> AWS_SDK_REQUEST_ATTRIBUTE =
      new ExecutionAttribute<>(AdotTracingExecutionInterceptor.class.getName() + ".AwsSdkRequest");

  private final FieldMapper fieldMapper = new FieldMapper();

  // This is the latest point we can obtain the Sdk Request after it is modified by the upstream
  // TracingInterceptor. It ensures upstream handles the request and applies its changes first.
  @Override
  public void beforeTransmission(
      Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {

    SdkRequest request = context.request();
    Span currentSpan = Span.current();

    try {
      // Skip injection if Otel span is invalid
      if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
        AwsSdkRequest awsSdkRequest = AwsSdkRequest.ofSdkRequest(request);
        if (awsSdkRequest != null) {
          executionAttributes.putAttribute(AWS_SDK_REQUEST_ATTRIBUTE, awsSdkRequest);
          fieldMapper.mapToAttributes(request, awsSdkRequest, currentSpan);
          if (awsSdkRequest.type() == BEDROCKRUNTIME) {
            currentSpan.setAttribute(GEN_AI_SYSTEM, GEN_AI_SYSTEM_BEDROCK);
          }
        }
      }
    } catch (Throwable throwable) {
      // ignore
    }
  }

  // This is the earliest point we can obtain the Sdk Response before it is modified by the upstream
  // interceptor. This ensures the execution attribute (AWS_SDK_REQUEST_ATTRIBUTE) added in by the
  // interceptor is handled only by this interceptor, and not the upstream interceptor.
  @Override
  public void afterUnmarshalling(
      Context.AfterUnmarshalling context, ExecutionAttributes executionAttributes) {

    Span currentSpan = Span.current();
    AwsSdkRequest sdkRequest = executionAttributes.getAttribute(AWS_SDK_REQUEST_ATTRIBUTE);

    if (sdkRequest != null) {
      fieldMapper.mapToAttributes(context.response(), sdkRequest, currentSpan);
      //      executionAttributes.putAttribute(AWS_SDK_REQUEST_ATTRIBUTE, null);
    }
  }
}
