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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.EndpointFilter;
import software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil;
import software.amazon.opentelemetry.serviceevents.InvestigationData;
import software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore;

/**
 * SpanProcessor that extracts exception and trace context from OTel spans and stores them in a
 * thread-local for {@code ServletAdvice.onExit} to consume.
 *
 * <p>This enables framework-agnostic exception capture: OTel's Java agent instruments every major
 * framework (Spring, Coral, JAX-RS, gRPC, etc.) and records exceptions on spans. By reading these
 * span events in onEnd(), we capture the original exception regardless of framework — without
 * needing per-framework instrumentation.
 *
 * <p>Filters for LOCAL_ROOT spans (same as AwsSpanMetricsProcessor) — a span whose parent is remote
 * or invalid. This catches both Coral inbound requests (INTERNAL kind, parent is remote caller) and
 * standard servlet SERVER spans.
 *
 * <p>Data flow:
 *
 * <pre>
 * For Coral:   CoralServerInstrument ends span → onEnd() stores in thread-local
 *              → ServletAdvice.onExit reads thread-local (fallback a)
 * For Spring:  onEnd() fires AFTER ServletAdvice.onExit, so thread-local is too late
 *              → ServletAdvice.onExit reads ReadableSpan directly (fallback b)
 * </pre>
 */
public class ServiceEventsSpanProcessor implements SpanProcessor {

  // Lazy logger: this class is loaded during agent init, before the OTel agent's
  // InternalLogger is initialized. A static final Logger would capture a NoopLogger
  // permanently. Deferring getLogger() to first use ensures we get a real logger.
  private static volatile Logger logger;

  private static Logger logger() {
    Logger local = logger;
    if (local == null) {
      local = Logger.getLogger(ServiceEventsSpanProcessor.class.getName());
      logger = local;
    }
    return local;
  }

  // Exception event attribute keys (OTel semantic conventions)
  private static final AttributeKey<String> EXCEPTION_TYPE =
      AttributeKey.stringKey("exception.type");
  private static final AttributeKey<String> EXCEPTION_MESSAGE =
      AttributeKey.stringKey("exception.message");
  private static final AttributeKey<String> EXCEPTION_STACKTRACE =
      AttributeKey.stringKey("exception.stacktrace");

  // HTTP semantic convention attribute keys
  private static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
  private static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
  private static final AttributeKey<String> HTTP_REQUEST_METHOD =
      AttributeKey.stringKey("http.request.method");
  private static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE =
      AttributeKey.longKey("http.response.status_code");
  private static final AttributeKey<String> THREAD_NAME = AttributeKey.stringKey("thread.name");

  private final EndpointFilter endpointFilter;

  public ServiceEventsSpanProcessor(EndpointFilter endpointFilter) {
    this.endpointFilter = endpointFilter;
    logger().fine("[SERVICE_EVENTS] ServiceEventsSpanProcessor created");
  }

  /** Back-compat constructor used by tests that don't configure endpoint filters. */
  public ServiceEventsSpanProcessor() {
    this(new EndpointFilter(java.util.Collections.emptyList(), java.util.Collections.emptyList()));
  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    // No action needed on span start
  }

  @Override
  public boolean isStartRequired() {
    return false;
  }

  @Override
  public boolean isEndRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {
    try {
      // Filter before toSpanData() to avoid expensive deep copy for non-SERVER/non-LOCAL_ROOT
      // spans (~5 out of ~6 spans per request are internal/DB/Redis spans that would be
      // filtered out immediately). ReadableSpan exposes getKind() and getParentSpanContext()
      // directly without copying.
      if (span.getKind() != SpanKind.SERVER && !isLocalRoot(span)) {
        return;
      }

      // This is the request-boundary (SERVER / LOCAL_ROOT) span, so the request is ending. Whatever
      // happens below — including the early returns for no-route / no-method / filtered endpoints —
      // we must clear the per-request thread-locals so state can't leak onto the next request that
      // reuses this pooled worker thread (beginInvestigation() is idempotent and would otherwise
      // keep appending to a stale InvestigationData).
      try {
        processRequestSpan(span);
      } finally {
        ServiceEventsDataStore.clearCurrentOperation();
        ServiceEventsDataStore.clearInvestigation();
      }
    } catch (Exception e) {
      // Never disrupt application processing
      logger().log(Level.WARNING, "[SERVICE_EVENTS-SPAN-PROCESSOR] Error processing span", e);
    }
  }

  private void processRequestSpan(ReadableSpan span) {
    SpanData spanData = span.toSpanData();

    // Extract trace context
    SpanContext spanContext = spanData.getSpanContext();
    String traceId = spanContext.isValid() ? spanContext.getTraceId() : null;
    String spanId = spanContext.isValid() ? spanContext.getSpanId() : null;

    // Extract exception from span events
    String exceptionType = null;
    String exceptionMessage = null;
    String stackTrace = null;

    for (EventData event : spanData.getEvents()) {
      if ("exception".equals(event.getName())) {
        exceptionType = event.getAttributes().get(EXCEPTION_TYPE);
        exceptionMessage = event.getAttributes().get(EXCEPTION_MESSAGE);
        stackTrace = event.getAttributes().get(EXCEPTION_STACKTRACE);
        break; // Use first exception event
      }
    }

    String method = spanData.getAttributes().get(HTTP_REQUEST_METHOD);
    if (method == null || method.isEmpty()) {
      return;
    }

    // Derive the operation via the shared App Signals path (span-name primary, first-path-segment
    // fallback) — consistent with Python/JS and with what App Signals reports. Then back the
    // route out of the operation so the collector rebuilds the identical operation string.
    String operation = AwsSpanProcessingUtil.getIngressOperation(spanData);
    String route = routeFromOperation(operation, method);
    if (route == null) {
      return;
    }

    // Apply user-configured include/exclude glob filters before recording.
    // Filtered-out endpoints don't contribute to aggregations, latency
    // histograms, or incident triggers — no-op path for noisy /health etc.
    if (!endpointFilter.shouldTrack(method, route)) {
      return;
    }

    Long statusCodeLong = spanData.getAttributes().get(HTTP_RESPONSE_STATUS_CODE);
    int statusCode = statusCodeLong != null ? statusCodeLong.intValue() : 0;

    long startTimeNs = spanData.getStartEpochNanos();
    long endTimeNs = spanData.getEndEpochNanos();
    long durationNs = endTimeNs - startTimeNs;
    double durationMs = durationNs / 1_000_000.0;

    String threadName = spanData.getAttributes().get(THREAD_NAME);
    if (threadName == null || threadName.isEmpty()) {
      threadName = Thread.currentThread().getName();
    }

    // Derive simple error type from FQCN (e.g. "java.lang.RuntimeException" ->
    // "RuntimeException")
    String errorType = null;
    if (exceptionType != null) {
      int lastDot = exceptionType.lastIndexOf('.');
      errorType = lastDot >= 0 ? exceptionType.substring(lastDot + 1) : exceptionType;
    }

    // Extract errorFunctionId: the fully-qualified class.method where the exception originated.
    // Strategy:
    //   1. Try InvestigationData.callPath (works for Coral where span ends before servlet
    // exits)
    //   2. Fall back to parsing the first stack frame from exception.stacktrace
    //      (works for Spring/@ControllerAdvice where the exception is caught before
    //       propagating through instrumented method exits, leaving callPath empty)
    String errorFunctionId = null;
    if (exceptionType != null) {
      InvestigationData invData = ServiceEventsDataStore.peekInvestigationData();
      if (invData != null && invData.getCallPath() != null && !invData.getCallPath().isEmpty()) {
        errorFunctionId = invData.getCallPath().get(0).getFunctionId();
      }

      // Fallback: parse origin function from stack trace
      if (errorFunctionId == null && stackTrace != null) {
        errorFunctionId = extractFunctionIdFromStackTrace(stackTrace);
      }
    }

    // 1. Record endpoint request
    ServiceEventsDataStore.recordEndpointRequest(
        operation, route, method, statusCode, durationNs, errorType, errorFunctionId, operation);

    // Set currentOperation so recordPotentialIncident can attach exemplars
    // to the EndpointAggregation. The thread-local may have been cleared by
    // ServletAdvice.onExit's finally block (which runs before onEnd for Spring).
    // currentOperation and investigation data are cleared in onEnd's finally, which runs
    // for every request-boundary span (including the early returns above).
    ServiceEventsDataStore.setCurrentOperation(operation);

    // 2. Record potential incident
    ServiceEventsDataStore.recordPotentialIncident(
        route,
        method,
        statusCode,
        durationMs,
        exceptionType,
        exceptionMessage,
        stackTrace,
        null, // headers
        null, // queryParams
        threadName,
        startTimeNs,
        endTimeNs,
        traceId,
        spanId,
        operation);
  }

  /**
   * Check if a span is a LOCAL_ROOT — same logic as AwsSpanProcessingUtil.isLocalRoot().
   *
   * <p>A span is a local root if its parent span context is either invalid (no parent) or remote
   * (parent is from a different service/process).
   */
  private static boolean isLocalRoot(ReadableSpan span) {
    SpanContext parentContext = span.getParentSpanContext();
    return !parentContext.isValid() || parentContext.isRemote();
  }

  /**
   * Back the route out of the App Signals operation so the collector rebuilds the identical
   * {@code method + " " + route} operation string.
   *
   * <p>Handles the three shapes {@code getIngressOperation} returns:
   *
   * <ul>
   *   <li>{@code "METHOD /route"} — common case. Strip the method prefix.
   *   <li>{@code "/route"} — bare path (no method prefix). Use verbatim.
   *   <li>{@code InternalOperation / UnknownOperation / bare method / lambda} — no resolvable
   *       route. Return null so the caller skips.
   * </ul>
   *
   * <p>Matches Python's {@code _route_from_operation} and JS's {@code routeFromOperation}.
   */
  static String routeFromOperation(String operation, String method) {
    if (operation == null || operation.isEmpty()) {
      return null;
    }
    if ("InternalOperation".equals(operation) || "UnknownOperation".equals(operation)) {
      return null;
    }
    if (operation.equals(method)) {
      return null;
    }
    String prefix = method + " ";
    if (operation.startsWith(prefix)) {
      String route = operation.substring(prefix.length());
      return route.isEmpty() ? null : route;
    }
    if (operation.startsWith("/")) {
      return operation;
    }
    return null;
  }

  /**
   * Extract "com.example.MyClass.myMethod" from the first "at" line of a Java stack trace.
   *
   * <p>Expected format: {@code "at com.example.MyClass.myMethod(MyClass.java:42)"}
   *
   * @return fully qualified class.method, or null if not parseable
   */
  static String extractFunctionIdFromStackTrace(String stackTrace) {
    if (stackTrace == null || stackTrace.isEmpty()) {
      return null;
    }

    // Find first stack frame line: "\tat " (standard) or "\nat " (tabless).
    // We never search for bare "at " because it can match inside the exception
    // message (e.g. "invalid value at position 3").
    int atIdx = stackTrace.indexOf("\tat ");
    if (atIdx < 0) {
      atIdx = stackTrace.indexOf("\nat ");
    }
    if (atIdx < 0) {
      return null;
    }

    // Extract the method reference between "at " and "("
    int start = stackTrace.indexOf("at ", atIdx) + 3;
    int end = stackTrace.indexOf('(', start);
    if (end < 0) {
      // No parenthesis — try end of line
      end = stackTrace.indexOf('\n', start);
      if (end < 0) {
        end = stackTrace.length();
      }
    }

    String functionId = stackTrace.substring(start, end).trim();
    return functionId.isEmpty() ? null : functionId;
  }
}
