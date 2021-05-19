/*
 * Copyright Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.opentelemetry.javaagent.instrumentation.log4j_2_13_2;

import static io.opentelemetry.javaagent.extension.matcher.ClassLoaderMatcher.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class AwsXrayLog4jInstrumentationModule extends InstrumentationModule {

  public AwsXrayLog4jInstrumentationModule() {
    super("log4j", "log4j-2.13.2", "aws-log4j", "aws-log4j-2.13.2");
  }

  // The SPI will be merged with what's in the agent so we don't need to inject it, only our
  // provider implementation.
  @Override
  public String[] helperResourceNames() {
    return new String[] {
      "software.amazon.opentelemetry.javaagent.instrumentation.log4j_2_13_2."
          + "AwsXrayContextDataProvider"
    };
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("org.apache.logging.log4j.core.util.ContextDataProvider");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Collections.singletonList(new EmptyTypeInstrumentation());
  }

  public static class EmptyTypeInstrumentation implements TypeInstrumentation {
    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      // we cannot use ContextDataProvider here because one of the classes that we inject implements
      // this interface, causing the interface to be loaded while it's being transformed, which
      // leads
      // to duplicate class definition error after the interface is transformed and the triggering
      // class loader tries to load it.
      return named("org.apache.logging.log4j.core.impl.ThreadContextDataInjector");
    }

    @Override
    public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
      // Nothing to instrument, no methods to match
      return Collections.emptyMap();
    }
  }
}
