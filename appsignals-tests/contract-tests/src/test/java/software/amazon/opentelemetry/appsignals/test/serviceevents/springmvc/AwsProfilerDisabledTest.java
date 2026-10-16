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

package software.amazon.opentelemetry.appsignals.test.serviceevents.springmvc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.opentelemetry.appsignals.test.serviceevents.base.ServiceEventsContractTestBase;

/**
 * Profiler-OFF negative contract test: with {@code OTEL_AWS_PROFILER_ENABLED} unset (the default),
 * the agent must export NO native OTLP profiles even under request traffic.
 *
 * <p>Uses its own container (a separate class instance ⇒ a separate container from {@link
 * AwsProfilerContractTest}); ServiceEvents itself stays enabled via the base env so this isolates
 * the profiler-enablement flag. Assertions use the non-blocking snapshot getter {@link
 * software.amazon.opentelemetry.appsignals.test.utils.MockCollectorClient#getProfilesSnapshot()} —
 * a blocking getter would spuriously fail on its timeout, which is the wrong shape for a negative
 * assertion.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwsProfilerDisabledTest extends ServiceEventsContractTestBase {

  @Override
  protected String getApplicationImageName() {
    return "aws-serviceevents-tests-http-server-spring-mvc";
  }

  @Test
  void testProfilerDisabledProducesNoProfiles() throws Exception {
    // Drive the traffic that would normally feed the profiler.
    for (int i = 0; i < 10; i++) {
      sendRequest("cpu-work");
      sendRequest("success");
    }

    // An enabled profiler only exports after its first JFR rotation (~60-90s), so an empty profiles
    // snapshot alone cannot show it is off. The agent's own log can: it must report the profiler as
    // disabled and never load or start async-profiler.
    String logs = application.getLogs();
    assertThat(logs)
        .as("agent must report the profiler as disabled")
        .contains("Profiler is disabled via configuration");
    assertThat(logs)
        .as("async-profiler must not be loaded or started when the profiler is disabled")
        .doesNotContain("async-profiler loaded")
        .doesNotContain("Started async-profiler");

    assertThat(mockCollectorClient.getProfilesSnapshot())
        .as("no ExportProfilesServiceRequest should be captured when the profiler is disabled")
        .isEmpty();
  }
}
