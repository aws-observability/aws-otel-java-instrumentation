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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.DeploymentEvent;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.DurationMetrics;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.EndpointMetricEvent;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.ExceptionMetricEvent;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.FunctionCallMetrics;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.IncidentMetadata;

/**
 * Emits ServiceEvents telemetry signals as OTLP LogRecords and OTel Metrics.
 *
 * <p>Maps existing collector data models to OTLP format per SERVICE_EVENTS_OTLP_SIGNALS_SPEC.md
 * v2.0. Uses dedicated SdkLoggerProvider and SdkMeterProvider, fully isolated from OTel application
 * logs/metrics and Application Signals.
 */
public class ServiceEventsOtlpEmitter {

  private static final String INSTRUMENTATION_SCOPE = "serviceevents";
  private static final String INSTRUMENTATION_VERSION = "1.0";

  // Eager-init fields (always available)
  private final String deploymentId;
  private final String gitCommitSha;
  private final String gitRepoUrl;
  private final String serviceCodeNamespace;

  // Lazy-init fields: created on first emit when ResourceHolder is populated
  private final Supplier<Resource> resourceSupplier;
  private final Supplier<SdkLoggerProvider> loggerProviderFactory;
  private final Supplier<SdkMeterProvider> meterProviderFactory;
  private volatile Logger logger;
  private volatile LongCounter errorCounter;
  private volatile SdkLoggerProvider ownedLoggerProvider;
  private volatile SdkMeterProvider ownedMeterProvider;

  /**
   * Primary constructor for production use with lazy provider creation.
   *
   * <p>Providers are created on the first emit call, allowing ResourceHolder to be populated by
   * OTel autoconfiguration before the Resource is read. The supplied factories create the dedicated
   * providers when invoked.
   */
  public ServiceEventsOtlpEmitter(
      Supplier<Resource> resourceSupplier,
      Supplier<SdkLoggerProvider> loggerProviderFactory,
      Supplier<SdkMeterProvider> meterProviderFactory,
      String deploymentId,
      String gitCommitSha,
      String gitRepoUrl,
      String serviceCodeNamespace) {
    this.resourceSupplier = resourceSupplier;
    this.loggerProviderFactory = loggerProviderFactory;
    this.meterProviderFactory = meterProviderFactory;
    this.deploymentId = deploymentId;
    this.gitCommitSha = gitCommitSha;
    this.gitRepoUrl = gitRepoUrl;
    this.serviceCodeNamespace = serviceCodeNamespace;
  }

  /**
   * Constructor for tests where providers are pre-built (e.g. InMemory exporters).
   *
   * <p>Providers are initialized eagerly — no lazy init needed.
   */
  public ServiceEventsOtlpEmitter(
      SdkLoggerProvider loggerProvider,
      SdkMeterProvider meterProvider,
      String deploymentId,
      String gitCommitSha,
      String gitRepoUrl,
      String serviceCodeNamespace) {
    this.resourceSupplier = null;
    this.loggerProviderFactory = null;
    this.meterProviderFactory = null;
    this.deploymentId = deploymentId;
    this.gitCommitSha = gitCommitSha;
    this.gitRepoUrl = gitRepoUrl;
    this.serviceCodeNamespace = serviceCodeNamespace;
    // Eagerly initialize from provided providers
    this.logger =
        loggerProvider
            .loggerBuilder(INSTRUMENTATION_SCOPE)
            .setInstrumentationVersion(INSTRUMENTATION_VERSION)
            .build();
    Meter meter =
        meterProvider
            .meterBuilder(INSTRUMENTATION_SCOPE)
            .setInstrumentationVersion(INSTRUMENTATION_VERSION)
            .build();
    this.errorCounter = meter.counterBuilder("count").setUnit("Count").build();
  }

  private void ensureInitialized() {
    if (logger != null) {
      return;
    }
    synchronized (this) {
      if (logger != null) {
        return;
      }
      ownedLoggerProvider = loggerProviderFactory.get();
      ownedMeterProvider = meterProviderFactory.get();
      logger =
          ownedLoggerProvider
              .loggerBuilder(INSTRUMENTATION_SCOPE)
              .setInstrumentationVersion(INSTRUMENTATION_VERSION)
              .build();
      Meter meter =
          ownedMeterProvider
              .meterBuilder(INSTRUMENTATION_SCOPE)
              .setInstrumentationVersion(INSTRUMENTATION_VERSION)
              .build();
      errorCounter = meter.counterBuilder("count").setUnit("Count").build();
    }
  }

  /** Get the Resource, reading from the supplier (which may use ResourceHolder). */
  public Resource getResource() {
    return resourceSupplier != null ? resourceSupplier.get() : Resource.getDefault();
  }

  /** Get the owned LoggerProvider (for shutdown). May be null if not yet initialized. */
  public SdkLoggerProvider getLoggerProvider() {
    return ownedLoggerProvider;
  }

  /** Get the owned MeterProvider (for shutdown). May be null if not yet initialized. */
  public SdkMeterProvider getMeterProvider() {
    return ownedMeterProvider;
  }

  /**
   * Create a {@link FunctionMetricsBridgeImpl} backed by this emitter's MeterProvider.
   *
   * <p>Forces lazy provider initialization so the Histogram instrument is owned by the same {@link
   * SdkMeterProvider} that handles AggregationTemporality, the {@code service.function.duration}
   * View, and shutdown lifecycle. The bridge is suitable for handing to {@code
   * ServiceEventsDataStore.setFunctionMetricsBridge} so {@code methodExit} can record into the OTel
   * pipeline directly from the application classloader hot path.
   *
   * @param baseAttributes per-data-point attributes layered onto every record (currently just
   *     {@code Telemetry.Source}; process-constants like {@code aws.service_events.version} and
   *     {@code vcs.*} live on the MeterProvider's Resource instead).
   * @return a fresh bridge instance; safe to share across threads
   */
  public FunctionMetricsBridgeImpl buildFunctionMetricsBridge(Attributes baseAttributes) {
    ensureInitialized();
    Meter meter =
        ownedMeterProvider
            .meterBuilder(INSTRUMENTATION_SCOPE)
            .setInstrumentationVersion(INSTRUMENTATION_VERSION)
            .build();
    // Histogram: sampled calls only. The View registered on the MeterProvider
    // converts this to a base-2 ExponentialHistogram on export.
    io.opentelemetry.api.metrics.DoubleHistogram durationHistogram =
        meter
            .histogramBuilder("service.function.duration")
            .setUnit("Microseconds")
            .setDescription("Function call duration")
            .build();
    return new FunctionMetricsBridgeImpl(durationHistogram, baseAttributes);
  }

  /** Emit an EndpointSummary OTLP LogRecord. */
  public void emitEndpointSummary(EndpointMetricEvent event) {
    ensureInitialized();
    AttributesBuilder attrs = Attributes.builder();
    attrs.put("http.request.method", event.getMethod());
    attrs.put("url.route", event.getRoute());
    attrs.put("aws.service_events.operation", event.getOperation());
    attrs.put("aws.service_events.request.count", event.getCount());
    attrs.put("aws.service_events.request.faults", event.getFaults());
    attrs.put("aws.service_events.request.errors", event.getErrors());
    int incidentCount =
        event.getIncidentsExemplar() != null ? event.getIncidentsExemplar().size() : 0;
    attrs.put("aws.service_events.incident.count", incidentCount);
    putVcsAndDeploymentAttrs(attrs);

    // Body: duration, exception_breakdown, incidents_exemplar
    Map<String, Value<?>> bodyMap = new HashMap<>();

    if (event.getDuration() != null) {
      bodyMap.put("duration", durationToValue(event.getDuration()));
    }

    if (event.getErrorBreakdown() != null) {
      List<Value<?>> breakdownList = new ArrayList<>();
      for (EndpointMetricEvent.ErrorBreakdownEntry entry : event.getErrorBreakdown()) {
        Map<String, Value<?>> entryMap = new HashMap<>();
        entryMap.put("failure_type", Value.of(entry.getFailureType()));
        entryMap.put("count", Value.of(entry.getCount()));
        List<Value<?>> errorsList = new ArrayList<>();
        if (entry.getErrors() != null) {
          for (EndpointMetricEvent.ErrorDetail detail : entry.getErrors()) {
            Map<String, Value<?>> detailMap = new HashMap<>();
            detailMap.put("exception_type", Value.of(detail.getErrorType()));
            detailMap.put("function_name", Value.of(detail.getFunctionId()));
            errorsList.add(Value.of(detailMap));
          }
        }
        entryMap.put("exceptions", Value.of(errorsList));
        breakdownList.add(Value.of(entryMap));
      }
      bodyMap.put("exception_breakdown", Value.of(breakdownList));
    }

    if (event.getIncidentsExemplar() != null) {
      List<Value<?>> exemplarList = new ArrayList<>();
      for (EndpointMetricEvent.IncidentExemplarEntry entry : event.getIncidentsExemplar()) {
        Map<String, Value<?>> entryMap = new HashMap<>();
        entryMap.put("snapshot_id", Value.of(entry.getSnapshotId()));
        entryMap.put("trigger_type", Value.of(entry.getTriggerType()));
        entryMap.put("timestamp", Value.of(entry.getTimestamp()));
        exemplarList.add(Value.of(entryMap));
      }
      bodyMap.put("incidents_exemplar", Value.of(exemplarList));
    }

    emitLog(logger, "aws.service_events.endpoint_summary", attrs.build(), Value.of(bodyMap), null);
  }

  /**
   * Emit a FunctionCall OTLP LogRecord (legacy path).
   *
   * <p>NOT called when the OTel histogram bridge is wired (the default in network OTLP mode). When
   * wired, {@link software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore#methodExit}
   * records each function call directly into the {@code service.function.duration} histogram at
   * call time, bypassing this method entirely. This method is retained only for the EMF/console
   * fallback path (no OTLP emitter) and the {@code OUTPUT_FILE} mode.
   */
  public void emitFunctionCall(FunctionCallMetrics event) {
    ensureInitialized();
    AttributesBuilder attrs = Attributes.builder();
    attrs.put("aws.service_events.function_name", event.getFunctionId());
    attrs.put("aws.service_events.operation", event.getOperation());
    if (event.getCaller() != null) {
      attrs.put("aws.service_events.caller", event.getCaller());
    }
    attrs.put("aws.service_events.version", event.getVersion());
    putVcsAndDeploymentAttrs(attrs);

    // Body: exceptions, duration
    Map<String, Value<?>> bodyMap = new HashMap<>();

    if (event.getExceptions() != null && !event.getExceptions().isEmpty()) {
      Map<String, Value<?>> exceptionsMap = new HashMap<>();
      for (Map.Entry<String, Integer> entry : event.getExceptions().entrySet()) {
        exceptionsMap.put(entry.getKey(), Value.of(entry.getValue()));
      }
      bodyMap.put("exceptions", Value.of(exceptionsMap));
    }

    if (event.getDuration() != null) {
      bodyMap.put("duration", durationToValue(event.getDuration()));
    }

    emitLog(logger, "aws.service_events.function_call", attrs.build(), Value.of(bodyMap), null);
  }

  /**
   * Emit an IncidentSnapshot OTLP LogRecord.
   *
   * @param record the raw record map built by IncidentSnapshotRecordBuilder (body fields)
   * @param incident the IncidentMetadata (attribute fields + trace context)
   */
  @SuppressWarnings("unchecked")
  public void emitIncidentSnapshot(Map<String, Object> record, IncidentMetadata incident) {
    ensureInitialized();
    AttributesBuilder attrs = Attributes.builder();
    attrs.put("aws.service_events.snapshot_id", incident.snapshotId);
    attrs.put("aws.service_events.trigger_type", incident.triggerType);
    attrs.put("aws.service_events.operation", incident.operation);
    attrs.put("aws.service_events.duration_ms", incident.durationMs);
    attrs.put("aws.service_events.is_partial", incident.isPartial);
    attrs.put("http.request.method", incident.method);
    attrs.put("url.route", incident.route);
    attrs.put("http.response.status_code", (long) incident.statusCode);
    attrs.put("aws.service_events.request.type", "http");
    putVcsAndDeploymentAttrs(attrs);
    putServiceCodeNamespaceAttr(attrs);

    // Body: extract from record map
    Map<String, Value<?>> bodyMap = new HashMap<>();

    Object exceptionInfo = record.get("exception_info");
    if (exceptionInfo != null) {
      bodyMap.put("exception_info", mapToValue(exceptionInfo));
    }

    Object requestContext = record.get("request_context");
    if (requestContext != null) {
      bodyMap.put("request_context", mapToValue(requestContext));
    }

    // Trace context from IncidentMetadata
    Context context = null;
    if (incident.traceId != null
        && !incident.traceId.isEmpty()
        && incident.spanId != null
        && !incident.spanId.isEmpty()) {
      SpanContext spanContext =
          SpanContext.create(
              incident.traceId, incident.spanId, TraceFlags.getSampled(), TraceState.getDefault());
      context = Context.root().with(Span.wrap(spanContext));
    }

    emitLog(
        logger, "aws.service_events.incident_snapshot", attrs.build(), Value.of(bodyMap), context);
  }

  /** Emit a DeploymentEvent OTLP LogRecord. */
  public void emitDeploymentEvent(DeploymentEvent event, String trigger) {
    ensureInitialized();
    AttributesBuilder attrs = Attributes.builder();
    putVcsAndDeploymentAttrs(attrs);

    DeploymentEvent.DeploymentContext ctx = event.getDeploymentContext();
    if (ctx != null) {
      if (ctx.getDeploymentUrl() != null) {
        attrs.put("aws.service_events.deployment.url", ctx.getDeploymentUrl());
      }
      if (ctx.getDeploymentTimestamp() != null) {
        attrs.put("aws.service_events.deployment.timestamp", ctx.getDeploymentTimestamp());
      }
    }

    attrs.put("aws.service_events.deployment.trigger", trigger);

    // DeploymentEvent has no body
    emitLog(logger, "aws.service_events.deployment_event", attrs.build(), null, null);
  }

  /** Emit EndpointErrorMetrics as OTel Counter data points. */
  public void emitEndpointErrorMetrics(List<ExceptionMetricEvent> metrics) {
    ensureInitialized();
    for (ExceptionMetricEvent metric : metrics) {
      AttributesBuilder metricAttrs =
          Attributes.builder()
              .put("Telemetry.Source", "ServiceEvents")
              .put("service_name", metric.getServiceName());
      // Omit the environment dimension when unset (no sentinel default), matching the EMF
      // Dimensions list dropped in ExceptionMetricEvent.toMap().
      String env = metric.getEnvironment();
      if (env != null && !env.isEmpty()) {
        metricAttrs.put("environment", env);
      }
      metricAttrs.put("operation", metric.getOperation()).put("exception", metric.getException());
      errorCounter.add(metric.getCount(), metricAttrs.build());
    }
  }

  private void emitLog(
      Logger loggerToUse, String eventName, Attributes attributes, Value<?> body, Context context) {
    LogRecordBuilder builder =
        loggerToUse
            .logRecordBuilder()
            .setTimestamp(Instant.now())
            .setEventName(eventName)
            .setAttribute(AttributeKey.stringKey("event.name"), eventName)
            .setAllAttributes(attributes);
    if (body != null) {
      builder.setBody(body);
    }
    if (context != null) {
      builder.setContext(context);
    }
    builder.emit();
  }

  private void putVcsAndDeploymentAttrs(AttributesBuilder attrs) {
    if (gitCommitSha != null && !gitCommitSha.isEmpty()) {
      attrs.put("vcs.ref.head.revision", gitCommitSha);
    }
    if (gitRepoUrl != null && !gitRepoUrl.isEmpty()) {
      attrs.put("vcs.repository.url.full", gitRepoUrl);
    }
    if (deploymentId != null && !deploymentId.isEmpty()) {
      attrs.put("aws.service_events.deployment.id", deploymentId);
    }
  }

  private void putServiceCodeNamespaceAttr(AttributesBuilder attrs) {
    if (serviceCodeNamespace != null && !serviceCodeNamespace.isEmpty()) {
      attrs.put("aws.service_events.service_code_namespace", serviceCodeNamespace);
    }
  }

  private Value<?> durationToValue(DurationMetrics duration) {
    Map<String, Value<?>> durationMap = new HashMap<>();
    List<Value<?>> valuesList = new ArrayList<>();
    for (Double v : duration.getValues()) {
      valuesList.add(Value.of(v));
    }
    durationMap.put("Values", Value.of(valuesList));

    List<Value<?>> countsList = new ArrayList<>();
    for (Double c : duration.getCounts()) {
      countsList.add(Value.of(c.longValue()));
    }
    durationMap.put("Counts", Value.of(countsList));

    durationMap.put("Max", Value.of(duration.getMax()));
    durationMap.put("Min", Value.of(duration.getMin()));
    durationMap.put("Count", Value.of((long) duration.getCount()));
    durationMap.put("Sum", Value.of(duration.getSum()));
    return Value.of(durationMap);
  }

  /**
   * Iteratively convert a Java object tree (Map, List, or primitive) to a Value.
   *
   * <p>Uses an explicit work stack and result stack (post-order traversal) instead of recursion to
   * handle deeply nested structures (e.g. incident call paths with many levels) without risking
   * StackOverflowError.
   */
  @SuppressWarnings("unchecked")
  private Value<?> mapToValue(Object obj) {
    if (isLeaf(obj)) {
      return leafToValue(obj);
    }

    // Work stack holds items to process. Markers indicate where to assemble a container
    // from already-computed child Values on the result stack.
    Deque<Object> workStack = new ArrayDeque<>();
    Deque<Value<?>> resultStack = new ArrayDeque<>();

    workStack.push(obj);

    while (!workStack.isEmpty()) {
      Object current = workStack.pop();

      if (current instanceof MapMarker) {
        MapMarker m = (MapMarker) current;
        Map<String, Value<?>> map = new LinkedHashMap<>();
        // The last-pushed child sits on top of the result stack, so pop into a temp array in
        // reverse index order, then insert forward. This preserves the source map's insertion
        // order (e.g. exception_type -> exception_message -> stack_trace -> call_path) instead
        // of reversing it, matching the Python/JS serviceevents schema. Mirrors ListMarker below.
        Value<?>[] vals = new Value<?>[m.keys.length];
        for (int i = m.keys.length - 1; i >= 0; i--) {
          vals[i] = resultStack.pop();
        }
        for (int i = 0; i < m.keys.length; i++) {
          map.put(m.keys[i], vals[i]);
        }
        resultStack.push(Value.of(map));
        continue;
      }

      if (current instanceof ListMarker) {
        ListMarker m = (ListMarker) current;
        Value<?>[] arr = new Value<?>[m.size];
        // Last-pushed child is on top, so pop in reverse index order.
        for (int i = m.size - 1; i >= 0; i--) {
          arr[i] = resultStack.pop();
        }
        List<Value<?>> list = new ArrayList<>(m.size);
        for (Value<?> v : arr) {
          list.add(v);
        }
        resultStack.push(Value.of(list));
        continue;
      }

      if (isLeaf(current)) {
        resultStack.push(leafToValue(current));
        continue;
      }

      if (current instanceof Map) {
        Map<?, ?> srcMap = (Map<?, ?>) current;
        String[] keys = new String[srcMap.size()];
        Object[] vals = new Object[srcMap.size()];
        int i = 0;
        for (Map.Entry<?, ?> entry : srcMap.entrySet()) {
          keys[i] = String.valueOf(entry.getKey());
          vals[i] = entry.getValue();
          i++;
        }
        // Push marker first (processed after all children).
        workStack.push(new MapMarker(keys));
        // Push values in reverse so first entry is processed first.
        for (int j = vals.length - 1; j >= 0; j--) {
          workStack.push(vals[j]);
        }
        continue;
      }

      if (current instanceof List) {
        List<?> srcList = (List<?>) current;
        workStack.push(new ListMarker(srcList.size()));
        for (int j = srcList.size() - 1; j >= 0; j--) {
          workStack.push(srcList.get(j));
        }
      }
    }

    return resultStack.pop();
  }

  /** Returns true if the object is a leaf (not a Map or List container). */
  private static boolean isLeaf(Object obj) {
    return !(obj instanceof Map) && !(obj instanceof List);
  }

  /** Convert a leaf object (null, String, Boolean, Number, or fallback) to a Value. */
  private static Value<?> leafToValue(Object obj) {
    if (obj == null) {
      return Value.of("");
    }
    if (obj instanceof String) {
      return Value.of((String) obj);
    }
    if (obj instanceof Boolean) {
      return Value.of((Boolean) obj);
    }
    if (obj instanceof Long) {
      return Value.of((Long) obj);
    }
    if (obj instanceof Integer) {
      return Value.of((long) (Integer) obj);
    }
    if (obj instanceof Double) {
      return Value.of((Double) obj);
    }
    if (obj instanceof Float) {
      return Value.of((double) (Float) obj);
    }
    return Value.of(String.valueOf(obj));
  }

  /** Marker pushed onto the work stack to signal assembly of a Map container. */
  private static final class MapMarker {
    final String[] keys;

    MapMarker(String[] keys) {
      this.keys = keys;
    }
  }

  /** Marker pushed onto the work stack to signal assembly of a List container. */
  private static final class ListMarker {
    final int size;

    ListMarker(int size) {
      this.size = size;
    }
  }
}
