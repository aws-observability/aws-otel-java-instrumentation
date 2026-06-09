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

package software.amazon.opentelemetry.serviceevents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bootstrap ClassLoader-visible data store for ServiceEvents observability.
 *
 * <p>This class is loaded into the bootstrap classloader so that ALL classloaders (agent and
 * application) see the SAME class instance and share the SAME static state. This solves the
 * cross-classloader data sharing problem inherent in Java agent architectures.
 *
 * <p>Architecture:
 *
 * <ul>
 *   <li>ByteBuddy advice (inlined into app classloader) WRITES data here
 *   <li>Collectors (running in agent classloader) READ data here
 *   <li>Both see the same static fields because this class is in bootstrap
 * </ul>
 *
 * <p><b>IMPORTANT:</b> This class MUST:
 *
 * <ul>
 *   <li>Have NO external dependencies (no imports except java.*)
 *   <li>Be compiled to Java 8 bytecode (for maximum compatibility)
 * </ul>
 */
public final class ServiceEventsDataStore {

  // Prevent instantiation
  private ServiceEventsDataStore() {}

  // =========================================================================
  // Incident Snapshot Pre-filter Threshold
  // =========================================================================

  /**
   * Pre-filter threshold for latency-triggered incident snapshots (milliseconds). Read from system
   * property at class load time. Incidents with duration below this AND no exception AND status <
   * 500 will be dropped early in the bootstrap bridge.
   *
   * <p>System property: otel.aws.service_events.incident.snapshot.duration.threshold.ms
   *
   * <p>Environment variable: OTEL_AWS_SERVICE_EVENTS_INCIDENT_SNAPSHOT_DURATION_THRESHOLD_MS
   *
   * <p>Default: 5000 (same as ServiceEventsConfig default)
   */
  private static final double INCIDENT_DURATION_THRESHOLD_MS = resolveIncidentDurationThreshold();

  private static double resolveIncidentDurationThreshold() {
    // Check system property first
    String value =
        System.getProperty("otel.aws.service_events.incident.snapshot.duration.threshold.ms");
    if (value == null || value.isEmpty()) {
      // Fall back to environment variable
      value = System.getenv("OTEL_AWS_SERVICE_EVENTS_INCIDENT_SNAPSHOT_DURATION_THRESHOLD_MS");
    }
    if (value != null && !value.isEmpty()) {
      try {
        double parsed = Double.parseDouble(value);
        // Reject NaN/Infinity: any `duration > threshold` comparison against these is always
        // false, which would silently disable latency-based incident detection.
        if (!Double.isNaN(parsed) && !Double.isInfinite(parsed)) {
          return parsed;
        }
      } catch (NumberFormatException e) {
        // fall through to default
      }
    }
    return 5000.0; // default matches ServiceEventsConfig default
  }

  /**
   * Whether byte-instrumentation mode is enabled. When true, incident snapshots are emitted
   * synchronously via {@link IncidentSnapshotEmitterBridge} with an in-request captured call path.
   *
   * <p>System property: otel.aws.service_events.function.instrument.enabled
   *
   * <p>Environment variable: OTEL_AWS_SERVICE_EVENTS_FUNCTION_INSTRUMENT_ENABLED
   *
   * <p>Default: true
   */
  private static final boolean BYTECODE_ENABLED = resolveBytecodeEnabled();

  private static boolean resolveBytecodeEnabled() {
    String value = System.getProperty("otel.aws.service_events.function.instrument.enabled");
    if (value == null || value.isEmpty()) {
      value = System.getenv("OTEL_AWS_SERVICE_EVENTS_FUNCTION_INSTRUMENT_ENABLED");
    }
    if (value != null && !value.isEmpty()) {
      return Boolean.parseBoolean(value);
    }
    return true;
  }

  // =========================================================================
  // Incident Snapshot Rate Limiting (delegated to IncidentRateLimiter)
  // =========================================================================

  /** Set the maximum incident snapshots per minute (called from agent classloader). */
  public static void setIncidentSnapshotMaxPerMinute(int value) {
    IncidentRateLimiter.setMaxPerMinute(value);
  }

  /** Set the maximum snapshots for the same error per period (called from agent classloader). */
  public static void setIncidentSnapshotMaxSameError(int value) {
    IncidentRateLimiter.setMaxSameError(value);
  }

  // =========================================================================
  // Adaptive Sampling (delegated to MethodAggregationStore)
  // =========================================================================

  /**
   * Set the sampling mode for function-call records. Accepted values: {@code "auto"} (default,
   * tiered), {@code "adaptive"} (tiered + hot-endpoint boost), {@code "always"}, {@code "never"}.
   * Invalid values are silently ignored.
   */
  public static void setSamplingMode(String mode) {
    MethodAggregationStore.setSamplingMode(mode);
  }

  /**
   * Read back the currently effective sampling mode. Useful at startup to log the post-validation
   * value when the operator-supplied env may have been rejected as invalid.
   */
  public static String getSamplingMode() {
    return MethodAggregationStore.samplingMode;
  }

  /**
   * Set the tiered-sampling thresholds and hot-endpoint cycle length. Non-positive values for any
   * parameter are silently ignored (current setting retained).
   */
  public static void setSamplingThresholds(
      long tier1Threshold, long tier2Threshold, int tier2Rate, int tier3Rate, int hotCycles) {
    MethodAggregationStore.setSamplingThresholds(
        tier1Threshold, tier2Threshold, tier2Rate, tier3Rate, hotCycles);
  }

  /**
   * Mark an operation "hot" for adaptive-mode sampling. Subsequent function calls within this
   * operation are 100% sampled for the next {@code HOT_ENDPOINT_CYCLES} flush cycles. Called after
   * an incident is recorded against the operation.
   */
  public static void markOperationHot(String operation) {
    MethodAggregationStore.markOperationHot(operation);
  }

  /**
   * Decrement every hot-operation countdown by one. Called once per function-call collector flush
   * cycle. Operations at zero are removed.
   */
  public static void tickHotOperations() {
    MethodAggregationStore.tickHotOperations();
  }

  // =========================================================================
  // Shared State — Visible to ALL classloaders
  // =========================================================================

  /**
   * Flat sampling counters: functionId -> call count. Decoupled from the nested aggregation map so
   * the non-sampled fast path (99% of calls) does ONE ConcurrentHashMap lookup instead of TWO
   * nested lookups. Counters reset on each aggregation swap to restart sampling tiers.
   */
  private static final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
      samplingCounters = new ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>();

  /** Thread-local current operation (e.g., "GET /api/users"). */
  private static final ThreadLocal<String> currentOperation = new ThreadLocal<String>();

  /** Thread-local call stack for caller tracking. */
  private static final ThreadLocal<List<String>> callStack =
      new ThreadLocal<List<String>>() {
        @Override
        protected List<String> initialValue() {
          return new ArrayList<String>();
        }
      };

  /** Thread-local investigation data for incident snapshots. */
  private static final ThreadLocal<InvestigationData> investigationData =
      new ThreadLocal<InvestigationData>();

  /** Endpoint aggregation: endpointKey -> EndpointAggregation. */
  private static final AtomicReference<ConcurrentHashMap<String, EndpointAggregation>>
      endpointAggregations =
          new AtomicReference<ConcurrentHashMap<String, EndpointAggregation>>(
              new ConcurrentHashMap<String, EndpointAggregation>());

  /**
   * Reference to the metadata writer bridge implementation. Set by the agent classloader at
   * initialization time. Called from advice code (app classloader) via the delegate methods below.
   */
  private static volatile MetadataWriterBridge metadataWriterBridge;

  /**
   * Reference to the incident-snapshot emitter bridge. Installed by the agent classloader when
   * byte-instrumentation mode is enabled. When non-null, incident snapshots are emitted directly
   * via this bridge instead of being written to the JFR metadata sidecar.
   */
  private static volatile IncidentSnapshotEmitterBridge incidentSnapshotEmitterBridge;

  /**
   * Reference to the function-call metrics bridge. Installed by the agent classloader when an OTLP
   * network endpoint is configured (i.e. not in {@code OUTPUT_FILE} mode). When non-null, {@link
   * #methodExit} records the {@code service.function.duration} Histogram via this bridge instead of
   * feeding the SEH pre-aggregation path.
   */
  private static volatile FunctionMetricsBridge functionMetricsBridge;

  /**
   * Reference to the latency-threshold bridge. Installed by the agent classloader when per-endpoint
   * overrides are configured via {@code OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS}. When null, the
   * global {@link #INCIDENT_DURATION_THRESHOLD_MS} is used for every request.
   */
  private static volatile LatencyThresholdBridge latencyThresholdBridge;

  // =========================================================================
  // Metadata Writer Bridge
  // =========================================================================

  /**
   * Set the metadata writer bridge implementation.
   *
   * <p>Called once from the agent classloader during initialization.
   *
   * @param bridge The MetadataWriterBridge implementation (LiteIncidentDrainer)
   */
  public static void setMetadataWriterBridge(MetadataWriterBridge bridge) {
    metadataWriterBridge = bridge;
  }

  /**
   * Set the incident-snapshot emitter bridge implementation.
   *
   * <p>Called once from the agent classloader during initialization when byte-instrumentation mode
   * is enabled.
   *
   * @param bridge The IncidentSnapshotEmitterBridge implementation
   */
  public static void setIncidentSnapshotEmitterBridge(IncidentSnapshotEmitterBridge bridge) {
    incidentSnapshotEmitterBridge = bridge;
  }

  /**
   * Set the function-call metrics bridge implementation.
   *
   * <p>Called once from the agent classloader during initialization in BOTH network and {@code
   * OUTPUT_FILE} mode — the file metric exporter now serializes the {@code
   * service.function.duration} ExponentialHistogram as OTLP/JSON, so the bridge is no longer gated
   * off locally. When the bridge is null (e.g. wiring failed), {@link #methodExit} falls back to
   * feeding the SEH pre-aggregation path consumed by {@code FunctionCallCollector}.
   *
   * @param bridge The FunctionMetricsBridge implementation, or {@code null} to disable direct
   *     metric recording
   */
  public static void setFunctionMetricsBridge(FunctionMetricsBridge bridge) {
    functionMetricsBridge = bridge;
  }

  /**
   * Set the latency-threshold bridge implementation.
   *
   * <p>Called from the agent classloader during initialization when {@code
   * OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS} produced one or more valid entries. Pass {@code
   * null} to remove the override and revert to the global threshold.
   */
  public static void setLatencyThresholdBridge(LatencyThresholdBridge bridge) {
    latencyThresholdBridge = bridge;
  }

  /** Whether byte-instrumentation mode is active. Visible for tests and routing decisions. */
  public static boolean isBytecodeEnabled() {
    return BYTECODE_ENABLED;
  }

  /**
   * Write an incident metadata record to the sidecar file.
   *
   * <p>Called from the incident recording path alongside recordPotentialIncident.
   */
  public static void metadataWriteIncident(
      String threadName,
      long startTimeNs,
      long endTimeNs,
      String route,
      String method,
      int statusCode,
      double durationMs,
      String triggerType,
      String severity,
      String snapshotId,
      String exceptionType,
      String exceptionMessage,
      String stackTrace,
      String traceId,
      String spanId,
      String operation) {
    MetadataWriterBridge bridge = metadataWriterBridge;
    if (bridge != null) {
      bridge.writeIncident(
          threadName,
          startTimeNs,
          endTimeNs,
          route,
          method,
          statusCode,
          durationMs,
          triggerType,
          severity,
          snapshotId,
          exceptionType,
          exceptionMessage,
          stackTrace,
          traceId,
          spanId,
          operation);
    }
  }

  // =========================================================================
  // Operation Context (called from advice in app classloader)
  // =========================================================================

  /**
   * Set current operation for this thread.
   *
   * @param operation Operation identifier (e.g., "GET /api/users")
   */
  public static void setCurrentOperation(String operation) {
    currentOperation.set(operation);
  }

  /**
   * Get current operation for this thread.
   *
   * @return The current operation or null if not set
   */
  public static String getCurrentOperation() {
    return currentOperation.get();
  }

  /** Clear current operation for this thread. */
  public static void clearCurrentOperation() {
    currentOperation.remove();
  }

  // =========================================================================
  // Method Entry/Exit (called from advice in app classloader)
  // =========================================================================

  /**
   * Record method entry. Handles sampling and call stack tracking.
   *
   * <p>Optimized: uses a flat sampling counter (single CHM lookup) instead of the nested 2-level
   * aggregation map. Investigation tracking no longer forces the sampled path — complete call paths
   * for incidents come from bytecode-captured call paths or from sampled calls only.
   *
   * @param functionId Function identifier (e.g., "com.example.MyClass.myMethod")
   * @return Context long[]{startTimeNs} or null if not sampled
   */
  public static Object methodEnter(String functionId) {
    // C1: Fast-path sampling using flat counter (single CHM lookup, no nested map traversal)
    java.util.concurrent.atomic.AtomicLong counter = samplingCounters.get(functionId);
    if (counter == null) {
      counter = new java.util.concurrent.atomic.AtomicLong(0);
      java.util.concurrent.atomic.AtomicLong existing =
          samplingCounters.putIfAbsent(functionId, counter);
      if (existing != null) {
        counter = existing;
      }
    }
    long callCount = counter.incrementAndGet();

    // C3: If not sampled, skip entirely — no ThreadLocal reads, no allocations.
    // Investigation data now only captures sampled calls.
    if (!MethodAggregationStore.shouldSample(callCount)) {
      return null;
    }

    // Push to call stack (only for sampled calls)
    callStack.get().add(functionId);

    // C5: Return lightweight long[] context — avoids Object[] + Long boxing overhead
    return new long[] {System.nanoTime()};
  }

  /**
   * Record method exit. Records into the OTel histogram bridge if wired, otherwise feeds the SEH
   * aggregation map consumed by {@code FunctionCallCollector}.
   *
   * <p>Optimized: context is now {@code long[]} (no unboxing). Caller retrieved from call stack
   * directly (C5). Only called for sampled methods, so no sampled flag check needed (C6).
   *
   * @param functionId Function identifier
   * @param context Context from methodEnter (null if not sampled)
   * @param exceptionType Exception class name, or null if no exception
   */
  public static void methodExit(String functionId, Object context, String exceptionType) {
    if (context == null) {
      return;
    }

    // C5: lightweight long[] context — no unboxing needed
    long[] ctx = (long[]) context;
    long startTimeNs = ctx[0];
    long durationNs = System.nanoTime() - startTimeNs;

    // Get caller from call stack BEFORE popping (caller is element below current)
    List<String> stack = callStack.get();
    String caller = stack.size() > 1 ? stack.get(stack.size() - 2) : null;

    // Pop from call stack
    if (!stack.isEmpty()) {
      stack.remove(stack.size() - 1);
    }

    // C6: context is only non-null for sampled calls — always record
    String operation = currentOperation.get();
    if (operation == null) {
      operation = "unknown";
    }

    FunctionMetricsBridge bridge = functionMetricsBridge;
    if (bridge != null) {
      // Direct OTel histogram path: bypasses MethodAggregationStore. The
      // FunctionCallCollector observes the (now-empty) aggregation map and no-ops.
      bridge.recordFunctionCall(functionId, operation, caller, durationNs, exceptionType);
    } else {
      // SEH pre-aggregation fallback, used only when the bridge failed to wire.
      MethodAggregationStore.recordMethodInvocation(
          operation, functionId, durationNs, caller, exceptionType);
    }

    // Record to investigation data (only for sampled calls now — C3)
    InvestigationData investigation = investigationData.get();
    if (investigation != null) {
      boolean error = exceptionType != null;
      investigation.addEntry(functionId, caller, durationNs, error, false);
    }
  }

  // =========================================================================
  // Method Aggregation (delegated to MethodAggregationStore)
  // =========================================================================

  /**
   * Record a method invocation into aggregation storage.
   *
   * @param operation The operation (e.g., "GET /api/users")
   * @param functionId The function identifier
   * @param durationNs Duration in nanoseconds
   * @param caller The caller function ID, or null
   * @param exceptionType Exception type if any, or null
   */
  public static void recordMethodInvocation(
      String operation, String functionId, long durationNs, String caller, String exceptionType) {
    MethodAggregationStore.recordMethodInvocation(
        operation, functionId, durationNs, caller, exceptionType);
  }

  /**
   * Get and swap all method aggregations. Returns current data and resets storage.
   *
   * @return Map of operation to functionId to AggregationData, or null if empty
   */
  public static Map<String, Map<String, AggregationData>> getAndSwapAggregations() {
    // Reset sampling counters so tiers restart each collection cycle
    samplingCounters.clear();
    return MethodAggregationStore.getAndSwapAggregations();
  }

  // =========================================================================
  // Dynamic Per-Operation Latency Thresholds
  // =========================================================================

  // =========================================================================
  // Endpoint Aggregation (called from ServletAdvice in app classloader)
  // =========================================================================

  /**
   * Record an HTTP endpoint request.
   *
   * @param endpointKey The endpoint key (e.g., "GET /api/users")
   * @param route The route pattern
   * @param method HTTP method
   * @param statusCode HTTP status code
   * @param durationNs Duration in nanoseconds
   * @param errorType Error type if any, or null
   * @param errorFunctionId Function ID where error occurred, or null
   * @param operation Operation name from AwsSpanProcessingUtil, or null to derive from method+route
   */
  public static void recordEndpointRequest(
      String endpointKey,
      String route,
      String method,
      int statusCode,
      long durationNs,
      String errorType,
      String errorFunctionId,
      String operation) {
    ConcurrentHashMap<String, EndpointAggregation> epAggs = endpointAggregations.get();
    EndpointAggregation agg = epAggs.get(endpointKey);
    if (agg == null) {
      agg = new EndpointAggregation(route, method);
      EndpointAggregation existing = epAggs.putIfAbsent(endpointKey, agg);
      if (existing != null) {
        agg = existing;
      }
    }
    if (operation != null) {
      agg.setOperation(operation);
    }

    agg.incrementCount();
    agg.recordDuration(durationNs);

    if (statusCode >= 500) {
      agg.incrementFaultCount();
    } else if (statusCode >= 400) {
      agg.incrementErrorCount();
    }

    if (statusCode >= 500 && errorType != null) {
      String errorKey = errorType + ":" + (errorFunctionId != null ? errorFunctionId : "unknown");
      agg.recordError(
          String.valueOf(statusCode),
          errorKey,
          errorType,
          errorFunctionId != null ? errorFunctionId : "unknown");
    }
  }

  /**
   * Get and swap all endpoint aggregations.
   *
   * @return Map of endpointKey to EndpointAggregation, or null if empty
   */
  public static Map<String, EndpointAggregation> getAndSwapEndpointAggregations() {
    ConcurrentHashMap<String, EndpointAggregation> old =
        endpointAggregations.getAndSet(new ConcurrentHashMap<String, EndpointAggregation>());
    if (old.isEmpty()) {
      return null;
    }

    return new HashMap<String, EndpointAggregation>(old);
  }

  // =========================================================================
  // Incident Snapshots
  // =========================================================================

  /**
   * Record a potential incident with trace context.
   *
   * @param route The route pattern
   * @param method HTTP method
   * @param statusCode HTTP status code
   * @param durationMs Request duration in milliseconds
   * @param exceptionType Exception class name, or null
   * @param exceptionMessage Exception message, or null
   * @param stackTrace Exception stack trace, or null
   * @param headers Request headers
   * @param queryParams Query parameters
   * @param threadName Thread name for JFR filtering, or null if profiling not active
   * @param requestStartTimeNs Request start time in epoch ns for JFR filtering
   * @param requestEndTimeNs Request end time in epoch ns for JFR filtering
   * @param traceId OTel trace ID, or null
   * @param spanId OTel span ID, or null
   * @param operation Operation name from AwsSpanProcessingUtil, or null to derive from method+route
   */
  public static void recordPotentialIncident(
      String route,
      String method,
      int statusCode,
      double durationMs,
      String exceptionType,
      String exceptionMessage,
      String stackTrace,
      Map<String, String> headers,
      Map<String, Object> queryParams,
      String threadName,
      long requestStartTimeNs,
      long requestEndTimeNs,
      String traceId,
      String spanId,
      String operation) {

    // Only record if there's a potential trigger condition. Per-endpoint latency overrides
    // come from the installed LatencyThresholdBridge; a non-positive (or NaN) override means
    // "no match — fall back to the global default".
    boolean isException = statusCode >= 500;
    double effectiveThresholdMs = INCIDENT_DURATION_THRESHOLD_MS;
    LatencyThresholdBridge thresholdBridge = latencyThresholdBridge;
    if (thresholdBridge != null) {
      double override = thresholdBridge.resolveThresholdMs(method, route);
      if (override > 0) {
        effectiveThresholdMs = override;
      }
    }
    boolean isLatencyTriggered = !isException && durationMs > effectiveThresholdMs;

    if (isException || isLatencyTriggered) {
      // Rate limiting: reject if global per-minute limit exceeded
      if (!IncidentRateLimiter.checkIncidentRateLimit()) {
        return;
      }

      // Deduplication: reject if the same error hash has been seen too many times this period
      String effectiveOperation = operation != null ? operation : method + " " + route;
      String errorHash = IncidentRateLimiter.generateErrorHash(effectiveOperation, exceptionType);
      if (!IncidentRateLimiter.checkErrorDeduplication(errorHash)) {
        return;
      }

      // Write incident metadata to sidecar file for rotation-boundary processing.
      String triggerType = statusCode >= 500 ? "exception" : "latency";
      String severity;
      if ("latency".equals(triggerType)) {
        // Latency-triggered incidents (statusCode < 500) are always medium severity.
        severity = "medium";
      } else if (statusCode <= 503) {
        // 500-503 server errors.
        severity = "critical";
      } else {
        // 504+ server errors.
        severity = "high";
      }
      String snapshotId = "snap_" + java.util.UUID.randomUUID().toString();

      // Always emit incident synchronously
      IncidentSnapshotEmitterBridge emitter = incidentSnapshotEmitterBridge;
      if (emitter != null) {
        InvestigationData inv = investigationData.get();
        List<CallPathEntry> callPathSnapshot =
            (BYTECODE_ENABLED && inv != null && inv.getCallPath() != null)
                ? new ArrayList<CallPathEntry>(inv.getCallPath())
                : Collections.<CallPathEntry>emptyList();
        try {
          emitter.emitIncident(
              snapshotId,
              triggerType,
              severity,
              threadName,
              requestStartTimeNs,
              requestEndTimeNs,
              route,
              method,
              effectiveOperation,
              statusCode,
              durationMs,
              exceptionType,
              exceptionMessage,
              stackTrace,
              traceId,
              spanId,
              callPathSnapshot);
        } catch (Throwable t) {
          // Never let a telemetry failure propagate into the request.
        }
      }

      // Record lightweight exemplar on the EndpointAggregation for EndpointSummary attachment
      String epId = currentOperation.get();
      if (epId != null) {
        ConcurrentHashMap<String, EndpointAggregation> epAggs = endpointAggregations.get();
        EndpointAggregation epAgg = epAggs.get(epId);
        if (epAgg != null) {
          epAgg.addIncidentExemplar(snapshotId, triggerType, severity, System.currentTimeMillis());
        }
      }
    }
  }

  // =========================================================================
  // Investigation Data (for incident call path tracking)
  // =========================================================================

  /**
   * Begin investigation tracking for the current thread.
   *
   * <p>Idempotent on re-entry: if an {@link InvestigationData} already exists on this thread (e.g.,
   * Spring Boot's internal {@code /error} forward re-enters {@code HttpServlet.service()}), keep
   * the outer request's data instead of overwriting it. The outermost {@code ServletAdvice.onEnter}
   * owns the object and is responsible for clearing it on exit.
   *
   * @return {@code true} if this call actually created a new {@code InvestigationData}, {@code
   *     false} if one was already present and we did nothing.
   */
  public static boolean beginInvestigation() {
    if (investigationData.get() != null) {
      return false; // nested servlet dispatch — outer request's call path wins
    }
    investigationData.set(new InvestigationData());
    return true;
  }

  /**
   * Record exception information for investigation.
   *
   * @param type Exception class name
   * @param message Exception message
   * @param stackTrace Formatted stack trace
   */
  public static void recordException(String type, String message, String stackTrace) {
    InvestigationData investigation = investigationData.get();
    if (investigation != null) {
      investigation.setException(type, message, stackTrace);
    }
  }

  /**
   * Peek at investigation data without clearing it.
   *
   * @return Investigation data or null if none active
   */
  public static InvestigationData peekInvestigationData() {
    return investigationData.get();
  }

  /**
   * Get investigation data and clear.
   *
   * @return Investigation data or null if none active
   */
  public static InvestigationData getAndClearInvestigationData() {
    InvestigationData investigation = investigationData.get();
    investigationData.remove();
    return investigation;
  }

  /** Clear investigation data without returning it. */
  public static void clearInvestigation() {
    investigationData.remove();
  }

  // =========================================================================
  // Call Stack Management
  // =========================================================================

  /** Push function onto call stack. */
  public static void pushCallStack(String functionId) {
    callStack.get().add(functionId);
  }

  /** Pop function from call stack. */
  public static void popCallStack() {
    List<String> stack = callStack.get();
    if (!stack.isEmpty()) {
      stack.remove(stack.size() - 1);
    }
  }

  /** Get current caller from call stack. */
  public static String getCurrentCaller() {
    List<String> stack = callStack.get();
    if (stack.size() > 1) {
      return stack.get(stack.size() - 2);
    }
    return null;
  }

  /**
   * Clear the call stack for this thread.
   *
   * <p>Uses {@link ThreadLocal#remove()} rather than {@link List#clear()} so a pooled request
   * thread does not retain its {@code ArrayList} (whose {@code functionId} Strings can pin the old
   * application classloader across hot redeploys, leaking metaspace). Mirrors {@link
   * #clearCurrentOperation()}. Invoked on request exit.
   */
  public static void clearCallStack() {
    callStack.remove();
  }

  // =========================================================================
  // State Management
  // =========================================================================

  /** Reset all state. Used for testing. */
  public static void resetState() {
    MethodAggregationStore.resetState();
    samplingCounters.clear();
    endpointAggregations.set(new ConcurrentHashMap<String, EndpointAggregation>());
    currentOperation.remove();
    callStack.remove();
    investigationData.remove();
    IncidentRateLimiter.resetState();
  }
}
