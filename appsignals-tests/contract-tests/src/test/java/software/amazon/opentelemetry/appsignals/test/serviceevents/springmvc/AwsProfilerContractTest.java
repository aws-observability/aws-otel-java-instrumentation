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

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.serviceevents.base.ServiceEventsContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeLog;

/**
 * End-to-end contract test for the ADOT Java profiler's native OTLP profiles export.
 *
 * <p>Runs the springmvc serviceevents sample app under the built agent with {@code
 * OTEL_AWS_PROFILER_ENABLED=true} + {@code OTEL_AWS_PROFILER_MEMORY_ENABLED=true}, pointed at the
 * mock collector's {@code /v1development/profiles} HTTP route. It drives continuous request traffic
 * across a JFR rotation window, then asserts a captured {@link ExportProfilesServiceRequest}
 * carries a wall {@link Profile} and an {@code alloc_space} {@link Profile} (both with {@code
 * period_type}/{@code period} set), and that samples correlate to requests — at least one sample
 * carries the {@code operation} attribute and, under {@code always_on} sampling, a trace {@link
 * Link} (16-byte trace id / 8-byte span id).
 *
 * <p><b>Runtime — musl and glibc both supported.</b> async-profiler's Linux native library supports
 * glibc and musl, so the default serviceevents sample-app image ({@code amazoncorretto:23-alpine})
 * works as-is. The agent no-ops the profiler only on Windows and AWS Lambda.
 *
 * <p>Requires Docker/testcontainers, the built agent jar (supplied via the {@code
 * io.awsobservability.instrumentation.contracttests.agentPath} system property by the Gradle {@code
 * serviceeventsContractTests} task) and the mock-collector + sample-app images. Run with:
 *
 * <pre>
 *   ./gradlew :appsignals-tests:contract-tests:serviceeventsContractTests \
 *       --tests "*AwsProfilerContractTest"
 * </pre>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AwsProfilerContractTest extends ServiceEventsContractTestBase {

  // The profiler exports on a 60s JFR rotation loop + a 10s RotationBoundaryProcessor check +
  // export latency, so the first ExportProfilesServiceRequest lands ~70-90s after the agent starts.
  // Poll well past one rotation so CI scheduling jitter does not flake the run.
  private static final Duration PROFILE_WAIT = Duration.ofSeconds(210);

  private List<ExportProfilesServiceRequest> profiles = List.of();

  @Override
  protected String getApplicationImageName() {
    return "aws-serviceevents-tests-http-server-spring-mvc";
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    // Profiler ON (wall) + memory (alloc), pointed at the mock collector's HTTP profiles route.
    // OTEL_AWS_PROFILER_ENDPOINT is used verbatim by OtlpHttpProfilesExporter (no path appended);
    // gzip mirrors the production default and MockCollectorHttpUtil gunzips before parsing. The
    // mock collector serves the profiles route on the same 4317 port as the logs/metrics routes.
    Map<String, String> env = new HashMap<>();
    env.put("OTEL_AWS_PROFILER_ENABLED", "true");
    env.put("OTEL_AWS_PROFILER_MEMORY_ENABLED", "true");
    env.put("OTEL_AWS_PROFILER_ENDPOINT", "http://mock-collector:4317/v1development/profiles");
    env.put("OTEL_AWS_PROFILER_EXPORT_COMPRESSION", "gzip");
    return env;
  }

  // ---------------------------------------------------------------------------
  // Manage the container manually — one long-running container for phase 0.
  // ---------------------------------------------------------------------------

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

    // Keep request threads continuously busy across the whole rotation window so wall/alloc samples
    // land on threads that are actively serving requests — that is what gives those samples an
    // operation attribute and (under always_on sampling) a trace Link.
    AtomicBoolean keepDriving = new AtomicBoolean(true);
    Thread traffic =
        new Thread(
            () -> {
              while (keepDriving.get()) {
                try {
                  sendRequest("cpu-work");
                  sendRequest("success");
                  sendRequest("moderate-work");
                  // /slow-success (3s) exceeds the per-endpoint 500ms latency threshold → a latency
                  // IncidentSnapshot; parked 3s it also yields many wall samples tagged with its
                  // request-boundary span. Those are the two signals the correlation test joins.
                  sendRequest("slow-success");
                } catch (RuntimeException ignored) {
                  // Container shutting down / transient — keep trying until the flag clears.
                }
              }
            },
            "profiler-contract-traffic");
    traffic.setDaemon(true);
    traffic.start();

    try {
      // getProfiles() blocks up to the client's single-call window; the profiler needs a full JFR
      // rotation before it exports, so retry across PROFILE_WAIT (mirrors the metrics idiom in
      // ServiceEventsSpringMvcTest).
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
        .as(
            "mock collector must capture at least one ExportProfilesServiceRequest after a JFR"
                + " rotation")
        .isNotEmpty();
  }

  // ===========================================================================
  // Phase 1: assertions over the harvested profiles (instant)
  // ===========================================================================

  @Test
  @Order(1)
  void testWallProfilePresentWithPeriod() {
    assertProfileTypeWithPeriod("wall", "nanoseconds");
  }

  @Test
  @Order(1)
  void testAllocatedSpaceProfilePresentWithPeriod() {
    // OTEL_AWS_PROFILER_MEMORY_ENABLED=true => async-profiler records alloc events in the same JFR
    // session and the builder emits an {alloc_space, bytes} Profile alongside wall.
    assertProfileTypeWithPeriod("alloc_space", "bytes");
  }

  @Test
  @Order(1)
  void testAllocatedObjectsProfilePresentWithPeriod() {
    // Memory profiling also emits the {alloc_objects, count} companion profile (period == 1).
    assertProfileTypeWithPeriod("alloc_objects", "count");
  }

  @Test
  @Order(1)
  void testWallSamplesCarryThreadStateAttribute() {
    // In wall mode each sample carries a thread.state attribute (RUNNABLE on-CPU / SLEEPING
    // off-CPU)
    // so a backend can split on- vs off-CPU time. This is the wall-mode counterpart of
    // AwsProfilerCpuModeContractTest#testCpuSamplesCarryNoThreadStateAttribute.
    String state = anyAttributeValue("thread.state");
    assertThat(state).as("wall-mode samples must carry a thread.state attribute").isNotNull();
    assertThat(state).isNotEmpty();
  }

  @Test
  @Order(1)
  void testSampleCarriesOperationAttribute() {
    String operation = anyOperationValue();
    assertThat(operation)
        .as("at least one profile sample must carry the 'operation' attribute (request-correlated)")
        .isNotNull();
    assertThat(operation).isNotEmpty();
  }

  @Test
  @Order(1)
  void testSampleCarriesTraceLinkUnderAlwaysOn() {
    // The base config forces OTEL_TRACES_SAMPLER=always_on, so every request is sampled and
    // request-correlated samples carry a trace Link with a 16-byte trace id / 8-byte span id.
    Link link = anyTraceLink();
    assertThat(link)
        .as("at least one profile sample must carry a trace Link (sampled under always_on)")
        .isNotNull();
    assertThat(link.getTraceId().size()).as("trace_id must be 16 bytes").isEqualTo(16);
    assertThat(link.getSpanId().size()).as("span_id must be 8 bytes").isEqualTo(8);
  }

  @Test
  @Order(1)
  void testIncidentSnapshotAndProfileShareTraceSpan() {
    // A latency IncidentSnapshot (from /slow-success) and the profile samples of that same
    // request-boundary span must carry the same (trace_id, span_id), so a backend can join them.

    // (trace_id, span_id) pairs from incident-snapshot logs. Incidents are identified by the
    // aws.service_events.snapshot_id attribute; the agent sets the log's trace/span context from
    // the incident's request-boundary span (ServiceEventsOtlpEmitter.emitIncidentSnapshot).
    Set<String> incidentKeys = new HashSet<>();
    for (ResourceScopeLog rsl : mockCollectorClient.getLogs()) {
      LogRecord log = rsl.getLog();
      boolean isIncident =
          log.getAttributesList().stream()
              .anyMatch(kv -> "aws.service_events.snapshot_id".equals(kv.getKey()));
      if (isIncident && !log.getTraceId().isEmpty() && !log.getSpanId().isEmpty()) {
        incidentKeys.add(hex(log.getTraceId()) + "/" + hex(log.getSpanId()));
      }
    }
    assertThat(incidentKeys)
        .as(
            "expected >=1 IncidentSnapshot carrying trace/span (latency on /slow-success, always_on)")
        .isNotEmpty();

    // (trace_id, span_id) pairs from every profile sample's trace Link, across all exported
    // windows.
    Set<String> profileKeys = new HashSet<>();
    for (ExportProfilesServiceRequest request : mockCollectorClient.getProfilesSnapshot()) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        for (Sample sample : profile.getSamplesList()) {
          if (sample.getLinkIndex() != 0) {
            Link link = dict.getLinkTable(sample.getLinkIndex());
            if (!link.getTraceId().isEmpty() && !link.getSpanId().isEmpty()) {
              profileKeys.add(hex(link.getTraceId()) + "/" + hex(link.getSpanId()));
            }
          }
        }
      }
    }
    assertThat(profileKeys).as("expected >=1 profile sample carrying a trace Link").isNotEmpty();

    Set<String> shared = new HashSet<>(incidentKeys);
    shared.retainAll(profileKeys);
    assertThat(shared)
        .as(
            "at least one (trace_id, span_id) must appear on BOTH an IncidentSnapshot and a profile"
                + " sample — proving profile<->incident correlate on the same request-boundary span")
        .isNotEmpty();
  }

  private static String hex(ByteString bytes) {
    StringBuilder sb = new StringBuilder(bytes.size() * 2);
    for (byte b : bytes.toByteArray()) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  // ===========================================================================
  // Helpers — resolve sample indices against each request's shared ProfilesDictionary.
  // ===========================================================================

  /** Flatten every Profile across all captured requests, paired with its request dictionary. */
  private void assertProfileTypeWithPeriod(String type, String expectedUnit) {
    for (ExportProfilesServiceRequest request : profiles) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        if (type.equals(dict.getStringTable(profile.getSampleType().getTypeStrindex()))) {
          assertThat(dict.getStringTable(profile.getSampleType().getUnitStrindex()))
              .as("%s sample_type unit", type)
              .isEqualTo(expectedUnit);
          // period_type mirrors sample_type; period must be set (> 0).
          assertThat(dict.getStringTable(profile.getPeriodType().getTypeStrindex()))
              .as("%s period_type type", type)
              .isEqualTo(type);
          assertThat(dict.getStringTable(profile.getPeriodType().getUnitStrindex()))
              .as("%s period_type unit", type)
              .isEqualTo(expectedUnit);
          assertThat(profile.getPeriod()).as("%s period must be set", type).isGreaterThan(0L);
          assertThat(profile.getSamplesCount())
              .as("%s Profile must carry samples", type)
              .isPositive();
          return;
        }
      }
    }
    fail("no Profile with sample_type '" + type + "' found across captured profiles");
  }

  /** First non-empty value of attribute {@code key} found on any sample, or null. */
  private String anyAttributeValue(String key) {
    for (ExportProfilesServiceRequest request : profiles) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        for (Sample sample : profile.getSamplesList()) {
          for (int attrIdx : sample.getAttributeIndicesList()) {
            KeyValueAndUnit attr = dict.getAttributeTable(attrIdx);
            if (key.equals(dict.getStringTable(attr.getKeyStrindex()))) {
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

  /** First non-empty {@code operation} attribute value found on any sample, or null. */
  private String anyOperationValue() {
    for (ExportProfilesServiceRequest request : profiles) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        for (Sample sample : profile.getSamplesList()) {
          for (int attrIdx : sample.getAttributeIndicesList()) {
            KeyValueAndUnit attr = dict.getAttributeTable(attrIdx);
            if ("operation".equals(dict.getStringTable(attr.getKeyStrindex()))) {
              // The builder emits the attribute value as an inline AnyValue.string_value (not a
              // string-table index — some OTLP profiles backends reject the index form), so
              // read getStringValue() directly. Matches OtlpProfileBuilderProtoTest.
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
