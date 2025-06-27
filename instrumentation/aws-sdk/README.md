## ADOT AWS SDK Instrumentation

### Overview
The aws-sdk instrumentation is an SPI-based implementation that extends the AWS SDK within the upstream OpenTelemetry Instrumentation for Java. 
This instrumentation:

1. Leverages OpenTelemetry's InstrumentationModule extension mechanism
2. Ensures AWS-specific instrumentation runs after core OpenTelemetry instrumentation
3. Maintains all existing functionality while improving ADOT maintainability


### AWS SDK v1 Instrumentation Summary
The AdotAwsSdkInstrumentationModule extends the InstrumentationModule from OpenTelemetry's SPI extensions and uses the instrumentation (specified in AdotAwsClientInstrumentation) 
to register the AdotTracingRequestHandler. Unlike the direct registration of the TracingExecutionInterceptor in aws-sdk v2, the @Adive mechanism is used to load the AdotTracingRequestHandler when an AWS client is constructed.

- **AdotAwsClientAdvice** (within AdotAwsClientInstrumentation) registers our handler only after the upstream OTel handler is registered. This order matters as it impacts the oder of instrumentation. It is key to first register the upstream aws-sdk handler, and then this handler.

The AdotTracingRequestHandler extends the AWS SDK RequestHandler2. It uses the **beforeRequest** and **afterAttempt** methods to hook onto the OTel spans during specific phases of the SDK request and response life cycle. 
This allows it to enrich the upstream spans with custom **AwsExperimentalAttributes**. These points in the life cycle are crucial as they impact the order in which attributes are added to the OTel span.

- **beforeRequest** is the latest point where the sdk request can be obtained after it is modified by the upstream aws-sdk v1.11 handler. This ensures the upstream handles the request and applies its changes first.
- **afterAttempt** is the latest point to access the sdk response before the span closes in the upstream afterResponse/afterError methods. This ensures we capture attributes from the final, fully modified response after all upstream handlers have processed it.

### AWS SDK v2 Instrumentation Summary
The AdotAwsSdkInstrumentationModule extends the InstrumentationModule from OpenTelemetry's SPI extensions and registers
the AdotTracingExecutionInterceptor to provide custom instrumentation which runs after the upstream OTel agent runs.
- **registerHelperResources** (within AdotAwsSdkInstrumentationModule) registers our resource (AdotTracingExecutionInterceptor) to inject into the user's class loader. The order the names are registered impactes the oder of instrumentation. It is key to first register the upstream aws-sdk execution interceptor, and then this interceptor.

The AdotTracingExecutionInterceptor extends the AWS SDK ExecutionInterceptor. It uses the **beforeTransmission** and **modifyResponse** methods
to hook onto the OTel spans during specific phases of the SDK request and response life cycle. This allows it to enrich the
upstream spans with custom **AwsExperimentalAttributes**. These points in the life cycle are crucial as they impact the order in which attributes
are added to the OTel span.

- **beforeTransmission** is the latest point where the sdk request can be obtained after it is modified by the upstream aws-sdk v2.2 interceptor. This ensures the upstream handles the request and applies its changes first.
- **modifyResponse** is the latest point to access the sdk response before the span closes in the upstream afterExecution method. This ensures we capture attributes from the final, fully modified response after all upstream interceptors have processed it.

The AdotTracingExecutionInterceptor is the main point which connects the other classes within awssdk_v2_2
through the fieldMapper variable.

_**Important Note:**_
Since the upstream interceptor closes the span in afterExecution, that method and any subsequent points are inaccessible for span modification.
We use modifyResponse as our final interception point, as it provides access to both the complete SDK response (after upstream applies it's modifications first) and the still-active span.
This ensures our attributes are added before span completion while still capturing the final state of the response after OTel.

### Example of Agent Initialization Workflow

_aws-sdk_v2_2:_
1. OpenTelemetry Agent starts
   - Loads default instrumentations
   - Loads aws-sdk instrumentation from opentelemetry-java-instrumentation
   - Registers **TracingExecutionInterceptor** (order = 0)
2. Scans for other SPI implementations
   - Finds ADOT’s **AdotAwsSdkInstrumentationModule**
   - Registers **AdotTracingExecutionInterceptor** (order > 0)

