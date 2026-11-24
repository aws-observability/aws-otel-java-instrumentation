# Service Function Duration Metric Spec

## Overview

Function call telemetry is emitted as a single OTel **Exponential Histogram**
recorded directly from `ServiceEventsDataStore.methodExit()` via
`FunctionMetricsBridgeImpl`. The OTel SDK handles aggregation and export — there
is no intermediate SEH pre-aggregation when the bridge is wired.

| Metric | Instrument | Recorded for | Role |
|---|---|---|---|
| `service.function.duration` | Exponential Histogram | Sampled calls | Latency distribution + sampled call counts (success / error) |

The histogram's `count` reflects the **sampled call count**, not the total
invocation count. Total invocations are the responsibility of upstream sampling
configuration (and the existing `samplingCounters` book-keeping); this signal
focuses on latency and per-status distribution.

---

## Definition

| Field | Value |
|---|---|
| **Name** | `service.function.duration` |
| **Instrument** | DoubleHistogram |
| **Aggregation** | `Aggregation.base2ExponentialBucketHistogram()` (registered via View) |
| **Unit** | `Microseconds` |
| **Description** | `Function call duration` |
| **Instrumentation Scope** | `serviceevents` (version `1.0`) |

### Recording Behavior

Recorded **only when the call is sampled**. Non-sampled calls return `null`
context from `methodEnter` and short-circuit out of `methodExit` entirely — no
zero-duration placeholders, no per-call attribute building.

```
duration_us = (System.nanoTime() - start_time_ns) / 1000.0   // sampled calls only
```

### Latency Queries

```
p99_success_latency = histogram_quantile(0.99, service.function.duration{status="success"})
avg_error_latency   = sum(service.function.duration{status="error"}) /
                      count(service.function.duration{status="error"})
```

---

## Attributes

Per-data-point attributes are intentionally minimal — process-constants ride at
the OTel **Resource** level on the dedicated MeterProvider so they're sent once
per OTLP batch instead of duplicated on every data point.

### Resource attributes (sent once per OTLP batch)

| Attribute Key                          | Type   | Source                  | Description                       |
|----------------------------------------|--------|-------------------------|-----------------------------------|
| `service.name`                         | string | OTel autoconfig         | Application service name          |
| `deployment.environment`               | string | OTel autoconfig         | Deployment environment            |
| `aws.service_events.version`           | string | constant `"1"`          | Telemetry format version          |
| `aws.service_events.deployment.id`     | string | env (when set)          | CI/CD deployment identifier       |
| `vcs.ref.head.revision`                | string | env (when set)          | Git commit SHA of deployment      |
| `vcs.repository.url.full`              | string | env (when set)          | Git repository URL                |

### Per-data-point — mandatory

| Attribute Key          | Type   | Description                                           | Example                          |
|------------------------|--------|-------------------------------------------------------|----------------------------------|
| `Telemetry.Source`     | string | Fixed signal origin identifier                        | `"ServiceEvents"`                |
| `function.name`        | string | Composite function identifier                         | `"com.example.MyClass.process"`  |
| `status`               | string | Call outcome: `"success"` or `"error"`                | `"success"`                      |

### Per-data-point — conditional (included when non-empty / non-default)

| Attribute Key                          | Type   | Condition              | Description                        | Example            |
|----------------------------------------|--------|------------------------|------------------------------------|--------------------|
| `aws.service_events.caller`            | string | when non-empty         | Parent function in the call graph  | `"com.example.MyClass.handle"` |

The exception class is intentionally NOT a histogram attribute — tagging by
`exception.type` would unbound success-path cardinality once the same function
starts throwing different exception classes. The failure-type breakdown lives
on `EndpointSummary` (per-endpoint exception counts) instead.

> Java does NOT carry the Python-only `aws.service_events.function_at_line` /
> `aws.service_events.async` attributes. Method-level line numbers and async
> coloring are not available cheaply at advice time in JVM bytecode.

---

## Architecture: Direct Recording vs SEH Pre-Aggregation

```
Function Call (instrumented method)
    │
    ▼
MethodAdvice.onExit() → ServiceEventsDataStore.methodExit()
    │
    ├──► FunctionMetricsBridgeImpl.recordFunctionCall()       (when bridge wired)
    │       └── OTel DoubleHistogram (sampled calls only)     ← service.function.duration
    │               └── base2ExponentialBucketHistogram (View)
    │                       └── PeriodicMetricReader → OTLP endpoint
    │
    └──► MethodAggregationStore.recordMethodInvocation()      (when bridge null)
            └── FunctionCallCollector (periodic flush)
                    └── ServiceEventsOtlpEmitter.emitFunctionCall()
                            └── aws.service_events.function_call LogRecord
```

When the OTel histogram bridge is wired (OTLP network endpoint configured via
`OTEL_AWS_OTLP_LOGS_ENDPOINT` and `OUTPUT_FILE` is unset):
- Direct recording path is active — the Histogram emits per sampled call.
- SEH aggregation and `MethodAggregationStore` recording are skipped.
- `FunctionCallCollector` drains empty aggregations (no-op).

When `OTEL_AWS_SERVICE_EVENTS_OUTPUT_FILE` is set (file mode), or no OTLP
emitter is configured (console fallback):
- SEH aggregation path is active.
- `FunctionCallCollector` periodically exports either `aws.service_events.function_call`
  LogRecords (via the OTLP log exporter, including the file-mode mirror) or
  EMF-formatted JSON to stdout.
- The histogram is intentionally NOT wired in `output_file` mode because the
  CloudWatch metric file exporter only serializes `Sum` metrics; histogram data
  points would be silently dropped.
