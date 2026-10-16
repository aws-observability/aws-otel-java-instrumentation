## JMX Metrics
The package provides all the configurations needed to have the [JMX Metric Insight](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/jmx-metrics/javaagent/README.md)
instrumentation support the same metrics as the [JMX Metric Gatherer](https://github.com/open-telemetry/opentelemetry-java-contrib/tree/main/jmx-metrics).

It is required at least until [open-telemetry/opentelemetry-java-instrumentation#9765](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/9765) is addressed.

```
OTEL_EXPERIMENTAL_METRICS_VIEW_CONFIG: classpath:/jmx/view.yaml 
```

### rules/*.yaml
The rules are a translation of the JMX Metric Gatherer's [target systems](https://github.com/open-telemetry/opentelemetry-java-contrib/tree/main/jmx-metrics/src/main/resources/target-systems)
based on the [JMX metric rule YAML schema](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/jmx-metrics/javaagent/README.md#basic-syntax).

The upstream JMX Metric Insight installer only loads its built-in target systems, so the `jvm` and `tomcat` rules are
picked up by it, while the ADOT-only `kafka`, `kafka-consumer`, and `kafka-producer` rules are loaded by
`AwsJmxRulesInstaller` in `awsagentprovider` when listed in `otel.jmx.target.system`.

### SystemCpuLoad
The `SystemCpuLoad` metric is deprecated and Java versions 14+ now use `CpuLoad`. However, to avoid emitting double metrics, we stick to using `SystemCpuLoad` as it still works on newer versions.