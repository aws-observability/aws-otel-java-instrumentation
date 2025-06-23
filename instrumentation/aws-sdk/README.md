## ADOT AWS SDK Instrumentation

### Overview
The aws-sdk instrumentation is an SPI-based implementation that extends the AWS SDK within the upstream OpenTelemetry Instrumentation for Java. 
This instrumentation:

1. Leverages OpenTelemetry's InstrumentationModule extension mechanism
2. Ensures AWS-specific instrumentation runs after core OpenTelemetry instrumentation
3. Maintains all existing functionality while improving ADOT maintainability

Benefits:

- **Better Extensibility:** Users can easily extend or modify AWS-specific behavior
- **Enhanced Compatibility:** Better alignment with OpenTelemetry's extension mechanisms
- **Clearer Code Organization:** More intuitive structure for future contributions

### AWS SDK v1 Instrumentation Summary
The AdotAwsSdkInstrumentationModule extends the InstrumentationModule from OpenTelemetry's SPI extensions and uses the instrumentation (specified in AdotAwsClientInstrumentation) to 
register the AdotTracingRequestHandler. Unlike the direct registration of the TracingExecutionInterceptor in aws-sdk v2, the @Adive mechanism is used to load the AdotTracingRequestHandler when an AWS client is constructed.

- **AdotAwsClientAdvice**  (within AdotAwsClientInstrumentation) registers our handler only after the upstream OTel handler is registered. This order matters as it impacts the oder of instrumentation. It is key to first register the upstream aws-sdk handler, and then this handler.

The AdotTracingRequestHandler extends the AWS SDK RequestHandler2. It uses the **beforeRequest** and **afterAttempt** methods 
to hook onto the OTel spans during specific phases of the SDK request and response life cycle. This allows it to enrich the 
upstream spans with custom **AwsExperimentalAttributes**. These points in the life cycle are crucial as they impact the order in which attributes 
are added to the OTel span.

- **beforeRequest** is the latest point where the sdk request can be obtained after it is modified by the upstream aws-sdk v1.11 handler. This ensures the upstream handles the request and applies its changes first.
- **afterAttempt** is the latest point to access the sdk response before the span closes in the upstream afterResponse/afterError methods. This ensures we capture attributes from the final, fully modified response after all upstream handlers have processed it.

_**Note on Execution Ordering:**_

1. OTel creates and starts the span in beforeMarshalling
2. Our beforeRequest runs after OTel's span creation and initial attribute setting
3. Request processing occurs
4. Our afterAttempt runs before OTel closes the span
5. OTel closes the span in afterResponse/afterError

This order ensures our attributes are added after OTel's initial attributes but before span completion.

The AwsExperimentalAttributesExtractor is the main point which connects the other classes within awssdk_v1_11 
through the class's experimentalAttributesExtractor variable. 

