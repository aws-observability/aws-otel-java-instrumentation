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

package software.amazon.opentelemetry.javaagent.instrumentation.logback_1_0;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.util.VirtualField;
import io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner.Typing;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.MDC;

public class AwsXrayLoggingEventInstrumentation implements TypeInstrumentation {
  private static final String TRACE_ID_KEY = "AWS-XRAY-TRACE-ID";

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed("ch.qos.logback.classic.spi.ILoggingEvent");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("ch.qos.logback.classic.spi.ILoggingEvent"));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isMethod()
            .and(isPublic())
            .and(named("getMDCPropertyMap").or(named("getMdc")))
            .and(takesArguments(0)),
        AwsXrayLoggingEventInstrumentation.class.getName() + "$GetMdcAdvice");
  }

  public static class GetMdcAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This ILoggingEvent event,
        @Advice.Return(typing = Typing.DYNAMIC, readOnly = false) Map<String, String> contextData) {
      if (contextData != null && contextData.containsKey(TRACE_ID_KEY)) {
        // Assume already instrumented event if traceId is present.
        return;
      }

      Context context = VirtualField.find(ILoggingEvent.class, Context.class).get(event);
      if (context == null) {
        return;
      }

      SpanContext spanContext = Java8BytecodeBridge.spanFromContext(context).getSpanContext();
      if (!spanContext.isValid()) {
        // Remove any remaining trace id from the MDC
        MDC.remove(TRACE_ID_KEY);
        return;
      }

      String value =
          "1-"
              + spanContext.getTraceId().substring(0, 8)
              + "-"
              + spanContext.getTraceId().substring(8)
              + "@"
              + spanContext.getSpanId();

      MDC.put(TRACE_ID_KEY, value);
    }
  }
}
