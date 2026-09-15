# Changelog

## Unreleased

* Added derived metric dimensions for dependency-edge (topology) metrics: additional messaging
  keys (`messaging.operation.type`, `messaging.consumer.group.name`), peer (`server.address`,
  `server.port`), GenAI (`gen_ai.request.model`, `gen_ai.provider.name`, `gen_ai.operation.name`),
  AWS resource identity (`aws.s3.bucket`, `aws.dynamodb.table_names`, `aws.lambda.invoked_arn`,
  `aws.sns.topic.arn`, `aws.sqs.queue.url`), and FaaS (`faas.invoked_name`, `faas.invoked_provider`,
  `faas.invoked_region`, `faas.trigger`) semantic-convention attributes, copied from the span when
  present.
* Fix `aws.otel.extension.lib.version` emitted as `"unknown"` in javaagent mode: the version is
  now baked in at build time instead of resolved from the jar manifest at runtime.
* `service.name` is no longer emitted as a metric datapoint attribute; it is carried by the
  metric's resource (the host SDK's resource). Consumers reading `service.name` from datapoint
  dimensions must read it from the resource instead.
* The `traces.span.metrics.calls` counter now uses the `{call}` unit (UCUM annotation) instead of
  an unset unit.

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
