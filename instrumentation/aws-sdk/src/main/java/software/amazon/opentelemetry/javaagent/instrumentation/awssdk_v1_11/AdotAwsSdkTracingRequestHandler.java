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
import com.amazonaws.handlers.HandlerAfterAttemptContext;
import com.amazonaws.handlers.RequestHandler2;

/**
 * Based on OpenTelemetry Java Instrumentation's AWS SDK v1.11 TracingRequestHandler
 * (release/v2.11.x). Adapts the base instrumentation pattern to add ADOT-specific functionality.
 *
 * <p>Source: <a
 * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/release/v2.11.x/instrumentation/aws-sdk/aws-sdk-1.11/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v1_11/TracingRequestHandler.java">...</a>
 */
public class AdotAwsSdkTracingRequestHandler extends RequestHandler2 {

  public AdotAwsSdkTracingRequestHandler() {}

  /**
   * This is the latest point we can obtain the Sdk Request after it is modified by the upstream
   * TracingInterceptor. It ensures upstream handles the request and applies its changes first.
   *
   * <p>Upstream's last Sdk Request modification: @see <a
   * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/aws-sdk/aws-sdk-1.11/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v1_11/TracingRequestHandler.java#L58">reference</a>
   */
  @Override
  public void beforeRequest(Request<?> request) {}

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
  public void afterAttempt(HandlerAfterAttemptContext context) {}
}
