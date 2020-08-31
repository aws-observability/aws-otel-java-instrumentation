# AWS OpenTelemetry Instrumentation for Java

## Introduction

This project is a redistribution of the [OpenTelemetry Agent for Java](https://github.com/open-telemetry/opentelemetry-java-instrumentation),
preconfigured for use with AWS services. Please check out that project too to get a better
understanding of the underlying internals. You won't see much code in this repository since we only
apply some small configuration changes, and our OpenTelemetry friends takes care of the rest.

We provided a Java agent JAR that can be attached to any Java 7+ application and dynamically injects 
bytecode to capture telemetry from a number of popular libraries and frameworks. The telemetry data 
can be exported in a variety of formats. In addition, the agent and exporter can be configured via 
command line arguments or environment variables. The net result is the ability to gather telemetry
data from a Java application without any code changes.

## Getting Started

Download the [latest version](https://github.com/anuraaga/aws-opentelemetry-java-instrumentation/releases/download/0.7.0-alpha.4/aws-opentelemetry-agent-0.7.0-alpha.4.jar).

This package includes the instrumentation agent,
instrumentations for all supported libraries and all available data exporters.
This provides completely automatic out of the box experience.
The agent is preconfigured to generate trace IDs compatible with [AWS X-Ray](https://aws.amazon.com/xray/)
and enables trace propagation using W3C Trace Context, B3, Jaeger, X-Ray, and OpenTracing.

The instrumentation agent is enabled using the `-javaagent` flag to the JVM.
```
java -javaagent:path/to/aws-opentelemetry-agent.jar \
     -jar myapp.jar
```
By default OpenTelemetry Java agent uses
[OTLP exporter](https://github.com/open-telemetry/opentelemetry-java/tree/master/exporters/otlp)
configured to send data to
[OpenTelemetry collector](https://github.com/open-telemetry/opentelemetry-collector/blob/master/receiver/otlpreceiver/README.md)
at `localhost:55680`.

Configuration parameters are passed as Java system properties (`-D` flags) or
as environment variables (see below for full list). For example:
```
java -javaagent:path/to/opentelemetry-javaagent-all.jar \
     -Dotel.exporter=zipkin \
     -jar myapp.jar
```

External exporter jar can be specified via `otel.exporter.jar` system property:
```
java -javaagent:path/to/opentelemetry-javaagent-all.jar \
     -Dotel.exporter.jar=path/to/external-exporter.jar \
     -jar myapp.jar
```

### Configuration

For the complete list of configuration parameters, please refer to the upstream documentation
[here](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/master/README.md#configuration-parameters-subject-to-change)

### Supported Java libraries and frameworks

For the complete list of supported frameworks, please refer to the upstream documentation
[here](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/master/README.md#supported-java-libraries-and-frameworks)

## How it works

The [OpenTelemetry Java SDK](https://github.com/open-telemetry/opentelemetry-java) provides knobs
for configuring aspects using Java [SPI](https://docs.oracle.com/javase/tutorial/sound/SPI-intro.html).
This configuration includes being able to reconfigure the [IdsGenerator](https://github.com/open-telemetry/opentelemetry-java/blob/master/sdk/tracing/src/main/java/io/opentelemetry/sdk/trace/IdsGenerator.java)
which we need to support X-Ray compatible trace IDs. Because the SDK uses SPI, it is sufficient for
the custom implementation to be on the classpath to be recognized. The AWS distribution of the
OpenTelemetry Java Agent repackages the upstream agent by simply adding our SPI implementation for
reconfiguring the ID generator. In addition, it includes [AWS resource providers](https://github.com/open-telemetry/opentelemetry-java/tree/master/sdk_extensions/aws_v1_support) 
by default, and it sets a system property to configure the agent to use multiple trace ID propagators, 
defaulting to maximum interoperability.

Other than that, the distribution is identical to the upstream agent and all configuration can be
used as is.
