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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.OtlpHttpProfilesExporter;

/**
 * Configuration management for ServiceEvents instrumentation.
 *
 * <p>Provides environment variable parsing and configuration defaults for all ServiceEvents
 * features including bytecode instrumentation, collectors, and exporters.
 *
 * <p>{@code serviceName} and {@code environment} are parsed from the standard OTel env var {@code
 * OTEL_RESOURCE_ATTRIBUTES} (keys {@code service.name} and {@code deployment.environment(.name)}).
 */
public class ServiceEventsConfig {

  // Enable/Disable
  private final boolean enabled;

  // Local-testing file exporter. When set, replaces the OTLP network exporters
  // (LOGS_ENDPOINT and METRICS_ENDPOINT are ignored). Output is CloudWatch-faithful
  // NDJSON — one flat line per LogRecord, EMF envelope per metric data point.
  // See SERVICE_EVENTS_LOCAL_FILE_FORMAT.md at the monorepo root.
  private final String outputFile;

  // Service identity (from OTEL_RESOURCE_ATTRIBUTES)
  private final String serviceName;
  private final String environment;

  // Deployment identity
  private final String deploymentId;
  private final String deploymentTimestamp;
  private final String deploymentUrl;
  private final String gitCommitSha;
  private final String gitRepoUrl;
  private final String serviceCodeNamespace;

  // Flush Intervals (milliseconds)
  private final int functionCallFlushInterval;
  private final int endpointFlushInterval;
  private final int deploymentEventFlushInterval;

  // Bytecode Instrumentation
  private final boolean bytecodeEnabled;
  private final List<String> packagesExclude;
  private final List<String> packagesInclude;

  // Per-endpoint latency thresholds (comma-separated entries, each "METHOD /route:threshold_ms")
  private final List<String> latencyThresholds;

  // Endpoint filter glob patterns. Format: "METHOD /route" (e.g. "GET /api/*",
  // "* /health"). Empty include-list = track everything; exclude-list filters
  // out matches after include-list narrowing. Mirrors JS shouldTrackEndpoint.
  private final List<String> endpointIncludePatterns;
  private final List<String> endpointExcludePatterns;

  // Incident snapshot rate-limit parameters. Both are startup defaults. The
  // rate-limit window is fixed at 1 minute (not configurable).
  private final int incidentSnapshotMaxPerMinute;
  private final int incidentSnapshotMaxSameError;

  // Sampling for aws.service_events.function_call records (gates MethodAdvice hot path).
  // Default mode is "always" (every call sampled); "auto" applies tiered sampling as a
  // high-volume cost cap; "never" disables the duration metric. Mirrors Python/JS sampling shape.
  private final String samplingMode;
  private final int sampleTier1Threshold;
  private final int sampleTier2Threshold;
  private final int sampleTier2Rate;
  private final int sampleTier3Rate;

  // OTLP Export
  private final String logsEndpoint;
  private final String metricsEndpoint;
  private final String logGroup;
  private final String logStream;

  // Application Signals bundling. When true, ServiceEvents suppresses
  // aws.service_events.endpoint_summary LogRecords because App Signals already
  // carries equivalent per-endpoint duration and error metrics. The
  // EndpointCollector still runs so latency histograms feed IncidentSnapshot
  // thresholds. Per-exception-type error metrics still emit.
  private final boolean applicationSignalsEnabled;

  // Async Profiler

  /**
   * Profiling mode WALL — async-profiler {@code event=wall} (captures on- and off-CPU activity).
   */
  public static final int PROFILER_MODE_WALL = 0;

  /** Profiling mode CPU — async-profiler {@code event=cpu} (on-CPU only; perf/ctimer). */
  public static final int PROFILER_MODE_CPU = 1;

  /** OTLP transport HTTP/protobuf ({@code OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf}). */
  public static final int PROFILER_PROTOCOL_HTTP_PROTOBUF = 0;

  /** OTLP transport gRPC ({@code OTEL_EXPORTER_OTLP_PROTOCOL=grpc}). */
  public static final int PROFILER_PROTOCOL_GRPC = 1;

  private final boolean asyncProfilerEnabled;
  // Which async-profiler sampling event to run: PROFILER_MODE_WALL or PROFILER_MODE_CPU
  // (OTEL_AWS_PROFILER_MODE = wall | cpu).
  private final int profilerMode;
  // On-CPU (event=cpu) sampling interval in ms; wall interval below.
  // OTEL_AWS_PROFILER_CPU_INTERVAL_MS.
  private final int asyncProfilerCpuIntervalMs;
  private final int asyncProfilerWallIntervalMs;
  private final String asyncProfilerJfrFilePath;
  private final String profilerDataDir;

  // Native OTLP profiles export. profilerEndpoint is the fully-resolved profiles target,
  // resolved by protocol: for http/protobuf it is OTEL_AWS_PROFILER_ENDPOINT verbatim, else the
  // localhost:4318 default with the v1development profiles path appended; for grpc it is the bare
  // host:port target (no path) defaulting to localhost:4317. Compression defaults to gzip; the
  // timeout bounds each best-effort send.
  private final String profilerEndpoint;
  private final String profilerExportCompression;
  private final int profilerExportTimeoutMs;
  // OTLP transport for profiles: PROFILER_PROTOCOL_HTTP_PROTOBUF (default) or
  // PROFILER_PROTOCOL_GRPC, read from the shared OTEL_EXPORTER_OTLP_PROTOCOL (see fromEnv).
  private final int profilerProtocol;

  // Memory/allocation profiling. When enabled, async-profiler records allocation events
  // (jdk.ObjectAllocationInNewTLAB) in the same JFR session as the primary (wall or cpu) event,
  // the scanner reads them, and the builder emits an alloc_space (bytes) and alloc_objects (count)
  // Profile. profilerAllocIntervalBytes is async-profiler's alloc= sampling interval in bytes
  // (default 512 KiB).
  private final boolean profilerMemoryEnabled;
  private final long profilerAllocIntervalBytes;
  // OtlpProfileBuilder sample-aggregation mode, chosen by name via
  // OTEL_AWS_PROFILER_AGGREGATION_MODE (none|full|sum, case-insensitive; default none) and stored
  // here as the builder's int. Samples
  // sharing an identity (stack + trace link + attributes) can be merged into one OTLP Sample, per
  // the OTLP profiles shapes:
  //   none (0) — DEFAULT. no aggregation: one OTLP Sample per collected sample.
  //   full (1) — merge by identity; keep all per-observation values + timestamps (full
  //              fidelity), using the most compact lossless shape per profile: a profile whose
  //              values are all 1 in its unit (alloc_objects) emits values=[],
  //              timestamps_unix_nano=[all] (count=1/ts); profiles with real weights (wall/cpu ns,
  //              alloc bytes) emit parallel values=[all] ∥ timestamps=[all].
  //   sum (2)  — merge by identity; values=[total], timestamps_unix_nano=[] (most compact; drops
  //              per-observation timestamps). The only mode whose size scales with the
  //              distinct-identity count rather than the raw sample count, so it's the one that
  //              lets a fine sample interval fit under a backend's per-request cap.
  // All modes preserve trace/operation correlation (link + attributes are in the merge key).
  private final int profilerAggregationMode;
  // JFR rotation / export window in seconds (async-profiler loop= AND RotationBoundaryProcessor
  // windowSeconds — threaded to both). Default 60. A shorter window emits smaller per-window OTLP
  // requests (fewer samples + distinct identities), a lever for fitting under a backend's request
  // cap and for fresher profiles; clamped to [5, 300]s.
  private final int profilerWindowSeconds;
  // Client-side payload-size guard, in uncompressed bytes. A backend that caps request size may
  // reject requests whose *decompressed* body exceeds its (server-side) cap with an HTTP 413 that
  // drops the whole window (gzip does not help). Before sending, the profiles exporter (HTTP or
  // gRPC) compares the serialized ExportProfilesServiceRequest size against this limit and drops an
  // over-limit window with a WARN instead — so the drop is observable and actionable rather than a
  // silent 413. Default 64 MiB: a loose backstop well above typical backend request caps, so normal
  // large windows are never dropped client-side; 0 or negative disables the guard.
  private final long profilerMaxPayloadBytes;

  private ServiceEventsConfig(Builder builder) {
    this.enabled = builder.enabled;
    this.outputFile = builder.outputFile;
    this.serviceName = builder.serviceName;
    this.environment = builder.environment;
    this.deploymentId = builder.deploymentId;
    this.deploymentTimestamp = builder.deploymentTimestamp;
    this.deploymentUrl = builder.deploymentUrl;
    this.gitCommitSha = builder.gitCommitSha;
    this.gitRepoUrl = builder.gitRepoUrl;
    this.serviceCodeNamespace = builder.serviceCodeNamespace;
    this.functionCallFlushInterval = builder.functionCallFlushInterval;
    this.endpointFlushInterval = builder.endpointFlushInterval;
    this.deploymentEventFlushInterval = builder.deploymentEventFlushInterval;
    this.bytecodeEnabled = builder.bytecodeEnabled;
    this.packagesExclude = builder.packagesExclude;
    this.packagesInclude = builder.packagesInclude;
    this.latencyThresholds = builder.latencyThresholds;
    this.endpointIncludePatterns = builder.endpointIncludePatterns;
    this.endpointExcludePatterns = builder.endpointExcludePatterns;
    this.incidentSnapshotMaxPerMinute = builder.incidentSnapshotMaxPerMinute;
    this.incidentSnapshotMaxSameError = builder.incidentSnapshotMaxSameError;
    this.samplingMode = builder.samplingMode;
    this.sampleTier1Threshold = builder.sampleTier1Threshold;
    this.sampleTier2Threshold = builder.sampleTier2Threshold;
    this.sampleTier2Rate = builder.sampleTier2Rate;
    this.sampleTier3Rate = builder.sampleTier3Rate;
    this.logsEndpoint = builder.logsEndpoint;
    this.metricsEndpoint = builder.metricsEndpoint;
    this.logGroup = builder.logGroup;
    this.logStream = builder.logStream;
    this.applicationSignalsEnabled = builder.applicationSignalsEnabled;
    this.asyncProfilerEnabled = builder.asyncProfilerEnabled;
    this.profilerMode = builder.profilerMode;
    this.asyncProfilerCpuIntervalMs = builder.asyncProfilerCpuIntervalMs;
    this.asyncProfilerWallIntervalMs = builder.asyncProfilerWallIntervalMs;
    this.asyncProfilerJfrFilePath = builder.asyncProfilerJfrFilePath;
    this.profilerDataDir = builder.profilerDataDir;
    this.profilerEndpoint = builder.profilerEndpoint;
    this.profilerExportCompression = builder.profilerExportCompression;
    this.profilerExportTimeoutMs = builder.profilerExportTimeoutMs;
    this.profilerProtocol = builder.profilerProtocol;
    this.profilerMemoryEnabled = builder.profilerMemoryEnabled;
    this.profilerAllocIntervalBytes = builder.profilerAllocIntervalBytes;
    this.profilerAggregationMode = builder.profilerAggregationMode;
    this.profilerWindowSeconds = builder.profilerWindowSeconds;
    this.profilerMaxPayloadBytes = builder.profilerMaxPayloadBytes;
  }

  /** Build configuration from environment variables. */
  public static ServiceEventsConfig fromEnv() {
    // Parse serviceName and environment from OTEL_RESOURCE_ATTRIBUTES.
    // environment has no default — it stays null when unset so emit paths omit it.
    String parsedServiceName = "UnknownService";
    String parsedEnvironment = null;
    String resourceAttrs = getConfigValue("OTEL_RESOURCE_ATTRIBUTES");
    if (resourceAttrs != null && !resourceAttrs.isEmpty()) {
      for (String pair : resourceAttrs.split(",")) {
        String[] kv = pair.split("=", 2);
        if (kv.length == 2) {
          String key = kv[0].trim();
          String value = kv[1].trim();
          if ("service.name".equals(key)) {
            parsedServiceName = value;
          } else if ("deployment.environment".equals(key)
              || "deployment.environment.name".equals(key)) {
            parsedEnvironment = value;
          }
        }
      }
    }

    // Fall back to ENVIRONMENT env var if not found in OTEL_RESOURCE_ATTRIBUTES.
    // When still unset, environment remains null and is omitted from all emit paths.
    if (parsedEnvironment == null || parsedEnvironment.isEmpty()) {
      String envVar = getConfigValue("ENVIRONMENT");
      if (envVar != null && !envVar.isEmpty()) {
        parsedEnvironment = envVar;
      }
    }

    // Check standalone OTEL_SERVICE_NAME / -Dotel.service.name (takes precedence
    // over service.name in OTEL_RESOURCE_ATTRIBUTES, per the OTel spec)
    String standaloneServiceName = getConfigValue("OTEL_SERVICE_NAME");
    if (standaloneServiceName != null && !standaloneServiceName.isEmpty()) {
      parsedServiceName = standaloneServiceName;
    }

    // Endpoint policy:
    // - App Signals enabled: unset/empty endpoints default to the 4316 App Signals receiver.
    // - App Signals disabled + ServiceEvents force-enabled: endpoints are required; disable
    //   ServiceEvents with an error log rather than silently defaulting.
    // - OUTPUT_FILE mode replaces the OTLP exporters entirely, so the endpoint requirement
    //   doesn't apply.
    boolean effectiveEnabled = resolveEffectiveEnabled();
    String outputFile = getStringEnv("OTEL_AWS_SERVICE_EVENTS_OUTPUT_FILE", "");
    String[] endpoints = resolveEndpoints(effectiveEnabled);
    String resolvedLogsEndpoint = endpoints[0];
    String resolvedMetricsEndpoint = endpoints[1];
    if (effectiveEnabled
        && outputFile.isEmpty()
        && (resolvedLogsEndpoint == null || resolvedMetricsEndpoint == null)) {
      System.err.println(
          "[SERVICE_EVENTS] Force-enabled (OTEL_AWS_SERVICE_EVENTS_ENABLED=true) without Application Signals,"
              + " but OTEL_AWS_OTLP_LOGS_ENDPOINT / OTEL_AWS_OTLP_METRICS_ENDPOINT are"
              + " unset or empty. Both are required in this mode. Disabling ServiceEvents.");
      effectiveEnabled = false;
      resolvedLogsEndpoint = "";
      resolvedMetricsEndpoint = "";
    } else {
      if (resolvedLogsEndpoint == null) resolvedLogsEndpoint = "";
      if (resolvedMetricsEndpoint == null) resolvedMetricsEndpoint = "";
    }

    // Internal knobs no longer have their own env vars; their hardcoded defaults stand. A few are
    // reachable only through the gated, undocumented test-config hook (DEBUG_SE_TEST_CONFIG) that
    // black-box contract/e2e suites set — see parseTestConfigHook().
    Map<String, String> hook = parseTestConfigHook();

    // OTLP transport for profiles, read from the shared OTEL_EXPORTER_OTLP_PROTOCOL: "profiles" is
    // not a standard OTel autoconfigure signal, so there is no per-signal protocol variable. The
    // endpoint stays independently settable via OTEL_AWS_PROFILER_ENDPOINT.
    // Resolved before the endpoint because endpoint resolution is protocol-aware (grpc uses a bare
    // host:port target with no path and a different default port).
    int profilerProtocol = parseProfilerProtocol(getConfigValue("OTEL_EXPORTER_OTLP_PROTOCOL"));

    ServiceEventsConfig config =
        new Builder()
            .enabled(effectiveEnabled)
            .outputFile(outputFile)
            .serviceName(parsedServiceName)
            .environment(parsedEnvironment)
            .deploymentId(
                getStringEnv("OTEL_AWS_SERVICE_EVENTS_DEPLOYMENT_ID", "unknown-deployment-id"))
            .deploymentTimestamp(getStringEnv("OTEL_AWS_SERVICE_EVENTS_DEPLOYMENT_TIMESTAMP", ""))
            .deploymentUrl(getStringEnv("OTEL_AWS_SERVICE_EVENTS_DEPLOYMENT_URL", ""))
            .gitCommitSha(getStringEnv("OTEL_AWS_SERVICE_EVENTS_GIT_COMMIT_SHA", ""))
            .gitRepoUrl(getStringEnv("OTEL_AWS_SERVICE_EVENTS_GIT_REPO_URL", ""))
            // serviceCodeNamespace and deploymentEventFlushInterval are internal with no
            // override — Builder defaults stand.
            .functionCallFlushInterval(hookInt(hook, "FUNCTION_CALL_FLUSH_INTERVAL", 30000))
            .endpointFlushInterval(hookInt(hook, "ENDPOINT_FLUSH_INTERVAL", 30000))
            .bytecodeEnabled(
                getBoolEnv("OTEL_AWS_SERVICE_EVENTS_FUNCTION_INSTRUMENT_ENABLED", true))
            // PACKAGES_INCLUDE is the only opt-in; PACKAGES_EXCLUDE always wins over it.
            // There is no implicit default scope and no user-overridable blocklist — the
            // non-configurable SDK_SELF_EXCLUDE is the SDK's only built-in filter.
            .packagesExclude(
                normalizePatterns(
                    getListEnv("OTEL_AWS_SERVICE_EVENTS_PACKAGES_EXCLUDE", new ArrayList<>()),
                    "OTEL_AWS_SERVICE_EVENTS_PACKAGES_EXCLUDE"))
            .packagesInclude(
                normalizePatterns(
                    getListEnv("OTEL_AWS_SERVICE_EVENTS_PACKAGES_INCLUDE", new ArrayList<>()),
                    "OTEL_AWS_SERVICE_EVENTS_PACKAGES_INCLUDE"))
            .latencyThresholds(
                getListEnv("OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS", new ArrayList<>()))
            .endpointIncludePatterns(
                getListEnv("OTEL_AWS_SERVICE_EVENTS_ENDPOINT_INCLUDE_PATTERNS", new ArrayList<>()))
            .endpointExcludePatterns(
                getListEnv("OTEL_AWS_SERVICE_EVENTS_ENDPOINT_EXCLUDE_PATTERNS", new ArrayList<>()))
            .incidentSnapshotMaxPerMinute(
                getIntEnv("OTEL_AWS_SERVICE_EVENTS_INCIDENT_SNAPSHOT_MAX_PER_MINUTE", 100))
            .incidentSnapshotMaxSameError(
                getIntEnv("OTEL_AWS_SERVICE_EVENTS_INCIDENT_SNAPSHOT_MAX_SAME_ERROR", 1))
            .samplingMode(getStringEnv("OTEL_AWS_SERVICE_EVENTS_SAMPLING_MODE", "always"))
            .sampleTier1Threshold(hookInt(hook, "SAMPLE_TIER1_THRESHOLD", 100))
            .sampleTier2Threshold(hookInt(hook, "SAMPLE_TIER2_THRESHOLD", 1000))
            .sampleTier2Rate(hookInt(hook, "SAMPLE_TIER2_RATE", 10))
            .sampleTier3Rate(hookInt(hook, "SAMPLE_TIER3_RATE", 100))
            .logsEndpoint(resolvedLogsEndpoint)
            .metricsEndpoint(resolvedMetricsEndpoint)
            .logGroup(hookStr(hook, "LOG_GROUP", "/serviceevents/telemetry"))
            .logStream(hookStr(hook, "LOG_STREAM", parsedServiceName))
            .applicationSignalsEnabled(getBoolEnv("OTEL_AWS_APPLICATION_SIGNALS_ENABLED", false))
            // Profiler enablement is decoupled from ServiceEvents/App Signals: the profiler is
            // enabled by its own OTEL_AWS_PROFILER_ENABLED (otel.aws.profiler.enabled) flag and can
            // run even when ServiceEvents is disabled.
            .asyncProfilerEnabled(getBoolEnv("OTEL_AWS_PROFILER_ENABLED", false))
            // Wall sampling interval (ms). OTEL_AWS_PROFILER_WALL_INTERVAL_MS.
            .asyncProfilerWallIntervalMs(getIntEnv("OTEL_AWS_PROFILER_WALL_INTERVAL_MS", 10))
            // Sampling event: wall (default, on- and off-CPU) | cpu (on-CPU only). Unrecognized
            // values fall back to wall (see parseProfilerMode).
            .profilerMode(parseProfilerMode(getStringEnv("OTEL_AWS_PROFILER_MODE", "wall")))
            // On-CPU sampling interval (ms), used in cpu mode. Wall uses WALL_INTERVAL_MS above.
            .asyncProfilerCpuIntervalMs(getIntEnv("OTEL_AWS_PROFILER_CPU_INTERVAL_MS", 10))
            // Data dir for JFR files + native-lib extraction. OTEL_AWS_PROFILER_DATA_DIR.
            .profilerDataDir(getStringEnv("OTEL_AWS_PROFILER_DATA_DIR", ""))
            // Transport for profiles (grpc | http/protobuf), from the shared
            // OTEL_EXPORTER_OTLP_PROTOCOL (see the profilerProtocol local above).
            .profilerProtocol(profilerProtocol)
            // Native OTLP profiles export. Protocol-aware endpoint resolution (OTel
            // per-signal-then-base): OTEL_AWS_PROFILER_ENDPOINT verbatim; else
            // OTEL_EXPORTER_OTLP_ENDPOINT base (http: + profiles path; grpc: as-is, no path); else
            // the default (http: localhost:4318 + path; grpc: localhost:4317, no path). Compression
            // defaults to gzip; timeout bounds each best-effort send.
            .profilerEndpoint(
                resolveProfilerEndpoint(
                    getConfigValue("OTEL_AWS_PROFILER_ENDPOINT"),
                    getConfigValue("OTEL_EXPORTER_OTLP_ENDPOINT"),
                    profilerProtocol))
            .profilerExportCompression(getStringEnv("OTEL_AWS_PROFILER_EXPORT_COMPRESSION", "gzip"))
            .profilerExportTimeoutMs(getIntEnv("OTEL_AWS_PROFILER_EXPORT_TIMEOUT_MS", 10000))
            // Memory/allocation profiling: off by default; when enabled it adds async-profiler's
            // alloc= event to the same JFR session. The interval is alloc= sampling bytes.
            .profilerMemoryEnabled(getBoolEnv("OTEL_AWS_PROFILER_MEMORY_ENABLED", false))
            .profilerAllocIntervalBytes(
                getLongEnv("OTEL_AWS_PROFILER_ALLOC_INTERVAL_BYTES", 524288L))
            // Sample-aggregation mode by name: none|full|sum (default none). none emits one OTLP
            // Sample per raw sample (widest backend compatibility, largest payload); full/sum merge
            // by identity to shrink the per-window payload (sum is the compact shape that best fits
            // a finer interval under a backend's request cap). See the profilerAggregationMode
            // field doc for the shapes.
            .profilerAggregationMode(
                parseAggregationMode(getStringEnv("OTEL_AWS_PROFILER_AGGREGATION_MODE", "none")))
            // JFR rotation / export window in seconds (default 60). Shorter = smaller per-window
            // OTLP requests + fresher profiles; threaded to both async-profiler loop= and the
            // RotationBoundaryProcessor window.
            .profilerWindowSeconds(getIntEnv("OTEL_AWS_PROFILER_WINDOW_SECONDS", 60))
            // Client-side payload-size guard in uncompressed bytes (default 64 MiB, a loose
            // backstop well above typical backend request caps). Over-limit windows are dropped
            // with a WARN instead of being sent and rejected with a 413. 0/negative disables the
            // guard.
            .profilerMaxPayloadBytes(
                getLongEnv(
                    "OTEL_AWS_PROFILER_MAX_PAYLOAD_BYTES",
                    OtlpHttpProfilesExporter.DEFAULT_MAX_PAYLOAD_BYTES))
            .build();

    // One-shot misconfig warning: function instrumentation is enabled but the allowlist is
    // empty, so no functions will be instrumented (there is no implicit default scope —
    // see SERVICE_EVENTS_ENV_VARS.md §3.4.1). The process keeps running; endpoint/incident
    // signals are unaffected. fromEnv() is called once per TypeInstrumentation, so this is
    // effectively one-shot per matcher.
    if (config.bytecodeEnabled && config.packagesInclude.isEmpty()) {
      Logger.getLogger(ServiceEventsConfig.class.getName())
          .warning(
              "OTEL_AWS_SERVICE_EVENTS_FUNCTION_INSTRUMENT_ENABLED=true but"
                  + " OTEL_AWS_SERVICE_EVENTS_PACKAGES_INCLUDE is empty — no functions will be"
                  + " instrumented. Set PACKAGES_INCLUDE to opt in.");
    }
    return config;
  }

  // Configuration parsing helpers - checks system properties first, then environment variables.
  // Each env var maps to a lowercase dotted system property, so config can be driven by either:
  //   OTEL_AWS_SERVICE_EVENTS_ENABLED  <->  -Dotel.aws.service_events.enabled
  // Names without the OTEL_AWS_SERVICE_EVENTS_ prefix use the generic transform, so the
  // internal test-config hook maps as DEBUG_SE_TEST_CONFIG <-> -Ddebug.se.test.config.

  private static String getConfigValue(String envName) {
    String propName;
    if (envName.startsWith("OTEL_AWS_SERVICE_EVENTS_")) {
      String suffix = envName.substring("OTEL_AWS_SERVICE_EVENTS_".length());
      propName = "otel.aws.service_events." + suffix.toLowerCase().replace('_', '.');
    } else {
      propName = envName.toLowerCase().replace('_', '.');
    }

    // Check system property first
    String value = System.getProperty(propName);
    if (value != null && !value.isEmpty()) {
      return value;
    }

    // Fall back to environment variable
    return System.getenv(envName);
  }

  // --- Internal test-config hook -------------------------------------------------------------
  // DEBUG_SE_TEST_CONFIG is an undocumented, gated, test-only affordance. Black-box contract/e2e
  // suites run the agent in a separate JVM and can only inject config via env/sysprop, so the
  // handful of internal knobs they need (flush intervals, sample tiers, profile-export
  // compression, log group/stream) are reachable through this single delimited string instead of
  // dedicated public env vars. Format: "KEY=value;KEY=value", KEY being the former env-var suffix.
  // NOT for production use.

  static final String TEST_CONFIG_HOOK_ENV = "DEBUG_SE_TEST_CONFIG";

  private static volatile boolean testConfigHookWarned = false;

  /**
   * Parse {@code DEBUG_SE_TEST_CONFIG} into a key->value map. Returns an empty map (and does
   * nothing else) when unset/empty — a literal no-op in the common case. Emits a one-time WARN when
   * active. Unparsable entries are skipped; the caller's {@link #hookInt}/{@link #hookStr} apply
   * only recognized keys.
   */
  private static Map<String, String> parseTestConfigHook() {
    String raw = getConfigValue(TEST_CONFIG_HOOK_ENV);
    if (raw == null || raw.isEmpty()) {
      return java.util.Collections.emptyMap();
    }
    if (!testConfigHookWarned) {
      testConfigHookWarned = true;
      Logger.getLogger(ServiceEventsConfig.class.getName())
          .log(
              Level.WARNING,
              "ServiceEvents: {0} is set — applying internal test config overrides. This is a"
                  + " test-only hook and is NOT for production use.",
              TEST_CONFIG_HOOK_ENV);
    }
    Map<String, String> overrides = new HashMap<>();
    for (String entry : raw.split(";")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      overrides.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
    }
    return overrides;
  }

  /** Integer override from the hook map, falling back to the hardcoded default. */
  private static int hookInt(Map<String, String> hook, String key, int defaultValue) {
    String value = hook.get(key);
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /** String override from the hook map, falling back to the hardcoded default. */
  private static String hookStr(Map<String, String> hook, String key, String defaultValue) {
    String value = hook.get(key);
    return (value == null || value.isEmpty()) ? defaultValue : value;
  }

  private static boolean getBoolEnv(String name, boolean defaultValue) {
    String value = getConfigValue(name);
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    return "true".equalsIgnoreCase(value);
  }

  /**
   * Compute effective ServiceEvents enablement.
   *
   * <p>ServiceEvents is bundled with Application Signals: enabled by default when App Signals is
   * enabled, disabled otherwise, and always disabled on Lambda regardless. An explicit {@code
   * OTEL_AWS_SERVICE_EVENTS_ENABLED} value (true/false) overrides the bundling.
   */
  private static boolean resolveEffectiveEnabled() {
    if (isLambdaEnvironment()) {
      return false;
    }
    String explicit = getConfigValue("OTEL_AWS_SERVICE_EVENTS_ENABLED");
    if (explicit != null && !explicit.isEmpty()) {
      return "true".equalsIgnoreCase(explicit);
    }
    String appSignals = getConfigValue("OTEL_AWS_APPLICATION_SIGNALS_ENABLED");
    return appSignals != null && "true".equalsIgnoreCase(appSignals);
  }

  // Public so the profiler-init path (ServiceEventsInstrumentation) can reuse the same Lambda
  // detection for its runtime gate; also used by tests. Accepts either the env var or the
  // lowercase dotted property form.
  public static boolean isLambdaEnvironment() {
    if (System.getenv("AWS_LAMBDA_FUNCTION_NAME") != null) {
      return true;
    }
    String prop = System.getProperty("aws.lambda.function.name");
    return prop != null && !prop.isEmpty();
  }

  /**
   * Resolve logs/metrics endpoints per the endpoint policy.
   *
   * <p>Returns a two-element array {@code [logs, metrics]}. When ServiceEvents is effectively
   * enabled and App Signals is enabled, unset/empty endpoints default to the 4316 App Signals
   * receiver. When ServiceEvents is force-enabled without App Signals, unset/empty endpoints are
   * returned as {@code null} so the caller can refuse to initialize.
   */
  private static String[] resolveEndpoints(boolean effectiveEnabled) {
    String logs = getConfigValue("OTEL_AWS_OTLP_LOGS_ENDPOINT");
    String metrics = getConfigValue("OTEL_AWS_OTLP_METRICS_ENDPOINT");
    boolean logsSet = logs != null && !logs.isEmpty();
    boolean metricsSet = metrics != null && !metrics.isEmpty();
    if (!effectiveEnabled) {
      return new String[] {logsSet ? logs : "", metricsSet ? metrics : ""};
    }
    String appSignals = getConfigValue("OTEL_AWS_APPLICATION_SIGNALS_ENABLED");
    boolean appSignalsEnabled = appSignals != null && "true".equalsIgnoreCase(appSignals);
    if (appSignalsEnabled) {
      return new String[] {
        logsSet ? logs : "http://localhost:4316/v1/logs",
        metricsSet ? metrics : "http://localhost:4316/v1/metrics"
      };
    }
    // Force-enabled without App Signals: endpoints are required.
    return new String[] {logsSet ? logs : null, metricsSet ? metrics : null};
  }

  /**
   * Default base for the native OTLP/HTTP profiles endpoint when OTEL_AWS_PROFILER_ENDPOINT is
   * unset.
   */
  static final String DEFAULT_PROFILER_ENDPOINT_BASE = "http://localhost:4318";

  /**
   * Default gRPC target when OTEL_AWS_PROFILER_ENDPOINT is unset and the protocol is grpc — the
   * standard OTLP/gRPC port, with no path (the RPC method path is added by the gRPC exporter).
   */
  static final String DEFAULT_PROFILER_GRPC_ENDPOINT_BASE = "http://localhost:4317";

  /** The v1development profiles path appended (exactly once) to a non-PROFILER_ENDPOINT base. */
  static final String PROFILES_PATH = "/v1development/profiles";

  /** Resolved default profiles endpoint = base + profiles path (HTTP). */
  static final String DEFAULT_PROFILER_ENDPOINT = DEFAULT_PROFILER_ENDPOINT_BASE + PROFILES_PATH;

  /**
   * Resolve the final native OTLP profiles target, mirroring the OTel per-signal-then-base
   * convention and adapting to the transport {@code protocol}:
   *
   * <ol>
   *   <li>{@code OTEL_AWS_PROFILER_ENDPOINT} — used <b>verbatim</b> for both transports as a full
   *       per-signal target (the gRPC exporter takes its scheme+authority and appends the RPC
   *       path).
   *   <li>else {@code OTEL_EXPORTER_OTLP_ENDPOINT} — the standard OTLP <b>base</b>: for
   *       http/protobuf, {@link #PROFILES_PATH} is appended (idempotently); for grpc it is used
   *       as-is (no path — gRPC targets a host:port).
   *   <li>else the default: {@link #DEFAULT_PROFILER_ENDPOINT_BASE} + {@link #PROFILES_PATH} for
   *       http/protobuf, or {@link #DEFAULT_PROFILER_GRPC_ENDPOINT_BASE} (no path) for grpc.
   * </ol>
   *
   * @param profilerEndpointRaw raw {@code OTEL_AWS_PROFILER_ENDPOINT} value (may be null/blank)
   * @param otlpBaseRaw raw {@code OTEL_EXPORTER_OTLP_ENDPOINT} value (may be null/blank)
   * @param protocol {@link #PROFILER_PROTOCOL_GRPC} or {@link #PROFILER_PROTOCOL_HTTP_PROTOBUF}
   */
  static String resolveProfilerEndpoint(
      String profilerEndpointRaw, String otlpBaseRaw, int protocol) {
    boolean grpc = protocol == PROFILER_PROTOCOL_GRPC;
    if (profilerEndpointRaw != null && !profilerEndpointRaw.trim().isEmpty()) {
      return profilerEndpointRaw.trim();
    }
    if (otlpBaseRaw != null && !otlpBaseRaw.trim().isEmpty()) {
      String base = otlpBaseRaw.trim();
      return grpc ? stripTrailingSlashes(base) : appendProfilesPath(base);
    }
    return grpc
        ? DEFAULT_PROFILER_GRPC_ENDPOINT_BASE
        : appendProfilesPath(DEFAULT_PROFILER_ENDPOINT_BASE);
  }

  private static String stripTrailingSlashes(String s) {
    String trimmed = s.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  /**
   * Append {@link #PROFILES_PATH} to a base URL exactly once. Idempotent (a base already ending in
   * the profiles path is returned unchanged) and trailing-slash safe.
   */
  static String appendProfilesPath(String base) {
    String trimmed = base.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    if (trimmed.endsWith(PROFILES_PATH)) {
      return trimmed;
    }
    return trimmed + PROFILES_PATH;
  }

  private static int getIntEnv(String name, int defaultValue) {
    String value = getConfigValue(name);
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static long getLongEnv(String name, long defaultValue) {
    String value = getConfigValue(name);
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static String getStringEnv(String name, String defaultValue) {
    String value = getConfigValue(name);
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    return value;
  }

  /**
   * Map the {@code OTEL_AWS_PROFILER_AGGREGATION_MODE} name (case-insensitive {@code none} | {@code
   * full} | {@code sum}) to the {@code OtlpProfileBuilder} mode int (0/1/2). An unrecognized value
   * falls back to {@code none} (the default) with a WARN naming the valid set.
   */
  private static int parseAggregationMode(String raw) {
    String v = (raw == null) ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    switch (v) {
      case "none":
        return 0;
      case "full":
        return 1;
      case "sum":
        return 2;
      default:
        Logger.getLogger(ServiceEventsConfig.class.getName())
            .warning(
                "Unknown OTEL_AWS_PROFILER_AGGREGATION_MODE '"
                    + raw
                    + "'; using 'none'. Valid values: none, full, sum.");
        return 0;
    }
  }

  /**
   * Map the shared {@code OTEL_EXPORTER_OTLP_PROTOCOL} value to the profiler transport int: {@code
   * grpc} → {@link #PROFILER_PROTOCOL_GRPC}, {@code http/protobuf} → {@link
   * #PROFILER_PROTOCOL_HTTP_PROTOBUF}. Unset falls back to http/protobuf silently. Any other value
   * — including {@code http/json}, which the profiler's exporters do not implement — also falls
   * back to http/protobuf, with a WARN so a shared setting the profiler can't honor is visible
   * (logged per {@code fromEnv()} call, matching {@code parseProfilerMode}/{@code
   * parseAggregationMode}).
   */
  private static int parseProfilerProtocol(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return PROFILER_PROTOCOL_HTTP_PROTOBUF;
    }
    String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
    switch (v) {
      case "grpc":
        return PROFILER_PROTOCOL_GRPC;
      case "http/protobuf":
        return PROFILER_PROTOCOL_HTTP_PROTOBUF;
      default:
        Logger.getLogger(ServiceEventsConfig.class.getName())
            .warning(
                "OTEL_EXPORTER_OTLP_PROTOCOL '"
                    + raw
                    + "' is not supported for profiles; using 'http/protobuf'. The profiler"
                    + " supports: grpc, http/protobuf.");
        return PROFILER_PROTOCOL_HTTP_PROTOBUF;
    }
  }

  /**
   * Map the {@code OTEL_AWS_PROFILER_MODE} name (case-insensitive {@code wall} | {@code cpu}) to
   * the profiling-mode int ({@link #PROFILER_MODE_WALL} / {@link #PROFILER_MODE_CPU}). Any
   * unrecognized value falls back to {@code wall} with a WARN.
   */
  private static int parseProfilerMode(String raw) {
    String v = (raw == null) ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    switch (v) {
      case "wall":
        return PROFILER_MODE_WALL;
      case "cpu":
        return PROFILER_MODE_CPU;
      default:
        Logger.getLogger(ServiceEventsConfig.class.getName())
            .warning(
                "Unknown OTEL_AWS_PROFILER_MODE '"
                    + raw
                    + "'; using 'wall'. Valid values: wall, cpu.");
        return PROFILER_MODE_WALL;
    }
  }

  private static List<String> getListEnv(String name, List<String> defaultValue) {
    return getDelimitedListEnv(name, ",", defaultValue);
  }

  /**
   * Strip entries that are not valid package prefixes. Currently that's just the bare {@code *}
   * sentinel — rejected as too broad (it would match every class, defeating the point of an
   * explicit allowlist). We log at INFO and ignore the entry; other entries in the same list pass
   * through untouched. An empty list instruments nothing — there is no implicit default scope.
   */
  private static List<String> normalizePatterns(List<String> patterns, String envName) {
    if (patterns == null || patterns.isEmpty()) {
      return patterns;
    }
    List<String> normalized = new ArrayList<>(patterns.size());
    for (String pattern : patterns) {
      if (pattern == null) {
        continue;
      }
      String trimmed = pattern.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (trimmed.equals("*")) {
        Logger.getLogger(ServiceEventsConfig.class.getName())
            .info(
                "ServiceEvents: ignoring bare '*' entry in "
                    + envName
                    + "; use specific package prefixes (e.g. com.myapp). An empty list instruments"
                    + " nothing.");
        continue;
      }
      normalized.add(trimmed);
    }
    return normalized;
  }

  private static List<String> getDelimitedListEnv(
      String name, String delimiterRegex, List<String> defaultValue) {
    String value = getConfigValue(name);
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    String[] parts = value.split(delimiterRegex);
    List<String> result = new ArrayList<>();
    for (String part : parts) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        result.add(trimmed);
      }
    }
    return result.isEmpty() ? defaultValue : result;
  }

  // Getters
  public boolean isEnabled() {
    return enabled;
  }

  public String getOutputFile() {
    return outputFile;
  }

  public String getServiceName() {
    return serviceName;
  }

  public String getEnvironment() {
    return environment;
  }

  public String getDeploymentId() {
    return deploymentId;
  }

  public String getDeploymentTimestamp() {
    return deploymentTimestamp;
  }

  public String getDeploymentUrl() {
    return deploymentUrl;
  }

  public String getGitCommitSha() {
    return gitCommitSha;
  }

  public String getGitRepoUrl() {
    return gitRepoUrl;
  }

  public String getServiceCodeNamespace() {
    return serviceCodeNamespace;
  }

  public int getFunctionCallFlushInterval() {
    return functionCallFlushInterval;
  }

  public int getEndpointFlushInterval() {
    return endpointFlushInterval;
  }

  public int getDeploymentEventFlushInterval() {
    return deploymentEventFlushInterval;
  }

  public boolean isBytecodeEnabled() {
    return bytecodeEnabled;
  }

  public List<String> getPackagesExclude() {
    return packagesExclude;
  }

  public List<String> getPackagesInclude() {
    return packagesInclude;
  }

  /**
   * SDK self-exclusion list — the non-configurable safety boundary applied before the
   * PACKAGES_INCLUDE rules. Covers the entire ADOT SDK (the agent's own code) and OpenTelemetry
   * itself. Customers cannot opt these in via {@code PACKAGES_INCLUDE}: instrumenting them would
   * cause classloader cycles or infinite recursion in the tracing pipeline.
   *
   * <p>Matched as an FQCN prefix (trailing dots included). Deliberately enumerates the real ADOT
   * module roots rather than the bare {@code software.amazon.opentelemetry.} umbrella — the
   * umbrella would also catch the {@code software.amazon.opentelemetry.appsignals.tests.*}
   * contract/e2e test apps, which must remain instrumentable. Customer code under {@code
   * software.amazon.<theirapp>.*} likewise stays instrumentable.
   */
  public static final List<String> SDK_SELF_EXCLUDE =
      java.util.Collections.unmodifiableList(
          Arrays.asList(
              "io.opentelemetry.",
              "software.amazon.opentelemetry.javaagent.",
              "software.amazon.opentelemetry.serviceevents.",
              "software.amazon.opentelemetry.awspropagator.",
              "software.amazon.opentelemetry.awsagentprovider.",
              "software.amazon.opentelemetry.di."));

  /**
   * Raw comma-separated entries parsed from {@code OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS}.
   * Each entry is of the form {@code METHOD /route:threshold_ms} (glob on method and route allowed;
   * threshold parsed from the text after the last {@code :}). Empty by default. Matches the comma
   * delimiter used by the Python and JS SDKs; routes containing literal commas (e.g. query strings
   * like {@code ?q=a,b,c}) must be expressed with a glob (e.g. {@code GET /search*}).
   */
  public List<String> getLatencyThresholds() {
    return latencyThresholds;
  }

  /** Glob patterns. Empty list means "track all endpoints" (include-all default). */
  public List<String> getEndpointIncludePatterns() {
    return endpointIncludePatterns;
  }

  /** Glob patterns. Endpoints matching any entry are filtered out (after include filter). */
  public List<String> getEndpointExcludePatterns() {
    return endpointExcludePatterns;
  }

  /** Max incident snapshots per minute (the rate-limit window is fixed at 1 minute). */
  public int getIncidentSnapshotMaxPerMinute() {
    return incidentSnapshotMaxPerMinute;
  }

  /** Max snapshots per distinct error hash within one rate-limit window. */
  public int getIncidentSnapshotMaxSameError() {
    return incidentSnapshotMaxSameError;
  }

  /**
   * Sampling mode for {@code aws.service_events.function_call} records: {@code "always"} (default),
   * {@code "auto"} (tiered cost cap), or {@code "never"}. Only gates MethodAdvice;
   * endpoint/incident signals are unaffected.
   */
  public String getSamplingMode() {
    return samplingMode;
  }

  public int getSampleTier1Threshold() {
    return sampleTier1Threshold;
  }

  public int getSampleTier2Threshold() {
    return sampleTier2Threshold;
  }

  public int getSampleTier2Rate() {
    return sampleTier2Rate;
  }

  public int getSampleTier3Rate() {
    return sampleTier3Rate;
  }

  public String getLogsEndpoint() {
    return logsEndpoint;
  }

  public String getMetricsEndpoint() {
    return metricsEndpoint;
  }

  public String getLogGroup() {
    return logGroup;
  }

  public String getLogStream() {
    return logStream;
  }

  /**
   * Whether Application Signals is enabled. When true, ServiceEvents suppresses EndpointSummary
   * LogRecords since App Signals carries equivalent per-endpoint metrics.
   */
  public boolean isApplicationSignalsEnabled() {
    return applicationSignalsEnabled;
  }

  /**
   * Whether the profiler is enabled. Resolved in {@link #fromEnv()} from the {@code
   * OTEL_AWS_PROFILER_ENABLED} / {@code otel.aws.profiler.enabled} flag. Independent of {@link
   * #isEnabled()}: the profiler can run even when ServiceEvents / Application Signals is disabled.
   * The Lambda / Windows runtime gates are applied in the profiler-init path (see {@code
   * ServiceEventsInstrumentation.initialize()}), not here.
   */
  public boolean isProfilerEnabled() {
    return asyncProfilerEnabled;
  }

  /** Alias for {@link #isProfilerEnabled()}. */
  public boolean isAsyncProfilerEnabled() {
    return isProfilerEnabled();
  }

  public int getAsyncProfilerCpuIntervalMs() {
    return asyncProfilerCpuIntervalMs;
  }

  /**
   * Profiling mode: {@link #PROFILER_MODE_WALL} (async-profiler {@code event=wall}) or {@link
   * #PROFILER_MODE_CPU} (on-CPU {@code event=cpu}). From {@code OTEL_AWS_PROFILER_MODE}; default
   * wall.
   */
  public int getProfilerMode() {
    return profilerMode;
  }

  public int getAsyncProfilerWallIntervalMs() {
    return asyncProfilerWallIntervalMs;
  }

  public String getAsyncProfilerJfrFilePath() {
    return asyncProfilerJfrFilePath;
  }

  /**
   * Get the directory path for profiler data files.
   *
   * <p>JFR files are written as {@code {dir}/profiler-jfr-{timestamp}.jfr}. Span&rarr;sample
   * correlation is carried inside the JFR via {@code one.profiler.Span} markers (decoded by {@link
   * software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.ProfilerSpanTag}),
   * so no separate metadata files are written.
   */
  public String getProfilerDataDir() {
    return profilerDataDir;
  }

  /** Fully-resolved native OTLP profiles endpoint URL (used verbatim by the exporter). */
  public String getProfilerEndpoint() {
    return profilerEndpoint;
  }

  /** Request compression for the profiles exporter ({@code gzip} by default). */
  public String getProfilerExportCompression() {
    return profilerExportCompression;
  }

  /** Per-request timeout (ms) for the profiles exporter. */
  public int getProfilerExportTimeoutMs() {
    return profilerExportTimeoutMs;
  }

  /**
   * OTLP transport for profiles: {@link #PROFILER_PROTOCOL_HTTP_PROTOBUF} (default) or {@link
   * #PROFILER_PROTOCOL_GRPC}. From the shared {@code OTEL_EXPORTER_OTLP_PROTOCOL}.
   */
  public int getProfilerProtocol() {
    return profilerProtocol;
  }

  /**
   * Whether memory/allocation profiling is enabled (default false). When true, {@code
   * AsyncProfilerWrapper} appends {@code alloc=<allocIntervalBytes>} to the start command so
   * async-profiler records {@code jdk.ObjectAllocationInNewTLAB} events in the same JFR session,
   * and the scanner/builder emit an {@code alloc_space} (bytes) + {@code alloc_objects} (count)
   * Profile alongside the primary (wall or cpu) Profile. Independent of {@link
   * #isProfilerEnabled()} in intent, but only has effect while the profiler itself is enabled and
   * running.
   */
  public boolean isProfilerMemoryEnabled() {
    return profilerMemoryEnabled;
  }

  /**
   * async-profiler {@code alloc=} sampling interval in bytes (default 524288 = 512 KiB). Also used
   * as the {@code period} of the emitted {@code alloc_space} Profile ({alloc_space, bytes}).
   */
  public long getProfilerAllocIntervalBytes() {
    return profilerAllocIntervalBytes;
  }

  /**
   * OTLP profile sample-aggregation mode as the {@code OtlpProfileBuilder} int (chosen by name via
   * {@code OTEL_AWS_PROFILER_AGGREGATION_MODE} = {@code none} | {@code full} | {@code sum}, default
   * {@code none}): {@code 0} NONE (one Sample per sample), {@code 1} FULL (merge by identity, keep
   * all per-observation values+timestamps; all-1-in-unit profiles use the empty-values shape),
   * {@code 2} SUM (merge by identity into a summed value with no timestamps — the compact shape
   * that lets a fine sample interval fit under a backend's request cap). All modes preserve
   * trace/operation correlation.
   */
  public int getProfilerAggregationMode() {
    return profilerAggregationMode;
  }

  /**
   * JFR rotation / export window in seconds (default 60), from {@code
   * OTEL_AWS_PROFILER_WINDOW_SECONDS}. Threaded to both the async-profiler {@code loop=} and the
   * RotationBoundaryProcessor window; a shorter window shrinks per-window OTLP requests and
   * freshens profiles.
   */
  public int getProfilerWindowSeconds() {
    return profilerWindowSeconds;
  }

  /**
   * Client-side payload-size guard in <b>uncompressed</b> bytes (default 64 MiB, from {@code
   * OTEL_AWS_PROFILER_MAX_PAYLOAD_BYTES}). The profiles exporter (HTTP or gRPC) drops any window
   * whose serialized {@code ExportProfilesServiceRequest} exceeds this — with a {@code WARN} —
   * instead of sending it and being rejected with an HTTP 413 that silently loses the whole window.
   * The default is a loose backstop well above typical backend request caps, so normal large
   * windows are never dropped client-side; {@code 0} or negative disables the guard.
   */
  public long getProfilerMaxPayloadBytes() {
    return profilerMaxPayloadBytes;
  }

  /** Builder for ServiceEventsConfig. */
  public static class Builder {
    private boolean enabled = false;
    private String outputFile = "";
    private String serviceName = "UnknownService";
    // No default — environment is omitted from emit paths when unset (null/empty).
    private String environment = null;
    private String deploymentId = "unknown-deployment-id";
    private String deploymentTimestamp = "";
    private String deploymentUrl = "";
    private String gitCommitSha = "";
    private String gitRepoUrl = "";
    private String serviceCodeNamespace = "";
    private int functionCallFlushInterval = 30000;
    private int endpointFlushInterval = 30000;
    private int deploymentEventFlushInterval = 86_400_000;
    private boolean bytecodeEnabled = true;
    // Both default to empty. An empty packagesInclude means "instrument nothing" — there is
    // no implicit default scope. The non-configurable SDK_SELF_EXCLUDE (in ServiceEventsConfig)
    // is the only built-in filter and is always subtracted in the matcher.
    private List<String> packagesExclude = new ArrayList<>();
    private List<String> packagesInclude = new ArrayList<>();
    private List<String> latencyThresholds = new ArrayList<>();
    private List<String> endpointIncludePatterns = new ArrayList<>();
    private List<String> endpointExcludePatterns = new ArrayList<>();
    private int incidentSnapshotMaxPerMinute = 100;
    private int incidentSnapshotMaxSameError = 1;
    private String samplingMode = "always";
    private int sampleTier1Threshold = 100;
    private int sampleTier2Threshold = 1000;
    private int sampleTier2Rate = 10;
    private int sampleTier3Rate = 100;
    private String logsEndpoint = "http://localhost:4316/v1/logs";
    private String metricsEndpoint = "http://localhost:4316/v1/metrics";
    private String logGroup = "/serviceevents/telemetry";
    private String logStream = "UnknownService";
    private boolean applicationSignalsEnabled = false;
    private boolean asyncProfilerEnabled = false;
    private int profilerMode = PROFILER_MODE_WALL;
    private int asyncProfilerCpuIntervalMs = 10;
    private int asyncProfilerWallIntervalMs = 10;
    private String asyncProfilerJfrFilePath = "profiler-jfr";
    private String profilerDataDir = "";
    private String profilerEndpoint = DEFAULT_PROFILER_ENDPOINT;
    private String profilerExportCompression = "gzip";
    private int profilerProtocol = PROFILER_PROTOCOL_HTTP_PROTOBUF;
    private int profilerExportTimeoutMs = 10000;
    private boolean profilerMemoryEnabled = false;
    private long profilerAllocIntervalBytes = 524288L;
    private int profilerAggregationMode = 0; // NONE (default)
    private int profilerWindowSeconds = 60;
    private long profilerMaxPayloadBytes = OtlpHttpProfilesExporter.DEFAULT_MAX_PAYLOAD_BYTES;

    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    public Builder outputFile(String outputFile) {
      this.outputFile = outputFile;
      return this;
    }

    public Builder serviceName(String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    public Builder environment(String environment) {
      this.environment = environment;
      return this;
    }

    public Builder deploymentId(String deploymentId) {
      this.deploymentId = deploymentId;
      return this;
    }

    public Builder deploymentTimestamp(String deploymentTimestamp) {
      this.deploymentTimestamp = deploymentTimestamp;
      return this;
    }

    public Builder deploymentUrl(String deploymentUrl) {
      this.deploymentUrl = deploymentUrl;
      return this;
    }

    public Builder gitCommitSha(String gitCommitSha) {
      this.gitCommitSha = gitCommitSha;
      return this;
    }

    public Builder gitRepoUrl(String gitRepoUrl) {
      this.gitRepoUrl = gitRepoUrl;
      return this;
    }

    public Builder serviceCodeNamespace(String serviceCodeNamespace) {
      this.serviceCodeNamespace = serviceCodeNamespace;
      return this;
    }

    public Builder functionCallFlushInterval(int functionCallFlushInterval) {
      this.functionCallFlushInterval = functionCallFlushInterval;
      return this;
    }

    public Builder endpointFlushInterval(int endpointFlushInterval) {
      this.endpointFlushInterval = endpointFlushInterval;
      return this;
    }

    public Builder deploymentEventFlushInterval(int deploymentEventFlushInterval) {
      this.deploymentEventFlushInterval = deploymentEventFlushInterval;
      return this;
    }

    public Builder bytecodeEnabled(boolean bytecodeEnabled) {
      this.bytecodeEnabled = bytecodeEnabled;
      return this;
    }

    public Builder packagesExclude(List<String> packagesExclude) {
      this.packagesExclude = packagesExclude;
      return this;
    }

    public Builder packagesInclude(List<String> packagesInclude) {
      this.packagesInclude = packagesInclude;
      return this;
    }

    public Builder latencyThresholds(List<String> latencyThresholds) {
      this.latencyThresholds = latencyThresholds;
      return this;
    }

    public Builder endpointIncludePatterns(List<String> endpointIncludePatterns) {
      this.endpointIncludePatterns = endpointIncludePatterns;
      return this;
    }

    public Builder endpointExcludePatterns(List<String> endpointExcludePatterns) {
      this.endpointExcludePatterns = endpointExcludePatterns;
      return this;
    }

    public Builder incidentSnapshotMaxPerMinute(int incidentSnapshotMaxPerMinute) {
      this.incidentSnapshotMaxPerMinute = incidentSnapshotMaxPerMinute;
      return this;
    }

    public Builder incidentSnapshotMaxSameError(int incidentSnapshotMaxSameError) {
      this.incidentSnapshotMaxSameError = incidentSnapshotMaxSameError;
      return this;
    }

    public Builder samplingMode(String samplingMode) {
      this.samplingMode = samplingMode;
      return this;
    }

    public Builder sampleTier1Threshold(int sampleTier1Threshold) {
      this.sampleTier1Threshold = sampleTier1Threshold;
      return this;
    }

    public Builder sampleTier2Threshold(int sampleTier2Threshold) {
      this.sampleTier2Threshold = sampleTier2Threshold;
      return this;
    }

    public Builder sampleTier2Rate(int sampleTier2Rate) {
      // Clamp to >= 1: the rate is used as a modulus (totalCalls % rate) on the sampling hot path,
      // so a 0 (e.g. via the test-config hook) would throw ArithmeticException.
      this.sampleTier2Rate = Math.max(1, sampleTier2Rate);
      return this;
    }

    public Builder sampleTier3Rate(int sampleTier3Rate) {
      // Clamp to >= 1 — see sampleTier2Rate.
      this.sampleTier3Rate = Math.max(1, sampleTier3Rate);
      return this;
    }

    public Builder logsEndpoint(String logsEndpoint) {
      this.logsEndpoint = logsEndpoint;
      return this;
    }

    public Builder metricsEndpoint(String metricsEndpoint) {
      this.metricsEndpoint = metricsEndpoint;
      return this;
    }

    public Builder logGroup(String logGroup) {
      this.logGroup = logGroup;
      return this;
    }

    public Builder logStream(String logStream) {
      this.logStream = logStream;
      return this;
    }

    public Builder applicationSignalsEnabled(boolean applicationSignalsEnabled) {
      this.applicationSignalsEnabled = applicationSignalsEnabled;
      return this;
    }

    public Builder asyncProfilerEnabled(boolean asyncProfilerEnabled) {
      this.asyncProfilerEnabled = asyncProfilerEnabled;
      return this;
    }

    public Builder asyncProfilerCpuIntervalMs(int asyncProfilerCpuIntervalMs) {
      this.asyncProfilerCpuIntervalMs = asyncProfilerCpuIntervalMs;
      return this;
    }

    public Builder profilerMode(int profilerMode) {
      // Only WALL/CPU are valid; anything else falls back to WALL (the default).
      this.profilerMode =
          (profilerMode == PROFILER_MODE_CPU) ? PROFILER_MODE_CPU : PROFILER_MODE_WALL;
      return this;
    }

    public Builder asyncProfilerWallIntervalMs(int asyncProfilerWallIntervalMs) {
      this.asyncProfilerWallIntervalMs = asyncProfilerWallIntervalMs;
      return this;
    }

    public Builder asyncProfilerJfrFilePath(String asyncProfilerJfrFilePath) {
      this.asyncProfilerJfrFilePath = asyncProfilerJfrFilePath;
      return this;
    }

    public Builder profilerDataDir(String profilerDataDir) {
      this.profilerDataDir = profilerDataDir;
      return this;
    }

    public Builder profilerEndpoint(String profilerEndpoint) {
      this.profilerEndpoint = profilerEndpoint;
      return this;
    }

    public Builder profilerExportCompression(String profilerExportCompression) {
      this.profilerExportCompression = profilerExportCompression;
      return this;
    }

    public Builder profilerExportTimeoutMs(int profilerExportTimeoutMs) {
      this.profilerExportTimeoutMs = profilerExportTimeoutMs;
      return this;
    }

    /** Re-clamp to a valid transport int, defaulting anything unexpected to http/protobuf. */
    public Builder profilerProtocol(int profilerProtocol) {
      this.profilerProtocol =
          (profilerProtocol == PROFILER_PROTOCOL_GRPC)
              ? PROFILER_PROTOCOL_GRPC
              : PROFILER_PROTOCOL_HTTP_PROTOBUF;
      return this;
    }

    public Builder profilerMemoryEnabled(boolean profilerMemoryEnabled) {
      this.profilerMemoryEnabled = profilerMemoryEnabled;
      return this;
    }

    public Builder profilerAggregationMode(int profilerAggregationMode) {
      // Clamp to a known mode (0 NONE, 1 FULL, 2 SUM); out-of-range falls back to NONE (the
      // default).
      this.profilerAggregationMode =
          (profilerAggregationMode >= 0 && profilerAggregationMode <= 2)
              ? profilerAggregationMode
              : 0;
      return this;
    }

    public Builder profilerWindowSeconds(int profilerWindowSeconds) {
      // Clamp to [5, 300]s: too short spends more on the fixed per-request dictionary + request
      // overhead (and stresses the 10s rotation-detect poll); too long defeats the point.
      // Out-of-range falls back to the 60s default.
      this.profilerWindowSeconds =
          (profilerWindowSeconds >= 5 && profilerWindowSeconds <= 300) ? profilerWindowSeconds : 60;
      return this;
    }

    public Builder profilerMaxPayloadBytes(long profilerMaxPayloadBytes) {
      // Negative is meaningless; normalize it to 0 (guard disabled). 0 and any positive value pass
      // through verbatim — 0 disables the guard, positive is the byte limit.
      this.profilerMaxPayloadBytes = Math.max(0L, profilerMaxPayloadBytes);
      return this;
    }

    public Builder profilerAllocIntervalBytes(long profilerAllocIntervalBytes) {
      // Clamp to >= 1: alloc=0 disables allocation sampling in async-profiler, which would silently
      // defeat OTEL_AWS_PROFILER_MEMORY_ENABLED=true; fall back to the 512 KiB default instead.
      this.profilerAllocIntervalBytes =
          profilerAllocIntervalBytes >= 1 ? profilerAllocIntervalBytes : 524288L;
      return this;
    }

    public ServiceEventsConfig build() {
      // Apply the same bare-'*' / empty-entry normalization that fromEnv() uses,
      // so programmatic/test Builder callers see the same rules as env-var users.
      this.packagesExclude = normalizePatterns(this.packagesExclude, "packagesExclude");
      this.packagesInclude = normalizePatterns(this.packagesInclude, "packagesInclude");
      return new ServiceEventsConfig(this);
    }
  }
}
