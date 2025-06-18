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
import static net.bytebuddy.matcher.ElementMatchers.*;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

public class AdotAwsSdkInstrumentationModule extends InstrumentationModule {

  public AdotAwsSdkInstrumentationModule() {
    super("aws-sdk-adot", "aws-sdk-1.11-adot");
  }

  @Override
  public int order() {
    // Ensure this runs after OTel (> 0)
    return 99;
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    System.out.println("ADOT in getAdditionalHelperClassNames");
    return Arrays.asList(
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AdotTracingRequestHandler",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsSdkExperimentalAttributesExtractor",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsBedrockResourceType",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsBedrockResourceType$AwsBedrockResourceTypeMap",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.BedrockJsonParser",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.RequestAccess",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.RequestAccess$1");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // We don't actually transform it but want to make sure we only apply the instrumentation when
    // our key dependency is present.
    return hasClassesNamed("com.amazonaws.AmazonWebServiceClient");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Collections.singletonList(new AdotAwsClientInstrumentation());
    //        new AdotAwsHttpClientInstrumentation(),
    //        new AdotRequestExecutorInstrumentation());
  }
}
