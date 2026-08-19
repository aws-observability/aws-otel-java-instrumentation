# Changelog

## Unreleased

### v0.1.0 / 2026-08-14

* Initial release of the CloudWatch Plugin for OpenTelemetry (Java).
* Generates span-derived request metrics `traces.span.metrics.calls` (counter) and
  `traces.span.metrics.duration` (histogram, seconds) inside the OpenTelemetry Java SDK, from 100%
  of spans, before trace sampling.
* Record-forcing sampler (`DROP` -> `RECORD_ONLY`) so metrics reflect every span while trace export
  still honors the configured sampling rate.
* Low-cardinality metric dimensions: base attributes (`service.name`, `span.name`, `span.kind`,
  `status.code`) plus allowlisted HTTP / RPC / database / messaging semantic-convention attributes
  present on the span, with pass-through of recognized legacy database keys.
* Four wiring modes with zero plugin configuration: Java agent extension, Spring Boot starter,
  plain SDK autoconfigure, and manual SDK (`SpanMetrics.bind`).
* Metrics emitted under instrumentation scope `cloudwatch.plugin.otel.span_metrics`.
* Compatible with OpenTelemetry Java SDK 1.32.0 or later.
