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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the registration gate in {@link ServiceEventsTracerCustomizerProvider}: the {@link
 * ServiceEventsSpanProcessor} is registered when EITHER ServiceEvents or the profiler is enabled,
 * and NOT registered when both are off.
 *
 * <p>The profiler uses the SpanProcessor as its correlation source (it writes {@code profiler.Span}
 * markers into the JFR), so the SpanProcessor must be registered even when ServiceEvents is
 * disabled — the profiler flag alone must open the gate. We verify the call to {@code
 * addTracerProviderCustomizer} (the registration path) rather than a live profiler.
 */
class ServiceEventsTracerCustomizerProviderTest {

  private static final String[] FLAGS = {
    "otel.aws.profiler.enabled",
    "otel.aws.service_events.enabled",
    "otel.aws.application.signals.enabled",
    "otel.aws.otlp.logs.endpoint",
    "otel.aws.otlp.metrics.endpoint",
    "aws.lambda.function.name",
  };

  @BeforeEach
  @AfterEach
  void clearProps() {
    for (String p : FLAGS) {
      System.clearProperty(p);
    }
  }

  @Test
  void allFlagsUnset_doesNotRegisterSpanProcessor() {
    AutoConfigurationCustomizer customizer = mock(AutoConfigurationCustomizer.class);
    new ServiceEventsTracerCustomizerProvider().customize(customizer);
    verify(customizer, never()).addTracerProviderCustomizer(any());
  }

  @Test
  void profilerEnabledServiceEventsDisabled_registersSpanProcessor() {
    // The decoupled gate: profiler ON, ServiceEvents/App Signals OFF. The customizer must still
    // register the span processor so profiler correlation writes happen.
    System.setProperty("otel.aws.profiler.enabled", "true");
    AutoConfigurationCustomizer customizer = mock(AutoConfigurationCustomizer.class);
    new ServiceEventsTracerCustomizerProvider().customize(customizer);
    verify(customizer).addTracerProviderCustomizer(any());
  }

  @Test
  void serviceEventsEnabledProfilerDisabled_registersSpanProcessor() {
    // Registers when ServiceEvents is enabled (App Signals bundling on) with the profiler off.
    System.setProperty("otel.aws.application.signals.enabled", "true");
    AutoConfigurationCustomizer customizer = mock(AutoConfigurationCustomizer.class);
    new ServiceEventsTracerCustomizerProvider().customize(customizer);
    verify(customizer).addTracerProviderCustomizer(any());
  }
}
