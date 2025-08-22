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
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static software.amazon.opentelemetry.javaagent.instrumentation.awslambdacore.v1_0.AdotAwsLambdaInstrumentationHelper.functionInstrumenter;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.io.InputStream;
import java.util.Collections;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class AdotAwsLambdaRequestStreamHandlerInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed("com.amazonaws.services.lambda.runtime.RequestStreamHandler");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("com.amazonaws.services.lambda.runtime.RequestStreamHandler"))
        .and(not(nameStartsWith("com.amazonaws.services.lambda.runtime.api.client")))
        // In Java 8 and Java 11 runtimes,
        // AWS Lambda runtime is packaged under `lambdainternal` package.
        // But it is `com.amazonaws.services.lambda.runtime.api.client`
        // for new runtime likes Java 17 and Java 21.
        .and(not(nameStartsWith("lambdainternal")));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isMethod()
            .and(isPublic())
            .and(named("handleRequest"))
            .and(takesArgument(2, named("com.amazonaws.services.lambda.runtime.Context"))),
        AdotAwsLambdaRequestStreamHandlerInstrumentation.class.getName()
            + "$AdotHandleRequestAdvice");
  }

  @SuppressWarnings("unused")
  public static class AdotHandleRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) InputStream input,
        @Advice.Argument(2) Context context,
        @Advice.Local("otelInput") AwsLambdaRequest otelInput,
        @Advice.Local("otelContext") io.opentelemetry.context.Context otelContext,
        @Advice.Local("otelScope") Scope otelScope) {

      otelInput = AwsLambdaRequest.create(context, input, Collections.emptyMap());
      io.opentelemetry.context.Context parentContext = functionInstrumenter().extract(otelInput);

      if (!functionInstrumenter().shouldStart(parentContext, otelInput)) {
        return;
      }

      otelContext = parentContext;
      otelScope = otelContext.makeCurrent();
    }
  }
}
