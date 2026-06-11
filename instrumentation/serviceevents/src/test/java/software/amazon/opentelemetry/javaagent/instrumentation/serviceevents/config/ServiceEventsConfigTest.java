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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceEventsConfig} — the release env surface, the endpoint policy, and
 * the internal {@code DEBUG_SE_TEST_CONFIG} test-config hook.
 */
class ServiceEventsConfigTest {

  private static final String[] PROFILE_ENV_VARS = {
    // Internal knobs that no longer have their own sysprop/env — listed so leftover values from a
    // previous test (or the host environment) are cleared; fromEnv() must ignore them now.
    "otel.aws.service_events.java.service.code.namespace",
    "otel.aws.service_events.sample.tier1.threshold",
    "otel.aws.service_events.sample.tier2.threshold",
    "otel.aws.service_events.sample.tier2.rate",
    "otel.aws.service_events.sample.tier3.rate",
    "otel.aws.service_events.hot.endpoint.cycles",
    "otel.aws.service_events.function.call.flush.interval",
    "otel.aws.service_events.endpoint.flush.interval",
    "otel.aws.service_events.deployment.event.flush.interval",
    "otel.aws.service_events.log.group",
    "otel.aws.service_events.log.stream",
    // The internal test-config hook (DEBUG_SE_TEST_CONFIG -> debug.se.test.config).
    "debug.se.test.config",
    // Release knobs that fromEnv() still reads.
    "otel.aws.service_events.latency.thresholds",
    "otel.aws.service_events.function.instrument.enabled",
    "otel.aws.service_events.enabled",
    "otel.aws.application.signals.enabled",
    "otel.aws.otlp.logs.endpoint",
    "otel.aws.otlp.metrics.endpoint",
    "aws.lambda.function.name",
    "otel.aws.service_events.output.file",
    "otel.aws.service_events.packages.exclude",
    "otel.aws.service_events.packages.include",
    "otel.aws.service_events.endpoint.include.patterns",
    "otel.aws.service_events.endpoint.exclude.patterns",
    "otel.aws.service_events.incident.snapshot.max.per.minute",
    "otel.aws.service_events.incident.snapshot.max.same.error",
    "otel.aws.service_events.sampling.mode",
    "sun.java.command",
  };

  @BeforeEach
  void clearProps() {
    for (String p : PROFILE_ENV_VARS) {
      System.clearProperty(p);
    }
  }

  @AfterEach
  void tearDown() {
    clearProps();
  }

  @Test
  void latencyThresholds_default_isEmpty() {
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.getLatencyThresholds().isEmpty());
  }

  @Test
  void latencyThresholds_pipeDelimited_parsedAndTrimmed() {
    System.setProperty(
        "otel.aws.service_events.latency.thresholds",
        "  POST /api/checkout:500  |  GET /api/health:50  |  GET /api/reports:5000");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    List<String> entries = cfg.getLatencyThresholds();
    assertEquals(3, entries.size());
    assertEquals("POST /api/checkout:500", entries.get(0));
    assertEquals("GET /api/health:50", entries.get(1));
    assertEquals("GET /api/reports:5000", entries.get(2));
  }

  @Test
  void latencyThresholds_emptySegmentsBetweenPipes_skipped() {
    System.setProperty(
        "otel.aws.service_events.latency.thresholds", "||POST /api/checkout:500||GET /ok:100||");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(2, cfg.getLatencyThresholds().size());
  }

  @Test
  void latencyThresholds_routeContainingComma_preservedByPipeDelimiter() {
    System.setProperty(
        "otel.aws.service_events.latency.thresholds", "GET /search?q=a,b,c:750|POST /orders:900");
    List<String> entries = ServiceEventsConfig.fromEnv().getLatencyThresholds();
    assertEquals(2, entries.size());
    assertEquals("GET /search?q=a,b,c:750", entries.get(0));
    assertEquals("POST /orders:900", entries.get(1));
  }

  @Test
  void serviceCodeNamespace_defaultIsEmpty() {
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.getServiceCodeNamespace().isEmpty());
  }

  @Test
  void serviceCodeNamespace_envIsIgnored() {
    // serviceCodeNamespace is internal now — the former env var has no effect.
    System.setProperty("otel.aws.service_events.java.service.code.namespace", "com.acme.orders");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.getServiceCodeNamespace().isEmpty());
  }

  @Test
  void serviceCodeNamespace_builderWiresField() {
    ServiceEventsConfig cfg =
        new ServiceEventsConfig.Builder().serviceCodeNamespace("com.acme.orders").build();
    assertEquals("com.acme.orders", cfg.getServiceCodeNamespace());
  }

  // ───── Bundling rule (OTEL_AWS_SERVICE_EVENTS_ENABLED × APPLICATION_SIGNALS × Lambda) ─────

  @Test
  void enabled_unsetWithoutAppSignals_defaultsDisabled() {
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertFalse(cfg.isEnabled());
  }

  @Test
  void enabled_unsetWithAppSignals_bundledOn() {
    System.setProperty("otel.aws.application.signals.enabled", "true");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.isEnabled());
  }

  @Test
  void enabled_explicitTrue_overridesAppSignalsOff() {
    System.setProperty("otel.aws.service_events.enabled", "true");
    System.setProperty("otel.aws.otlp.logs.endpoint", "http://custom:9999/v1/logs");
    System.setProperty("otel.aws.otlp.metrics.endpoint", "http://custom:9999/v1/metrics");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.isEnabled());
  }

  @Test
  void enabled_explicitFalse_overridesAppSignalsOn() {
    System.setProperty("otel.aws.service_events.enabled", "false");
    System.setProperty("otel.aws.application.signals.enabled", "true");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertFalse(cfg.isEnabled());
  }

  @Test
  void enabled_lambdaDisablesRegardlessOfOtherFlags() {
    System.setProperty("aws.lambda.function.name", "my-fn");
    System.setProperty("otel.aws.service_events.enabled", "true");
    System.setProperty("otel.aws.application.signals.enabled", "true");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertFalse(cfg.isEnabled());
  }

  // ───── Application Signals bundling flag (mirrored onto ServiceEvents config) ─────

  @Test
  void applicationSignalsEnabled_defaultFalse() {
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertFalse(cfg.isApplicationSignalsEnabled());
  }

  @Test
  void applicationSignalsEnabled_trueFromEnv() {
    System.setProperty("otel.aws.application.signals.enabled", "true");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.isApplicationSignalsEnabled());
  }

  @Test
  void applicationSignalsEnabled_builder() {
    ServiceEventsConfig cfg =
        new ServiceEventsConfig.Builder().applicationSignalsEnabled(true).build();
    assertTrue(cfg.isApplicationSignalsEnabled());
  }

  // ───── Endpoint policy ─────

  @Test
  void endpoints_appSignalsOn_unsetEndpointsDefaultTo4316() {
    System.setProperty("otel.aws.application.signals.enabled", "true");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.isEnabled());
    assertEquals("http://localhost:4316/v1/logs", cfg.getLogsEndpoint());
    assertEquals("http://localhost:4316/v1/metrics", cfg.getMetricsEndpoint());
  }

  @Test
  void endpoints_appSignalsOn_explicitEndpointsHonored() {
    System.setProperty("otel.aws.application.signals.enabled", "true");
    System.setProperty("otel.aws.otlp.logs.endpoint", "http://custom:9999/v1/logs");
    System.setProperty("otel.aws.otlp.metrics.endpoint", "http://custom:9999/v1/metrics");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals("http://custom:9999/v1/logs", cfg.getLogsEndpoint());
    assertEquals("http://custom:9999/v1/metrics", cfg.getMetricsEndpoint());
  }

  @Test
  void endpoints_forceEnabledWithoutAppSignals_requiresBothEndpoints() {
    System.setProperty("otel.aws.service_events.enabled", "true");
    // No App Signals, no endpoints → disables ServiceEvents per the policy
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertFalse(cfg.isEnabled());
  }

  @Test
  void endpoints_forceEnabledWithoutAppSignals_withEndpointsHonored() {
    System.setProperty("otel.aws.service_events.enabled", "true");
    System.setProperty("otel.aws.otlp.logs.endpoint", "http://custom:9999/v1/logs");
    System.setProperty("otel.aws.otlp.metrics.endpoint", "http://custom:9999/v1/metrics");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.isEnabled());
    assertEquals("http://custom:9999/v1/logs", cfg.getLogsEndpoint());
    assertEquals("http://custom:9999/v1/metrics", cfg.getMetricsEndpoint());
  }

  @Test
  void endpoints_forceEnabledWithoutAppSignals_onlyLogsSet_disabled() {
    System.setProperty("otel.aws.service_events.enabled", "true");
    System.setProperty("otel.aws.otlp.logs.endpoint", "http://custom:9999/v1/logs");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertFalse(cfg.isEnabled());
  }

  @Test
  void endpoints_outputFileModeBypassesEndpointRequirement() {
    // OUTPUT_FILE replaces the OTLP network exporters, so unset endpoints are fine
    // even when force-enabled without App Signals.
    System.setProperty("otel.aws.service_events.enabled", "true");
    System.setProperty("otel.aws.service_events.output.file", "/tmp/serviceevents.log");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.isEnabled());
    assertEquals("/tmp/serviceevents.log", cfg.getOutputFile());
  }

  // ───── OTEL_AWS_SERVICE_EVENTS_PACKAGES_INCLUDE / PACKAGES_EXCLUDE contract ─────

  @Test
  void packagesExclude_default_isEmpty() {
    // No implicit default scope and no user-overridable blocklist. The non-configurable
    // SDK_SELF_EXCLUDE is the only built-in filter; the user denylist is empty by default.
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.getPackagesExclude().isEmpty());
  }

  @Test
  void packagesExclude_userPatternsRoundTrip() {
    System.setProperty(
        "otel.aws.service_events.packages.exclude", "com.acme.internal.*,generated.*");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(List.of("com.acme.internal.*", "generated.*"), cfg.getPackagesExclude());
  }

  @Test
  void packagesInclude_default_isEmpty() {
    // Empty include = no functions instrumented (no implicit default scope).
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.getPackagesInclude().isEmpty());
  }

  @Test
  void packagesInclude_userValueRoundTrip() {
    System.setProperty(
        "otel.aws.service_events.packages.include", "com.acme.orders,com.acme.billing");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(List.of("com.acme.orders", "com.acme.billing"), cfg.getPackagesInclude());
  }

  @Test
  void packagesInclude_bareStarNormalizedAway() {
    System.setProperty("otel.aws.service_events.packages.include", "*");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    // Bare '*' is rejected as invalid input and ignored, leaving the list empty — which now
    // means "instrument nothing" (rule 1), not "default scope".
    assertTrue(cfg.getPackagesInclude().isEmpty());
  }

  @Test
  void packagesInclude_bareStarStrippedFromMixedList() {
    System.setProperty("otel.aws.service_events.packages.include", "com.acme,*,com.other");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(List.of("com.acme", "com.other"), cfg.getPackagesInclude());
  }

  @Test
  void packagesExclude_bareStarNormalizedAway() {
    System.setProperty("otel.aws.service_events.packages.exclude", "*");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.getPackagesExclude().isEmpty());
  }

  @Test
  void builder_normalizesBareStarInPackagesInclude() {
    // Builder callers (tests, programmatic configs) get the same '*' normalization
    // that fromEnv() applies — no footgun where List.of("*") behaves like a wildcard.
    ServiceEventsConfig cfg =
        new ServiceEventsConfig.Builder().packagesInclude(java.util.Arrays.asList("*")).build();
    assertTrue(cfg.getPackagesInclude().isEmpty());
  }

  @Test
  void builder_normalizesBareStarInPackagesExclude() {
    ServiceEventsConfig cfg =
        new ServiceEventsConfig.Builder().packagesExclude(java.util.Arrays.asList("*", "")).build();
    assertTrue(cfg.getPackagesExclude().isEmpty());
  }

  @Test
  void sdkSelfExclude_enumeratesRealAdotRootsNotUmbrella() {
    // Non-configurable safety boundary. Must cover OTel + the real ADOT module roots, but
    // NOT the bare software.amazon.opentelemetry. umbrella (that would catch the
    // appsignals.tests.* contract-test apps, which must remain instrumentable).
    assertTrue(ServiceEventsConfig.SDK_SELF_EXCLUDE.contains("io.opentelemetry."));
    assertTrue(
        ServiceEventsConfig.SDK_SELF_EXCLUDE.contains("software.amazon.opentelemetry.javaagent."));
    assertTrue(
        ServiceEventsConfig.SDK_SELF_EXCLUDE.contains(
            "software.amazon.opentelemetry.serviceevents."));
    assertFalse(
        ServiceEventsConfig.SDK_SELF_EXCLUDE.contains("software.amazon.opentelemetry."),
        "must not list the bare umbrella — it would catch appsignals.tests.* test apps");
    assertTrue(
        ServiceEventsConfig.SDK_SELF_EXCLUDE.stream().allMatch(p -> p.endsWith(".")),
        "SDK_SELF_EXCLUDE entries are FQCN prefixes with trailing dots");
  }

  @Test
  void sdkSelfExclude_isImmutable() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> ServiceEventsConfig.SDK_SELF_EXCLUDE.add("should.fail"));
  }

  @Test
  void warn_loggedWhenEnabledWithoutInclude() {
    // FUNCTION_INSTRUMENT_ENABLED=true + empty PACKAGES_INCLUDE → config still builds (it just
    // instruments nothing). The WARN itself is emitted to java.util.logging at fromEnv() time;
    // here we assert the resulting state that triggers it.
    System.setProperty("otel.aws.service_events.enabled", "true");
    System.setProperty("otel.aws.service_events.function.instrument.enabled", "true");
    System.setProperty("otel.aws.otlp.logs.endpoint", "http://localhost:4316/v1/logs");
    System.setProperty("otel.aws.otlp.metrics.endpoint", "http://localhost:4316/v1/metrics");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.isBytecodeEnabled());
    assertTrue(cfg.getPackagesInclude().isEmpty());
  }

  // ───── Endpoint include/exclude filter lists ─────

  @Test
  void endpointFilterPatterns_defaultEmpty() {
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertTrue(cfg.getEndpointIncludePatterns().isEmpty());
    assertTrue(cfg.getEndpointExcludePatterns().isEmpty());
  }

  @Test
  void endpointFilterPatterns_csvListParsed() {
    System.setProperty(
        "otel.aws.service_events.endpoint.include.patterns", "GET /api/*,POST /api/*");
    System.setProperty("otel.aws.service_events.endpoint.exclude.patterns", "* /health,* /metrics");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(2, cfg.getEndpointIncludePatterns().size());
    assertEquals("GET /api/*", cfg.getEndpointIncludePatterns().get(0));
    assertEquals("POST /api/*", cfg.getEndpointIncludePatterns().get(1));
    assertEquals(2, cfg.getEndpointExcludePatterns().size());
    assertEquals("* /health", cfg.getEndpointExcludePatterns().get(0));
  }

  // ───── Incident snapshot rate-limit parameters ─────

  @Test
  void incidentSnapshotLimits_defaultsMatchSpec() {
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(100, cfg.getIncidentSnapshotMaxPerMinute());
    assertEquals(1, cfg.getIncidentSnapshotMaxSameError());
  }

  @Test
  void incidentSnapshotLimits_envRoundTrip() {
    System.setProperty("otel.aws.service_events.incident.snapshot.max.per.minute", "50");
    System.setProperty("otel.aws.service_events.incident.snapshot.max.same.error", "10");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(50, cfg.getIncidentSnapshotMaxPerMinute());
    assertEquals(10, cfg.getIncidentSnapshotMaxSameError());
  }

  @Test
  void incidentSnapshotLimits_malformedFallsBackToDefault() {
    System.setProperty("otel.aws.service_events.incident.snapshot.max.per.minute", "not-a-number");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(100, cfg.getIncidentSnapshotMaxPerMinute());
  }

  // ───── Adaptive sampling for function-call records ─────

  @Test
  void samplingMode_defaultIsAdaptive() {
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals("adaptive", cfg.getSamplingMode());
  }

  @Test
  void samplingMode_envOverride() {
    System.setProperty("otel.aws.service_events.sampling.mode", "always");
    assertEquals("always", ServiceEventsConfig.fromEnv().getSamplingMode());
  }

  @Test
  void samplingThresholds_defaults() {
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(100, cfg.getSampleTier1Threshold());
    assertEquals(1000, cfg.getSampleTier2Threshold());
    assertEquals(10, cfg.getSampleTier2Rate());
    assertEquals(100, cfg.getSampleTier3Rate());
    assertEquals(100, cfg.getHotEndpointCycles());
  }

  @Test
  void samplingThresholds_envIsIgnored() {
    // Sampling tiers + hot-endpoint cycles are internal now — the former sysprops/env vars have
    // no effect. (Tier thresholds/rates are reachable via the test-config hook; see below.)
    System.setProperty("otel.aws.service_events.sample.tier1.threshold", "50");
    System.setProperty("otel.aws.service_events.sample.tier2.threshold", "500");
    System.setProperty("otel.aws.service_events.sample.tier2.rate", "5");
    System.setProperty("otel.aws.service_events.sample.tier3.rate", "50");
    System.setProperty("otel.aws.service_events.hot.endpoint.cycles", "20");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(100, cfg.getSampleTier1Threshold());
    assertEquals(1000, cfg.getSampleTier2Threshold());
    assertEquals(10, cfg.getSampleTier2Rate());
    assertEquals(100, cfg.getSampleTier3Rate());
    assertEquals(100, cfg.getHotEndpointCycles());
  }

  // ───── Flush intervals + log group/stream are internal (no env override) ─────

  @Test
  void flushIntervals_defaults() {
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(30000, cfg.getFunctionCallFlushInterval());
    assertEquals(30000, cfg.getEndpointFlushInterval());
    assertEquals(86_400_000, cfg.getDeploymentEventFlushInterval());
  }

  @Test
  void flushIntervals_envIsIgnored() {
    System.setProperty("otel.aws.service_events.function.call.flush.interval", "1111");
    System.setProperty("otel.aws.service_events.endpoint.flush.interval", "2222");
    System.setProperty("otel.aws.service_events.deployment.event.flush.interval", "3333");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(30000, cfg.getFunctionCallFlushInterval());
    assertEquals(30000, cfg.getEndpointFlushInterval());
    assertEquals(86_400_000, cfg.getDeploymentEventFlushInterval());
  }

  @Test
  void logGroupStream_defaults() {
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals("/serviceevents/telemetry", cfg.getLogGroup());
    // logStream defaults to the parsed service name (UnknownService when unset).
    assertEquals("UnknownService", cfg.getLogStream());
  }

  @Test
  void logGroupStream_envIsIgnored() {
    System.setProperty("otel.aws.service_events.log.group", "/from/env");
    System.setProperty("otel.aws.service_events.log.stream", "from-env");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals("/serviceevents/telemetry", cfg.getLogGroup());
    assertEquals("UnknownService", cfg.getLogStream());
  }

  // ───── Internal test-config hook (DEBUG_SE_TEST_CONFIG -> debug.se.test.config) ─────

  @Test
  void testConfigHook_unsetIsNoop() {
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(30000, cfg.getEndpointFlushInterval());
    assertEquals(100, cfg.getSampleTier1Threshold());
    assertEquals("/serviceevents/telemetry", cfg.getLogGroup());
  }

  @Test
  void testConfigHook_overridesRecognizedKeys() {
    System.setProperty(
        "debug.se.test.config",
        "FUNCTION_CALL_FLUSH_INTERVAL=2000;ENDPOINT_FLUSH_INTERVAL=2500;"
            + "SAMPLE_TIER1_THRESHOLD=7;SAMPLE_TIER2_THRESHOLD=70;SAMPLE_TIER2_RATE=3;"
            + "SAMPLE_TIER3_RATE=30;"
            + "LOG_GROUP=/test/group;LOG_STREAM=test-stream");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    assertEquals(2000, cfg.getFunctionCallFlushInterval());
    assertEquals(2500, cfg.getEndpointFlushInterval());
    assertEquals(7, cfg.getSampleTier1Threshold());
    assertEquals(70, cfg.getSampleTier2Threshold());
    assertEquals(3, cfg.getSampleTier2Rate());
    assertEquals(30, cfg.getSampleTier3Rate());
    assertEquals("/test/group", cfg.getLogGroup());
    assertEquals("test-stream", cfg.getLogStream());
  }

  @Test
  void testConfigHook_ignoresUnknownAndGarbage() {
    System.setProperty(
        "debug.se.test.config", "UNKNOWN_KEY=1;ENDPOINT_FLUSH_INTERVAL=notanint;LOG_GROUP=/ok");
    ServiceEventsConfig cfg = ServiceEventsConfig.fromEnv();
    // Garbage int keeps the default; valid string key still applies; unknown key is ignored.
    assertEquals(30000, cfg.getEndpointFlushInterval());
    assertEquals("/ok", cfg.getLogGroup());
  }
}
