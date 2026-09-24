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

import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.profiles.v1development.KeyValueAndUnit;
import io.opentelemetry.proto.profiles.v1development.Link;
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
 * End-to-end contract test for the profiler running <b>fully standalone</b> — the profiler enabled
 * with <b>both</b> ServiceEvents and Application Signals disabled. This is the profiler's
 * advertised independent mode (opt-in via {@code OTEL_AWS_PROFILER_ENABLED} alone), and the case
 * {@link AwsProfilerContractTest} does <em>not</em> cover: that suite runs with {@code
 * OTEL_AWS_SERVICE_EVENTS_ENABLED=true} (App Signals is off there too, via the base).
 *
 * <p>The base ({@link ServiceEventsContractTestBase}) already sets {@code
 * OTEL_AWS_APPLICATION_SIGNALS_ENABLED=false} and {@code OTEL_TRACES_SAMPLER=always_on}; this test
 * additionally overrides {@code OTEL_AWS_SERVICE_EVENTS_ENABLED=false} so nothing but the profiler
 * runs. It asserts two things:
 *
 * <ul>
 *   <li><b>The profiler still exports correlated profiles.</b> A wall Profile arrives, and samples
 *       carry the {@code operation} attribute plus (under {@code always_on}) a trace {@link Link} —
 *       proving span&rarr;profile correlation is driven purely by the profiler's {@code
 *       one.profiler.Span} markers, independent of the ServiceEvents/App Signals pipeline.
 *   <li><b>No ServiceEvents telemetry is emitted.</b> The mock collector receives zero OTLP logs
 *       (ServiceEvents incident snapshots are the only OTLP-log source here — App Signals is off
 *       and {@code OTEL_LOGS_EXPORTER=none}), so endpoint/incident recording must be skipped rather
 *       than accumulated undrained.
 * </ul>
 *
 * <p>Requires Docker/testcontainers, the built agent jar, and the mock-collector + sample-app
 * images. Run with:
 *
 * <pre>
 *   ./gradlew :appsignals-tests:contract-tests:serviceeventsContractTests \
 *       --tests "*AwsProfilerStandaloneContractTest"
 * </pre>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AwsProfilerStandaloneContractTest extends ServiceEventsContractTestBase {

  // Same rotation/export budget as AwsProfilerContractTest: first ExportProfilesServiceRequest
  // lands ~70-90s after start; poll well past one rotation so CI jitter does not flake the run.
  private static final Duration PROFILE_WAIT = Duration.ofSeconds(210);

  private List<ExportProfilesServiceRequest> profiles = List.of();

  @Override
  protected String getApplicationImageName() {
    return "aws-serviceevents-tests-http-server-spring-mvc";
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    // Profiler ON (wall + alloc), ServiceEvents OFF. App Signals is already off in the base, so
    // this yields config.isEnabled()=false and config.isProfilerEnabled()=true — the standalone
    // path. The extra env is applied after the base env (GenericContainer.withEnv is called twice),
    // so this override of OTEL_AWS_SERVICE_EVENTS_ENABLED wins over the base's "true".
    Map<String, String> env = new HashMap<>();
    env.put("OTEL_AWS_SERVICE_EVENTS_ENABLED", "false");
    env.put("OTEL_AWS_PROFILER_ENABLED", "true");
    env.put("OTEL_AWS_PROFILER_MEMORY_ENABLED", "true");
    env.put("OTEL_AWS_PROFILER_ENDPOINT", "http://mock-collector:4317/v1development/profiles");
    env.put("OTEL_AWS_PROFILER_EXPORT_COMPRESSION", "gzip");
    return env;
  }

  // One long-running container for the whole class (mirrors AwsProfilerContractTest).
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

  // ===========================================================================
  // Phase 0: start container, drive traffic across a rotation, harvest profiles
  // ===========================================================================

  @Test
  @Order(0)
  void testSetupDriveTrafficAndHarvestProfiles() throws Exception {
    super.setUp();

    // Keep request threads busy across the whole rotation so wall/alloc samples land on threads
    // actively serving requests — that is what gives them an operation attribute and trace Link.
    AtomicBoolean keepDriving = new AtomicBoolean(true);
    Thread traffic =
        new Thread(
            () -> {
              while (keepDriving.get()) {
                try {
                  sendRequest("cpu-work");
                  sendRequest("success");
                  sendRequest("moderate-work");
                } catch (RuntimeException ignored) {
                  // Container shutting down / transient — keep trying until the flag clears.
                }
              }
            },
            "profiler-standalone-contract-traffic");
    traffic.setDaemon(true);
    traffic.start();

    try {
      applicationLogger.info("=== Waiting for OTLP profiles export (JFR rotation ~60s) ===");
      Instant deadline = Instant.now().plus(PROFILE_WAIT);
      while (Instant.now().isBefore(deadline)) {
        try {
          List<ExportProfilesServiceRequest> captured = mockCollectorClient.getProfiles();
          if (!captured.isEmpty()) {
            profiles = captured;
            break;
          }
        } catch (RuntimeException e) {
          applicationLogger.warn("Waiting for OTLP profiles export: " + e.getMessage());
        }
      }
    } finally {
      keepDriving.set(false);
      traffic.join(Duration.ofSeconds(10).toMillis());
    }

    applicationLogger.info("=== Harvested " + profiles.size() + " ExportProfilesServiceRequest(s)");
    assertThat(profiles)
        .as("profiler must export profiles even with ServiceEvents and App Signals both disabled")
        .isNotEmpty();
  }

  // ===========================================================================
  // Phase 1: assertions over the harvested profiles (instant)
  // ===========================================================================

  @Test
  @Order(1)
  void testWallProfilePresent() {
    assertThat(hasProfileType("wall"))
        .as("a wall Profile must be present in standalone (profiler-only) mode")
        .isTrue();
  }

  @Test
  @Order(1)
  void testSampleCarriesOperationAttribute() {
    // Correlation is driven purely by the profiler's one.profiler.Span markers, so the operation
    // attribute must still be present with the ServiceEvents pipeline disabled.
    String operation = anyOperationValue();
    assertThat(operation)
        .as("standalone-mode samples must still carry the 'operation' attribute (Span-API driven)")
        .isNotNull();
    assertThat(operation).isNotEmpty();
  }

  @Test
  @Order(1)
  void testSampleCarriesTraceLinkUnderAlwaysOn() {
    // The base forces OTEL_TRACES_SAMPLER=always_on, so every request is sampled and correlated
    // samples carry a trace Link — even though App Signals' AlwaysRecordSampler is not in play.
    Link link = anyTraceLink();
    assertThat(link)
        .as("standalone-mode samples must carry a trace Link (sampled under always_on)")
        .isNotNull();
    assertThat(link.getTraceId().size()).as("trace_id must be 16 bytes").isEqualTo(16);
    assertThat(link.getSpanId().size()).as("span_id must be 8 bytes").isEqualTo(8);
  }

  @Test
  @Order(1)
  void testNoServiceEventsTelemetryEmitted() {
    // ServiceEvents incident snapshots are the only OTLP-log source here (App Signals off,
    // OTEL_LOGS_EXPORTER=none). With ServiceEvents disabled none must be produced. Use the
    // non-blocking snapshot: the request stream ran for the full profile-harvest window (well past
    // ServiceEvents' 2s flush cadence), so an empty result is a strong, non-flaky signal that the
    // endpoint/incident pipeline stayed off (no undrained state, no export).
    assertThat(mockCollectorClient.getLogsSnapshot())
        .as("ServiceEvents disabled: the collector must receive no ServiceEvents incident logs")
        .isEmpty();
  }

  // ===========================================================================
  // Helpers — resolve sample indices against each request's shared ProfilesDictionary.
  // ===========================================================================

  private boolean hasProfileType(String type) {
    for (ExportProfilesServiceRequest request : profiles) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        if (type.equals(dict.getStringTable(profile.getSampleType().getTypeStrindex()))
            && profile.getSamplesCount() > 0) {
          return true;
        }
      }
    }
    return false;
  }

  /** First non-empty {@code operation} attribute value found on any sample, or null. */
  private String anyOperationValue() {
    for (ExportProfilesServiceRequest request : profiles) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        for (Sample sample : profile.getSamplesList()) {
          for (int attrIdx : sample.getAttributeIndicesList()) {
            KeyValueAndUnit attr = dict.getAttributeTable(attrIdx);
            if ("operation".equals(dict.getStringTable(attr.getKeyStrindex()))) {
              // Inline AnyValue.string_value (not a string-table index) — matches the builder and
              // OtlpProfileBuilderProtoTest.
              String value = attr.getValue().getStringValue();
              if (value != null && !value.isEmpty()) {
                return value;
              }
            }
          }
        }
      }
    }
    return null;
  }

  /** First trace {@link Link} referenced by any sample ({@code link_index != 0}), or null. */
  private Link anyTraceLink() {
    for (ExportProfilesServiceRequest request : profiles) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        for (Sample sample : profile.getSamplesList()) {
          if (sample.getLinkIndex() != 0) {
            Link link = dict.getLinkTable(sample.getLinkIndex());
            if (!link.getTraceId().isEmpty()) {
              return link;
            }
          }
        }
      }
    }
    return null;
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
