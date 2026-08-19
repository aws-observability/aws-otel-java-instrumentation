# CloudWatch Plugin for OpenTelemetry (Java)

A plugin for the OpenTelemetry Java SDK that generates request ("RED") metrics from spans
**in-process, from 100% of spans, before trace sampling is applied**.

Span metrics are normally generated downstream (e.g. by the OpenTelemetry Collector's
`spanmetrics` connector) from whatever spans arrive there — which is only the *sampled* subset.
At low sampling rates that undercounts. This plugin records the metrics inside the SDK, alongside
a record-forcing sampler, so the metrics reflect every span while trace **export** still honors the
configured sampling rate.

Span metrics are the first feature of this plugin; more CloudWatch-oriented OpenTelemetry features
may be added over time.

## What it produces

Two metrics, matching the OpenTelemetry SpanMetrics naming:

| Metric | Instrument | Unit |
| --- | --- | --- |
| `traces.span.metrics.calls` | Counter (monotonic sum) | unset |
| `traces.span.metrics.duration` | Histogram | `s` (seconds) |

Each datapoint carries low-cardinality dimensions: `service.name`, `span.name`, `span.kind`,
`status.code`, plus any allowlisted semantic-convention attributes present on the span
(e.g. `http.request.method`, `http.route`, `http.response.status_code`, `rpc.system.name`/`rpc.service`/`rpc.method`,
`db.system.name`/`db.operation.name`/`db.collection.name`, `messaging.system`/`messaging.operation.name`/`messaging.destination.name`).
Current semantic-convention keys are used, with recognized legacy keys passed through under their own
key/value when the current key is absent.

Both metrics also carry two identity attributes:

- `aws.otel.span.metrics.schema` — the metric schema version (e.g. `v1`).
- `aws.otel.extension.lib.version` — the plugin's library version (for support/debugging only).

The metrics are emitted under the instrumentation scope `cloudwatch.plugin.otel.span_metrics` and ride your
application's existing `MeterProvider` — they flow wherever your other metrics already go.

The plugin also **writes these two attributes onto every recorded span** (not just the metrics). The
`aws.otel.span.metrics.schema` attribute marks the span so downstream span-metric generation can tell
the metric was already produced in-process and skip regenerating it. This is a visible modification to
your spans.

## Requirements & compatibility

- **OpenTelemetry Java SDK 1.32.0 or later.** CI runs the unit tests against the minimum supported
  version (1.32.0) and the latest release; the build defaults to 1.44.x.
- **Java 8+** for the core (javaagent, autoconfigure, and manual modes).
- **Java 17+ / Spring Boot 3** for the Spring Boot starter mode.
- The plugin bundles **no** OpenTelemetry — it links against the OpenTelemetry already on your
  application's classpath, so the plugin itself adds no version constraints. (In Spring Boot apps,
  note that Spring Boot's managed dependencies can pin an OpenTelemetry version that conflicts with
  a newer OTel starter; align the OTel version in your build if the starter fails to load.)

## Install

```kotlin
implementation("software.amazon.opentelemetry:cloudwatch-plugin-otel:0.1.0")
```

```xml
<dependency>
  <groupId>software.amazon.opentelemetry</groupId>
  <artifactId>cloudwatch-plugin-otel</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Usage

There is **zero configuration** — no options, no environment variables owned by the plugin. It wires
into the SDK four ways depending on how your application constructs OpenTelemetry.

### 1. Java agent (zero code changes)

Add the jar to the agent's extensions list:

```
OTEL_JAVAAGENT_EXTENSIONS=/path/to/cloudwatch-plugin-otel-0.1.0.jar
```

### 2. Spring Boot starter

Put the jar on the classpath of a Spring Boot 3 app that uses the OpenTelemetry Spring Boot starter.
It is auto-configured — no code changes.

### 3. Plain SDK autoconfigure

Put the jar on the classpath of an app using `opentelemetry-sdk-extension-autoconfigure`. The
plugin's `AutoConfigurationCustomizerProvider` is discovered automatically — no code changes.

### 4. Manual SDK

When you build the SDK yourself, hand the built `OpenTelemetry` instance to the plugin so the span
processor can obtain a `Meter`:

```java
OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
    .setTracerProvider(
        SdkTracerProvider.builder()
            .setSampler(AlwaysRecordSampler.create(yourSampler))     // record-forcing wrapper
            .addSpanProcessor(new SpanMetricsProcessor())
            .build())
    .setMeterProvider(yourMeterProvider)
    .build();

SpanMetrics.bind(sdk);   // required in manual mode only
```

Modes 1–3 bind the `MeterProvider` for you; only manual mode calls `SpanMetrics.bind(...)`.

## How sampling is handled

The plugin wraps your configured sampler and turns `DROP` decisions into `RECORD_ONLY`. Dropped
spans are still recorded (so the metrics see them) but are **not exported**, so trace volume and
cost are unchanged. Your `OTEL_TRACES_SAMPLER` / `OTEL_TRACES_SAMPLER_ARG` settings are untouched.

## Notes

- **Metric destination and export interval** follow your host SDK's `MeterProvider` — configure them
  the usual way (`OTEL_METRICS_EXPORTER`, `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`,
  `OTEL_METRIC_EXPORT_INTERVAL`). If no metrics exporter is configured, the metrics have nowhere to go.
- **Database attributes:** some instrumentation still emits legacy database semantic conventions
  (`db.system` rather than `db.system.name`). The plugin passes recognized legacy keys through
  unchanged rather than inventing values, so database metric dimensions depend on what the
  instrumentation actually emits.

## Limitations

- **One OpenTelemetry instance per classloader.** The plugin binds to the first OpenTelemetry
  instance it sees and records all span metrics through that instance's `MeterProvider`. If an
  application builds more than one SDK in the same classloader, span metrics use the first one.
- **Requires a configured metrics pipeline.** If the host has no metrics pipeline (e.g.
  `OTEL_METRICS_EXPORTER=none` or a `MeterProvider` with no reader), the plugin stays inert: it
  records nothing and does not mark spans, so downstream span-metric generation still produces the
  metrics from the sampled spans. Configure a metrics exporter to get in-process span metrics.
- **Declarative (file-based) SDK configuration is not supported.** When the SDK is built from a
  declarative configuration file (`OTEL_EXPERIMENTAL_CONFIG_FILE` / `otel.experimental.config.file`),
  the autoconfigure customizer and post-build hooks the plugin relies on do not run, so the plugin
  is not wired in. Use one of the four documented wiring modes without declarative file config.

## License

Apache License 2.0.
