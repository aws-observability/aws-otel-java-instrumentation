package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.HelperResourceBuilder;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;

import java.util.Arrays;
import java.util.List;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class) //register this class as a service provider
public class AwsSdkInstrumentationModule extends InstrumentationModule {

    public AwsSdkInstrumentationModule() {
        super("aws-sdk", "aws-sdk-2.2"); //name of this instrumentation and service
    }

    @Override // Need: sets priority of this instrumentation
    public int order() {
        return 1;
    }

    @Override
    public boolean isIndyModule() {
        return false;
    }

    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        // Checks if AWS SDK v2 is present before applying instrumentation
        return hasClassesNamed("software.amazon.awssdk.core.interceptor.ExecutionInterceptor");
    }

    @Override
    public List<String> getAdditionalHelperClassNames() {
        return Arrays.asList(
                // classes needed for instrumentation
                "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsTracingExecutionInterceptor", // main interceptor
                "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkRequest", // Request wrapper
                "software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2.AwsSdkInstrumenterFactory" // Creates instrumenters
        );
    }

    @Override
    public void registerHelperResources(HelperResourceBuilder helperResourceBuilder) {
        helperResourceBuilder.register(
                // registers the interceptor configuration file
                "software/amazon/awssdk/global/handlers/execution.interceptors"
        );
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return singletonList(new AwsSdkClientInstrumentation());
    }

    // Defines what to instrument
    public static class AwsSdkClientInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            // Matches AWS SDK client interface
            return named("software.amazon.awssdk.core.SdkClient");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            // Empty as we use ExecutionInterceptor
        }
    }
}