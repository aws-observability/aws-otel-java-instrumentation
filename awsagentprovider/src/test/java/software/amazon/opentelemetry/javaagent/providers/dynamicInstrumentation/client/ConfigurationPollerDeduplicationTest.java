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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ServiceAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.DynamicInstrumentationManager;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.config.DynamicInstrumentationConfig;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;

class ConfigurationPollerDeduplicationTest {

  private ConfigurationPoller poller;
  private DynamicInstrumentationClient client;

  @BeforeEach
  void setUp() {
    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    client = new DynamicInstrumentationClient(config);
    poller = new ConfigurationPoller(client);
  }

  @Test
  void testUnchangedConfigsSkipRetransformation() {
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(
        createConfigWithCreatedAt(
            "com.example",
            "OrderService",
            "processOrder",
            0,
            "BREAKPOINT",
            "hash1",
            Instant.parse("2024-01-01T10:00:00Z")));

    try (MockedStatic<DynamicInstrumentationManager> managerMock =
        mockStatic(DynamicInstrumentationManager.class)) {
      DynamicInstrumentationManager mockManager =
          org.mockito.Mockito.mock(DynamicInstrumentationManager.class);
      managerMock.when(DynamicInstrumentationManager::getInstance).thenReturn(mockManager);

      // First call — should apply
      poller.applyMergedConfiguration(null, configs);
      verify(mockManager, times(1)).applyConfigurations(any());

      // Second call with identical configs — should skip
      poller.applyMergedConfiguration(null, configs);
      verify(mockManager, times(1)).applyConfigurations(any());
      verify(mockManager, times(0)).removeInstrumentations(anySet());
    }
  }

  @Test
  void testRecreatedConfigTriggersRetransformation() {
    List<InstrumentationConfiguration> configs1 = new ArrayList<>();
    configs1.add(
        createConfigWithCreatedAt(
            "com.example",
            "OrderService",
            "processOrder",
            0,
            "BREAKPOINT",
            "hash1",
            Instant.parse("2024-01-01T10:00:00Z")));

    List<InstrumentationConfiguration> configs2 = new ArrayList<>();
    configs2.add(
        createConfigWithCreatedAt(
            "com.example",
            "OrderService",
            "processOrder",
            0,
            "BREAKPOINT",
            "hash1",
            Instant.parse("2024-01-01T11:00:00Z")));

    try (MockedStatic<DynamicInstrumentationManager> managerMock =
        mockStatic(DynamicInstrumentationManager.class)) {
      DynamicInstrumentationManager mockManager =
          org.mockito.Mockito.mock(DynamicInstrumentationManager.class);
      managerMock.when(DynamicInstrumentationManager::getInstance).thenReturn(mockManager);

      // First call
      poller.applyMergedConfiguration(null, configs1);
      verify(mockManager, times(1)).applyConfigurations(any());

      // Second call with different createdAt — should apply again
      poller.applyMergedConfiguration(null, configs2);
      verify(mockManager, times(2)).applyConfigurations(any());
    }
  }

  @Test
  void testRemovedConfigTriggersRetransformation() {
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(
        createConfigWithCreatedAt(
            "com.example",
            "OrderService",
            "processOrder",
            0,
            "BREAKPOINT",
            "hash1",
            Instant.parse("2024-01-01T10:00:00Z")));

    try (MockedStatic<DynamicInstrumentationManager> managerMock =
        mockStatic(DynamicInstrumentationManager.class)) {
      DynamicInstrumentationManager mockManager =
          org.mockito.Mockito.mock(DynamicInstrumentationManager.class);
      managerMock.when(DynamicInstrumentationManager::getInstance).thenReturn(mockManager);

      // First call with one config
      poller.applyMergedConfiguration(null, configs);
      verify(mockManager, times(1)).applyConfigurations(any());

      // Second call with empty configs — removal detected
      poller.applyMergedConfiguration(null, new ArrayList<>());
      verify(mockManager, times(1)).removeInstrumentations(anySet());
      verify(mockManager, times(2)).applyConfigurations(any());
    }
  }

  @Test
  void testFailedApplicationAllowsRetry() {
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(
        createConfigWithCreatedAt(
            "com.example",
            "OrderService",
            "processOrder",
            0,
            "BREAKPOINT",
            "hash1",
            Instant.parse("2024-01-01T10:00:00Z")));

    try (MockedStatic<DynamicInstrumentationManager> managerMock =
        mockStatic(DynamicInstrumentationManager.class)) {
      DynamicInstrumentationManager mockManager =
          org.mockito.Mockito.mock(DynamicInstrumentationManager.class);
      managerMock.when(DynamicInstrumentationManager::getInstance).thenReturn(mockManager);

      // First call — applyConfigurations throws
      doThrow(new RuntimeException("simulated failure"))
          .doNothing()
          .when(mockManager)
          .applyConfigurations(any());

      poller.applyMergedConfiguration(null, configs);

      // Second call with same configs — should retry because first attempt failed
      poller.applyMergedConfiguration(null, configs);
      verify(mockManager, times(2)).applyConfigurations(any());

      // Third call — should now skip because second call succeeded and fingerprint was saved
      poller.applyMergedConfiguration(null, configs);
      verify(mockManager, times(2)).applyConfigurations(any());
    }
  }

  @Test
  void testEmptyToEmptySkipsRetransformation() {
    try (MockedStatic<DynamicInstrumentationManager> managerMock =
        mockStatic(DynamicInstrumentationManager.class)) {
      DynamicInstrumentationManager mockManager =
          org.mockito.Mockito.mock(DynamicInstrumentationManager.class);
      managerMock.when(DynamicInstrumentationManager::getInstance).thenReturn(mockManager);

      // Both calls with empty — fingerprint matches initial state, no calls to manager
      poller.applyMergedConfiguration(null, new ArrayList<>());
      poller.applyMergedConfiguration(null, new ArrayList<>());
      verify(mockManager, times(0)).applyConfigurations(any());
      verify(mockManager, times(0)).removeInstrumentations(anySet());
    }
  }

  @Test
  void testNewConfigAddedTriggersRetransformation() {
    List<InstrumentationConfiguration> configs1 = new ArrayList<>();
    configs1.add(
        createConfigWithCreatedAt(
            "com.example",
            "OrderService",
            "processOrder",
            0,
            "BREAKPOINT",
            "hash1",
            Instant.parse("2024-01-01T10:00:00Z")));

    List<InstrumentationConfiguration> configs2 = new ArrayList<>();
    configs2.add(
        createConfigWithCreatedAt(
            "com.example",
            "OrderService",
            "processOrder",
            0,
            "BREAKPOINT",
            "hash1",
            Instant.parse("2024-01-01T10:00:00Z")));
    configs2.add(
        createConfigWithCreatedAt(
            "com.example",
            "OrderService",
            "cancelOrder",
            0,
            "BREAKPOINT",
            "hash2",
            Instant.parse("2024-01-01T10:00:00Z")));

    try (MockedStatic<DynamicInstrumentationManager> managerMock =
        mockStatic(DynamicInstrumentationManager.class)) {
      DynamicInstrumentationManager mockManager =
          org.mockito.Mockito.mock(DynamicInstrumentationManager.class);
      managerMock.when(DynamicInstrumentationManager::getInstance).thenReturn(mockManager);

      // First call with one config
      poller.applyMergedConfiguration(null, configs1);
      verify(mockManager, times(1)).applyConfigurations(any());

      // Second call with additional config — should apply
      poller.applyMergedConfiguration(null, configs2);
      verify(mockManager, times(2)).applyConfigurations(any());
    }
  }

  @Test
  void testDifferentPollerTypesIndependentlyTracked() {
    List<InstrumentationConfiguration> probeConfigs = new ArrayList<>();
    probeConfigs.add(
        createConfigWithCreatedAt(
            "com.example",
            "OrderService",
            "processOrder",
            0,
            "PROBE",
            "hash-probe",
            Instant.parse("2024-01-01T10:00:00Z")));

    List<InstrumentationConfiguration> breakpointConfigs = new ArrayList<>();
    breakpointConfigs.add(
        createConfigWithCreatedAt(
            "com.example",
            "OrderService",
            "processOrder",
            42,
            "BREAKPOINT",
            "hash-bp",
            Instant.parse("2024-01-01T10:00:00Z")));

    try (MockedStatic<DynamicInstrumentationManager> managerMock =
        mockStatic(DynamicInstrumentationManager.class)) {
      DynamicInstrumentationManager mockManager =
          org.mockito.Mockito.mock(DynamicInstrumentationManager.class);
      managerMock.when(DynamicInstrumentationManager::getInstance).thenReturn(mockManager);

      // Apply probes only
      poller.applyMergedConfiguration(probeConfigs, null);
      verify(mockManager, times(1)).applyConfigurations(any());

      // Apply same probes again — skip
      poller.applyMergedConfiguration(probeConfigs, null);
      verify(mockManager, times(1)).applyConfigurations(any());

      // Now add breakpoints — fingerprint changes, should apply
      poller.applyMergedConfiguration(null, breakpointConfigs);
      verify(mockManager, times(2)).applyConfigurations(any());

      // Same breakpoints again — skip
      poller.applyMergedConfiguration(null, breakpointConfigs);
      verify(mockManager, times(2)).applyConfigurations(any());

      // No removals should have occurred throughout
      verify(mockManager, times(0)).removeInstrumentations(anySet());
    }
  }

  @Test
  void testNullCreatedAtDeduplicatesCorrectly() {
    // Config without createdAt (null) — common for older API responses
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(
        createConfigWithCreatedAt(
            "com.example", "OrderService", "processOrder", 0, "BREAKPOINT", "hash1", null));

    try (MockedStatic<DynamicInstrumentationManager> managerMock =
        mockStatic(DynamicInstrumentationManager.class)) {
      DynamicInstrumentationManager mockManager =
          org.mockito.Mockito.mock(DynamicInstrumentationManager.class);
      managerMock.when(DynamicInstrumentationManager::getInstance).thenReturn(mockManager);

      // First call — should apply
      poller.applyMergedConfiguration(null, configs);
      verify(mockManager, times(1)).applyConfigurations(any());

      // Second call with same null createdAt — should skip
      poller.applyMergedConfiguration(null, configs);
      verify(mockManager, times(1)).applyConfigurations(any());

      // Third call with non-null createdAt (config recreated) — should apply
      List<InstrumentationConfiguration> recreatedConfigs = new ArrayList<>();
      recreatedConfigs.add(
          createConfigWithCreatedAt(
              "com.example",
              "OrderService",
              "processOrder",
              0,
              "BREAKPOINT",
              "hash1",
              Instant.parse("2024-01-01T12:00:00Z")));
      poller.applyMergedConfiguration(null, recreatedConfigs);
      verify(mockManager, times(2)).applyConfigurations(any());
    }
  }

  private InstrumentationConfiguration createConfigWithCreatedAt(
      String codeUnit,
      String className,
      String methodName,
      int lineNumber,
      String type,
      String locationHash,
      Instant createdAt) {
    Map<String, Object> location = new HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", codeUnit);
    location.put("ClassName", className);
    location.put("MethodName", methodName);
    location.put("LineNumber", lineNumber);
    location.put("FilePath", className + ".java");

    Map<String, Object> locationWrapper = new HashMap<>();
    locationWrapper.put("CodeLocation", location);

    Map<String, Object> captureWrapper = new HashMap<>();
    captureWrapper.put("CodeCapture", Collections.emptyMap());

    Map<String, Object> apiConfig = new HashMap<>();
    apiConfig.put("Location", locationWrapper);
    apiConfig.put("LocationHash", locationHash);
    apiConfig.put("InstrumentationType", type);
    apiConfig.put("CaptureConfiguration", captureWrapper);

    if ("PROBE".equals(type)) {
      apiConfig.put("InstrumentationName", "test-probe");
    }

    if (createdAt != null) {
      apiConfig.put("CreatedAt", createdAt.toString());
    }

    return InstrumentationConfiguration.fromApiConfig(apiConfig);
  }
}
