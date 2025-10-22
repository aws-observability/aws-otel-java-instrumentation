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

import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_SPAN_KIND;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import java.util.Map;

public class AwsAttributeGeneratingSpanProcessor implements ExtendedSpanProcessor {

  private final MetricAttributeGenerator generator;
  private final Resource resource;

  public static AwsAttributeGeneratingSpanProcessor create(
      MetricAttributeGenerator generator, Resource resource) {
    return new AwsAttributeGeneratingSpanProcessor(generator, resource);
  }

  private AwsAttributeGeneratingSpanProcessor(
      MetricAttributeGenerator generator, Resource resource) {
    this.generator = generator;
    this.resource = resource;
  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {}

  @Override
  public boolean isStartRequired() {
    return false;
  }

  @Override
  public void onEnding(ReadWriteSpan span) {
    // If the map has no items, no modifications are required. If there is one item, it means the
    // span either produces Service or Dependency metric attributes, and in either case we want to
    // modify the span with them. If there are two items, the span produces both Service and
    // Dependency metric attributes indicating the span is a local dependency root. The Service
    // Attributes must be a subset of the Dependency, with the exception of AWS_SPAN_KIND. The
    // knowledge that the span is a local root is more important than knowing that it is a
    // Dependency metric, so we take all the Dependency metrics but replace AWS_SPAN_KIND with
    // LOCAL_ROOT.
    SpanData spanData = span.toSpanData();
    Map<String, Attributes> attributeMap =
        generator.generateMetricAttributeMapFromSpan(span.toSpanData(), resource);

    boolean generatesServiceMetrics =
        AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(spanData);
    boolean generatesDependencyMetrics =
        AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(spanData);

    if (generatesServiceMetrics && generatesDependencyMetrics) {
      // Order matters: dependency metric attributes include AWS_SPAN_KIND key
      span.setAllAttributes(attributeMap.get(MetricAttributeGenerator.DEPENDENCY_METRIC));
      span.setAttribute(AWS_SPAN_KIND, AwsSpanProcessingUtil.LOCAL_ROOT);
    } else if (generatesServiceMetrics) {
      span.setAllAttributes(attributeMap.get(MetricAttributeGenerator.SERVICE_METRIC));
    } else if (generatesDependencyMetrics) {
      span.setAllAttributes(attributeMap.get(MetricAttributeGenerator.DEPENDENCY_METRIC));
    }
  }

  @Override
  public boolean isOnEndingRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {}

  @Override
  public boolean isEndRequired() {
    return false;
  }
}
