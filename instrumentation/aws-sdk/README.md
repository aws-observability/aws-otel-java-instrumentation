## ADOT AWS SDK Instrumentation

### Overview
This instrumentation is an SPI-based implementation, in place of ADOT's git patches, to extend the AWS SDK within the upstream OpenTelemetry Instrumentation for Java. 
This instrumentation:

1. Leverages OpenTelemetry's InstrumentationModule extension mechanism
2. Replaces Git patches with proper SPI implementation
3. Ensures AWS-specific instrumentation runs after core OpenTelemetry instrumentation
4. Maintains all existing functionality while improving ADOT maintainability

_Workflow_

1. OpenTelemetry Agent starts
   - Loads default instrumentations
   - Loads aws-sdk instrumentation from opentelemetry-java-instrumentation
   - Registers **TracingExecutionInterceptor** (order = 0)
2. Scans for other SPI implementations
   - Finds ADOT’s **AdotAwsSdkInstrumentationModule**
   - Registers **AdotTracingExecutionInterceptor** (order > 0)

### AWS SDK v2 Instrumentation Summary
The AdotAwsSdkInstrumentationModule extends the InstrumentationModule from OpenTelemetry's SPI extensions and registers 
the AdotTracingExecutionInterceptor to provide custom instrumentation which runs after the upstream OTel agent runs. 
- **registerHelperResources** (within AdotAwsSdkInstrumentationModule) registers our resource (AdotTracingExecutionInterceptor) to inject into the user's class loader. The order the names are registered impactes the oder of instrumentation. It is key to first register the upstream aws-sdk execution interceptor, and then this interceptor.

The AdotTracingExecutionInterceptor extends the AWS SDK ExecutionInterceptor. It uses the **beforeTransmission** and **afterUnmarshalling** methods 
to hook onto the OTel spans during specific phases of the SDK request and response life cycle. This allows it to enrich the 
upstream spans with custom **AwsExperimentalAttributes**. These points in the life cycle are crucial as they impact the order in which attributes 
are added to the OTel span.

- **beforeTransmission** is the latest point where the sdk request can be obtained after it is modified by the upstream aws-sdk v2.2 interceptor. This ensures the upstream handles the request and applies its changes first.
- **afterUnmarshalling** is the earliest point to access the sdk response before it is modified by the upstream interceptor. This ensures the execution attributes added in by this interceptor (like AWS_SDK_REQUEST_ATTRIBUTE) are handled by this interceptor only, and not the upstream interceptor.

The AdotTracingExecutionInterceptor is the main point which connects the other classes within awssdk_v2_2 
(which contain the patched logic) through the class's fieldMapper variable. 

<ins>Note:</ins> The files that contain the patched logic are:
* AwsExperimentalAttributes
* AwsSdkRequest
* AwsSdkRequestType
* BedrockJsonParser
* FieldMapper
* Serializer
* AdotTracingExecutionInterceptor
* BedrockJsonParserTest
* AbstractAws2ClientTest

