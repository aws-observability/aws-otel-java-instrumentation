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
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.*;

public class AdotTracingExecutionInterceptor implements ExecutionInterceptor {

  private static final String GEN_AI_SYSTEM_BEDROCK = "aws.bedrock";
  private final FieldMapper fieldMapper = new FieldMapper();

  private static final ExecutionAttribute<AwsSdkRequest> AWS_SDK_REQUEST_ATTRIBUTE =
      new ExecutionAttribute<>(AdotTracingExecutionInterceptor.class.getName() + ".awsSdkRequest");

  public AdotTracingExecutionInterceptor() {}

  @Override
  public void beforeTransmission(
      Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
    System.out.println("modifyRequest !!!!!");

    SdkRequest request = context.request();
    Span currentSpan = Span.current();

    System.out.println(currentSpan);

    try {
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
    }
  }

  @Override
  public void afterUnmarshalling(
      Context.AfterUnmarshalling context, ExecutionAttributes executionAttributes) {

    Span currentSpan = Span.current();

    if (currentSpan == null || !currentSpan.getSpanContext().isValid()) {
      return;
    }

    SdkResponse response = context.response();
    AwsSdkRequest awssdkRequest = executionAttributes.getAttribute(AWS_SDK_REQUEST_ATTRIBUTE);

    if (awssdkRequest != null) {
      fieldMapper.mapToAttributes(response, awssdkRequest, currentSpan);
    }
  }
}
