## ADOT AWS SDK Instrumentation

### Overview
The aws-sdk instrumentation is an SPI-based implementation that extends the upstream OpenTelemetry AWS Java SDK instrumentation.

_Initialization Workflow_

1. OpenTelemetry Agent starts
   - Loads default instrumentations
   - Loads aws-sdk instrumentation from opentelemetry-java-instrumentation
   - Registers **TracingExecutionInterceptor** (order = 0)
2. Scans for other SPI implementations
   - Finds ADOT’s **AdotAwsSdkInstrumentationModule**
   - Registers **AdotAwsSdkTracingExecutionInterceptor** (order > 0)

### AWS SDK v2 Instrumentation Summary

**AdotAwsSdkInstrumentationModule**

The AdotAwsSdkInstrumentationModule registers the AdotAwsSdkTracingExecutionInterceptor in `registerHelperResources`.

Key aspects of interceptor registration:
- AWS SDK's ExecutionInterceptor loads global interceptors from files named '/software/amazon/awssdk/global/handlers/execution.interceptors' in the classpath
- Interceptors are executed in the order they appear in the classpath - earlier entries run first
- `order` method ensures ADOT instrumentation runs after OpenTelemetry's base instrumentation, maintaining proper sequence of interceptor registration in AWS SDK classpath

**AdotAwsSdkTracingExecutionInterceptor**

The AdotAwsSdkTracingExecutionInterceptor hooks onto OpenTelemetry's spans during specific phases of the SDK request and response life cycle. These hooks are strategically chosen to ensure proper ordering of attribute injection.

1. `beforeTransmission`: the latest point where the SDK request can be obtained after it is modified by the upstream's interceptor
2. `modifyResponse`: the latest point to access the SDK response before the span closes in the upstream afterExecution method

All the span lifecycle hooks provided by AWS SDK ExecutionInterceptor can be found [here.](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/core/interceptor/ExecutionInterceptor.html)

_**Important Notes:**_
- The upstream interceptor's last point of request modification occurs in [beforeTransmission](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/release/v2.11.x/instrumentation/aws-sdk/aws-sdk-2.2/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v2_2/internal/TracingExecutionInterceptor.java#L237).
- The upstream interceptor closes the span in [afterExecution](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/release/v2.11.x/instrumentation/aws-sdk/aws-sdk-2.2/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v2_2/internal/TracingExecutionInterceptor.java#L348). That hook is inaccessible for span modification.
`modifyResponse` is our final hook point, giving us access to both the fully processed response and active span.

Variables in AdotAwsSdkTracingExecutionInterceptor:
1. `FieldMapper fieldMapper` variable helps extract specific attributes from AWS SDK requests/responses and maps them to OpenTelemetry span attributes.
2. `ExecutionAttribute<AwsSdkRequest> AWS_SDK_REQUEST_ATTRIBUTE` saves the AWS SDK request initially made. It is accessed in the `modifyResponse` hook for mapping the response of that specific request.