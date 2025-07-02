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
import io.opentelemetry.javaagent.extension.instrumentation.internal.ExperimentalInstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.internal.injection.ClassInjector;
import io.opentelemetry.javaagent.extension.instrumentation.internal.injection.InjectionMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class AdotAwsSdkInstrumentationModule extends InstrumentationModule
    implements ExperimentalInstrumentationModule {

  public AdotAwsSdkInstrumentationModule() {
    super("aws-sdk-adot", "aws-sdk-2.2-adot");
  }

  @Override
  public int order() {
    // Ensure this runs after OTel (> 0)
    return 99;
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return Arrays.asList(
        "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AdotTracingExecutionInterceptor");
  }

  // This registers the upstream execution interceptor first, and then the
  // AdotTracingExecutionInterceptor. This ensures the upstream interceptor runs before ADOT's
  // interceptor and maintains order.
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
  public void injectClasses(final ClassInjector injector) {
    injector
        .proxyBuilder(
            "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AdotTracingExecutionInterceptor")
        .inject(InjectionMode.CLASS_ONLY);
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Collections.singletonList(new ResourceInjectingTypeInstrumentation());
  }

  public static class ResourceInjectingTypeInstrumentation implements TypeInstrumentation {
    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named("software.amazon.awssdk.core.SdkClient");
    }

    @Override
    public void transform(TypeTransformer transformer) {
      // Empty as we use ExecutionInterceptor
    }
  }
}
