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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;

class InstrumentationRegistryTest {

  @AfterEach
  void cleanup() {
    InstrumentationRegistry.clearAll();
  }

  @Test
  void testRegisterAndGet() {
    InstrumentationConfiguration config =
        createConfig("com.example", "OrderService", "processOrder", 0, "BREAKPOINT");
    String key = config.getMethodKey();

    InstrumentationRegistry.register(key, config);

    assertThat(InstrumentationRegistry.get(key)).isEqualTo(config);
    assertThat(InstrumentationRegistry.contains(key)).isTrue();
    assertThat(InstrumentationRegistry.size()).isEqualTo(1);
  }

  @Test
  void testRemove() {
    InstrumentationConfiguration config =
        createConfig("com.example", "OrderService", "processOrder", 0, "BREAKPOINT");
    String key = config.getMethodKey();

    InstrumentationRegistry.register(key, config);
    InstrumentationConfiguration removed = InstrumentationRegistry.remove(key);

    assertThat(removed).isEqualTo(config);
    assertThat(InstrumentationRegistry.get(key)).isNull();
    assertThat(InstrumentationRegistry.size()).isEqualTo(0);
  }

  @Test
  void testClearAll() {
    InstrumentationRegistry.register(
        "key1", createConfig("com.example", "OrderService", "method1", 0, "BREAKPOINT"));
    InstrumentationRegistry.register(
        "key2", createConfig("com.example", "OrderService", "method2", 0, "BREAKPOINT"));

    InstrumentationRegistry.clearAll();

    assertThat(InstrumentationRegistry.size()).isEqualTo(0);
    assertThat(InstrumentationRegistry.getAllConfigurations()).isEmpty();
  }

  @Test
  void testGetConfigsForClass() {
    InstrumentationRegistry.register(
        "key1", createConfig("com.example", "OrderService", "processOrder", 0, "BREAKPOINT"));
    InstrumentationRegistry.register(
        "key2", createConfig("com.example", "OrderService", "cancelOrder", 0, "BREAKPOINT"));
    InstrumentationRegistry.register(
        "key3", createConfig("com.example", "PaymentService", "processPayment", 0, "BREAKPOINT"));

    List<InstrumentationConfiguration> orderServiceConfigs =
        InstrumentationRegistry.getConfigsForClass("com.example.OrderService");

    assertThat(orderServiceConfigs).hasSize(2);
    assertThat(orderServiceConfigs)
        .allMatch(c -> c.getFullyQualifiedClassName().equals("com.example.OrderService"));
  }

  @Test
  void testGetAllInstrumentedClasses() {
    InstrumentationRegistry.register(
        "key1", createConfig("com.example", "OrderService", "processOrder", 0, "BREAKPOINT"));
    InstrumentationRegistry.register(
        "key2", createConfig("com.example", "PaymentService", "processPayment", 0, "BREAKPOINT"));
    InstrumentationRegistry.register(
        "key3", createConfig("com.other", "ShippingService", "ship", 0, "PROBE"));

    assertThat(InstrumentationRegistry.getAllInstrumentedClasses())
        .containsExactlyInAnyOrder(
            "com.example.OrderService", "com.example.PaymentService", "com.other.ShippingService");
  }

  @Test
  void testGetAllConfigurations() {
    InstrumentationConfiguration config1 =
        createConfig("com.example", "OrderService", "processOrder", 0, "BREAKPOINT");
    InstrumentationConfiguration config2 =
        createConfig("com.example", "PaymentService", "processPayment", 0, "PROBE");

    InstrumentationRegistry.register("key1", config1);
    InstrumentationRegistry.register("key2", config2);

    List<InstrumentationConfiguration> allConfigs = InstrumentationRegistry.getAllConfigurations();

    assertThat(allConfigs).hasSize(2);
    assertThat(allConfigs).containsExactlyInAnyOrder(config1, config2);
  }

  @Test
  void testGetFunctionSets_SingleClass() {
    InstrumentationRegistry.register(
        "key1", createConfig("com.example", "OrderService", "processOrder", 0, "BREAKPOINT"));
    InstrumentationRegistry.register(
        "key2", createConfig("com.example", "OrderService", "processOrder", 42, "BREAKPOINT"));
    InstrumentationRegistry.register(
        "key3", createConfig("com.example", "OrderService", "cancelOrder", 0, "PROBE"));

    Map<String, FunctionInstrumentationSet> functionSets =
        InstrumentationRegistry.getFunctionSets("com.example.OrderService");

    assertThat(functionSets).hasSize(2);
    assertThat(functionSets)
        .containsKeys(
            "com.example.OrderService.processOrder", "com.example.OrderService.cancelOrder");

    FunctionInstrumentationSet processOrderSet =
        functionSets.get("com.example.OrderService.processOrder");
    assertThat(processOrderSet.hasMethodLevelInstrumentation()).isTrue();
    assertThat(processOrderSet.hasLineLevelBreakpoints()).isTrue();
  }

  @Test
  void testGetFunctionSets_NoConfigs() {
    Map<String, FunctionInstrumentationSet> functionSets =
        InstrumentationRegistry.getFunctionSets("com.example.OrderService");

    assertThat(functionSets).isEmpty();
  }

  @Test
  void testRegisterFromPrimitives() {
    String[] captureLocals = new String[] {"var1", "var2"};

    InstrumentationRegistry.registerFromPrimitives(
        "com.example.OrderService.processOrder:42",
        "test-location-hash",
        "com.example",
        "OrderService",
        "processOrder",
        42,
        "OrderService.java",
        "BREAKPOINT",
        true, // captureReturn
        false, // captureStackTrace
        new String[] {"arg1"},
        captureLocals,
        100, // maxStringLength
        10, // maxCollectionWidth
        3, // maxCollectionDepth
        2, // maxStackFrames
        200, // maxStackTraceSize
        3, // maxObjectDepth
        10, // maxFieldsPerObject
        100 // maxHits
        );

    InstrumentationConfiguration config =
        InstrumentationRegistry.get("com.example.OrderService.processOrder:42");

    assertThat(config).isNotNull();
    assertThat(config.getLineNumber()).isEqualTo(42);
    assertThat(config.getCaptureConfig()).isNotNull();
    assertThat(config.getCaptureConfig().isCaptureReturn()).isTrue();
    assertThat(config.getCaptureConfig().getCaptureLocals()).containsExactly("var1", "var2");
    assertThat(config.getCaptureConfig().getMaxStringLength()).isEqualTo(100);
  }

  @Test
  void testRegisterFromPrimitives_NullArrays() {
    InstrumentationRegistry.registerFromPrimitives(
        "com.example.OrderService.processOrder:0",
        "test-location-hash-2",
        "com.example",
        "OrderService",
        "processOrder",
        0,
        "OrderService.java",
        "PROBE",
        false,
        false,
        null, // null array
        null, // null array
        100,
        10,
        3,
        2,
        200,
        3,
        10,
        100);

    // Method-level (line=0) uses methodKey without ":0"
    InstrumentationConfiguration config =
        InstrumentationRegistry.get("com.example.OrderService.processOrder");

    assertThat(config).isNotNull();
    // null arrays passed to registerFromPrimitives preserve null (= do not capture)
    assertThat(config.getCaptureConfig().getCaptureArguments()).isNull();
    assertThat(config.getCaptureConfig().getCaptureLocals()).isNull();
  }

  @Test
  void testRegisterAndGetParameterNames() {
    String methodKey = "com.example.OrderService.processOrder";
    String[] names = {"orderId", "quantity", "currency"};

    InstrumentationRegistry.registerParameterNames(methodKey, names);

    String[] result = InstrumentationRegistry.getParameterNames(methodKey);
    assertThat(result).isNotNull();
    assertThat(result).containsExactly("orderId", "quantity", "currency");
  }

  @Test
  void testGetParameterNames_notFound() {
    assertThat(InstrumentationRegistry.getParameterNames("nonexistent")).isNull();
  }

  @Test
  void testRegisterParameterNamesFromPrimitives() {
    String methodKey = "com.example.OrderService.processOrder";
    InstrumentationRegistry.registerParameterNamesFromPrimitives(
        methodKey, "orderId,quantity,currency");

    String[] result = InstrumentationRegistry.getParameterNames(methodKey);
    assertThat(result).isNotNull();
    assertThat(result).containsExactly("orderId", "quantity", "currency");
  }

  @Test
  void testRegisterParameterNamesFromPrimitives_withEmptySegments() {
    String methodKey = "com.example.OrderService.processOrder";
    InstrumentationRegistry.registerParameterNamesFromPrimitives(methodKey, "orderId,,currency");

    String[] result = InstrumentationRegistry.getParameterNames(methodKey);
    assertThat(result).isNotNull();
    assertThat(result).containsExactly("orderId", "", "currency");
  }

  @Test
  void testRegisterParameterNamesFromPrimitives_nullString() {
    InstrumentationRegistry.registerParameterNamesFromPrimitives("key", null);
    assertThat(InstrumentationRegistry.getParameterNames("key")).isNull();
  }

  @Test
  void testRemove_cleansUpParameterNames() {
    String methodKey = "com.example.OrderService.processOrder";
    InstrumentationRegistry.registerParameterNames(methodKey, new String[] {"orderId"});

    InstrumentationConfiguration config =
        createConfig("com.example", "OrderService", "processOrder", 0, "PROBE");
    InstrumentationRegistry.register(methodKey, config);
    InstrumentationRegistry.remove(methodKey);

    assertThat(InstrumentationRegistry.getParameterNames(methodKey)).isNull();
  }

  @Test
  void testClearAll_cleansUpParameterNames() {
    InstrumentationRegistry.registerParameterNames("key1", new String[] {"a", "b"});
    InstrumentationRegistry.registerParameterNames("key2", new String[] {"x", "y"});

    InstrumentationRegistry.clearAll();

    assertThat(InstrumentationRegistry.getParameterNames("key1")).isNull();
    assertThat(InstrumentationRegistry.getParameterNames("key2")).isNull();
  }

  @Test
  void testRegisterSameKeyDifferentCreatedAt_ResetsState() {
    Instant t1 = Instant.parse("2024-01-01T10:00:00Z");
    Instant t2 = Instant.parse("2024-01-01T11:00:00Z");

    // Register config with createdAt=T1
    InstrumentationConfiguration config1 =
        createConfigWithCreatedAt(
            "com.example", "OrderService", "processOrder", 0, "BREAKPOINT", "hash1", t1);
    String key = config1.getMethodKey();
    InstrumentationRegistry.register(key, config1);

    // Record some hits to build up hitCount
    InstrumentationRegistry.recordHit(key);
    InstrumentationRegistry.recordHit(key);
    InstrumentationRegistry.recordHit(key);

    assertThat(InstrumentationRegistry.getState(key).getHitCount()).isEqualTo(3);

    // Register NEW config with SAME key, SAME locationHash, but DIFFERENT createdAt=T2
    InstrumentationConfiguration config2 =
        createConfigWithCreatedAt(
            "com.example", "OrderService", "processOrder", 0, "BREAKPOINT", "hash1", t2);
    InstrumentationRegistry.register(key, config2);

    // Assert state was reset (hit count back to 0)
    assertThat(InstrumentationRegistry.getState(key).getHitCount()).isEqualTo(0);
    assertThat(InstrumentationRegistry.getState(key).getCreatedAt()).isEqualTo(t2);
  }

  @Test
  void testRegisterSameKeyAndCreatedAt_PreservesState() {
    Instant t1 = Instant.parse("2024-01-01T10:00:00Z");

    // Register config with createdAt=T1
    InstrumentationConfiguration config1 =
        createConfigWithCreatedAt(
            "com.example", "OrderService", "processOrder", 0, "BREAKPOINT", "hash1", t1);
    String key = config1.getMethodKey();
    InstrumentationRegistry.register(key, config1);

    // Record some hits
    InstrumentationRegistry.recordHit(key);
    InstrumentationRegistry.recordHit(key);
    InstrumentationRegistry.recordHit(key);

    assertThat(InstrumentationRegistry.getState(key).getHitCount()).isEqualTo(3);

    // Register config with SAME key, SAME locationHash, SAME createdAt
    InstrumentationConfiguration config2 =
        createConfigWithCreatedAt(
            "com.example", "OrderService", "processOrder", 0, "BREAKPOINT", "hash1", t1);
    InstrumentationRegistry.register(key, config2);

    // Assert hitCount is preserved
    assertThat(InstrumentationRegistry.getState(key).getHitCount()).isEqualTo(3);
  }

  @Test
  void testRegisterSameKeyNullCreatedAt_FallsBackToLocationHash() {
    // Register config with locationHash="hash1", createdAt=null
    InstrumentationConfiguration config1 =
        createConfigWithCreatedAt(
            "com.example", "OrderService", "processOrder", 0, "BREAKPOINT", "hash1", null);
    String key = config1.getMethodKey();
    InstrumentationRegistry.register(key, config1);

    // Record some hits
    InstrumentationRegistry.recordHit(key);
    InstrumentationRegistry.recordHit(key);

    assertThat(InstrumentationRegistry.getState(key).getHitCount()).isEqualTo(2);

    // Register config with SAME key, SAME locationHash, createdAt=null
    InstrumentationConfiguration config2 =
        createConfigWithCreatedAt(
            "com.example", "OrderService", "processOrder", 0, "BREAKPOINT", "hash1", null);
    InstrumentationRegistry.register(key, config2);

    // Assert state is preserved (same locationHash)
    assertThat(InstrumentationRegistry.getState(key).getHitCount()).isEqualTo(2);

    // Register config with SAME key but DIFFERENT locationHash, createdAt=null
    InstrumentationConfiguration config3 =
        createConfigWithCreatedAt(
            "com.example", "OrderService", "processOrder", 0, "BREAKPOINT", "hash2", null);
    InstrumentationRegistry.register(key, config3);

    // Assert state is reset (different locationHash)
    assertThat(InstrumentationRegistry.getState(key).getHitCount()).isEqualTo(0);
    assertThat(InstrumentationRegistry.getState(key).getLocationHash()).isEqualTo("hash2");
  }

  private InstrumentationConfiguration createConfig(
      String codeUnit, String className, String methodName, int lineNumber, String type) {
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
    apiConfig.put("LocationHash", "test-hash");
    apiConfig.put("InstrumentationType", type);
    apiConfig.put("CaptureConfiguration", captureWrapper);

    if ("PROBE".equals(type)) {
      apiConfig.put("InstrumentationName", "test-probe");
    }

    return InstrumentationConfiguration.fromApiConfig(apiConfig);
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
