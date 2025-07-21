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

/**
 * This class provides instrumentation by injecting our request handler into the AWS client's
 * handler chain. Key components:
 *
 * <p>1. Type Matching: Targets AmazonWebServiceClient (base class for all AWS SDK v1.11 clients).
 * Ensures handler injection during client initialization.
 *
 * <p>2. Transformation: Uses ByteBuddy to modify the client constructor. Injects our handler
 * registration code.
 *
 * <p>3. Handler Registration (via Advice): Checks for existing OpenTelemetry handler and adds ADOT
 * handler only if: a) OpenTelemetry handler is present (ensuring base instrumentation) b) ADOT
 * handler isn't already added (preventing duplicates)
 *
 * <p>Based on OpenTelemetry Java Instrumentation's AWS SDK v1.11 AwsClientInstrumentation
 * (release/v2.11.x). Adapts the base instrumentation pattern to add ADOT-specific functionality.
 *
 * <p>Source: <a
 * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/release/v2.11.x/instrumentation/aws-sdk/aws-sdk-1.11/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/awssdk/v1_11/AwsClientInstrumentation.java">...</a>
 */
public class AdotAwsSdkClientInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    // AmazonWebServiceClient is the base interface for all AWS SDK clients.
    // Type matching against it ensures our interceptor is injected as soon as any AWS SDK client is
    // initialized.
    return named("com.amazonaws.AmazonWebServiceClient")
        .and(declaresField(named("requestHandler2s")));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isConstructor(),
        AdotAwsSdkClientInstrumentation.class.getName() + "$AdotAwsSdkClientAdvice");
  }

  /**
   * Upstream handler registration: @see <a
   * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/aws-sdk/aws-sdk-1.11/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/awssdk/v1_11/AwsClientInstrumentation.java#L39">...</a>
   */
  @SuppressWarnings("unused")
  public static class AdotAwsSdkClientAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addHandler(
        @Advice.FieldValue(value = "requestHandler2s") List<RequestHandler2> handlers) {

      if (handlers == null) {
        return;
      }

      boolean hasOtelHandler = false;
      boolean hasAdotHandler = false;

      // Checks if aws-sdk spans are enabled
      for (RequestHandler2 handler : handlers) {
        if (handler
            .toString()
            .contains(
                "io.opentelemetry.javaagent.instrumentation.awssdk.v1_11.TracingRequestHandler")) {
          hasOtelHandler = true;
        }
        if (handler instanceof AdotAwsSdkTracingRequestHandler) {
          hasAdotHandler = true;
          break;
        }
      }

      // Only adds our handler if aws-sdk spans are enabled. This also ensures upstream
      // instrumentation is applied first.
      if (hasOtelHandler && !hasAdotHandler) {
        handlers.add(new AdotAwsSdkTracingRequestHandler());
      }
    }
  }
}
