# Getting Started with OpenTelemetry and the Java Agent

## Requirements

[Java 8 (or later)](https://adoptopenjdk.net/) is required to run an application using OpenTelemetry.

## Getting the agent

Download the [latest version](https://github.com/anuraaga/aws-opentelemetry-java-instrumentation/releases/latest/download/aws-opentelemetry-agent.jar).
If you'd prefer to pin to a specific version, check out our [releases](https://github.com/anuraaga/aws-opentelemetry-java-instrumentation/releases).

This package includes the instrumentation agent, instrumentations for all supported libraries and 
all available data exporters, providing a complete out of the box experience for tracing on AWS.
The agent is preconfigured to generate trace IDs compatible with [AWS X-Ray](https://aws.amazon.com/xray/),
which will also work with any other tracing system, and enables trace propagation using 
W3C Trace Context, B3, and X-Ray.

## Running the agent from the command line

To run your app with the agent, specify the `-javaagent` flag when starting up your application,
pointing to the downloaded agent JAR. In addition, while not required by the agent itself, almost all
tracing systems require a service name defined to identify your application, which you can specify
with the `OTEL_RESOURCE_ATTRIBUTES` environment variable and `service.name` attribute key.

```
OTEL_RESOURCE_ATTRIBUTES=service.name=MyApp java -javaagent:path/to/aws-opentelemetry-agent.jar -jar myapp.jar
```

Don't forget that like normal system properties, the `-javaagent` flag must come before `-jar` or 
your main class name.

This will start up your app with the agent enabled, and instrumentation will be enabled 
automatically. For many cases, this is all you need to use tracing.

## Running the agent in Docker

If your app is packaged in Docker, the easiest way to run with the agent is often to use the
`JAVA_TOOL_OPTIONS` environment variable, which automatically sets flags for `java`. Adding this
snippet to your `Dockerfile` will often be enough to enable tracing, though if you already set
`JAVA_TOOL_OPTIONS`, don't forget to make sure to add to your existing setting rather than replacing
it.

```
COPY https://github.com/anuraaga/aws-opentelemetry-java-instrumentation/releases/latest/download/aws-opentelemetry-agent.jar /opt/aws-opentelemetry-agent.jar
ENV JAVA_TOOL_OPTIONS -javaagent:/opt/aws-opentelemetry-agent.jar
ENV OTEL_RESOURCE_ATTRIBUTES service.name=MyApp
```

## Configuring the agent

By default OpenTelemetry Java agent uses the
[OTLP exporter](https://github.com/open-telemetry/opentelemetry-java/tree/master/exporters/otlp)
configured to send data to a
[OpenTelemetry collector](https://github.com/open-telemetry/opentelemetry-collector/blob/master/receiver/otlpreceiver/README.md)
at `localhost:55680`.

The agent can be configured using standard OpenTelemetry options for configuration which you can find [here](https://github.com/open-telemetry/opentelemetry-java-instrumentation#configuration-parameters-subject-to-change).
For example, to set the random sampling rate for creating traces, you can set either the
`-Dotel.config.sampler.probability` Java system property or `OTEL_CONFIG_SAMPLER_PROBABILITY` environment
variable to a value between 0 and 1 with the sampling rate.

## Instrumenting within your app

While the Java agent provides automatic instrumentation for popular frameworks, you may find the need
to perform instrumentation in your application, for example, to provide custom data or to instrument 
code within the application itself.

### Adding custom attributes

You can add custom attributes to a `Span` by defining an `AttributeKey` and calling `setAttribute` 
on the current `Span`.

```java
@Controller
public class AppController {

  private static final AttributeKey<String> ORGANIZATION_ID = AttributeKeys.stringKey("organization.id");

  @GetMapping("/")
  @ResponseBody
  public String handler() {
     String organizationId = findOrganizationIdForCurrentUser();
     TracingContextUtils.getCurrentSpan().setAttribute(ORGANIZATION_ID, organizationId);
  }
}
``` 

In this example, the Java agent will have already created a span corresponding to the Spring
handler method. You use `TracingContextUtils.getCurrentSpan()` to access that span and set the
attribute for a key you have defined as a constant using `AttributeKeys`.

### Creating spans

The easiest way to add a span corresponding to a method in your application is to use the `@WithSpan`
annotation.

```java
import io.opentelemetry.extensions.auto.annotations.WithSpan;

public class MyClass {
  @WithSpan
  public void MyLogic() {
      // Span created encapsulating the logic
  }
}
```

This automatically creates a span corresponding to the method with the same name as the method. You
can use `TracingContextUtils.getCurrentSpan()` inside the method to customize it, for example by
adding attributes.

You can also use the `Tracer` API if you need more functionality or want to trace only a block,
rather than a method, of code, as described [here](https://github.com/open-telemetry/opentelemetry-java/blob/master/QUICKSTART.md#tracing).

### Using the AWS SDK

The Java agent includes instrumentation for the AWS SDK which is enabled by default. You can enjoy
detailed tracing of the AWS SDK with no additional steps.
