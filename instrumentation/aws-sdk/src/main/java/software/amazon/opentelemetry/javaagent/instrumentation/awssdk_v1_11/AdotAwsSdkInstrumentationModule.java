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

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Based on OpenTelemetry Java Instrumentation's AWS SDK v1.11 AbstractAwsSdkInstrumentationModule
 * (release/v2.11.x). Adapts the base instrumentation pattern to add ADOT-specific functionality.
 *
 * <p>Source: <a
 * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/release/v2.11.x/instrumentation/aws-sdk/aws-sdk-1.11/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/awssdk/v1_11/AbstractAwsSdkInstrumentationModule.java">...</a>
 */
public class AdotAwsSdkInstrumentationModule extends InstrumentationModule {

  public AdotAwsSdkInstrumentationModule() {
    super("aws-sdk-adot", "aws-sdk-1.11-adot");
  }

  @Override
  public int order() {
    // Ensure this runs after OTel (> 0)
    return Integer.MAX_VALUE;
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return Arrays.asList(
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AdotAwsSdkTracingRequestHandler");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("com.amazonaws.AmazonWebServiceClient");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Collections.singletonList(new AdotAwsSdkClientInstrumentation());
  }
}
