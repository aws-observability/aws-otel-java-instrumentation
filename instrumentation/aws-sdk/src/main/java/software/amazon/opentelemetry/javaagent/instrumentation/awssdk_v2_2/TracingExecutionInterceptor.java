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

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import java.time.Instant;
import java.util.logging.Logger;
import software.amazon.awssdk.auth.signer.AwsSignerExecutionAttribute;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpResponse;

public class TracingExecutionInterceptor implements ExecutionInterceptor {
  private static final String GEN_AI_SYSTEM_BEDROCK = "aws.bedrock";

  private static final ExecutionAttribute<io.opentelemetry.context.Context> CONTEXT_ATTRIBUTE =
      new ExecutionAttribute<>(TracingExecutionInterceptor.class.getName() + ".Context");

  private static final ExecutionAttribute<Scope> SCOPE_ATTRIBUTE =
      new ExecutionAttribute<>(TracingExecutionInterceptor.class.getName() + ".Scope");

  private static final ExecutionAttribute<AwsSdkRequest> AWS_SDK_REQUEST_ATTRIBUTE =
      new ExecutionAttribute<>(TracingExecutionInterceptor.class.getName() + ".AwsSdkRequest");

  private final AwsSdkInstrumenterFactory instrumenterFactory; // ADDED

  private static final Logger logger =
      Logger.getLogger(TracingExecutionInterceptor.class.getName()); // ADDED

  private static final ExecutionAttribute<RequestSpanFinisher> REQUEST_FINISHER_ATTRIBUTE =
      new ExecutionAttribute<>(TracingExecutionInterceptor.class.getName() + ".RequestFinisher");

  private final FieldMapper fieldMapper = new FieldMapper();

  public TracingExecutionInterceptor() {
    // for instantiation
    System.out.println("Creating TracingExecutionInterceptor");
    this.instrumenterFactory = new AwsSdkInstrumenterFactory(GlobalOpenTelemetry.get());
  }

  @Override
  public SdkRequest modifyRequest(
      Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
    System.out.println(
        "ADOT !! HERE AwsSdkPatchInstrumentationModule.modifyRequest!!!!!!!!!!!!!: ");

    // This is the latest point where we can start the span, since we might need to inject
    // it into the request payload. This means that HTTP attributes need to be captured later.

    io.opentelemetry.context.Context parentOtelContext = io.opentelemetry.context.Context.current();
    SdkRequest request = context.request();

    // Ignore presign request. These requests don't run all interceptor methods and the span
    // created
    // here would never be ended and scope closed.
    if (executionAttributes.getAttribute(AwsSignerExecutionAttribute.PRESIGNER_EXPIRATION)
        != null) {
      return request;
    }

    // executionAttributes.putAttribute(SDK_REQUEST_ATTRIBUTE, request);
    Instrumenter<ExecutionAttributes, Response> instrumenter =
        instrumenterFactory.requestInstrumenter();

    if (!instrumenter.shouldStart(parentOtelContext, executionAttributes)) {
      // NB: We also skip injection in case we don't start.
      return request;
    }

    RequestSpanFinisher requestFinisher = instrumenter::end;
    io.opentelemetry.context.Context otelContext =
        instrumenter.start(parentOtelContext, executionAttributes);

    Instant requestStart = Instant.now();

    Span span = Span.fromContext(otelContext);

    System.out.println("ADOT !! HERE Span span = Span.fromContext(otelContext)!!!!!!!!!!!!!: ");
    System.out.println(span);

    try {
      AwsSdkRequest awsSdkRequest = AwsSdkRequest.ofSdkRequest(context.request());
      if (awsSdkRequest != null) {
        executionAttributes.putAttribute(AWS_SDK_REQUEST_ATTRIBUTE, awsSdkRequest);
        System.out.println("ADOT !! HERE populateRequestAttributes!!!!!!!!!!!!!: ");
        populateRequestAttributes(span, awsSdkRequest, context.request(), executionAttributes);
      }
    } catch (Throwable throwable) {
      requestFinisher.finish(otelContext, executionAttributes, null, throwable);
      clearAttributes(executionAttributes);
      System.out.println("ADOT !! HERE returning the request successfully!!!!!!!!!!!!!: ");
      throw throwable;
    }

    return request;
  }

  private void populateRequestAttributes(
      Span span,
      AwsSdkRequest awsSdkRequest,
      SdkRequest sdkRequest,
      ExecutionAttributes attributes) {

    fieldMapper.mapToAttributes(sdkRequest, awsSdkRequest, span);

    System.out.println(awsSdkRequest.type());
    //    span.setAttribute(GEN_AI_SYSTEM, GEN_AI_SYSTEM_BEDROCK);
    //    System.out.println(span);

    if (awsSdkRequest.type() == BEDROCKRUNTIME) {
      span.setAttribute(GEN_AI_SYSTEM, GEN_AI_SYSTEM_BEDROCK);
      System.out.println(span);
    }
  }

  @Override
  public void afterExecution(
      Context.AfterExecution context, ExecutionAttributes executionAttributes) {

    io.opentelemetry.context.Context otelContext = getContext(executionAttributes);
    if (otelContext != null) {

      Span span = Span.fromContext(otelContext);
      onSdkResponse(span, context.response(), executionAttributes);

      SdkHttpResponse httpResponse = context.httpResponse();

      RequestSpanFinisher finisher = executionAttributes.getAttribute(REQUEST_FINISHER_ATTRIBUTE);
      finisher.finish(
          otelContext, executionAttributes, new Response(httpResponse, context.response()), null);
    }
    System.out.println("ADOT !! HERE afterExecution- otelContext!!!!!!!!!!!!!: ");
    clearAttributes(executionAttributes);
  }

  private void onSdkResponse(
      Span span, SdkResponse response, ExecutionAttributes executionAttributes) {
    AwsSdkRequest sdkRequest = executionAttributes.getAttribute(AWS_SDK_REQUEST_ATTRIBUTE);
    if (sdkRequest != null) {
      fieldMapper.mapToAttributes(response, sdkRequest, span);
    }
  }

  private static void clearAttributes(ExecutionAttributes executionAttributes) {
    Scope scope = executionAttributes.getAttribute(SCOPE_ATTRIBUTE);
    if (scope != null) {
      scope.close();
    }
  }

  /**
   * Returns the {@link Context} stored in the {@link ExecutionAttributes}, or {@code null} if there
   * is no operation set.
   */
  static io.opentelemetry.context.Context getContext(ExecutionAttributes attributes) {
    return attributes.getAttribute(CONTEXT_ATTRIBUTE);
  }

  private interface RequestSpanFinisher {
    void finish(
        io.opentelemetry.context.Context otelContext,
        ExecutionAttributes executionAttributes,
        Response response,
        Throwable exception);
  }
}

//  @Override
//  public SdkRequest modifyRequest(
//      Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
//    System.out.println(
//        "ADOT !! HERE AwsSdkPatchInstrumentationModule.modifyRequest!!!!!!!!!!!!!: ");
//    Span span = Span.current();
//
//    if (span != null) {
//      AwsSdkRequest awsSdkRequest = AwsSdkRequest.ofSdkRequest(context.request());
//      if (awsSdkRequest != null && awsSdkRequest.type() == BEDROCKRUNTIME) {
//        span.setAttribute(GEN_AI_SYSTEM, GEN_AI_SYSTEM_BEDROCK);
//      }
//    }
//    return context.request();
//  }
