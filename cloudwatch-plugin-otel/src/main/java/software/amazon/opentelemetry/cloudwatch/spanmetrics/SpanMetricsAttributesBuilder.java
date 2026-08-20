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

package software.amazon.opentelemetry.cloudwatch.spanmetrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the metric attribute set for a span: three base dimensions (span.name, span.kind,
 * status.code), any allowlisted semantic-convention attribute present on the span, and the
 * identity/schema attributes. The allowlist is the low-cardinality subset OTel semconv defines on
 * the corresponding request metrics. It is flat: copy any listed key that is present, regardless of
 * span family.
 */
final class SpanMetricsAttributesBuilder {

  static final AttributeKey<String> SPAN_NAME = AttributeKey.stringKey("span.name");
  static final AttributeKey<String> SPAN_KIND = AttributeKey.stringKey("span.kind");
  static final AttributeKey<String> STATUS_CODE = AttributeKey.stringKey("status.code");

  // Copied when present; a span only carries the keys of its own family, so no family branching.
  // These are the current semconv keys; legacy predecessors are handled by LEGACY_FALLBACKS below.
  private static final List<AttributeKey<?>> ALLOWLIST =
      Arrays.asList(
          AttributeKey.stringKey("http.request.method"),
          AttributeKey.longKey("http.response.status_code"),
          AttributeKey.stringKey("http.route"),
          AttributeKey.stringKey("error.type"),
          AttributeKey.stringKey("rpc.system.name"),
          AttributeKey.stringKey("rpc.service"),
          AttributeKey.stringKey("rpc.method"),
          AttributeKey.stringKey("db.system.name"),
          AttributeKey.stringKey("db.operation.name"),
          AttributeKey.stringKey("db.collection.name"),
          AttributeKey.stringKey("messaging.system"),
          AttributeKey.stringKey("messaging.operation.name"));

  // Current semconv key -> legacy key, checked when the current key is absent (spec §4). Needed
  // because some instrumentation has not migrated (e.g. OTel Java still emits the legacy HTTP/DB
  // keys). When the current key is absent, the legacy key/value is passed through unchanged.
  private static final List<LegacyFallback<?>> LEGACY_FALLBACKS =
      Arrays.asList(
          new LegacyFallback<>(
              AttributeKey.stringKey("http.request.method"), AttributeKey.stringKey("http.method")),
          new LegacyFallback<>(
              AttributeKey.longKey("http.response.status_code"),
              AttributeKey.longKey("http.status_code")),
          new LegacyFallback<>(
              AttributeKey.stringKey("rpc.system.name"), AttributeKey.stringKey("rpc.system")),
          new LegacyFallback<>(
              AttributeKey.stringKey("db.system.name"), AttributeKey.stringKey("db.system")),
          new LegacyFallback<>(
              AttributeKey.stringKey("db.operation.name"), AttributeKey.stringKey("db.operation")),
          new LegacyFallback<>(
              AttributeKey.stringKey("db.collection.name"),
              AttributeKey.stringKey("db.sql.table")));

  private static final AttributeKey<String> MESSAGING_DESTINATION_NAME =
      AttributeKey.stringKey("messaging.destination.name");
  private static final AttributeKey<Boolean> MESSAGING_DESTINATION_TEMPORARY =
      AttributeKey.booleanKey("messaging.destination.temporary");
  private static final AttributeKey<Boolean> MESSAGING_DESTINATION_ANONYMOUS =
      AttributeKey.booleanKey("messaging.destination.anonymous");

  static Attributes build(SpanData span) {
    AttributesBuilder builder =
        Attributes.builder()
            .put(SPAN_NAME, span.getName())
            .put(SPAN_KIND, span.getKind().name())
            .put(STATUS_CODE, span.getStatus().getStatusCode().name())
            // Schema + library-version markers appear on both spans and metrics (spec §6).
            .put(SpanMetricsProcessor.SCHEMA_ATTR, SpanMetricsProcessor.SCHEMA_VERSION)
            .put(SpanMetricsProcessor.LIB_VERSION_ATTR, SpanMetricsProcessor.LIB_VERSION);

    // service.name is deliberately NOT a datapoint attribute: the metrics are recorded into the
    // host SDK's MeterProvider, whose resource already carries service.name, so duplicating it on
    // every datapoint would add a redundant dimension. Consumers read it from the metric resource.
    // (Intentional divergence from the collector spanmetrics connector, which flattens it into
    // datapoint attributes because collector-side consumers may drop the resource.)

    Attributes spanAttributes = span.getAttributes();
    for (AttributeKey<?> key : ALLOWLIST) {
      copyIfPresent(builder, spanAttributes, key);
    }
    applyLegacyFallbacks(builder, spanAttributes);
    copyDestinationIfNamed(builder, spanAttributes);
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private static <T> void copyIfPresent(
      AttributesBuilder builder, Attributes source, AttributeKey<T> key) {
    T value = source.get(key);
    if (value != null) {
      builder.put((AttributeKey<T>) key, value);
    }
  }

  // When the current key is absent, pass the legacy key and value through unchanged (no value
  // translation) so we never emit a value the instrumentation did not produce.
  private static void applyLegacyFallbacks(AttributesBuilder builder, Attributes source) {
    for (LegacyFallback<?> fallback : LEGACY_FALLBACKS) {
      fallback.apply(builder, source);
    }
  }

  // Messaging destinations that are temporary or anonymous have unbounded names; omit them.
  private static void copyDestinationIfNamed(AttributesBuilder builder, Attributes source) {
    String destination = source.get(MESSAGING_DESTINATION_NAME);
    if (destination == null) {
      return;
    }
    if (Boolean.TRUE.equals(source.get(MESSAGING_DESTINATION_TEMPORARY))
        || Boolean.TRUE.equals(source.get(MESSAGING_DESTINATION_ANONYMOUS))) {
      return;
    }
    builder.put(MESSAGING_DESTINATION_NAME, destination);
  }

  // Current and legacy keys share a type (both string, or both long) so the legacy value is emitted
  // under the legacy key unchanged when the current key is absent.
  private static final class LegacyFallback<T> {
    private final AttributeKey<T> currentKey;
    private final AttributeKey<T> legacyKey;

    LegacyFallback(AttributeKey<T> currentKey, AttributeKey<T> legacyKey) {
      this.currentKey = currentKey;
      this.legacyKey = legacyKey;
    }

    void apply(AttributesBuilder builder, Attributes source) {
      if (source.get(currentKey) == null) {
        T legacyValue = source.get(legacyKey);
        if (legacyValue != null) {
          builder.put(legacyKey, legacyValue);
        }
      }
    }
  }

  private SpanMetricsAttributesBuilder() {}
}
