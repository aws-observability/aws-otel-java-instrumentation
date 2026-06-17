# Dynamic Instrumentation (Preview)

Dynamic Instrumentation lets you capture additional runtime telemetry from a running Java
application — without restarting or redeploying it. Instead of adding logging and redeploying to
diagnose a production issue, you define *instrumentation configurations* (probes and breakpoints)
out-of-band; the ADOT Java agent picks them up at runtime, applies them via bytecode
instrumentation, and emits the captured runtime context as *snapshots*.

> **Opt-in.** Dynamic Instrumentation is **disabled by default**. When it is not enabled, the agent
> performs no polling, starts no background threads, and applies no bytecode transformations — there
> is no change to your application's behavior.

## How it works

1. The agent periodically polls an instrumentation-configuration API (proxied locally by the
   CloudWatch Agent) for configurations that target this application.
2. When a configuration is found, the agent instruments the target location at runtime using
   bytecode instrumentation.
3. When the instrumented location is hit, the agent captures a *snapshot* (method arguments, local
   variables, return value, and — for breakpoints — captured exceptions) subject to configurable
   limits.
4. Snapshots are exported as OTLP log records to the configured logs endpoint, where they can be
   forwarded (for example, by the CloudWatch Agent) to a destination for you to inspect.

## Enabling Dynamic Instrumentation

Set the following environment variable (or the equivalent system property) when starting your
application with the ADOT Java agent:

```
OTEL_AWS_DYNAMIC_INSTRUMENTATION_ENABLED=true \
  java -javaagent:path/to/aws-opentelemetry-agent.jar -jar myapp.jar
```

## Configuration

All settings have safe defaults; only `..._ENABLED` is required to turn the feature on. Each
environment variable has an equivalent system property (lowercase, `_` → `.`).

| Environment variable | System property | Default | Description |
| --- | --- | --- | --- |
| `OTEL_AWS_DYNAMIC_INSTRUMENTATION_ENABLED` | `otel.aws.dynamic.instrumentation.enabled` | `false` | Master switch. When `false`, the feature is fully inert. |
| `OTEL_AWS_DYNAMIC_INSTRUMENTATION_API_URL` | `otel.aws.dynamic.instrumentation.api.url` | `http://localhost:2000` | Base URL of the instrumentation-configuration API (typically proxied by the CloudWatch Agent). |
| `OTEL_AWS_DYNAMIC_INSTRUMENTATION_PROBE_POLL_INTERVAL` | `otel.aws.dynamic.instrumentation.probe.poll.interval` | `600` | Probe configuration poll interval, in seconds. |
| `OTEL_AWS_DYNAMIC_INSTRUMENTATION_BREAKPOINT_POLL_INTERVAL` | `otel.aws.dynamic.instrumentation.breakpoint.poll.interval` | `60` | Breakpoint configuration poll interval, in seconds. |
| `OTEL_AWS_OTLP_LOGS_ENDPOINT` | `otel.aws.otlp.logs.endpoint` | `http://localhost:4316/v1/logs` | OTLP logs endpoint that captured snapshots are exported to. A CloudWatch Logs OTLP endpoint (`https://logs.<region>.amazonaws.com/v1/logs`) is automatically signed with SigV4. |

## Limitations

- **AWS Lambda is not supported.** When a Lambda environment is detected
  (`AWS_LAMBDA_FUNCTION_NAME` is set), Dynamic Instrumentation is automatically skipped even if
  enabled.
- Captured values are subject to enforced maximums (for example, maximum captured string length and
  maximum number of collection elements) to bound overhead and snapshot size.

## Safety

Dynamic Instrumentation is designed to be safe to ship enabled-by-configuration in production:

- It is **off unless explicitly enabled**; the disabled path is a no-op.
- Polling, capture, and export run on dedicated background threads and are isolated so that a
  failure in Dynamic Instrumentation does not affect your application or the rest of the agent.
- Captured data is bounded by enforced limits.
