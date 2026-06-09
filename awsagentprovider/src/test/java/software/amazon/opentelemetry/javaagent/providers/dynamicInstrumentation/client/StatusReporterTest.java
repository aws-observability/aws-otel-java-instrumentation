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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.bootstrap.di.DIDataStore;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.InstrumentationRegistry;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.ConfigurationStatus;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.StatusEntry;

/**
 * Lifecycle tests for {@link StatusReporter}: READY -> ACTIVE -> DISABLED.
 *
 * <p>Drives the real live hit path ({@link DIDataStore#recordHit}) the same way the injected Advice
 * classes do, and invokes the reporter's periodic logic directly via the package-private {@link
 * StatusReporter#pullAndReportStatuses(boolean)} — so the full status lifecycle is asserted
 * deterministically with no scheduler timing and no test containers.
 *
 * <p>This is the regression guard for the defect where the Advice path only ever incremented {@code
 * DIDataStore.HitState} (bootstrap classloader) while the reporter read {@code InstrumentationState}
 * (agent classloader). The two stores were never reconciled, so ACTIVE and DISABLED never emitted —
 * only READY was ever sent.
 */
class StatusReporterTest {

  private static final String CODE_UNIT = "com.example";
  private static final String CLASS_NAME = "OrderService";
  private static final String METHOD_NAME = "processOrder";
  private static final String LOCATION_HASH = "aabb000000000001";
  private static final int MAX_HITS = 3;

  /** Capturing sink — records every batch of status entries the reporter sends. */
  private static final class CapturingSink implements StatusReportSink {
    final List<StatusEntry> entries = new ArrayList<>();

    @Override
    public void reportConfigurationStatus(List<StatusEntry> statusEntries) {
      entries.addAll(statusEntries);
    }

    /** All reported statuses for the given location hash, in report order. */
    List<ConfigurationStatus> statusesFor(String locationHash) {
      return entries.stream()
          .filter(e -> locationHash.equals(e.getLocationHash()))
          .map(StatusEntry::getStatus)
          .collect(Collectors.toList());
    }

    long countOf(String locationHash, ConfigurationStatus status) {
      return statusesFor(locationHash).stream().filter(s -> s == status).count();
    }

    void clear() {
      entries.clear();
    }
  }

  private CapturingSink sink;
  private StatusReporter reporter;
  private String key;

  @BeforeEach
  void setUp() {
    InstrumentationRegistry.clearAll();

    InstrumentationConfiguration config =
        breakpointConfig(CODE_UNIT, CLASS_NAME, METHOD_NAME, LOCATION_HASH, MAX_HITS);
    key = config.getMethodKey();

    // Register static metadata (what StatusReporter reads location/type from).
    InstrumentationRegistry.register(key, config);

    // Register the live runtime store on the bootstrap classloader (what the Advice path hits).
    // limits values are irrelevant to status reporting; expiry disabled (0).
    DIDataStore.registerConfig(
        key,
        new int[] {3, 20, 20, 255, 3},
        new String[0],
        null,
        true,
        MAX_HITS,
        0L);

    sink = new CapturingSink();
    reporter = new StatusReporter(sink, 60);
  }

  @AfterEach
  void tearDown() {
    InstrumentationRegistry.clearAll();
    DIDataStore.removeConfig(key);
  }

  /** Simulate one Advice hit through the real live path. */
  private void hit() {
    DIDataStore.recordHit(key);
  }

  @Test
  void reportsReadyOnceBeforeAnyTraffic() {
    // Initial (out-of-band) report, no traffic yet.
    reporter.pullAndReportStatuses(true);

    assertThat(sink.statusesFor(LOCATION_HASH)).containsExactly(ConfigurationStatus.READY);

    // Reporting READY again must not duplicate it.
    reporter.pullAndReportStatuses(true);
    assertThat(sink.countOf(LOCATION_HASH, ConfigurationStatus.READY)).isEqualTo(1);
  }

  @Test
  void reportsActiveWhenHitInPeriod() {
    // Initial report -> READY.
    reporter.pullAndReportStatuses(true);
    sink.clear();

    // Traffic arrives, then a periodic report runs.
    hit();
    reporter.pullAndReportStatuses(false);

    assertThat(sink.statusesFor(LOCATION_HASH)).containsExactly(ConfigurationStatus.ACTIVE);
  }

  @Test
  void initialReportDoesNotConsumePendingActive() {
    // Regression guard: traffic can land after the transformer goes live but before the
    // out-of-band initial report (reportNow) fires. The initial report must NOT consume the
    // per-period hit flag, or the next periodic report would miss the ACTIVE signal.
    reporter.pullAndReportStatuses(true); // initial READY
    sink.clear();

    // Traffic arrives, then an initial report fires (e.g. another config is applied).
    hit();
    reporter.pullAndReportStatuses(true); // initial report — must not emit nor consume ACTIVE
    assertThat(sink.statusesFor(LOCATION_HASH)).doesNotContain(ConfigurationStatus.ACTIVE);

    // The next periodic report must still see the hit and emit ACTIVE.
    reporter.pullAndReportStatuses(false);
    assertThat(sink.countOf(LOCATION_HASH, ConfigurationStatus.ACTIVE)).isEqualTo(1);
  }

  @Test
  void reportsActiveEveryPeriodUnderSustainedTraffic() {
    reporter.pullAndReportStatuses(true); // READY
    sink.clear();

    // Period 1: hit -> ACTIVE.
    hit();
    reporter.pullAndReportStatuses(false);
    // Period 2: hit -> ACTIVE again (continuous reporting).
    hit();
    reporter.pullAndReportStatuses(false);

    assertThat(sink.countOf(LOCATION_HASH, ConfigurationStatus.ACTIVE)).isEqualTo(2);
  }

  @Test
  void doesNotReportActiveInIdlePeriod() {
    reporter.pullAndReportStatuses(true); // READY
    sink.clear();

    // Period with traffic -> ACTIVE.
    hit();
    reporter.pullAndReportStatuses(false);
    // Period with no traffic -> no ACTIVE (verifies the per-period flag is reset after reporting).
    reporter.pullAndReportStatuses(false);

    assertThat(sink.countOf(LOCATION_HASH, ConfigurationStatus.ACTIVE)).isEqualTo(1);
  }

  @Test
  void reportsDisabledExactlyOnceAfterMaxHits() {
    reporter.pullAndReportStatuses(true); // READY
    sink.clear();

    // Drive past maxHits. tryHit() allows exactly MAX_HITS captures, then disables.
    for (int i = 0; i < MAX_HITS + 2; i++) {
      hit();
    }

    reporter.pullAndReportStatuses(false);
    assertThat(sink.countOf(LOCATION_HASH, ConfigurationStatus.DISABLED)).isEqualTo(1);

    // Subsequent periods must not emit DISABLED again, nor ACTIVE.
    sink.clear();
    hit(); // hits after disable are ignored by the gate
    reporter.pullAndReportStatuses(false);
    assertThat(sink.statusesFor(LOCATION_HASH)).isEmpty();
  }

  @Test
  void fullLifecycleReadyThenActiveThenDisabled() {
    // READY
    reporter.pullAndReportStatuses(true);
    assertThat(sink.statusesFor(LOCATION_HASH)).containsExactly(ConfigurationStatus.READY);

    // ACTIVE
    hit();
    reporter.pullAndReportStatuses(false);

    // DISABLED (exceed maxHits)
    for (int i = 0; i < MAX_HITS + 2; i++) {
      hit();
    }
    reporter.pullAndReportStatuses(false);

    List<ConfigurationStatus> statuses = sink.statusesFor(LOCATION_HASH);
    assertThat(statuses).contains(ConfigurationStatus.READY);
    assertThat(statuses).contains(ConfigurationStatus.ACTIVE);
    assertThat(statuses).contains(ConfigurationStatus.DISABLED);
    assertThat(sink.countOf(LOCATION_HASH, ConfigurationStatus.READY)).isEqualTo(1);
    assertThat(sink.countOf(LOCATION_HASH, ConfigurationStatus.DISABLED)).isEqualTo(1);
  }

  /** Build a method-level (lineNumber 0) BREAKPOINT config with the given maxHits. */
  private static InstrumentationConfiguration breakpointConfig(
      String codeUnit, String className, String methodName, String locationHash, int maxHits) {
    Map<String, Object> location = new HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", codeUnit);
    location.put("ClassName", className);
    location.put("MethodName", methodName);
    location.put("LineNumber", 0);
    location.put("FilePath", className + ".java");

    Map<String, Object> locationWrapper = new HashMap<>();
    locationWrapper.put("CodeLocation", location);

    Map<String, Object> captureLimits = new HashMap<>();
    captureLimits.put("MaxHits", maxHits);

    Map<String, Object> codeCapture = new HashMap<>();
    codeCapture.put("CaptureReturn", true);
    codeCapture.put("CaptureArguments", Collections.emptyList());
    codeCapture.put("CaptureLocals", Collections.emptyList());
    codeCapture.put("CaptureLimits", captureLimits);

    Map<String, Object> captureWrapper = new HashMap<>();
    captureWrapper.put("CodeCapture", codeCapture);

    Map<String, Object> apiConfig = new HashMap<>();
    apiConfig.put("Location", locationWrapper);
    apiConfig.put("LocationHash", locationHash);
    apiConfig.put("InstrumentationType", "BREAKPOINT");
    apiConfig.put("CaptureConfiguration", captureWrapper);

    return InstrumentationConfiguration.fromApiConfig(apiConfig);
  }
}
