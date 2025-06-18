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

import static net.bytebuddy.matcher.ElementMatchers.*;

import com.amazonaws.handlers.RequestHandler2;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class AdotAwsClientInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {

    System.out.println("Type matcher called for AmazonWebServiceClient");

    return named("com.amazonaws.AmazonWebServiceClient")
        .and(declaresField(named("requestHandler2s")));
  }

  @Override
  public void transform(TypeTransformer transformer) {

    System.out.println("Transforming AmazonWebServiceClient");

    transformer.applyAdviceToMethod(
        isConstructor(), AdotAwsClientInstrumentation.class.getName() + "$AdotAwsClientAdvice");
  }

  @SuppressWarnings("unused")
  public static class AdotAwsClientAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addHandler(
        @Advice.FieldValue(value = "requestHandler2s", readOnly = false)
            List<RequestHandler2> handlers) {
      System.out.println("Current handlers before ADOT: {}" + handlers);

      if (handlers == null) {
        System.out.println("Handlers list is null");
        return;
      }

      // Check if OTel handler is present
      boolean hasOtelHandler = false;
      boolean hasAdotHandler = false;

      for (RequestHandler2 handler : handlers) {
        if (handler
            .toString()
            .contains(
                "io.opentelemetry.javaagent.instrumentation.awssdk.v1_11.TracingRequestHandler")) {
          hasOtelHandler = true;
          System.out.println("has Otel Handler");
        }

        if (handler instanceof AdotTracingRequestHandler) {
          hasAdotHandler = true;
          System.out.println("has Adot Handler");
          break;
        }
      }

      System.out.println(hasOtelHandler);
      System.out.println(hasAdotHandler);

      System.out.println("Current handlers before ADOT: {}" + handlers);

      // Only add our handler if OTel's is present and ours isn't
      if (hasOtelHandler && !hasAdotHandler) {
        System.out.println("adding handler");
        handlers.add(new AdotTracingRequestHandler());
        System.out.println("after adding handler:" + handlers);
      }

      System.out.println("Final handlers before ADOT: {}" + handlers);
    }
  }
}
