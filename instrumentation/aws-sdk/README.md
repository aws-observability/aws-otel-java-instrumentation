## ADOT AWS SDK Instrumentation

### Overview
The aws-sdk instrumentation is an SPI-based implementation that extends the upstream OpenTelemetry AWS Java SDK instrumentation.

_Example: v2.2 Initialization Workflow_

1. OpenTelemetry Agent starts
   - Loads default instrumentations
   - Loads aws-sdk instrumentation from opentelemetry-java-instrumentation
   - Registers **TracingExecutionInterceptor** (order = 0)
2. Scans for other SPI implementations
   - Finds ADOT’s **AdotAwsSdkInstrumentationModule**
   - Registers **AdotAwsSdkTracingExecutionInterceptor** (order > 0)

### AWS SDK v1 Instrumentation Summary
The AdotAwsSdkInstrumentationModule uses the instrumentation (specified in AdotAwsClientInstrumentation) to register the AdotAwsSdkTracingRequestHandler through `typeInstrumentations`.

Key aspects of handler registration:
- `order` method ensures ADOT instrumentation runs after OpenTelemetry's base instrumentation. It is set to 99 as precaution, in case upstream aws-sdk registers more handlers.
- `AdotAwsSdkClientInstrumentation` class
  - `AdotAwsSdkClientAdvice` registers our handler only if the upstream aws-sdk span is enabled (i.e. it checks if the upstream handler is present when an AWS SDK client is
  initialized).
  - Ensures the OpenTelemetry handler is registered first.

**AdotAwsSdkTracingRequestHandler**

The AdotAwsSdkTracingRequestHandler hooks onto OpenTelemetry's spans during specific phases of the SDK request and response life cycle. These hooks are strategically chosen to ensure proper ordering of attribute injection.

1.  `beforeRequest`: the latest point where the SDK request can be obtained after it is modified by the upstream aws-sdk v1.11 handler
2.  `afterAttempt`: the latest point to access the SDK response before the span closes in the upstream afterResponse/afterError methods

All the span lifecycle hooks provided by AWS SDK RequestHandler2 can be found [here.](https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/handlers/RequestHandler2.html#beforeMarshalling-com.amazonaws.AmazonWebServiceRequest)

_**Important Notes:**_
- The upstream interceptor's last point of request modification occurs in [beforeRequest](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/aws-sdk/aws-sdk-1.11/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v1_11/TracingRequestHandler.java#L58).
- The upstream interceptor closes the span in [afterResponse](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/aws-sdk/aws-sdk-1.11/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v1_11/TracingRequestHandler.java#L116) and/or [afterError](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/aws-sdk/aws-sdk-1.11/library/src/main/java/io/opentelemetry/instrumentation/awssdk/v1_11/TracingRequestHandler.java#L131). These hooks are inaccessible for span modification.
  `afterAttempt` is our final hook point, giving us access to both the fully processed response and active span.

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

_**Important Note:**_
The upstream interceptor closes the span in `afterExecution`. That hook is inaccessible for span modification.
`modifyResponse` is our final hook point, giving us access to both the fully processed response and active span.


