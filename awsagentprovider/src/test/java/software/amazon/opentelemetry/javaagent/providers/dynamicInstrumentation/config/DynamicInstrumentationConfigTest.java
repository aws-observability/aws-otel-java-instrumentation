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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ServiceAttributes;
import org.junit.jupiter.api.Test;

class DynamicInstrumentationConfigTest {

  @Test
  void testBuilder_defaults() {
    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    assertThat(config.isEnabled()).isFalse();
    assertThat(config.getApiUrl()).isEqualTo("http://localhost:2000");
    assertThat(config.getProbePollIntervalSeconds()).isEqualTo(600);
    assertThat(config.getBreakpointPollIntervalSeconds()).isEqualTo(60);
    assertThat(config.getHttpTimeoutSeconds()).isEqualTo(30);
    assertThat(config.getMaxRetries()).isEqualTo(3);
  }

  @Test
  void testBuilder_customValues() {
    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .enabled(true)
            .apiUrl("http://custom-api:8080")
            .probePollIntervalSeconds(300)
            .breakpointPollIntervalSeconds(30)
            .httpTimeoutSeconds(60)
            .maxRetries(5)
            .resource(resource)
            .build();

    assertThat(config.isEnabled()).isTrue();
    assertThat(config.getApiUrl()).isEqualTo("http://custom-api:8080");
    assertThat(config.getProbePollIntervalSeconds()).isEqualTo(300);
    assertThat(config.getBreakpointPollIntervalSeconds()).isEqualTo(30);
    assertThat(config.getHttpTimeoutSeconds()).isEqualTo(60);
    assertThat(config.getMaxRetries()).isEqualTo(5);
  }

  @Test
  void testGetServiceName_fromResource() {
    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "order-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    assertThat(config.getServiceName()).isEqualTo("order-service");
  }

  @Test
  void testGetServiceName_nullResourceReturnsUnknown() {
    Resource resource = Resource.empty();

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    assertThat(config.getServiceName()).isEqualTo("UnknownService");
  }

  @Test
  void testGetServiceName_unknownServicePrefixReturnsUnknown() {
    Resource resource =
        Resource.create(
            Attributes.builder()
                .put(ServiceAttributes.SERVICE_NAME, "unknown_service:test")
                .build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    assertThat(config.getServiceName()).isEqualTo("UnknownService");
  }

  @Test
  void testGetDeploymentEnvironment_fromResource() {
    Resource resource =
        Resource.create(
            Attributes.builder()
                .put(ServiceAttributes.SERVICE_NAME, "test-service")
                .put(AttributeKey.stringKey("deployment.environment.name"), "production")
                .build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    assertThat(config.getDeploymentEnvironment()).isEqualTo("production");
  }

  @Test
  void testGetDeploymentEnvironment_missingReturnsUnknown() {
    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    assertThat(config.getDeploymentEnvironment()).isEqualTo("UnknownEnvironment");
  }

  @Test
  void testBuilder_missingApiUrlThrowsException() {
    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    assertThatThrownBy(() -> DynamicInstrumentationConfig.builder().resource(resource).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("API URL must be specified");
  }

  @Test
  void testBuilder_emptyApiUrlThrowsException() {
    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    assertThatThrownBy(
            () -> DynamicInstrumentationConfig.builder().apiUrl("").resource(resource).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("API URL must be specified");
  }

  @Test
  void testBuilder_missingResourceThrowsException() {
    assertThatThrownBy(
            () -> DynamicInstrumentationConfig.builder().apiUrl("http://localhost:2000").build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Resource must be provided");
  }

  @Test
  void testBuilder_negativeProbePollIntervalThrowsException() {
    assertThatThrownBy(() -> DynamicInstrumentationConfig.builder().probePollIntervalSeconds(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Probe poll interval must be positive");
  }

  @Test
  void testBuilder_zeroBreakpointPollIntervalThrowsException() {
    assertThatThrownBy(
            () -> DynamicInstrumentationConfig.builder().breakpointPollIntervalSeconds(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Breakpoint poll interval must be positive");
  }

  @Test
  void testBuilder_negativeHttpTimeoutThrowsException() {
    assertThatThrownBy(() -> DynamicInstrumentationConfig.builder().httpTimeoutSeconds(-10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTP timeout must be positive");
  }

  @Test
  void testBuilder_negativeMaxRetriesThrowsException() {
    assertThatThrownBy(() -> DynamicInstrumentationConfig.builder().maxRetries(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Max retries must be non-negative");
  }

  @Test
  void testBuilder_zeroMaxRetriesIsValid() {
    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    DynamicInstrumentationConfig config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .maxRetries(0)
            .build();

    assertThat(config.getMaxRetries()).isEqualTo(0);
  }
}
