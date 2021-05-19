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

package software.amazon.opentelemetry.javaagent.instrumentation.logback_1_0;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AwsXrayLogbackInstrumentationModule extends InstrumentationModule {
  public AwsXrayLogbackInstrumentationModule() {
    super("logback", "logback-1.0", "aws-logback", "aws-logback-1.0");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Collections.singletonList(new AwsXrayLoggingEventInstrumentation());
  }

  @Override
  public Map<String, String> getMuzzleContextStoreClasses() {
    return Collections.singletonMap(
        "ch.qos.logback.classic.spi.ILoggingEvent", Span.class.getName());
  }
}
