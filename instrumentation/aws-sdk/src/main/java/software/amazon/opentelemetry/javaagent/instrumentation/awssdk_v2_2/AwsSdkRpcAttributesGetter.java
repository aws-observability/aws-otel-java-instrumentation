package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2;

import io.opentelemetry.instrumentation.api.incubator.semconv.rpc.RpcAttributesGetter;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;

enum AwsSdkRpcAttributesGetter implements RpcAttributesGetter<ExecutionAttributes> {
    INSTANCE;

    @Override
    public String getSystem(ExecutionAttributes request) {
        return "aws-api";
    }

    @Override
    public String getService(ExecutionAttributes request) {
        return request.getAttribute(SdkExecutionAttribute.SERVICE_NAME);
    }

    @Override
    public String getMethod(ExecutionAttributes request) {
        return request.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
    }
}
