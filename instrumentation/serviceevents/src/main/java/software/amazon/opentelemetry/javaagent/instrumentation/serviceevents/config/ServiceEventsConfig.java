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

  // Per-endpoint latency thresholds (pipe-delimited entries, each "METHOD /route:threshold_ms")
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
                getPipeListEnv("OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS", new ArrayList<>()))
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

  // package-private for tests; accepts either env var or the lowercase dotted property form
  static boolean isLambdaEnvironment() {
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

  private static String getStringEnv(String name, String defaultValue) {
    String value = getConfigValue(name);
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    return value;
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

  private static List<String> getPipeListEnv(String name, List<String> defaultValue) {
    return getDelimitedListEnv(name, "\\|", defaultValue);
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
   * Raw pipe-delimited entries parsed from {@code OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS}. Each
   * entry is of the form {@code METHOD /route:threshold_ms} (glob on method and route allowed;
   * threshold parsed from the text after the last {@code :}). Empty by default.
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

    public ServiceEventsConfig build() {
      // Apply the same bare-'*' / empty-entry normalization that fromEnv() uses,
      // so programmatic/test Builder callers see the same rules as env-var users.
      this.packagesExclude = normalizePatterns(this.packagesExclude, "packagesExclude");
      this.packagesInclude = normalizePatterns(this.packagesInclude, "packagesInclude");
      return new ServiceEventsConfig(this);
    }
  }
}
