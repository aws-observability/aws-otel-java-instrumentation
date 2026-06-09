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

package software.amazon.opentelemetry.appsignals.test.di;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * End-to-end wiring smoke test for instrumentation status reporting: verifies that READY, ACTIVE,
 * and DISABLED statuses actually reach the control plane via the
 * /report-instrumentation-configuration-status API.
 *
 * <p>Target: {@code InstrumentedService.limitedFunction()} — a BREAKPOINT with MaxHits=3,
 * locationHash {@code aabb000000000004}, endpoint GET /limited. Driving it past its cap lets us
 * observe the DISABLED transition end-to-end.
 *
 * <p>The exhaustive, deterministic lifecycle assertions live in the fast in-process {@code
 * StatusReporterTest}. This test only confirms the full pipeline is connected: hits recorded on the
 * bootstrap-classloader store flow through the reporter and arrive at the control plane. Because
 * the report interval is 60s (not test-configurable by design), periodic ACTIVE/DISABLED statuses
 * are awaited with a generous timeout.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DIStatusReportingTest extends DIContractTestBase {

  private static final String LIMITED_LOCATION_HASH = "aabb000000000004";
  private static final int MAX_HITS = 3;

  /** Periodic reports run every 60s; allow ample margin for one full period plus jitter. */
  private static final Duration PERIODIC_TIMEOUT = Duration.ofSeconds(90);

  @Test
  @Order(1)
  void reportsReadyAfterConfigApplied() throws Exception {
    // READY is reported out-of-band when the config is first applied, before any traffic.
    // Config application already happened during @BeforeAll, so this should be quick.
    waitForReportedStatus(LIMITED_LOCATION_HASH, "READY", Duration.ofSeconds(30));
  }

  @Test
  @Order(2)
  void reportsActiveUnderTraffic() throws Exception {
    // A single hit within a reporting period should produce an ACTIVE status on the next periodic
    // report. Stay under MAX_HITS so the breakpoint is not disabled yet.
    sendRequest("/limited");

    waitForReportedStatus(LIMITED_LOCATION_HASH, "ACTIVE", PERIODIC_TIMEOUT);
  }

  @Test
  @Order(3)
  void reportsDisabledAfterMaxHits() throws Exception {
    // Drive past MaxHits=3 so the breakpoint disables, then expect a DISABLED status on the next
    // periodic report.
    for (int i = 0; i < MAX_HITS + 2; i++) {
      sendRequest("/limited");
    }

    waitForReportedStatus(LIMITED_LOCATION_HASH, "DISABLED", PERIODIC_TIMEOUT);

    // DISABLED is a one-time status: it must not be reported more than once.
    long disabledCount =
        reportedStatusesFor(LIMITED_LOCATION_HASH).stream().filter("DISABLED"::equals).count();
    assertTrue(disabledCount == 1, "Expected exactly one DISABLED report, got: " + disabledCount);
  }
}
