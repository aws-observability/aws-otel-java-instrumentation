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
import java.util.logging.Logger;
import javax.annotation.concurrent.Immutable;

@Immutable
public final class AwsLambdaSpanProcessor implements SpanProcessor {
  private static final Logger logger =
      Logger.getLogger(AwsApplicationSignalsCustomizerProvider.class.getName());
  private ReadWriteSpan lambdaServerSpan = null;

  public AwsLambdaSpanProcessor() {}

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    if (AwsSpanProcessingUtil.isLambdaServerSpan(span)) {
      if (lambdaServerSpan != null) {
        logger.warning("More than one Lambda server span detected.");
      }
      lambdaServerSpan = span;
      logger.info("now lambda server span recorded!!!.");
    }

    if (AwsSpanProcessingUtil.isServletServerSpan(span)) {
      if (lambdaServerSpan == null) {
        logger.warning("lambda server span is null when servlet span is detected.");
        return;
      }

      assert (lambdaServerSpan != null);
      Span parentSpan = Span.fromContextOrNull(parentContext);
      if (parentSpan != null && (parentSpan instanceof ReadableSpan)) {
        ReadableSpan parentReadableSpan = (ReadableSpan) parentSpan;
        if (parentReadableSpan.getSpanContext().getSpanId()
            == lambdaServerSpan.getSpanContext().getSpanId()) {
          lambdaServerSpan.setAttribute(AwsAttributeKeys.AWS_TRACE_LAMBDA_MULTIPLE_SERVER, true);
        }
      }
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
