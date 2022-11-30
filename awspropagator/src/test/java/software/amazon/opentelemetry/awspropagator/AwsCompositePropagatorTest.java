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

package software.amazon.opentelemetry.awspropagator;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.contrib.awsxray.propagator.AwsXrayPropagator;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

class AwsCompositePropagatorTest {

  private static final Span SPAN1 =
      Span.wrap(
          SpanContext.createFromRemoteParent(
              "ff000000000000000000000000000041",
              "ff00000000000041",
              TraceFlags.getDefault(),
              TraceState.getDefault()));
  private static final Span SPAN2 =
      Span.wrap(
          SpanContext.createFromRemoteParent(
              "ff000000000000000000000000000042",
              "ff00000000000042",
              TraceFlags.getDefault(),
              TraceState.getDefault()));

  private static final TextMapGetter<Map<String, String>> MAP_GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier.get(key);
        }
      };

  @ParameterizedTest
  @ArgumentsSource(SupportedPropagators.class)
  void extractsAndInjectAws(TextMapPropagator propagator) {
    Map<String, String> map = new HashMap<>();
    propagator.inject(Context.root().with(SPAN1), map, Map::put);
    TextMapPropagator awsPropagator = AwsCompositePropagator.injectingAwsFormat();
    Context context = awsPropagator.extract(Context.root(), map, MAP_GETTER);
    assertThat(Span.fromContext(context).getSpanContext()).isEqualTo(SPAN1.getSpanContext());

    Map<String, String> map2 = new HashMap<>();
    awsPropagator.inject(context, map2, Map::put);

    Map<String, String> map3 = new HashMap<>();
    AwsXrayPropagator.getInstance().inject(context, map3, Map::put);
    assertThat(map2).containsExactlyInAnyOrderEntriesOf(map3);
  }

  @ParameterizedTest
  @ArgumentsSource(SupportedPropagators.class)
  void extractsAndInjectExtracted(TextMapPropagator propagator) {
    Map<String, String> map = new HashMap<>();
    propagator.inject(Context.root().with(SPAN1), map, Map::put);
    TextMapPropagator awsPropagator = AwsCompositePropagator.injectingExtractedFormat();
    Context context = awsPropagator.extract(Context.root(), map, MAP_GETTER);
    assertThat(Span.fromContext(context).getSpanContext()).isEqualTo(SPAN1.getSpanContext());

    Map<String, String> map2 = new HashMap<>();
    awsPropagator.inject(context, map2, Map::put);

    Map<String, String> map3 = new HashMap<>();
    propagator.inject(context, map3, Map::put);
    assertThat(map2).containsExactlyInAnyOrderEntriesOf(map3);
  }

  @ParameterizedTest
  @ArgumentsSource(SupportedPropagators.class)
  void baggageExtractedFormat(TextMapPropagator propagator) {
    Map<String, String> map = new HashMap<>();
    propagator.inject(Context.root().with(SPAN1), map, Map::put);
    Baggage baggage = Baggage.builder().put("cat", "meow").build();
    W3CBaggagePropagator.getInstance().inject(Context.root().with(baggage), map, Map::put);
    TextMapPropagator awsPropagator = AwsCompositePropagator.injectingExtractedFormat();
    Context context = awsPropagator.extract(Context.root(), map, MAP_GETTER);
    assertThat(Span.fromContext(context).getSpanContext()).isEqualTo(SPAN1.getSpanContext());
    assertThat(Baggage.fromContext(context)).isEqualTo(baggage);

    Map<String, String> map2 = new HashMap<>();
    awsPropagator.inject(context, map2, Map::put);

    Map<String, String> map3 = new HashMap<>();
    propagator.inject(context, map3, Map::put);

    if (propagator != AwsXrayPropagator.getInstance()) {
      W3CBaggagePropagator.getInstance().inject(context, map3, Map::put);
    }

    assertThat(map2).containsExactlyInAnyOrderEntriesOf(map3);
  }

  @Test
  void awsPrioritized() {
    Map<String, String> map = new HashMap<>();
    AwsXrayPropagator.getInstance().inject(Context.root().with(SPAN1), map, Map::put);
    W3CTraceContextPropagator.getInstance().inject(Context.root().with(SPAN2), map, Map::put);
    assertThat(
            Span.fromContext(
                    AwsCompositePropagator.injectingExtractedFormat()
                        .extract(Context.root(), map, MAP_GETTER))
                .getSpanContext())
        .isEqualTo(SPAN1.getSpanContext());
  }

  private static class SupportedPropagators implements ArgumentsProvider {
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
      return Stream.of(
              AwsXrayPropagator.getInstance(),
              W3CTraceContextPropagator.getInstance(),
              B3Propagator.injectingMultiHeaders())
          .map(Arguments::of);
    }
  }
}
