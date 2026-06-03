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

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ServiceAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.config.DynamicInstrumentationConfig;

class ConfigurationPollerTest {

  private DynamicInstrumentationConfig config;
  private DynamicInstrumentationClient client;

  @BeforeEach
  void setUp() {
    Resource resource =
        Resource.create(
            Attributes.builder().put(ServiceAttributes.SERVICE_NAME, "test-service").build());

    config =
        DynamicInstrumentationConfig.builder()
            .apiUrl("http://localhost:2000")
            .resource(resource)
            .build();

    client = new DynamicInstrumentationClient(config);
  }

  @Test
  void testConstruction() {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    assertThat(poller).isNotNull();
    assertThat(poller.isRunning()).isFalse();
  }

  @Test
  void testStart() throws InterruptedException {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    assertThat(poller.isRunning()).isFalse();

    poller.start();

    // Give threads a moment to start
    Thread.sleep(100);

    assertThat(poller.isRunning()).isTrue();

    poller.stop();
  }

  @Test
  void testStop() throws InterruptedException {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    poller.start();
    Thread.sleep(100);

    assertThat(poller.isRunning()).isTrue();

    poller.stop();

    assertThat(poller.isRunning()).isFalse();
  }

  @Test
  void testStartWhenAlreadyRunning() throws InterruptedException {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    poller.start();
    Thread.sleep(100);

    // Try to start again - should be a no-op
    poller.start();

    assertThat(poller.isRunning()).isTrue();

    poller.stop();
  }

  @Test
  void testStopWhenNotRunning() {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    // Try to stop when not running - should be a no-op
    poller.stop();

    assertThat(poller.isRunning()).isFalse();
  }

  @Test
  void testStartAndStopMultipleTimes() throws InterruptedException {
    ConfigurationPoller poller = new ConfigurationPoller(client);

    // First cycle
    poller.start();
    Thread.sleep(100);
    assertThat(poller.isRunning()).isTrue();
    poller.stop();
    assertThat(poller.isRunning()).isFalse();

    // Wait a bit
    Thread.sleep(100);

    // Second cycle
    poller.start();
    Thread.sleep(100);
    assertThat(poller.isRunning()).isTrue();
    poller.stop();
    assertThat(poller.isRunning()).isFalse();
  }
}
