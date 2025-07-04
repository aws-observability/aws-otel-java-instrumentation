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

import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.*;

public class AdotAwsSdkTracingExecutionInterceptor implements ExecutionInterceptor {

  // This is the latest point we can obtain the Sdk Request after it is modified by the upstream
  // TracingInterceptor. It ensures upstream handles the request and applies its changes first.
  @Override
  public void beforeTransmission(
      Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {}

  // This is the latest point we can obtain the Sdk Response before span completion in upstream's
  // afterExecution. This ensures we capture attributes from the final, fully modified response
  // after all upstream interceptors have processed it.
  @Override
  public SdkResponse modifyResponse(
      Context.ModifyResponse context, ExecutionAttributes executionAttributes) {

    return context.response();
  }
}
