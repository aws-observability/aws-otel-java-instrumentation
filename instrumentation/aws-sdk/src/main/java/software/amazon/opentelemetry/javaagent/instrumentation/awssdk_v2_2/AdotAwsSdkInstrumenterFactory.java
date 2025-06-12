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

import static java.util.Collections.emptyList;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.SpanKindExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor;
import java.util.Arrays;
import java.util.List;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;

public class AdotAwsSdkInstrumenterFactory {
  private static final String INSTRUMENTATION_NAME = "adot.aws-sdk-2.2";

  private static final AwsSdkExperimentalAttributesExtractor experimentalAttributesExtractor =
      new AwsSdkExperimentalAttributesExtractor();

  private final OpenTelemetry openTelemetry; // GlobalOpenTelemetry.get()

  public AdotAwsSdkInstrumenterFactory(OpenTelemetry openTelemetry) {
    this.openTelemetry = openTelemetry;
  }

  Instrumenter<ExecutionAttributes, Response> requestInstrumenter() {
    return createInstrumenter(
        openTelemetry,
        AdotAwsSdkInstrumenterFactory::spanName, // spanNameExtractor
        SpanKindExtractor.alwaysClient(), // spanKindExtractor
        Arrays.asList(experimentalAttributesExtractor),
        emptyList(), // additionalAttributeExtractors, None
        true);
  }

  public static <REQUEST, RESPONSE> Instrumenter<REQUEST, RESPONSE> createInstrumenter(
      OpenTelemetry openTelemetry,
      SpanNameExtractor<REQUEST> spanNameExtractor,
      SpanKindExtractor<REQUEST> spanKindExtractor,
      List<? extends AttributesExtractor<? super REQUEST, ? super RESPONSE>> attributeExtractors,
      List<AttributesExtractor<REQUEST, RESPONSE>> additionalAttributeExtractors,
      boolean enabled) {

    return Instrumenter.<REQUEST, RESPONSE>builder(
            openTelemetry, INSTRUMENTATION_NAME, spanNameExtractor)
        .addAttributesExtractors(attributeExtractors)
        .addAttributesExtractors(additionalAttributeExtractors)
        .setEnabled(enabled)
        .buildInstrumenter(spanKindExtractor);
  }

  private static String spanName(ExecutionAttributes attributes) {
    String awsServiceName = attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME);
    String awsOperation = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
    return awsServiceName + "." + awsOperation;
  }

  private interface RequestSpanFinisher {
    void finish(
        io.opentelemetry.context.Context otelContext,
        ExecutionAttributes executionAttributes,
        Response response,
        Throwable exception);
  }
}
