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

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.internal.ContextPropagationDebug;
import java.util.HashMap;
import java.util.Map;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public class AwsLambdaFunctionInstrumenter {

  private final OpenTelemetry openTelemetry;
  final Instrumenter<AwsLambdaRequest, Object> instrumenter;

  public AwsLambdaFunctionInstrumenter(
      OpenTelemetry openTelemetry, Instrumenter<AwsLambdaRequest, Object> instrumenter) {
    this.openTelemetry = openTelemetry;
    this.instrumenter = instrumenter;
  }

  public boolean shouldStart(Context parentContext, AwsLambdaRequest input) {
    return instrumenter.shouldStart(parentContext, input);
  }

  public Context extract(AwsLambdaRequest input) {
    // Look in both the http headers and the custom client context
    Map<String, String> headers = input.getHeaders();
    if (input.getAwsContext() != null && input.getAwsContext().getClientContext() != null) {
      Map<String, String> customContext = input.getAwsContext().getClientContext().getCustom();
      if (customContext != null) {
        headers = new HashMap<>(headers);
        headers.putAll(customContext);
      }
    }

    return ParentContextExtractor.extract(headers, this);
  }

  public Context extract(Map<String, String> headers, TextMapGetter<Map<String, String>> getter) {
    ContextPropagationDebug.debugContextLeakIfEnabled();
    return openTelemetry
        .getPropagators()
        .getTextMapPropagator()
        .extract(Context.root(), headers, getter);
  }
}
