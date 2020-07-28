/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package com.softwareaws.xray.opentelemetry.exporters;

import io.opentelemetry.OpenTelemetry;
import io.opentelemetry.auto.bootstrap.spi.TracerCustomizer;
import io.opentelemetry.context.propagation.DefaultContextPropagators;
import io.opentelemetry.sdk.trace.TracerSdkProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AwsTracerCustomizer implements TracerCustomizer {

    private static final Logger logger = LoggerFactory.getLogger(AwsTracerProviderFactory.class);

    @Override
    public void configure(TracerSdkProvider unused) {
        logger.info("AwsTracerCustomizer");
        OpenTelemetry.setPropagators(DefaultContextPropagators.builder()
                                                              .addHttpTextFormat(new AwsXRayPropagator())
                                                              .build());
    }
}
