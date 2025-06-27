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
    return named("com.amazonaws.AmazonWebServiceClient")
        .and(declaresField(named("requestHandler2s")));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isConstructor(), AdotAwsClientInstrumentation.class.getName() + "$AdotAwsClientAdvice");
  }

  @SuppressWarnings("unused")
  public static class AdotAwsClientAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addHandler(
        @Advice.FieldValue(value = "requestHandler2s", readOnly = false)
            List<RequestHandler2> handlers) {

      if (handlers == null) {
        return;
      }

      boolean hasOtelHandler = false;
      boolean hasAdotHandler = false;

      // Checks if OTel handler is present.
      for (RequestHandler2 handler : handlers) {
        if (handler
            .toString()
            .contains(
                "io.opentelemetry.javaagent.instrumentation.awssdk.v1_11.TracingRequestHandler")) {
          hasOtelHandler = true;
        }
        if (handler instanceof AdotTracingRequestHandler) {
          hasAdotHandler = true;
          break;
        }
      }

      // Only adds our ADOT handler if OTel's is present and ours isn't. This ensures upstream
      // instrumentation is applied first.
      if (hasOtelHandler && !hasAdotHandler) {
        handlers.add(new AdotTracingRequestHandler());
      }
    }
  }
}
