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

package software.amazon.opentelemetry.javaagent.instrumentation.awslambdacore.v1_0;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.*;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Based on OpenTelemetry Java Instrumentation's AWS Lambda core-1.0 AwsLambdaInstrumentationModule
 * (release/v2.11.x). Adapts the base instrumentation pattern to add ADOT-specific functionality.
 *
 * <p>Source: <a
 * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/release/v2.11.x/instrumentation/aws-lambda/aws-lambda-core-1.0/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/awslambdacore/v1_0/AwsLambdaInstrumentationModule.java">...</a>
 */
public class AdotAwsLambdaInstrumentationModule extends InstrumentationModule {

  public AdotAwsLambdaInstrumentationModule() {
    super("aws-lambda-core-adot", "aws-lambda-core-1.0-adot");
    System.out.println("=== ADOT MODULE LOADED ===");
  }

  @Override
  public int order() {
    // Ensure this runs before OTel (< 0)
    return -1;
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // aws-lambda-events-2.2 is used when SQSEvent is present
    return not(hasClassesNamed("com.amazonaws.services.lambda.runtime.events.SQSEvent"));
  }

  //  @Override
  //  public List<String> getAdditionalHelperClassNames() {
  //    return Collections.singletonList(
  //
  // "software.amazon.opentelemetry.javaagent.instrumentation.awslambdacore.v1_0.AdotTracingRequestHandler");
  //  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    System.out.println("=== CHECKING TYPE MATCHER ===");
    return Arrays.asList(
        new AdotAwsLambdaRequestHandlerInstrumentation(),
        new AdotAwsLambdaRequestStreamHandlerInstrumentation());
  }
}
