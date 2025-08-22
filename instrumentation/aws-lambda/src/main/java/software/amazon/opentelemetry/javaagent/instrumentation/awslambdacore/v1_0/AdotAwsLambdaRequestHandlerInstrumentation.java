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
import java.util.Collections;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner.Typing;
import net.bytebuddy.matcher.ElementMatcher;

public class AdotAwsLambdaRequestHandlerInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed("com.amazonaws.services.lambda.runtime.RequestHandler");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("com.amazonaws.services.lambda.runtime.RequestHandler"))
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
            .and(takesArgument(1, named("com.amazonaws.services.lambda.runtime.Context"))),
        AdotAwsLambdaRequestHandlerInstrumentation.class.getName() + "$AdotHandleRequestAdvice");
  }

  @SuppressWarnings("unused")
  public static class AdotHandleRequestAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(value = 0, typing = Typing.DYNAMIC) Object arg,
        @Advice.Argument(1) Context context,
        @Advice.Local("otelInput") AwsLambdaRequest input,
        @Advice.Local("otelContext") io.opentelemetry.context.Context otelContext,
        @Advice.Local("otelScope") Scope otelScope) {
      System.out.println("ADOT instrumentation running");
      System.out.println("Extracting context...");

      // Log X-Ray header
      String xrayHeader = System.getenv("_X_AMZN_TRACE_ID");
      System.out.println("X-Ray header: " + xrayHeader);

      input = AwsLambdaRequest.create(context, arg, Collections.emptyMap());
      io.opentelemetry.context.Context parentContext = functionInstrumenter().extract(input);

      // Log context
      System.out.println("Extracted context: " + parentContext);

      if (!functionInstrumenter().shouldStart(parentContext, input)) {
        return;
      }

      otelContext = parentContext;
      otelScope = otelContext.makeCurrent();
    }
  }
}
