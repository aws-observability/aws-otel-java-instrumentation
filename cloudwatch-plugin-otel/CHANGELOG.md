# Changelog

## Unreleased

- Initial release: generate request metrics (`traces.span.metrics.calls`,
  `traces.span.metrics.duration`) from spans inside the OpenTelemetry Java SDK, with an
  always-record sampler so metrics reflect 100% of spans regardless of the trace sampling rate.
