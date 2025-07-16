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

import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsExperimentalAttributes.AWS_AUTH_ACCESS_KEY;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsExperimentalAttributes.AWS_AUTH_REGION;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsExperimentalAttributes.GEN_AI_SYSTEM;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkRequestType.BEDROCKRUNTIME;

import io.opentelemetry.api.trace.Span;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.signer.AwsSignerExecutionAttribute;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.*;
import software.amazon.awssdk.regions.Region;

public class AdotAwsSdkTracingExecutionInterceptor implements ExecutionInterceptor {

  private static final String GEN_AI_SYSTEM_BEDROCK = "aws.bedrock";
  private static final ExecutionAttribute<AwsSdkRequest> AWS_SDK_REQUEST_ATTRIBUTE =
      new ExecutionAttribute<>(
          AdotAwsSdkTracingExecutionInterceptor.class.getName() + ".AwsSdkRequest");

  private final FieldMapper fieldMapper = new FieldMapper();

  /**
   * This is the latest point we can obtain the Sdk Request after it is modified by the upstream
   * TracingInterceptor. It ensures upstream handles the request and applies its changes first.
   *
   * <p>Upstream's last Sdk Request modification: @see <a
   * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/release/v2.11.x/instrumentation/aws-sdk/aws-sdk-2.2/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v2_2/internal/TracingExecutionInterceptor.java#L237">reference</a>
   */
  @Override
  public void beforeTransmission(
      Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {

    SdkRequest request = context.request();
    Span currentSpan = Span.current();

    try {
      if (currentSpan == null || !currentSpan.getSpanContext().isValid()) {
        return;
      }

      AwsCredentials credentials =
          executionAttributes.getAttribute(AwsSignerExecutionAttribute.AWS_CREDENTIALS);
      Region signingRegion =
          executionAttributes.getAttribute(AwsSignerExecutionAttribute.SIGNING_REGION);

      if (credentials != null) {
        String accessKeyId = credentials.accessKeyId();
        if (accessKeyId != null) {
          currentSpan.setAttribute(AWS_AUTH_ACCESS_KEY, accessKeyId);
        }
      }

      if (signingRegion != null) {
        String region = signingRegion.toString();
        currentSpan.setAttribute(AWS_AUTH_REGION, region);
      }

      AwsSdkRequest awsSdkRequest = AwsSdkRequest.ofSdkRequest(request);
      if (awsSdkRequest != null) {
        executionAttributes.putAttribute(AWS_SDK_REQUEST_ATTRIBUTE, awsSdkRequest);
        fieldMapper.mapToAttributes(request, awsSdkRequest, currentSpan);
        if (awsSdkRequest.type() == BEDROCKRUNTIME) {
          currentSpan.setAttribute(GEN_AI_SYSTEM, GEN_AI_SYSTEM_BEDROCK);
        }
      }
    } catch (Throwable throwable) {
      // ignore
    }
  }

  /**
   * This is the latest point we can obtain the Sdk Response before span completion in upstream's
   * afterExecution. This ensures we capture attributes from the final, fully modified response
   * after all upstream interceptors have processed it.
   *
   * <p>Upstream's last Sdk Response modification before span closure: @see <a
   * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/release/v2.11.x/instrumentation/aws-sdk/aws-sdk-2.2/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v2_2/internal/TracingExecutionInterceptor.java#L348">reference</a>
   */
  @Override
  public SdkResponse modifyResponse(
      Context.ModifyResponse context, ExecutionAttributes executionAttributes) {

    Span currentSpan = Span.current();
    AwsSdkRequest sdkRequest = executionAttributes.getAttribute(AWS_SDK_REQUEST_ATTRIBUTE);

    if (sdkRequest != null) {
      fieldMapper.mapToAttributes(context.response(), sdkRequest, currentSpan);
      executionAttributes.putAttribute(AWS_SDK_REQUEST_ATTRIBUTE, null);
    }

    return context.response();
  }
}
