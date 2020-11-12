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

package com.softwareaws.xray.opentelemetry.providers;

import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.api.trace.spi.TracerProviderFactory;
import io.opentelemetry.sdk.extension.trace.aws.AwsXrayIdGenerator;
import io.opentelemetry.sdk.trace.TracerSdkProvider;

public class AwsTracerProviderFactory implements TracerProviderFactory {

  private static final TracerSdkProvider TRACER_PROVIDER;

  static {
    if (System.getProperty("otel.aws.imds.endpointOverride") == null) {
      String overrideFromEnv = System.getenv("OTEL_AWS_IMDS_ENDPOINT_OVERRIDE");
      if (overrideFromEnv != null) {
        System.setProperty("otel.aws.imds.endpointOverride", overrideFromEnv);
      }
    }

    TRACER_PROVIDER = TracerSdkProvider.builder().setIdsGenerator(new AwsXrayIdGenerator()).build();
  }

  @Override
  public TracerProvider create() {
    return TRACER_PROVIDER;
  }
}
