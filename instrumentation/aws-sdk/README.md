## ADOT AWS SDK Instrumentation

### Overview
The aws-sdk instrumentation is an SPI-based implementation to extend the AWS SDK within the upstream OpenTelemetry Instrumentation for Java. 
This instrumentation:

1. Leverages OpenTelemetry's InstrumentationModule extension mechanism
2. Ensures AWS-specific instrumentation runs after core OpenTelemetry instrumentation
3. Maintains all existing functionality while improving ADOT maintainability

Benefits:

- **Better Extensibility:** Users can easily extend or modify AWS-specific behavior
- **Enhanced Compatibility:** Better alignment with OpenTelemetry's extension mechanisms
- **Clearer Code Organization:** More intuitive structure for future contributions

### AWS SDK v2 Instrumentation Summary

**AdotAwsSdkInstrumentationModule**

The AdotAwsSdkInstrumentationModule extends the InstrumentationModule from OpenTelemetry's SPI extensions and registers
the AdotTracingExecutionInterceptor to provide custom instrumentation which runs after the upstream OTel agent runs.

- `registerHelperResources`: Sets up our AdotTracingExecutionInterceptor to work alongside OTel's instrumentation. The registration order is important - upstream AWS SDK interceptor must be before our ADOT interceptor - ensuring our customizations run after OTels's base instrumentation.

Key aspects of interceptor registration:
- AWS SDK's ExecutionInterceptor loads global interceptors from files named '/software/amazon/awssdk/global/handlers/execution.interceptors' in the classpath
- The `order` method (in our AdotAwsSdkInstrumentationModule) is used to ensure the OTel instrumentation runs first, before the ADOT instrumentation. This will also ensure the upstream interceptor is registered before our interceptor in the AWS SDK classpath
- Interceptors are executed in the order they appear in the classpath - earlier entries run first

`helperResourceBuilder.register` within the `registerHelperResources` method uses two important path inputs:
1. `applicationResourcePath`: Points to the standard AWS SDK interceptor location, where the OpenTelemetry aws-sdk registers its interceptor ([reference](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/release/v2.11.x/instrumentation/aws-sdk/aws-sdk-2.2/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/awssdk/v2_2/AwsSdkInstrumentationModule.java#L27))
2. `agentResourcePath`: Points to our interceptor's resource file location in ADOT's code structure

**AdotTracingExecutionInterceptor**

The AdotTracingExecutionInterceptor extends the AWS SDK ExecutionInterceptor. It uses the **beforeTransmission** and **modifyResponse** methods 
to hook onto the OTel spans during specific phases of the SDK request and response life cycle. This allows it to enrich the 
upstream spans with custom **AwsExperimentalAttributes**. These points in the life cycle are crucial as they impact the order in which attributes 
are added to the OTel span.

1. `beforeTransmission`: is the latest point where the sdk request can be obtained after it is modified by the upstream aws-sdk v2.2 interceptor. This ensures the upstream handles the request and applies its changes first.
2. `modifyResponse`: is the latest point to access the sdk response before the span closes in the upstream afterExecution method. This ensures we capture attributes from the final, fully modified response after all upstream interceptors have processed it.

The AdotTracingExecutionInterceptor is the main point which connects the other classes within awssdk_v2_2
through the fieldMapper variable.

_**Important Note:**_
Since the upstream interceptor closes the span in afterExecution, that method and any subsequent points are inaccessible for span modification. 
We use modifyResponse as our final interception point, as it provides access to both the complete SDK response (after upstream applies it's modifications first) and the still-active span.
This ensures our attributes are added before span completion while still capturing the final state of the response after OTel.

_Initialization Workflow_

1. OpenTelemetry Agent starts
   - Loads default instrumentations
   - Loads aws-sdk instrumentation from opentelemetry-java-instrumentation
   - Registers **TracingExecutionInterceptor** (order = 0)
2. Scans for other SPI implementations
   - Finds ADOT’s **AdotAwsSdkInstrumentationModule**
   - Registers **AdotTracingExecutionInterceptor** (order > 0)


