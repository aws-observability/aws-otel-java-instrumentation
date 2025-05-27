package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2;

import io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.tooling.muzzle.NoMuzzle;
import software.amazon.awssdk.core.SdkRequest;

final class LambdaAccess {
    private LambdaAccess() {}

    private static final boolean enabled = PluginImplUtil.isImplPresent("LambdaImpl");

    @NoMuzzle
    public static SdkRequest modifyRequest(SdkRequest request, Context otelContext) {
        return enabled ? LambdaImpl.modifyRequest(request, otelContext) : null;
    }
}
