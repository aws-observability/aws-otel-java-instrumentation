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
import software.amazon.awssdk.http.SdkHttpResponse;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public final class Response {
  private final SdkHttpResponse sdkHttpResponse;
  private final SdkResponse sdkResponse;

  Response(SdkHttpResponse sdkHttpResponse) {
    this(sdkHttpResponse, null);
  }

  Response(SdkHttpResponse sdkHttpResponse, SdkResponse sdkResponse) {
    this.sdkHttpResponse = sdkHttpResponse;
    this.sdkResponse = sdkResponse;
  }

  public SdkHttpResponse getSdkHttpResponse() {
    return sdkHttpResponse;
  }

  public SdkResponse getSdkResponse() {
    return sdkResponse;
  }
}
