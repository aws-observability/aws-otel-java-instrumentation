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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Logger;
import javax.annotation.concurrent.Immutable;

/**
 * This exporter will update a span with metric attributes before exporting. It depends on a {@link
 * SpanExporter} being provided on instantiation, which the AwsSpanMetricsExporter will delegate
 * export to. Also, a {@link MetricAttributeGenerator} must be provided, which will provide a means
 * to determine attributes which should be applied to the span. Finally, a {@link Resource} must be
 * provided, which is used to generate metric attributes.
 *
 * <p>This exporter should be coupled with the {@link AwsSpanMetricsProcessor} using the same {@link
 * MetricAttributeGenerator}. This will result in metrics and spans being produced with common
 * attributes.
 */
@Immutable
public class AwsMetricAttributesSpanExporter implements SpanExporter {
  private static final Logger logger =
      Logger.getLogger(AwsMetricAttributesSpanExporter.class.getName());

  private final SpanExporter delegate;
  private final MetricAttributeGenerator generator;
  private final Resource resource;

  /** Use {@link AwsMetricAttributesSpanExporterBuilder} to construct this exporter. */
  static AwsMetricAttributesSpanExporter create(
      SpanExporter delegate, MetricAttributeGenerator generator, Resource resource) {
    return new AwsMetricAttributesSpanExporter(delegate, generator, resource);
  }

  private AwsMetricAttributesSpanExporter(
      SpanExporter delegate, MetricAttributeGenerator generator, Resource resource) {
    this.delegate = delegate;
    this.generator = generator;
    this.resource = resource;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    logger.info("Exporting spans with metric attributes!!!!!!!!!");
    logger.info("before spans data!!!!!!!: " + spans);
    List<SpanData> modifiedSpans = addMetricAttributes(spans);
    logger.info("after modifiedSpans data!!!!!!!: " + modifiedSpans);
    return delegate.export(modifiedSpans);
  }

  @Override
  public CompletableResultCode flush() {
    return delegate.flush();
  }

  @Override
  public CompletableResultCode shutdown() {
    return delegate.shutdown();
  }

  @Override
  public void close() {
    delegate.close();
  }

  private List<SpanData> addMetricAttributes(Collection<SpanData> spans) {
    List<SpanData> modifiedSpans = new ArrayList<>();

    for (SpanData span : spans) {
      // If the map has no items, no modifications are required. If there is one item, it means the
      // span either produces Service or Dependency metric attributes, and in either case we want to
      // modify the span with them. If there are two items, the span produces both Service and
      // Dependency metric attributes indicating the span is a local dependency root. The Service
      // Attributes must be a subset of the Dependency, with the exception of AWS_SPAN_KIND. The
      // knowledge that the span is a local root is more important that knowing that it is a
      // Dependency metric, so we take all the Dependency metrics but replace AWS_SPAN_KIND with
      // LOCAL_ROOT.
      Map<String, Attributes> attributeMap =
          generator.generateMetricAttributeMapFromSpan(span, resource);
      Attributes attributes = Attributes.empty();

      boolean generatesServiceMetrics =
          AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(span);
      boolean generatesDependencyMetrics =
          AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(span);

      if (generatesServiceMetrics && generatesDependencyMetrics) {
        attributes =
            copyAttributesWithLocalRoot(
                attributeMap.get(MetricAttributeGenerator.DEPENDENCY_METRIC));
      } else if (generatesServiceMetrics) {
        attributes = attributeMap.get(MetricAttributeGenerator.SERVICE_METRIC);
      } else if (generatesDependencyMetrics) {
        attributes = attributeMap.get(MetricAttributeGenerator.DEPENDENCY_METRIC);
      }

      if (!attributes.isEmpty()) {
        span = wrapSpanWithAttributes(span, attributes);
      }
      modifiedSpans.add(span);
    }

    return modifiedSpans;
  }

  private Attributes copyAttributesWithLocalRoot(Attributes attributes) {
    AttributesBuilder builder = attributes.toBuilder();
    builder.remove(AWS_SPAN_KIND);
    builder.put(AWS_SPAN_KIND, AwsSpanProcessingUtil.LOCAL_ROOT);
    return builder.build();
  }

  /**
   * {@link #export} works with a {@link SpanData}, which does not permit modification. However, we
   * need to add derived metric attributes to the span. To work around this, we will wrap the
   * SpanData with a {@link DelegatingSpanData} that simply passes through all API calls, except for
   * those pertaining to Attributes, i.e. {@link SpanData#getAttributes()} and {@link
   * SpanData#getTotalAttributeCount} APIs.
   *
   * <p>See https://github.com/open-telemetry/opentelemetry-specification/issues/1089 for more
   * context on this approach.
   */
  private static SpanData wrapSpanWithAttributes(SpanData span, Attributes attributes) {
    Attributes originalAttributes = span.getAttributes();
    Attributes replacementAttributes = originalAttributes.toBuilder().putAll(attributes).build();

    int newAttributeKeyCount = 0;
    for (Entry<AttributeKey<?>, Object> entry : attributes.asMap().entrySet()) {
      if (originalAttributes.get(entry.getKey()) == null) {
        newAttributeKeyCount++;
      }
    }
    int originalTotalAttributeCount = span.getTotalAttributeCount();
    int replacementTotalAttributeCount = originalTotalAttributeCount + newAttributeKeyCount;

    return new DelegatingSpanData(span) {
      @Override
      public Attributes getAttributes() {
        return replacementAttributes;
      }

      @Override
      public int getTotalAttributeCount() {
        return replacementTotalAttributeCount;
      }
    };
  }
}
