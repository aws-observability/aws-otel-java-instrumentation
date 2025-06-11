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

import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsExperimentalAttributes.GEN_AI_SYSTEM;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkRequestType.BEDROCKRUNTIME;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.*;

public class AdotTracingExecutionInterceptor implements ExecutionInterceptor {

  private static final String GEN_AI_SYSTEM_BEDROCK = "aws.bedrock";
  private final AdotAwsSdkInstrumenterFactory instrumenterFactory;
  private final FieldMapper fieldMapper = new FieldMapper();
  private static final ExecutionAttribute<Scope> SCOPE_ATTRIBUTE =
      new ExecutionAttribute<>(AdotTracingExecutionInterceptor.class.getName() + ".Scope");

  public AdotTracingExecutionInterceptor() {
    // for instantiation
    this.instrumenterFactory = new AdotAwsSdkInstrumenterFactory();
  }

  @Override
  public void beforeTransmission(
      Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
    System.out.println("modifyRequest !!!!!");

    io.opentelemetry.context.Context parentOtelContext = io.opentelemetry.context.Context.current();
    SdkRequest request = context.request();

    Instrumenter<ExecutionAttributes, Response> instrumenter =
        instrumenterFactory.requestInstrumenter();

    if (!instrumenter.shouldStart(parentOtelContext, executionAttributes)) {
      // NB: We also skip injection in case we don't start.
      return;
    }

    io.opentelemetry.context.Context otelContext =
        instrumenter.start(parentOtelContext, executionAttributes);
    final Span currentSpan = Span.current();

    System.out.println(currentSpan);

    try {
      if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
        AwsSdkRequest awsSdkRequest = AwsSdkRequest.ofSdkRequest(request);
        if (awsSdkRequest != null) {
          fieldMapper.mapToAttributes(request, awsSdkRequest, currentSpan);
          if (awsSdkRequest.type() == BEDROCKRUNTIME) {
            currentSpan.setAttribute(GEN_AI_SYSTEM, GEN_AI_SYSTEM_BEDROCK);
          }
        }
      }
    } catch (Throwable throwable) {
      //            instrumenter.end(otelContext, executionAttributes, null, throwable);
      //            clearAttributes(executionAttributes);
    }
  }

  private static void clearAttributes(ExecutionAttributes executionAttributes) {
    Scope scope = executionAttributes.getAttribute(SCOPE_ATTRIBUTE);
    if (scope != null) {
      scope.close();
    }
  }
}
