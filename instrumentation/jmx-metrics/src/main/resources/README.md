## JMX Metrics
The package provides all the configurations needed to have the [JMX Metric Insight](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/jmx-metrics/javaagent/README.md)
instrumentation support the same metrics as the [JMX Metric Gatherer](https://github.com/open-telemetry/opentelemetry-java-contrib/tree/main/jmx-metrics).

It is required at least until [open-telemetry/opentelemetry-java-instrumentation#9765](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/9765) is addressed.

```
OTEL_EXPERIMENTAL_METRICS_VIEW_CONFIG: classpath:/jmx/view.yaml 
```

### aws/rules/*.yaml
The rules are a translation of the JMX Metric Gatherer's [target systems](https://github.com/open-telemetry/opentelemetry-java-contrib/tree/main/jmx-metrics/src/main/resources/target-systems)
based on the [JMX metric rule YAML schema](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/jmx-metrics/javaagent/README.md#basic-syntax).

The `jvm`, `tomcat`, `kafka`, `kafka-consumer`, and `kafka-producer` entries of `otel.jmx.target.system` are loaded
from these rules by `AwsJmxRulesInstaller` in `awsagentprovider`, instead of by the upstream JMX Metric Insight
installer, which handles the other target systems. The rules are kept out of `jmx/rules/`, where the upstream installer
looks for its built-in rules.

### SystemCpuLoad
The `SystemCpuLoad` metric is deprecated and Java versions 14+ now use `CpuLoad`. However, to avoid emitting double metrics, we stick to using `SystemCpuLoad` as it still works on newer versions.