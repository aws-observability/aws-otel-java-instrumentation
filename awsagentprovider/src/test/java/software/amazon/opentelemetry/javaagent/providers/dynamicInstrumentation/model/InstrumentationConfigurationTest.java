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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InstrumentationConfigurationTest {

  @Test
  void testFromApiConfig_validBreakpointWithLineNumber() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 42);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getCodeUnit()).isEqualTo("com.example");
    assertThat(config.getClassName()).isEqualTo("OrderService");
    assertThat(config.getMethodName()).isEqualTo("processOrder");
    assertThat(config.getLineNumber()).isEqualTo(42);
    assertThat(config.getInstrumentationType()).isEqualTo(InstrumentationType.BREAKPOINT);
    assertThat(config.isLineLevel()).isTrue();
    assertThat(config.isMethodLevel()).isFalse();
    assertThat(config.isTemporary()).isTrue();
    assertThat(config.isPermanent()).isFalse();
  }

  @Test
  void testFromApiConfig_validBreakpointMethodLevel() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getLineNumber()).isEqualTo(0);
    assertThat(config.isLineLevel()).isFalse();
    assertThat(config.isMethodLevel()).isTrue();
  }

  @Test
  void testFromApiConfig_validProbe() {
    Map<String, Object> apiConfig =
        createValidApiConfig("PROBE", "com.example", "OrderService", "processOrder", 0);
    apiConfig.put("InstrumentationName", "order-processing-probe");

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getInstrumentationType()).isEqualTo(InstrumentationType.PROBE);
    assertThat(config.getInstrumentationName()).isEqualTo("order-processing-probe");
    assertThat(config.isPermanent()).isTrue();
    assertThat(config.isTemporary()).isFalse();
  }

  @Test
  void testFromApiConfig_probeWithLineNumberForcedToZero() {
    Map<String, Object> apiConfig =
        createValidApiConfig("PROBE", "com.example", "OrderService", "processOrder", 42);
    apiConfig.put("InstrumentationName", "test-probe");

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getLineNumber()).isEqualTo(0);
    assertThat(config.isMethodLevel()).isTrue();
  }

  @Test
  void testFromApiConfig_probeMissingInstrumentationName() {
    Map<String, Object> apiConfig =
        createValidApiConfig("PROBE", "com.example", "OrderService", "processOrder", 0);
    // No InstrumentationName set

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    // InstrumentationName is now optional - should return valid config with empty name
    assertThat(config).isNotNull();
    assertThat(config.getInstrumentationType()).isEqualTo(InstrumentationType.PROBE);
    assertThat(config.getInstrumentationName()).isEmpty();
  }

  @Test
  void testFromApiConfig_missingLocation() {
    Map<String, Object> apiConfig = new HashMap<>();
    apiConfig.put("InstrumentationType", "BREAKPOINT");
    apiConfig.put("LocationHash", "abc123");

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNull();
  }

  @Test
  void testFromApiConfig_wrongLanguage() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);
    Map<String, Object> locationWrapper = (Map<String, Object>) apiConfig.get("Location");
    Map<String, Object> location = (Map<String, Object>) locationWrapper.get("CodeLocation");
    location.put("Language", "Python");

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNull();
  }

  @Test
  void testFromApiConfig_missingCodeUnit() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "", "OrderService", "processOrder", 0);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNull();
  }

  @Test
  void testFromApiConfig_missingClassName() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "", "processOrder", 0);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNull();
  }

  @Test
  void testFromApiConfig_missingMethodName() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "", 0);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNull();
  }

  @Test
  void testFromApiConfig_negativeLineNumber() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", -1);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNull();
  }

  @Test
  void testFromApiConfig_defaultToBreakpointWhenTypeNotSpecified() {
    Map<String, Object> apiConfig =
        createValidApiConfig(null, "com.example", "OrderService", "processOrder", 0);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getInstrumentationType()).isEqualTo(InstrumentationType.BREAKPOINT);
  }

  @Test
  void testFromApiConfig_invalidInstrumentationTypeDefaultsToBreakpoint() {
    Map<String, Object> apiConfig =
        createValidApiConfig("INVALID_TYPE", "com.example", "OrderService", "processOrder", 0);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getInstrumentationType()).isEqualTo(InstrumentationType.BREAKPOINT);
  }

  @Test
  void testFromApiConfig_parseExpiryTimestampAsUnixSeconds() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);
    apiConfig.put("ExpiresAt", 1706035011L);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getExpiresAt()).isEqualTo(Instant.ofEpochSecond(1706035011L));
  }

  @Test
  void testFromApiConfig_parseExpiryTimestampAsIso8601String() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);
    apiConfig.put("ExpiresAt", "2026-01-23T10:36:51Z");

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getExpiresAt()).isEqualTo(Instant.parse("2026-01-23T10:36:51Z"));
  }

  @Test
  void testFromApiConfig_probeIgnoresExpiresAt() {
    Map<String, Object> apiConfig =
        createValidApiConfig("PROBE", "com.example", "OrderService", "processOrder", 0);
    apiConfig.put("InstrumentationName", "test-probe");
    apiConfig.put("ExpiresAt", "2026-01-23T10:36:51Z");

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getExpiresAt()).isNull();
  }

  @Test
  void testFromApiConfig_parseCaptureConfiguration() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);

    Map<String, Object> captureConfig = new HashMap<>();
    captureConfig.put("CaptureReturn", true);
    captureConfig.put("CaptureStackTrace", true);
    captureConfig.put("CaptureArguments", List.of("arg1", "arg2"));

    Map<String, Object> captureLimits = new HashMap<>();
    captureLimits.put("MaxStringLength", 200);
    captureLimits.put("MaxHits", 50);
    captureConfig.put("CaptureLimits", captureLimits);

    Map<String, Object> captureWrapper = new HashMap<>();
    captureWrapper.put("CodeCapture", captureConfig);
    apiConfig.put("CaptureConfiguration", captureWrapper);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getCaptureConfig().isCaptureReturn()).isTrue();
    assertThat(config.getCaptureConfig().isCaptureStackTrace()).isTrue();
    assertThat(config.getCaptureConfig().getCaptureArguments()).containsExactly("arg1", "arg2");
    assertThat(config.getCaptureConfig().getMaxStringLength()).isEqualTo(200);
    assertThat(config.getMaxHits()).isEqualTo(50);
  }

  @Test
  void testFromApiConfig_parseAttributeFilters() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);

    List<Map<String, String>> filters =
        List.of(
            Map.of("instance.id", "i-1234567890abcdef0"),
            Map.of("deployment.environment", "production"));
    apiConfig.put("AttributeFilters", filters);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getAttributeFilters()).hasSize(2);
    assertThat(config.getAttributeFilters().get(0))
        .containsEntry("instance.id", "i-1234567890abcdef0");
  }

  @Test
  void testGetMethodKey() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example.orders", "OrderService", "processOrder", 0);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getMethodKey()).isEqualTo("com.example.orders.OrderService.processOrder");
  }

  @Test
  void testGetInstrumentationKey() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 42);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getInstrumentationKey())
        .isEqualTo("com.example.OrderService.processOrder:42");
  }

  @Test
  void testGetFullyQualifiedClassName() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example.orders", "OrderService", "processOrder", 0);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getFullyQualifiedClassName()).isEqualTo("com.example.orders.OrderService");
  }

  @Test
  void testToString() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 42);
    apiConfig.put("LocationHash", "abc123xyz");

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    String str = config.toString();
    assertThat(str).contains("BREAKPOINT");
    assertThat(str).contains("com.example.OrderService.processOrder");
    assertThat(str).contains("42");
    assertThat(str).contains("abc123xyz");
  }

  @Test
  void testFromApiConfig_maxHitsClampedToValidRange() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);

    Map<String, Object> captureConfig = new HashMap<>();
    Map<String, Object> captureLimits = new HashMap<>();
    captureLimits.put("MaxHits", 5000); // Above max
    captureConfig.put("CaptureLimits", captureLimits);
    Map<String, Object> captureWrapper2 = new HashMap<>();
    captureWrapper2.put("CodeCapture", captureConfig);
    apiConfig.put("CaptureConfiguration", captureWrapper2);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getMaxHits()).isEqualTo(1000); // Clamped to MAX_MAX_HITS
  }

  @Test
  void testFromApiConfig_probeIgnoresMaxHits() {
    Map<String, Object> apiConfig =
        createValidApiConfig("PROBE", "com.example", "OrderService", "processOrder", 0);
    apiConfig.put("InstrumentationName", "test-probe");

    Map<String, Object> captureConfig = new HashMap<>();
    Map<String, Object> captureLimits = new HashMap<>();
    captureLimits.put("MaxHits", 50);
    captureConfig.put("CaptureLimits", captureLimits);
    Map<String, Object> captureWrapper3 = new HashMap<>();
    captureWrapper3.put("CodeCapture", captureConfig);
    apiConfig.put("CaptureConfiguration", captureWrapper3);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getMaxHits()).isEqualTo(Integer.MAX_VALUE); // PROBE configs are unlimited
  }

  @Test
  void testFromApiConfig_missingLineNumberDefaultsToZero() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", null);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getLineNumber()).isEqualTo(0);
    assertThat(config.isMethodLevel()).isTrue();
  }

  @Test
  void testFromApiConfig_parseTimestampWithInvalidFormat() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);
    apiConfig.put("ExpiresAt", "invalid-timestamp");

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getExpiresAt()).isNull();
  }

  @Test
  void testFromApiConfig_attributeFiltersNullDefaultsToEmptyList() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);
    // AttributeFilters not set

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getAttributeFilters()).isEmpty();
  }

  @Test
  void testFromApiConfig_missingCaptureArgumentsAndLocalsAreNull() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);

    // CaptureConfiguration with CodeCapture that has no CaptureArguments or CaptureLocals
    Map<String, Object> captureConfig = new HashMap<>();
    captureConfig.put("CaptureReturn", true);
    Map<String, Object> captureWrapper = new HashMap<>();
    captureWrapper.put("CodeCapture", captureConfig);
    apiConfig.put("CaptureConfiguration", captureWrapper);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getCaptureConfig().getCaptureArguments()).isNull();
    assertThat(config.getCaptureConfig().getCaptureLocals()).isNull();
  }

  @Test
  void testFromApiConfig_emptyCaptureArgumentsAndLocalsMeanCaptureAll() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);

    Map<String, Object> captureConfig = new HashMap<>();
    captureConfig.put("CaptureArguments", List.of());
    captureConfig.put("CaptureLocals", List.of());
    Map<String, Object> captureWrapper = new HashMap<>();
    captureWrapper.put("CodeCapture", captureConfig);
    apiConfig.put("CaptureConfiguration", captureWrapper);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getCaptureConfig().getCaptureArguments()).isNotNull();
    assertThat(config.getCaptureConfig().getCaptureArguments()).isEmpty();
    assertThat(config.getCaptureConfig().getCaptureLocals()).isNotNull();
    assertThat(config.getCaptureConfig().getCaptureLocals()).isEmpty();
  }

  @Test
  void testFromApiConfig_invalidTypeCaptureArgumentsTreatedAsEmptyList() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);

    Map<String, Object> captureConfig = new HashMap<>();
    captureConfig.put("CaptureArguments", "not-a-list");
    captureConfig.put("CaptureLocals", 123);
    Map<String, Object> captureWrapper = new HashMap<>();
    captureWrapper.put("CodeCapture", captureConfig);
    apiConfig.put("CaptureConfiguration", captureWrapper);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    // Invalid types are treated as present-but-empty (capture all)
    assertThat(config.getCaptureConfig().getCaptureArguments()).isNotNull();
    assertThat(config.getCaptureConfig().getCaptureArguments()).isEmpty();
    assertThat(config.getCaptureConfig().getCaptureLocals()).isNotNull();
    assertThat(config.getCaptureConfig().getCaptureLocals()).isEmpty();
  }

  @Test
  void testFromApiConfig_mixedMissingAndPresentCaptureFields() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);

    // CaptureArguments missing, CaptureLocals present as empty
    Map<String, Object> captureConfig = new HashMap<>();
    captureConfig.put("CaptureLocals", List.of());
    Map<String, Object> captureWrapper = new HashMap<>();
    captureWrapper.put("CodeCapture", captureConfig);
    apiConfig.put("CaptureConfiguration", captureWrapper);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getCaptureConfig().getCaptureArguments()).isNull(); // missing = no capture
    assertThat(config.getCaptureConfig().getCaptureLocals()).isNotNull(); // present = capture all
    assertThat(config.getCaptureConfig().getCaptureLocals()).isEmpty();
  }

  @Test
  void testFromApiConfig_emptyCaptureConfigurationUsesDefaults() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 0);
    // No CaptureConfiguration set

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.getCaptureConfig()).isNotNull();
    assertThat(config.getCaptureConfig().isCaptureReturn()).isFalse();
    assertThat(config.getCaptureConfig().isCaptureStackTrace()).isFalse();
    assertThat(config.getCaptureConfig().getMaxStringLength())
        .isEqualTo(CaptureConfiguration.DEFAULT_MAX_STRING_LENGTH);
  }

  @Test
  void testIsValid() {
    Map<String, Object> apiConfig =
        createValidApiConfig("BREAKPOINT", "com.example", "OrderService", "processOrder", 42);

    InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(apiConfig);

    assertThat(config).isNotNull();
    assertThat(config.isValid()).isTrue();
  }

  private Map<String, Object> createValidApiConfig(
      String instrumentationType,
      String codeUnit,
      String className,
      String methodName,
      Integer lineNumber) {
    Map<String, Object> location = new HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", codeUnit);
    location.put("ClassName", className);
    location.put("MethodName", methodName);
    if (lineNumber != null) {
      location.put("LineNumber", lineNumber);
    }
    location.put("FilePath", "OrderService.java");

    Map<String, Object> locationWrapper = new HashMap<>();
    locationWrapper.put("CodeLocation", location);

    Map<String, Object> apiConfig = new HashMap<>();
    apiConfig.put("Location", locationWrapper);
    apiConfig.put("LocationHash", "test-hash-123");
    if (instrumentationType != null) {
      apiConfig.put("InstrumentationType", instrumentationType);
    }
    Map<String, Object> captureWrapper = new HashMap<>();
    captureWrapper.put("CodeCapture", Collections.emptyMap());
    apiConfig.put("CaptureConfiguration", captureWrapper);

    return apiConfig;
  }
}
