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

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import io.opentelemetry.javaagent.extension.instrumentation.HelperResourceBuilder;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class AwsSdkInstrumentationModule extends InstrumentationModule {

  public AwsSdkInstrumentationModule() {
    super("aws-sdk", "aws-sdk-2.2", "aws-sdk-2.2-core");
  }

  //  @Override
  //  public int order() {
  //    // Ensure this runs after OTel
  //    return 1;
  //  }

  @Override // Need
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {

    System.out.println("ADOT in Instrumentaiton Module Request !!!!!!!");

    // We don't actually transform it but want to make sure we only apply the instrumentation when
    // our key dependency is present.
    return hasClassesNamed("software.amazon.awssdk.core.interceptor.ExecutionInterceptor");
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return Arrays.asList(
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.TracingExecutionInterceptor",
        // other helper classes as needed
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.TracingExecutionInterceptor$RequestSpanFinisher",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkRequest",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkInstrumenterFactory",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.FieldMapper",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.FieldMapping",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.FieldMapping$Type",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsExperimentalAttributes",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkExperimentalAttributesExtractor",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.BedrockJsonParser$JsonPathResolver",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.MethodHandleFactory",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.BedrockJsonParser$LlmJson",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.MethodHandleFactory$1",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.Serializer",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.BedrockJsonParser$JsonParser",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkRequestType$AttributeKeys",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsJsonProtocolFactoryAccess",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.BedrockJsonParser",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkRequestType",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.Response");
  }

  @Override
  public void registerHelperResources(HelperResourceBuilder helperResourceBuilder) {
    String resourcePath = "software/amazon/awssdk/global/handlers/execution.interceptors";
    helperResourceBuilder.register(resourcePath);
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Collections.singletonList(new AwsSdkClientInstrumentation());
  }

  // Defines what to instrument
  public static class AwsSdkClientInstrumentation implements TypeInstrumentation {
    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      // Matches AWS SDK client interface
      return named("software.amazon.awssdk.core.SdkClient");
    }

    @Override
    public void transform(TypeTransformer transformer) {
      // Empty as we use ExecutionInterceptor
    }
  }
}
