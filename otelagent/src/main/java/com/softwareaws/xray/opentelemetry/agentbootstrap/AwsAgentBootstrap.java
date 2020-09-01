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

package com.softwareaws.xray.opentelemetry.agentbootstrap;

import io.opentelemetry.javaagent.OpenTelemetryAgent;
import java.lang.instrument.Instrumentation;

public class AwsAgentBootstrap {

  public static void premain(final String agentArgs, final Instrumentation inst) {
    agentmain(agentArgs, inst);
  }

  public static void agentmain(final String agentArgs, final Instrumentation inst) {
    System.setProperty(
        "io.opentelemetry.javaagent.shaded.io.opentelemetry.trace.spi.TracerProviderFactory",
        "com.softwareaws.xray.opentelemetry.exporters.AwsTracerProviderFactory");
    System.setProperty("otel.propagators", "xray,tracecontext,b3");
    OpenTelemetryAgent.agentmain(agentArgs, inst);
  }
}
