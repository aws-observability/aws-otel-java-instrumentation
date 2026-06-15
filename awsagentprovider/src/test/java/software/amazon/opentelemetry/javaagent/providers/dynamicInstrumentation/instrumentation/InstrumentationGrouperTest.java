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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationType;

class InstrumentationGrouperTest {

  @Test
  void testGroupByFunctionForClass_SingleConfig() {
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(createConfig("com.example", "OrderService", "processOrder", 0, "BREAKPOINT"));

    Map<String, FunctionInstrumentationSet> result =
        InstrumentationGrouper.groupByFunctionForClass(configs, "com.example.OrderService");

    assertThat(result).hasSize(1);
    assertThat(result).containsKey("com.example.OrderService.processOrder");

    FunctionInstrumentationSet functionSet = result.get("com.example.OrderService.processOrder");
    assertThat(functionSet.getMethodName()).isEqualTo("processOrder");
    assertThat(functionSet.hasMethodLevelInstrumentation()).isTrue();
  }

  @Test
  void testGroupByFunctionForClass_MultipleMethodsSameClass() {
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(createConfig("com.example", "OrderService", "processOrder", 0, "BREAKPOINT"));
    configs.add(createConfig("com.example", "OrderService", "cancelOrder", 0, "BREAKPOINT"));
    configs.add(createConfig("com.example", "OrderService", "refundOrder", 0, "PROBE"));

    Map<String, FunctionInstrumentationSet> result =
        InstrumentationGrouper.groupByFunctionForClass(configs, "com.example.OrderService");

    assertThat(result).hasSize(3);
    assertThat(result)
        .containsKeys(
            "com.example.OrderService.processOrder",
            "com.example.OrderService.cancelOrder",
            "com.example.OrderService.refundOrder");
  }

  @Test
  void testGroupByFunctionForClass_SameMethodMultipleConfigs() {
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(createConfig("com.example", "OrderService", "processOrder", 0, "BREAKPOINT"));
    configs.add(createConfig("com.example", "OrderService", "processOrder", 0, "PROBE"));
    configs.add(createConfig("com.example", "OrderService", "processOrder", 42, "BREAKPOINT"));
    configs.add(createConfig("com.example", "OrderService", "processOrder", 55, "BREAKPOINT"));

    Map<String, FunctionInstrumentationSet> result =
        InstrumentationGrouper.groupByFunctionForClass(configs, "com.example.OrderService");

    assertThat(result).hasSize(1);

    FunctionInstrumentationSet functionSet = result.get("com.example.OrderService.processOrder");
    assertThat(functionSet.hasMethodLevelInstrumentation()).isTrue();
    assertThat(functionSet.hasLineLevelBreakpoints()).isTrue();
    assertThat(functionSet.getLineNumbers()).containsExactlyInAnyOrder(42, 55);
    assertThat(functionSet.getMethodSpanConfig().getInstrumentationType().name())
        .isEqualTo("PROBE");
  }

  @Test
  void testGroupByFunctionForClass_EmptyList() {
    Map<String, FunctionInstrumentationSet> result =
        InstrumentationGrouper.groupByFunctionForClass(
            Collections.emptyList(), "com.example.OrderService");

    assertThat(result).isEmpty();
  }

  @Test
  void testOnlyGroupsForTargetClass() {
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(createConfig("com.example", "OrderService", "processOrder", 0, "BREAKPOINT"));
    configs.add(createConfig("com.example", "PaymentService", "processPayment", 0, "BREAKPOINT"));
    configs.add(createConfig("com.other", "OrderService", "processOrder", 0, "BREAKPOINT"));

    Map<String, FunctionInstrumentationSet> result =
        InstrumentationGrouper.groupByFunctionForClass(configs, "com.example.OrderService");

    assertThat(result).hasSize(1);
    assertThat(result).containsKey("com.example.OrderService.processOrder");
    assertThat(result).doesNotContainKey("com.example.PaymentService.processPayment");
  }

  @Test
  void testProbeBreakpointConflict_ProbeWins() {
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(createConfig("com.example", "OrderService", "processOrder", 0, "BREAKPOINT"));
    configs.add(createConfig("com.example", "OrderService", "processOrder", 0, "PROBE"));

    Map<String, FunctionInstrumentationSet> result =
        InstrumentationGrouper.groupByFunctionForClass(configs, "com.example.OrderService");

    assertThat(result).hasSize(1);

    FunctionInstrumentationSet functionSet = result.get("com.example.OrderService.processOrder");
    assertThat(functionSet.getMethodSpanConfig().getInstrumentationType())
        .isEqualTo(InstrumentationType.PROBE);
  }

  @Test
  void testMultipleLineLevelsNoConflict() {
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(createConfig("com.example", "OrderService", "processOrder", 42, "BREAKPOINT"));
    configs.add(createConfig("com.example", "OrderService", "processOrder", 55, "BREAKPOINT"));
    configs.add(createConfig("com.example", "OrderService", "processOrder", 67, "BREAKPOINT"));

    Map<String, FunctionInstrumentationSet> result =
        InstrumentationGrouper.groupByFunctionForClass(configs, "com.example.OrderService");

    assertThat(result).hasSize(1);

    FunctionInstrumentationSet functionSet = result.get("com.example.OrderService.processOrder");
    assertThat(functionSet.getLineNumbers()).containsExactlyInAnyOrder(42, 55, 67);
    assertThat(functionSet.hasLineLevelBreakpoints()).isTrue();
    assertThat(functionSet.hasMethodLevelInstrumentation()).isFalse();
  }

  @Test
  void testFunctionKeyFormat() {
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(
        createConfig("com.example.orders", "OrderService", "processOrder", 0, "BREAKPOINT"));

    Map<String, FunctionInstrumentationSet> result =
        InstrumentationGrouper.groupByFunctionForClass(configs, "com.example.orders.OrderService");

    assertThat(result).containsKey("com.example.orders.OrderService.processOrder");
  }

  @Test
  void testFullyQualifiedClassNameMatching() {
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(createConfig("com.example.orders", "OrderService", "process", 0, "BREAKPOINT"));
    configs.add(createConfig("com.example.payments", "OrderService", "process", 0, "BREAKPOINT"));

    Map<String, FunctionInstrumentationSet> result =
        InstrumentationGrouper.groupByFunctionForClass(configs, "com.example.orders.OrderService");

    assertThat(result).hasSize(1);
    assertThat(result).containsKey("com.example.orders.OrderService.process");
  }

  @Test
  void testGroupByFunctionForClass_DifferentMethodsSameClass() {
    List<InstrumentationConfiguration> configs = new ArrayList<>();
    configs.add(createConfig("com.example", "OrderService", "create", 0, "PROBE"));
    configs.add(createConfig("com.example", "OrderService", "update", 0, "BREAKPOINT"));
    configs.add(createConfig("com.example", "OrderService", "delete", 42, "BREAKPOINT"));

    Map<String, FunctionInstrumentationSet> result =
        InstrumentationGrouper.groupByFunctionForClass(configs, "com.example.OrderService");

    assertThat(result).hasSize(3);
    assertThat(
            result
                .get("com.example.OrderService.create")
                .getMethodSpanConfig()
                .getInstrumentationType())
        .isEqualTo(InstrumentationType.PROBE);
    assertThat(result.get("com.example.OrderService.update").hasMethodLevelInstrumentation())
        .isTrue();
    assertThat(result.get("com.example.OrderService.delete").hasLineLevelBreakpoints()).isTrue();
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
    }

    return InstrumentationConfiguration.fromApiConfig(apiConfig);
  }
}
