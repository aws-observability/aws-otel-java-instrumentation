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
import static org.assertj.core.api.Assertions.fail;

import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.profiles.v1development.KeyValueAndUnit;
import io.opentelemetry.proto.profiles.v1development.Profile;
import io.opentelemetry.proto.profiles.v1development.ProfilesDictionary;
import io.opentelemetry.proto.profiles.v1development.ResourceProfiles;
import io.opentelemetry.proto.profiles.v1development.Sample;
import io.opentelemetry.proto.profiles.v1development.ScopeProfiles;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.serviceevents.base.ServiceEventsContractTestBase;

/**
 * End-to-end contract test for the profiler's <b>cpu</b> mode ({@code OTEL_AWS_PROFILER_MODE=cpu}).
 *
 * <p>The wall-mode path is covered by {@link AwsProfilerContractTest}; this asserts the cpu-mode
 * differences that only surface end-to-end: the primary Profile is {@code {cpu, nanoseconds}} (not
 * {@code wall}), and cpu samples carry <b>no</b> {@code thread.state} attribute (every on-CPU sample
 * is {@code STATE_DEFAULT}, so the builder omits it — see {@code OtlpProfileBuilder.isPrimaryWall}).
 * Request correlation still applies, so samples on request threads carry the {@code operation}
 * attribute. Memory profiling is left off to keep the focus on the on-CPU primary profile.
 *
 * <p>cpu sampling uses perf_events with a ctimer fallback, so it produces samples in a container
 * without perf privileges. Requires Docker/testcontainers, the built agent jar, and the
 * mock-collector + sample-app images (same harness as {@link AwsProfilerContractTest}).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AwsProfilerCpuModeContractTest extends ServiceEventsContractTestBase {

  // Same rotation/export budget as the wall test: first export lands ~70-90s after start.
  private static final Duration PROFILE_WAIT = Duration.ofSeconds(210);

  private List<ExportProfilesServiceRequest> profiles = List.of();

  @Override
  protected String getApplicationImageName() {
    return "aws-serviceevents-tests-http-server-spring-mvc";
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    Map<String, String> env = new HashMap<>();
    env.put("OTEL_AWS_PROFILER_ENABLED", "true");
    env.put("OTEL_AWS_PROFILER_MODE", "cpu");
    env.put("OTEL_AWS_PROFILER_ENDPOINT", "http://mock-collector:4317/v1development/profiles");
    env.put("OTEL_AWS_PROFILER_EXPORT_COMPRESSION", "gzip");
    return env;
  }

  // One long-running container managed manually (phase 0 starts it, @AfterAll tears it down).
  @Override
  protected void setUp() {}

  @Override
  protected void tearDown() {}

  @AfterAll
  void tearDownOnce() {
    if (application != null && application.isRunning()) {
      application.stop();
    }
    if (mockCollector != null && mockCollector.isRunning()) {
      mockCollector.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  @Test
  @Order(0)
  void testSetupDriveTrafficAndHarvestProfiles() throws Exception {
    super.setUp();

    // Keep request threads on-CPU across the rotation so cpu samples land on request-serving
    // threads (gives them the operation attribute).
    AtomicBoolean keepDriving = new AtomicBoolean(true);
    Thread traffic =
        new Thread(
            () -> {
              while (keepDriving.get()) {
                try {
                  sendRequest("cpu-work");
                  sendRequest("moderate-work");
                  sendRequest("success");
                } catch (RuntimeException ignored) {
                  // container shutting down / transient
                }
              }
            },
            "profiler-cpu-contract-traffic");
    traffic.setDaemon(true);
    traffic.start();

    try {
      applicationLogger.info("=== Waiting for cpu-mode OTLP profiles export (JFR rotation ~60s) ===");
      Instant deadline = Instant.now().plus(PROFILE_WAIT);
      while (Instant.now().isBefore(deadline)) {
        try {
          List<ExportProfilesServiceRequest> captured = mockCollectorClient.getProfiles();
          if (!captured.isEmpty()) {
            profiles = captured;
            break;
          }
        } catch (RuntimeException e) {
          applicationLogger.warn("Waiting for cpu-mode OTLP profiles export: " + e.getMessage());
        }
      }
    } finally {
      keepDriving.set(false);
      traffic.join(Duration.ofSeconds(10).toMillis());
    }

    applicationLogger.info("=== Harvested " + profiles.size() + " ExportProfilesServiceRequest(s)");
    assertThat(profiles)
        .as("mock collector must capture at least one cpu-mode ExportProfilesServiceRequest")
        .isNotEmpty();
  }

  @Test
  @Order(1)
  void testCpuPrimaryProfilePresentWithPeriod() {
    assertProfileTypeWithPeriod("cpu", "nanoseconds");
  }

  @Test
  @Order(1)
  void testNoWallProfileInCpuMode() {
    assertThat(hasProfileType("wall"))
        .as("cpu mode must NOT emit a {wall, nanoseconds} primary profile")
        .isFalse();
  }

  @Test
  @Order(1)
  void testCpuSamplesCarryNoThreadStateAttribute() {
    // In cpu mode every sample is on-CPU (STATE_DEFAULT), so the builder omits thread.state; a
    // present-but-constant attribute would be useless. Assert it never appears.
    assertThat(anyAttributeKeyPresent("thread.state"))
        .as("cpu-mode samples must not carry a thread.state attribute")
        .isFalse();
  }

  @Test
  @Order(1)
  void testCpuSampleCarriesOperationAttribute() {
    // Correlation is mode-independent: on-CPU samples of request threads still get the operation.
    assertThat(anyAttributeKeyPresent("operation"))
        .as("at least one cpu-mode sample must carry the request 'operation' attribute")
        .isTrue();
  }

  // --- helpers (resolve indices against each request's shared ProfilesDictionary) ---

  private void assertProfileTypeWithPeriod(String type, String expectedUnit) {
    for (ExportProfilesServiceRequest request : profiles) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        if (type.equals(dict.getStringTable(profile.getSampleType().getTypeStrindex()))) {
          assertThat(dict.getStringTable(profile.getSampleType().getUnitStrindex()))
              .as("%s sample_type unit", type)
              .isEqualTo(expectedUnit);
          assertThat(dict.getStringTable(profile.getPeriodType().getTypeStrindex()))
              .as("%s period_type type", type)
              .isEqualTo(type);
          assertThat(profile.getPeriod()).as("%s period must be set", type).isGreaterThan(0L);
          assertThat(profile.getSamplesCount())
              .as("%s Profile must carry samples", type)
              .isPositive();
          return;
        }
      }
    }
    fail("no Profile with sample_type '" + type + "' found across captured cpu-mode profiles");
  }

  private boolean hasProfileType(String type) {
    for (ExportProfilesServiceRequest request : profiles) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        if (type.equals(dict.getStringTable(profile.getSampleType().getTypeStrindex()))) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean anyAttributeKeyPresent(String key) {
    for (ExportProfilesServiceRequest request : profiles) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        for (Sample sample : profile.getSamplesList()) {
          for (int attrIdx : sample.getAttributeIndicesList()) {
            KeyValueAndUnit attr = dict.getAttributeTable(attrIdx);
            if (key.equals(dict.getStringTable(attr.getKeyStrindex()))) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private static List<Profile> allProfiles(ExportProfilesServiceRequest request) {
    List<Profile> out = new ArrayList<>();
    for (ResourceProfiles rp : request.getResourceProfilesList()) {
      for (ScopeProfiles sp : rp.getScopeProfilesList()) {
        out.addAll(sp.getProfilesList());
      }
    }
    return out;
  }
}
