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

public class AdotAwsSdkInstrumentationModule extends InstrumentationModule {

  public AdotAwsSdkInstrumentationModule() {
    super("aws-sdk-adot", "aws-sdk-2.2-adot");
  }

  @Override
  public int order() {
    // Ensure this runs after OTel (> 0)
    return Integer.MAX_VALUE;
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return Arrays.asList(
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AdotAwsSdkTracingExecutionInterceptor",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AdotTracingExecutionInterceptor$RequestSpanFinisher",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkRequest",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkRequestType",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.FieldMapper",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.FieldMapping",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.FieldMapping$Type",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsExperimentalAttributes",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.BedrockJsonParser",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.BedrockJsonParser$JsonPathResolver",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.BedrockJsonParser$LlmJson",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.BedrockJsonParser$JsonParser",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.MethodHandleFactory",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.MethodHandleFactory$1",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.Serializer",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsJsonProtocolFactoryAccess",
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkRequestType$AttributeKeys");
  }

  /**
   * Registers resource file containing reference to our {@link
   * AdotAwsSdkTracingExecutionInterceptor} with SDK's service loading mechanism. The 'order' method
   * ensures this interceptor is registered after upstream. Interceptors are executed in the order
   * they appear in the classpath.
   *
   * @see <a
   *     href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/release/v2.11.x/instrumentation/aws-sdk/aws-sdk-2.2/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/awssdk/v2_2/AwsSdkInstrumentationModule.java#L27">reference</a>
   */
  @Override
  public void registerHelperResources(HelperResourceBuilder helperResourceBuilder) {
    helperResourceBuilder.register(
        "software/amazon/awssdk/global/handlers/execution.interceptors",
        "software/amazon/awssdk/global/handlers/execution.interceptors.adot");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("software.amazon.awssdk.core.interceptor.ExecutionInterceptor");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Collections.singletonList(new ResourceInjectingTypeInstrumentation());
  }

  public static class ResourceInjectingTypeInstrumentation implements TypeInstrumentation {
    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      // SdkClient is the base interface for all AWS SDK clients. Type matching against it ensures
      // our interceptor is injected as soon as any AWS SDK client is initialized.
      return named("software.amazon.awssdk.core.SdkClient");
    }

    @Override
    public void transform(TypeTransformer transformer) {
      // Empty as we use ExecutionInterceptor
    }
  }
}
