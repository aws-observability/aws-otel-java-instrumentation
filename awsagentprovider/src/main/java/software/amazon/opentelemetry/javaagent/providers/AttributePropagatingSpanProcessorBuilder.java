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

import static java.util.Objects.requireNonNull;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * AttributePropagatingSpanProcessorBuilder is used to construct a {@link
 * AttributePropagatingSpanProcessor}. If {@link #setPropagationDataExtractor}, {@link
 * #setPropagationDataKey} or {@link #setAttributesKeysToPropagate} are not invoked, the builder
 * defaults to using specific propagation targets.
 */
public class AttributePropagatingSpanProcessorBuilder {

  private Function<SpanData, String> propagationDataExtractor =
      AwsSpanProcessingUtil::getIngressOperation;
  private AttributeKey<String> propagationDataKey = AwsAttributeKeys.AWS_LOCAL_OPERATION;
  private List<AttributeKey<String>> attributesKeysToPropagate =
      Arrays.asList(AwsAttributeKeys.AWS_REMOTE_SERVICE, AwsAttributeKeys.AWS_REMOTE_OPERATION);

  public static AttributePropagatingSpanProcessorBuilder create() {
    return new AttributePropagatingSpanProcessorBuilder();
  }

  private AttributePropagatingSpanProcessorBuilder() {}

  @CanIgnoreReturnValue
  public AttributePropagatingSpanProcessorBuilder setPropagationDataExtractor(
      Function<SpanData, String> propagationDataExtractor) {
    requireNonNull(propagationDataExtractor, "propagationDataExtractor");
    this.propagationDataExtractor = propagationDataExtractor;
    return this;
  }

  @CanIgnoreReturnValue
  public AttributePropagatingSpanProcessorBuilder setPropagationDataKey(
      AttributeKey<String> propagationDataKey) {
    requireNonNull(propagationDataKey, "setPropagationDataKey");
    this.propagationDataKey = propagationDataKey;
    return this;
  }

  @CanIgnoreReturnValue
  public AttributePropagatingSpanProcessorBuilder setAttributesKeysToPropagate(
      List<AttributeKey<String>> attributesKeysToPropagate) {
    requireNonNull(attributesKeysToPropagate, "attributesKeysToPropagate");
    this.attributesKeysToPropagate = Collections.unmodifiableList(attributesKeysToPropagate);
    return this;
  }

  public AttributePropagatingSpanProcessor build() {
    return AttributePropagatingSpanProcessor.create(
        propagationDataExtractor, propagationDataKey, attributesKeysToPropagate);
  }
}
