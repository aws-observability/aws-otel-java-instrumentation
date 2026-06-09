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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;

class FunctionInstrumentationSetTest {

  @Test
  void testConstructor() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");

    assertThat(functionSet.getClassName()).isEqualTo("com.example.OrderService");
    assertThat(functionSet.getMethodName()).isEqualTo("processOrder");
    assertThat(functionSet.getFunctionKey()).isEqualTo("com.example.OrderService.processOrder");
  }

  @Test
  void testAddMethodLevelProbe() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    InstrumentationConfiguration probe = createConfig("PROBE", 0);

    functionSet.addConfiguration(probe);

    assertThat(functionSet.getMethodLevelProbe()).isEqualTo(probe);
    assertThat(functionSet.getMethodLevelBreakpoint()).isNull();
    assertThat(functionSet.hasMethodLevelInstrumentation()).isTrue();
  }

  @Test
  void testAddMethodLevelBreakpoint() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    InstrumentationConfiguration breakpoint = createConfig("BREAKPOINT", 0);

    functionSet.addConfiguration(breakpoint);

    assertThat(functionSet.getMethodLevelBreakpoint()).isEqualTo(breakpoint);
    assertThat(functionSet.getMethodLevelProbe()).isNull();
    assertThat(functionSet.hasMethodLevelInstrumentation()).isTrue();
  }

  @Test
  void testProbeOverridesBreakpointAtLine0() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    InstrumentationConfiguration breakpoint = createConfig("BREAKPOINT", 0);
    InstrumentationConfiguration probe = createConfig("PROBE", 0);

    functionSet.addConfiguration(breakpoint);
    functionSet.addConfiguration(probe);

    assertThat(functionSet.getMethodLevelProbe()).isEqualTo(probe);
    assertThat(functionSet.getMethodLevelBreakpoint()).isEqualTo(breakpoint);
    assertThat(functionSet.getMethodSpanConfig()).isEqualTo(probe); // PROBE has priority
  }

  @Test
  void testGetMethodSpanConfig_ProbeExists() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    InstrumentationConfiguration probe = createConfig("PROBE", 0);

    functionSet.addConfiguration(probe);

    assertThat(functionSet.getMethodSpanConfig()).isEqualTo(probe);
  }

  @Test
  void testGetMethodSpanConfig_OnlyBreakpoint() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    InstrumentationConfiguration breakpoint = createConfig("BREAKPOINT", 0);

    functionSet.addConfiguration(breakpoint);

    assertThat(functionSet.getMethodSpanConfig()).isEqualTo(breakpoint);
  }

  @Test
  void testGetMethodSpanConfig_OnlyLineLevelReturnsNull() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    InstrumentationConfiguration lineBreakpoint = createConfig("BREAKPOINT", 42);

    functionSet.addConfiguration(lineBreakpoint);

    assertThat(functionSet.getMethodSpanConfig()).isNull();
  }

  @Test
  void testAddLineLevelBreakpoint() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    InstrumentationConfiguration lineBreakpoint = createConfig("BREAKPOINT", 42);

    functionSet.addConfiguration(lineBreakpoint);

    assertThat(functionSet.getLineLevelBreakpoints()).containsEntry(42, lineBreakpoint);
    assertThat(functionSet.hasLineLevelBreakpoints()).isTrue();
    assertThat(functionSet.getLineNumbers()).containsExactly(42);
  }

  @Test
  void testAddMultipleLineLevelBreakpoints() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    InstrumentationConfiguration line42 = createConfig("BREAKPOINT", 42);
    InstrumentationConfiguration line55 = createConfig("BREAKPOINT", 55);
    InstrumentationConfiguration line67 = createConfig("BREAKPOINT", 67);

    functionSet.addConfiguration(line42);
    functionSet.addConfiguration(line55);
    functionSet.addConfiguration(line67);

    assertThat(functionSet.getLineLevelBreakpoints()).hasSize(3);
    assertThat(functionSet.getLineNumbers()).containsExactlyInAnyOrder(42, 55, 67);
    assertThat(functionSet.hasLineLevelBreakpoints()).isTrue();
  }

  @Test
  void testNeedsMethodWrapper_MethodLevelOnly() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfig("BREAKPOINT", 0));

    assertThat(functionSet.needsMethodWrapper()).isTrue();
  }

  @Test
  void testNeedsMethodWrapper_LineLevelOnly() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfig("BREAKPOINT", 42));

    assertThat(functionSet.needsMethodWrapper()).isTrue();
  }

  @Test
  void testNeedsMethodWrapper_BothTypes() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfig("BREAKPOINT", 0));
    functionSet.addConfiguration(createConfig("BREAKPOINT", 42));

    assertThat(functionSet.needsMethodWrapper()).isTrue();
  }

  @Test
  void testNeedsMethodWrapper_EmptySet() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");

    assertThat(functionSet.needsMethodWrapper()).isFalse();
  }

  @Test
  void testHasMethodLevelInstrumentation_Probe() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfig("PROBE", 0));

    assertThat(functionSet.hasMethodLevelInstrumentation()).isTrue();
  }

  @Test
  void testHasMethodLevelInstrumentation_Breakpoint() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfig("BREAKPOINT", 0));

    assertThat(functionSet.hasMethodLevelInstrumentation()).isTrue();
  }

  @Test
  void testHasMethodLevelInstrumentation_OnlyLineLevel() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfig("BREAKPOINT", 42));

    assertThat(functionSet.hasMethodLevelInstrumentation()).isFalse();
  }

  @Test
  void testHasLineLevelBreakpoints_True() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfig("BREAKPOINT", 42));

    assertThat(functionSet.hasLineLevelBreakpoints()).isTrue();
  }

  @Test
  void testHasLineLevelBreakpoints_False() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfig("BREAKPOINT", 0));

    assertThat(functionSet.hasLineLevelBreakpoints()).isFalse();
  }

  @Test
  void testMixedMethodAndLineLevel() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    InstrumentationConfiguration methodProbe = createConfig("PROBE", 0);
    InstrumentationConfiguration line42 = createConfig("BREAKPOINT", 42);
    InstrumentationConfiguration line55 = createConfig("BREAKPOINT", 55);

    functionSet.addConfiguration(methodProbe);
    functionSet.addConfiguration(line42);
    functionSet.addConfiguration(line55);

    assertThat(functionSet.hasMethodLevelInstrumentation()).isTrue();
    assertThat(functionSet.hasLineLevelBreakpoints()).isTrue();
    assertThat(functionSet.getMethodSpanConfig()).isEqualTo(methodProbe);
    assertThat(functionSet.getLineNumbers()).containsExactlyInAnyOrder(42, 55);
    assertThat(functionSet.needsMethodWrapper()).isTrue();
  }

  @Test
  void testMultipleConfigsSameLineOverwrite() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    InstrumentationConfiguration line42First = createConfig("BREAKPOINT", 42);
    InstrumentationConfiguration line42Second = createConfig("BREAKPOINT", 42);

    functionSet.addConfiguration(line42First);
    functionSet.addConfiguration(line42Second);

    assertThat(functionSet.getLineLevelBreakpoints()).hasSize(1);
    assertThat(functionSet.getLineLevelBreakpoints().get(42)).isEqualTo(line42Second);
  }

  @Test
  void testGetLineNumbers_Empty() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");

    assertThat(functionSet.getLineNumbers()).isEmpty();
  }

  @Test
  void testGetLineLevelBreakpoints_Empty() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");

    assertThat(functionSet.getLineLevelBreakpoints()).isEmpty();
  }

  @Test
  void testNeedsDataCapture_MethodLevelWithCaptureReturn() {
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfigWithCaptureReturn("BREAKPOINT", 0, true));

    assertThat(functionSet.needsDataCapture()).isTrue();
  }

  @Test
  void testNeedsDataCapture_LineLevelWithCaptureReturn_DoesNotTrigger() {
    // Line-level configs do not trigger MethodCaptureAdvice — they use LineCaptureAdvice instead
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfigWithCaptureReturn("BREAKPOINT", 42, true));

    assertThat(functionSet.needsDataCapture()).isFalse();
  }

  @Test
  void testNeedsDataCapture_MethodLevelNoCaptureReturn() {
    // Method-level config always triggers MethodCaptureAdvice (for argument capture)
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfigWithCaptureReturn("BREAKPOINT", 0, false));

    assertThat(functionSet.needsDataCapture()).isTrue();
  }

  @Test
  void testNeedsDataCapture_OnlyLineLevelNoCaptureReturn() {
    // Line-level only — no method-level config means no MethodCaptureAdvice
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfigWithCaptureReturn("BREAKPOINT", 42, false));

    assertThat(functionSet.needsDataCapture()).isFalse();
  }

  @Test
  void testNeedsDataCapture_MixedConfigs_MethodLevelAlwaysTriggers() {
    // Method-level config triggers needsDataCapture regardless of captureReturn
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");
    functionSet.addConfiguration(createConfigWithCaptureReturn("BREAKPOINT", 0, false));
    functionSet.addConfiguration(
        createConfigWithCaptureReturn("BREAKPOINT", 42, true)); // Line-level — ignored

    assertThat(functionSet.needsDataCapture()).isTrue();
  }

  private InstrumentationConfiguration createConfigWithCaptureReturn(
      String type, int lineNumber, boolean captureReturn) {
    Map<String, Object> location = new HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", "com.example");
    location.put("ClassName", "OrderService");
    location.put("MethodName", "processOrder");
    location.put("LineNumber", lineNumber);
    location.put("FilePath", "OrderService.java");

    Map<String, Object> captureConfig = new HashMap<>();
    captureConfig.put("CaptureReturn", captureReturn);

    Map<String, Object> locationWrapper = new HashMap<>();
    locationWrapper.put("CodeLocation", location);

    Map<String, Object> captureWrapper = new HashMap<>();
    captureWrapper.put("CodeCapture", captureConfig);

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

  private InstrumentationConfiguration createConfig(String type, int lineNumber) {
    Map<String, Object> location = new HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", "com.example");
    location.put("ClassName", "OrderService");
    location.put("MethodName", "processOrder");
    location.put("LineNumber", lineNumber);
    location.put("FilePath", "OrderService.java");

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

  @Test
  void testNeedsDataCapture_LineLevelStackTraceOnly_DoesNotTrigger() {
    // Line-level stack trace config does not trigger MethodCaptureAdvice
    FunctionInstrumentationSet functionSet =
        new FunctionInstrumentationSet("com.example.OrderService", "processOrder");

    Map<String, Object> captureConfig = new HashMap<>();
    captureConfig.put("CaptureReturn", false);
    captureConfig.put("CaptureStackTrace", true);

    functionSet.addConfiguration(createConfigWithCapture("BREAKPOINT", 42, captureConfig));

    assertThat(functionSet.needsDataCapture()).isFalse();
  }

  private InstrumentationConfiguration createConfigWithCapture(
      String type, int lineNumber, Map<String, Object> captureSettings) {
    Map<String, Object> location = new HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", "com.example");
    location.put("ClassName", "OrderService");
    location.put("MethodName", "processOrder");
    location.put("LineNumber", lineNumber);
    location.put("FilePath", "OrderService.java");

    Map<String, Object> locationWrapper = new HashMap<>();
    locationWrapper.put("CodeLocation", location);

    Map<String, Object> captureWrapper = new HashMap<>();
    captureWrapper.put("CodeCapture", captureSettings);

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
}
