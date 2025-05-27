package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2;

import software.amazon.awssdk.core.interceptor.ExecutionAttributes;

abstract class AbstractSqsRequest {

    public abstract ExecutionAttributes getRequest();
}
