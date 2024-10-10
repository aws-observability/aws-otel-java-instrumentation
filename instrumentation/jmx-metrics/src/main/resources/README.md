## JMX Metrics
The package provides all the configurations needed to have the [JMX Metric Insight](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/jmx-metrics/javaagent/README.md)
instrumentation support the same metrics as the [JMX Metric Gatherer](https://github.com/open-telemetry/opentelemetry-java-contrib/tree/main/jmx-metrics).

It is required at least until [open-telemetry/opentelemetry-java-instrumentation#9765](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/9765) is addressed.

### view.yaml
A [Metric View](https://opentelemetry.io/docs/specs/otel/metrics/sdk/#view) is functionality the OpenTelemetry SDK
supports that allows users to customize the metrics outputted by the SDK. The SDK also supports [configuring views
via YAML](https://github.com/open-telemetry/opentelemetry-java/tree/main/sdk-extensions/incubator#view-file-configuration),
which can be specified via property or environment variable. In this case, the view is configured to only retain metrics
from the JMX Metric Insight instrumentation.

```
OTEL_EXPERIMENTAL_METRICS_VIEW_CONFIG: classpath:/jmx/view.yaml 
```

### rules/*.yaml
The rules are a translation of the JMX Metric Gatherer's [target systems](https://github.com/open-telemetry/opentelemetry-java-contrib/tree/main/jmx-metrics/src/main/resources/target-systems)
based on the [JMX metric rule YAML schema](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/jmx-metrics/javaagent/README.md#basic-syntax).