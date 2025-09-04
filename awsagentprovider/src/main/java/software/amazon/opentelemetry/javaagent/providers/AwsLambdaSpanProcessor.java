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

package software.amazon.opentelemetry.javaagent.providers;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import javax.annotation.concurrent.Immutable;

@Immutable
public final class AwsLambdaSpanProcessor implements SpanProcessor {
  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    if (AwsSpanProcessingUtil.isServletServerSpan(span)) {
      Span parentSpan = Span.fromContextOrNull(parentContext);
      if (parentSpan == null || !(parentSpan instanceof ReadWriteSpan)) {
        return;
      }

      ReadWriteSpan parentReadWriteSpan = (ReadWriteSpan) parentSpan;
      if (!AwsSpanProcessingUtil.isLambdaServerSpan(parentReadWriteSpan)) {
        return;
      }
      parentReadWriteSpan.setAttribute(AwsAttributeKeys.AWS_TRACE_LAMBDA_MULTIPLE_SERVER, true);
    }
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {}

  @Override
  public boolean isEndRequired() {
    return false;
  }
}
