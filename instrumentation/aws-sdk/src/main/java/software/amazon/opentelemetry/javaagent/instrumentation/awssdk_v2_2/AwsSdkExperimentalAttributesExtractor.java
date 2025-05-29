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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import javax.annotation.Nullable;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;

class AwsSdkExperimentalAttributesExtractor
    implements AttributesExtractor<ExecutionAttributes, Response> {

  private static final String COMPONENT_NAME = "java-aws-sdk";
  private static final AttributeKey<String> AWS_AGENT = AttributeKey.stringKey("aws.agent");

  @Override
  public void onStart(
      AttributesBuilder attributes,
      Context parentContext,
      ExecutionAttributes executionAttributes) {
    attributes.put(AWS_AGENT, COMPONENT_NAME);
  }

  @Override
  public void onEnd(
      AttributesBuilder attributes,
      Context context,
      ExecutionAttributes executionAttributes,
      @Nullable Response response,
      @Nullable Throwable error) {}
}
