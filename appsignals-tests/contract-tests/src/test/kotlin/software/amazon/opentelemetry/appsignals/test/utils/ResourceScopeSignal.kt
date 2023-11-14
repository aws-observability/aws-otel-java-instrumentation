package software.amazon.opentelemetry.appsignals.test.utils

import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.ResourceMetrics
import io.opentelemetry.proto.metrics.v1.ScopeMetrics
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span

/**
 * Data classes used to correlate resources, scope and telemetry signals.
 */

// Correlate resource, scope and span
data class ResourceScopeSpan(val resource: ResourceSpans, val scope: ScopeSpans, val span: Span)

// Correlate resource, scope and metric
data class ResourceScopeMetric(val resource: ResourceMetrics, val scope: ScopeMetrics, val metric: Metric)
