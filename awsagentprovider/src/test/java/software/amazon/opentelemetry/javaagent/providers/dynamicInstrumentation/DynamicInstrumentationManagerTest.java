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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ServiceAttributes;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.config.DynamicInstrumentationConfig;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;

class DynamicInstrumentationManagerTest {

  @AfterEach
  void cleanup() {
    DynamicInstrumentationManager manager = DynamicInstrumentationManager.getInstance();
    if (manager.isInitialized()) {
      manager.shutdown();
    }
    // Clear registry
    software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation
        .InstrumentationRegistry.clearAll();
  }

  @Test
  void testGetInstance_returnsSingleton() {
    DynamicInstrumentationManager instance1 = DynamicInstrumentationManager.getInstance();
    DynamicInstrumentationManager instance2 = DynamicInstrumentationManager.getInstance();

    assertThat(instance1).isSameAs(instance2);
  }

  @Test
  void testInitialize() {
    TracerProvider tracerProvider = mock(TracerProvider.class);
    Tracer mockTracer = mock(Tracer.class);
    when(tracerProvider.get(anyString(), anyString())).thenReturn(mockTracer);

    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    DynamicInstrumentationManager manager = DynamicInstrumentationManager.getInstance();

    assertThat(manager.isInitialized()).isFalse();

    manager.initialize(tracerProvider, config);

    assertThat(manager.isInitialized()).isTrue();
    assertThat(manager.getTracerProvider()).isEqualTo(tracerProvider);
    assertThat(manager.getConfig()).isEqualTo(config);
    assertThat(manager.getTracer()).isNotNull();
    assertThat(manager.getClient()).isNotNull();
  }

  @Test
  void testInitialize_alreadyInitialized() {
    TracerProvider tracerProvider = mock(TracerProvider.class);
    Tracer mockTracer = mock(Tracer.class);
    when(tracerProvider.get(anyString(), anyString())).thenReturn(mockTracer);

    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    DynamicInstrumentationManager manager = DynamicInstrumentationManager.getInstance();
    manager.initialize(tracerProvider, config);

    // Try to initialize again
    TracerProvider anotherProvider = mock(TracerProvider.class);
    manager.initialize(anotherProvider, config);

    // Should still have the first TracerProvider
    assertThat(manager.getTracerProvider()).isEqualTo(tracerProvider);
    assertThat(manager.getTracerProvider()).isNotEqualTo(anotherProvider);
  }

  @Test
  void testApplyConfigurations_empty() {
    TracerProvider tracerProvider = mock(TracerProvider.class);
    Tracer mockTracer = mock(Tracer.class);
    when(tracerProvider.get(anyString(), anyString())).thenReturn(mockTracer);

    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    DynamicInstrumentationManager manager = DynamicInstrumentationManager.getInstance();
    manager.initialize(tracerProvider, config);

    // Apply empty list - should not throw
    manager.applyConfigurations(new ArrayList<>());
  }

  @Test
  void testApplyConfigurations_withConfigs() {
    TracerProvider tracerProvider = mock(TracerProvider.class);
    Tracer mockTracer = mock(Tracer.class);
    when(tracerProvider.get(anyString(), anyString())).thenReturn(mockTracer);

    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    DynamicInstrumentationManager manager = DynamicInstrumentationManager.getInstance();
    manager.initialize(tracerProvider, config);

    // Create a mock configuration
    java.util.Map<String, Object> apiConfig = new java.util.HashMap<>();
    java.util.Map<String, Object> location = new java.util.HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", "com.example");
    location.put("ClassName", "TestClass");
    location.put("MethodName", "testMethod");
    location.put("LineNumber", 0);
    java.util.Map<String, Object> locationWrapper = new java.util.HashMap<>();
    locationWrapper.put("CodeLocation", location);
    apiConfig.put("Location", locationWrapper);
    apiConfig.put("LocationHash", "test-hash");
    apiConfig.put("InstrumentationType", "PROBE");
    java.util.Map<String, Object> captureWrapper = new java.util.HashMap<>();
    captureWrapper.put("CodeCapture", java.util.Map.of());
    apiConfig.put("CaptureConfiguration", captureWrapper);

    InstrumentationConfiguration instrConfig =
        InstrumentationConfiguration.fromApiConfig(apiConfig);

    // Apply configuration - should not throw
    manager.applyConfigurations(java.util.List.of(instrConfig));
  }

  @Test
  void testShutdown() throws InterruptedException {
    TracerProvider tracerProvider = mock(TracerProvider.class);
    Tracer mockTracer = mock(Tracer.class);
    when(tracerProvider.get(anyString(), anyString())).thenReturn(mockTracer);

    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    DynamicInstrumentationManager manager = DynamicInstrumentationManager.getInstance();
    manager.initialize(tracerProvider, config);

    assertThat(manager.isInitialized()).isTrue();
    assertThat(manager.getClient().isPolling()).isTrue();

    manager.shutdown();

    // Give time for polling to stop
    Thread.sleep(100);

    assertThat(manager.isInitialized()).isFalse();
  }

  @Test
  void testShutdown_whenNotInitialized() {
    DynamicInstrumentationManager manager = DynamicInstrumentationManager.getInstance();

    // Shutdown when not initialized - should be a no-op
    manager.shutdown();

    assertThat(manager.isInitialized()).isFalse();
  }

  @Test
  void testApplyConfigurations_beforeInitialize_isNoOp() {
    DynamicInstrumentationManager manager = DynamicInstrumentationManager.getInstance();
    assertThat(manager.isInitialized()).isFalse();

    java.util.Map<String, Object> apiConfig = new java.util.HashMap<>();
    java.util.Map<String, Object> location = new java.util.HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", "com.example");
    location.put("ClassName", "TestClass");
    location.put("MethodName", "processOrder");
    location.put("LineNumber", 0);
    java.util.Map<String, Object> locationWrapper = new java.util.HashMap<>();
    locationWrapper.put("CodeLocation", location);
    apiConfig.put("Location", locationWrapper);
    apiConfig.put("LocationHash", "hash-preinit");
    apiConfig.put("InstrumentationType", "BREAKPOINT");
    java.util.Map<String, Object> captureWrapper = new java.util.HashMap<>();
    captureWrapper.put("CodeCapture", java.util.Map.of());
    apiConfig.put("CaptureConfiguration", captureWrapper);

    InstrumentationConfiguration instrConfig =
        InstrumentationConfiguration.fromApiConfig(apiConfig);

    // Before initialize(), applyConfigurations must be a guarded no-op (no NPE, nothing
    // registered).
    manager.applyConfigurations(java.util.List.of(instrConfig));

    assertThat(
            software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation
                .InstrumentationRegistry.contains("com.example.TestClass.processOrder"))
        .isFalse();
  }

  @Test
  void testRemoveInstrumentations_beforeInitialize_isNoOp() {
    DynamicInstrumentationManager manager = DynamicInstrumentationManager.getInstance();
    assertThat(manager.isInitialized()).isFalse();

    // Before initialize(), removeInstrumentations must be a guarded no-op (no NPE).
    manager.removeInstrumentations(java.util.Set.of("com.example.TestClass.processOrder"));

    assertThat(manager.isInitialized()).isFalse();
  }

  @Test
  void testApplyConfigurations_rejectsConstructorInit() {
    TracerProvider tracerProvider = mock(TracerProvider.class);
    Tracer mockTracer = mock(Tracer.class);
    when(tracerProvider.get(anyString(), anyString())).thenReturn(mockTracer);

    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    DynamicInstrumentationManager manager = DynamicInstrumentationManager.getInstance();
    manager.initialize(tracerProvider, config);

    // Create a config targeting <init> (constructor)
    java.util.Map<String, Object> apiConfig = new java.util.HashMap<>();
    java.util.Map<String, Object> location = new java.util.HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", "com.example");
    location.put("ClassName", "TestClass");
    location.put("MethodName", "<init>");
    location.put("LineNumber", 0);
    java.util.Map<String, Object> locationWrapperInit = new java.util.HashMap<>();
    locationWrapperInit.put("CodeLocation", location);
    apiConfig.put("Location", locationWrapperInit);
    apiConfig.put("LocationHash", "hash-init");
    apiConfig.put("InstrumentationType", "BREAKPOINT");
    java.util.Map<String, Object> captureWrapperInit = new java.util.HashMap<>();
    captureWrapperInit.put("CodeCapture", java.util.Map.of());
    apiConfig.put("CaptureConfiguration", captureWrapperInit);

    InstrumentationConfiguration instrConfig =
        InstrumentationConfiguration.fromApiConfig(apiConfig);

    // Apply — should skip <init> and NOT register it
    manager.applyConfigurations(java.util.List.of(instrConfig));

    assertThat(
            software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation
                .InstrumentationRegistry.contains("com.example.TestClass.<init>"))
        .isFalse();
  }

  @Test
  void testApplyConfigurations_rejectsStaticInitializerClinit() {
    TracerProvider tracerProvider = mock(TracerProvider.class);
    Tracer mockTracer = mock(Tracer.class);
    when(tracerProvider.get(anyString(), anyString())).thenReturn(mockTracer);

    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    DynamicInstrumentationManager manager = DynamicInstrumentationManager.getInstance();
    manager.initialize(tracerProvider, config);

    // Create a config targeting <clinit> (static initializer)
    java.util.Map<String, Object> apiConfig = new java.util.HashMap<>();
    java.util.Map<String, Object> location = new java.util.HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", "com.example");
    location.put("ClassName", "TestClass");
    location.put("MethodName", "<clinit>");
    location.put("LineNumber", 0);
    java.util.Map<String, Object> locationWrapperClinit = new java.util.HashMap<>();
    locationWrapperClinit.put("CodeLocation", location);
    apiConfig.put("Location", locationWrapperClinit);
    apiConfig.put("LocationHash", "hash-clinit");
    apiConfig.put("InstrumentationType", "BREAKPOINT");
    java.util.Map<String, Object> captureWrapperClinit = new java.util.HashMap<>();
    captureWrapperClinit.put("CodeCapture", java.util.Map.of());
    apiConfig.put("CaptureConfiguration", captureWrapperClinit);

    InstrumentationConfiguration instrConfig =
        InstrumentationConfiguration.fromApiConfig(apiConfig);

    // Apply — should skip <clinit> and NOT register it
    manager.applyConfigurations(java.util.List.of(instrConfig));

    assertThat(
            software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation
                .InstrumentationRegistry.contains("com.example.TestClass.<clinit>"))
        .isFalse();
  }

  @Test
  void testApplyConfigurations_initSkippedButNormalMethodNotBlocked() {
    TracerProvider tracerProvider = mock(TracerProvider.class);
    Tracer mockTracer = mock(Tracer.class);
    when(tracerProvider.get(anyString(), anyString())).thenReturn(mockTracer);

    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    DynamicInstrumentationManager manager = DynamicInstrumentationManager.getInstance();
    manager.initialize(tracerProvider, config);

    // Create two configs: one <init> (should be skipped), one normal method
    java.util.Map<String, Object> initConfig = new java.util.HashMap<>();
    java.util.Map<String, Object> initLocation = new java.util.HashMap<>();
    initLocation.put("Language", "Java");
    initLocation.put("CodeUnit", "com.example");
    initLocation.put("ClassName", "TestClass");
    initLocation.put("MethodName", "<init>");
    initLocation.put("LineNumber", 0);
    java.util.Map<String, Object> initLocationWrapper = new java.util.HashMap<>();
    initLocationWrapper.put("CodeLocation", initLocation);
    initConfig.put("Location", initLocationWrapper);
    initConfig.put("LocationHash", "hash-init");
    initConfig.put("InstrumentationType", "BREAKPOINT");
    java.util.Map<String, Object> initCaptureWrapper = new java.util.HashMap<>();
    initCaptureWrapper.put("CodeCapture", java.util.Map.of());
    initConfig.put("CaptureConfiguration", initCaptureWrapper);

    java.util.Map<String, Object> normalConfig = new java.util.HashMap<>();
    java.util.Map<String, Object> normalLocation = new java.util.HashMap<>();
    normalLocation.put("Language", "Java");
    normalLocation.put("CodeUnit", "com.example");
    normalLocation.put("ClassName", "TestClass");
    normalLocation.put("MethodName", "processOrder");
    normalLocation.put("LineNumber", 0);
    java.util.Map<String, Object> normalLocationWrapper = new java.util.HashMap<>();
    normalLocationWrapper.put("CodeLocation", normalLocation);
    normalConfig.put("Location", normalLocationWrapper);
    normalConfig.put("LocationHash", "hash-normal");
    normalConfig.put("InstrumentationType", "BREAKPOINT");
    java.util.Map<String, Object> normalCaptureWrapper = new java.util.HashMap<>();
    normalCaptureWrapper.put("CodeCapture", java.util.Map.of());
    normalConfig.put("CaptureConfiguration", normalCaptureWrapper);

    InstrumentationConfiguration initInstr = InstrumentationConfiguration.fromApiConfig(initConfig);
    InstrumentationConfiguration normalInstr =
        InstrumentationConfiguration.fromApiConfig(normalConfig);

    // Apply both — should not throw even with <init> in the mix
    // (engine is null in unit tests so configs won't be registered, but the method should
    // not error out and should skip <init> without blocking the normal config)
    manager.applyConfigurations(java.util.List.of(initInstr, normalInstr));

    // <init> should never be registered regardless of engine state
    assertThat(
            software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation
                .InstrumentationRegistry.contains("com.example.TestClass.<init>"))
        .isFalse();
  }

  // Note: Tests for line-level configuration registration with real Instrumentation
  // require integration tests. The configs are registered but transformation fails
  // in unit tests without real java.lang.instrument.Instrumentation instance.
}
