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

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ServiceAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.config.DynamicInstrumentationConfig;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;

class DynamicInstrumentationClientTest {

  private DynamicInstrumentationConfig config;
  private DynamicInstrumentationClient client;

  @BeforeEach
  void setUp() {
    Resource resource =
        Resource.create(
            Attributes.builder()
                .put(ServiceAttributes.SERVICE_NAME, "test-service")
                .put(AttributeKey.stringKey("deployment.environment.name"), "staging")
                .build());

    config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    client = new DynamicInstrumentationClient(config);
  }

  @Test
  void testGetServiceName() {
    assertThat(client.getServiceName()).isEqualTo("test-service");
  }

  @Test
  void testGetEnvironment() {
    assertThat(client.getEnvironment()).isEqualTo("staging");
  }

  @Test
  void testGetConfig() {
    assertThat(client.getConfig()).isEqualTo(config);
  }

  @Test
  void testParseConfigurations_nullResponse() {
    List<InstrumentationConfiguration> configs = client.parseConfigurations(null);

    assertThat(configs).isEmpty();
  }

  @Test
  void testParseConfigurations_emptyList() {
    ApiResponse response = new ApiResponse(true, null, null, List.of());

    List<InstrumentationConfiguration> configs = client.parseConfigurations(response);

    assertThat(configs).isEmpty();
  }

  @Test
  void testParseConfigurations_validConfigs() {
    Map<String, Object> location1 = new HashMap<>();
    location1.put("Language", "Java");
    location1.put("CodeUnit", "com.example");
    location1.put("ClassName", "OrderService");
    location1.put("MethodName", "processOrder");
    location1.put("LineNumber", 42);

    Map<String, Object> locationWrapper1 = new HashMap<>();
    locationWrapper1.put("CodeLocation", location1);

    Map<String, Object> config1 = new HashMap<>();
    config1.put("Location", locationWrapper1);
    config1.put("LocationHash", "hash1");
    config1.put("InstrumentationType", "BREAKPOINT");
    Map<String, Object> captureWrapper1 = new HashMap<>();
    captureWrapper1.put("CodeCapture", Map.of());
    config1.put("CaptureConfiguration", captureWrapper1);

    ApiResponse response = new ApiResponse(true, null, null, List.of(config1));

    List<InstrumentationConfiguration> configs = client.parseConfigurations(response);

    assertThat(configs).hasSize(1);
    assertThat(configs.get(0).getClassName()).isEqualTo("OrderService");
    assertThat(configs.get(0).getMethodName()).isEqualTo("processOrder");
  }

  @Test
  void testParseConfigurations_filtersByLanguage() {
    Map<String, Object> pythonLocation = new HashMap<>();
    pythonLocation.put("Language", "Python");
    pythonLocation.put("CodeUnit", "main");
    pythonLocation.put("MethodName", "process");

    Map<String, Object> pythonLocationWrapper = new HashMap<>();
    pythonLocationWrapper.put("CodeLocation", pythonLocation);

    Map<String, Object> pythonConfig = new HashMap<>();
    pythonConfig.put("Location", pythonLocationWrapper);
    pythonConfig.put("LocationHash", "hash1");

    ApiResponse response = new ApiResponse(true, null, null, List.of(pythonConfig));

    List<InstrumentationConfiguration> configs = client.parseConfigurations(response);

    assertThat(configs).isEmpty(); // Python configs should be filtered out
  }

  @Test
  void testParseConfigurations_skipsInvalidConfigs() {
    Map<String, Object> invalidLocation = new HashMap<>();
    invalidLocation.put("Language", "Java");
    invalidLocation.put("CodeUnit", ""); // Invalid - empty
    invalidLocation.put("ClassName", "Test");
    invalidLocation.put("MethodName", "method");

    Map<String, Object> invalidLocationWrapper = new HashMap<>();
    invalidLocationWrapper.put("CodeLocation", invalidLocation);

    Map<String, Object> invalidConfig = new HashMap<>();
    invalidConfig.put("Location", invalidLocationWrapper);
    invalidConfig.put("LocationHash", "hash1");

    ApiResponse response = new ApiResponse(true, null, null, List.of(invalidConfig));

    List<InstrumentationConfiguration> configs = client.parseConfigurations(response);

    assertThat(configs).isEmpty(); // Invalid config should be filtered out
  }

  @Test
  void testParseConfigurations_attributeFilterMatching() {
    Resource resourceWithInstance =
        Resource.create(
            Attributes.builder()
                .put(ServiceAttributes.SERVICE_NAME, "test-service")
                .put(AttributeKey.stringKey("instance.id"), "i-1234567890")
                .build());

    DynamicInstrumentationConfig configWithResource =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resourceWithInstance)
            .build();

    DynamicInstrumentationClient clientWithResource =
        new DynamicInstrumentationClient(configWithResource);

    Map<String, Object> location = new HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", "com.example");
    location.put("ClassName", "Test");
    location.put("MethodName", "method");

    Map<String, Object> locationWrapperMatch = new HashMap<>();
    locationWrapperMatch.put("CodeLocation", location);

    Map<String, Object> configItem = new HashMap<>();
    configItem.put("Location", locationWrapperMatch);
    configItem.put("LocationHash", "hash1");
    configItem.put("InstrumentationType", "BREAKPOINT");
    Map<String, Object> captureWrapperMatch = new HashMap<>();
    captureWrapperMatch.put("CodeCapture", Map.of());
    configItem.put("CaptureConfiguration", captureWrapperMatch);

    // Config with matching filter
    configItem.put("AttributeFilters", List.of(Map.of("instance.id", "i-1234567890")));

    ApiResponse response = new ApiResponse(true, null, null, List.of(configItem));
    List<InstrumentationConfiguration> configs = clientWithResource.parseConfigurations(response);

    assertThat(configs).hasSize(1); // Should match
  }

  @Test
  void testParseConfigurations_attributeFilterNonMatching() {
    Map<String, Object> location = new HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", "com.example");
    location.put("ClassName", "Test");
    location.put("MethodName", "method");

    Map<String, Object> locationWrapperNonMatch = new HashMap<>();
    locationWrapperNonMatch.put("CodeLocation", location);

    Map<String, Object> configItem = new HashMap<>();
    configItem.put("Location", locationWrapperNonMatch);
    configItem.put("LocationHash", "hash1");
    configItem.put("InstrumentationType", "BREAKPOINT");
    Map<String, Object> captureWrapperNonMatch = new HashMap<>();
    captureWrapperNonMatch.put("CodeCapture", Map.of());
    configItem.put("CaptureConfiguration", captureWrapperNonMatch);

    // Config with non-matching filter
    configItem.put("AttributeFilters", List.of(Map.of("instance.id", "i-different")));

    ApiResponse response = new ApiResponse(true, null, null, List.of(configItem));
    List<InstrumentationConfiguration> configs = client.parseConfigurations(response);

    assertThat(configs).isEmpty(); // Should be filtered out
  }

  @Test
  void testParseConfigurations_emptyAttributeFiltersAllowAll() {
    Map<String, Object> location = new HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", "com.example");
    location.put("ClassName", "Test");
    location.put("MethodName", "method");

    Map<String, Object> locationWrapperEmpty = new HashMap<>();
    locationWrapperEmpty.put("CodeLocation", location);

    Map<String, Object> configItem = new HashMap<>();
    configItem.put("Location", locationWrapperEmpty);
    configItem.put("LocationHash", "hash1");
    configItem.put("InstrumentationType", "BREAKPOINT");
    Map<String, Object> captureWrapperEmpty = new HashMap<>();
    captureWrapperEmpty.put("CodeCapture", Map.of());
    configItem.put("CaptureConfiguration", captureWrapperEmpty);
    configItem.put("AttributeFilters", List.of()); // Empty filters

    ApiResponse response = new ApiResponse(true, null, null, List.of(configItem));
    List<InstrumentationConfiguration> configs = client.parseConfigurations(response);

    assertThat(configs).hasSize(1); // Should be included
  }

  @Test
  void testIsPolling_initiallyFalse() {
    assertThat(client.isPolling()).isFalse();
  }
}
