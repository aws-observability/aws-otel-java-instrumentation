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

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.DeploymentEvent;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.DurationMetrics;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.EndpointMetricEvent;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.ExceptionMetricEvent;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.FunctionCallMetrics;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.IncidentMetadata;

/** Unit tests for {@link ServiceEventsOtlpEmitter}. */
class ServiceEventsOtlpEmitterTest {

  private InMemoryLogRecordExporter logExporter;
  private InMemoryMetricReader metricReader;
  private SdkLoggerProvider loggerProvider;
  private SdkMeterProvider meterProvider;
  private ServiceEventsOtlpEmitter emitter;

  @BeforeEach
  void setUp() {
    logExporter = InMemoryLogRecordExporter.create();
    metricReader = InMemoryMetricReader.create();

    Resource resource =
        Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "test-service"));

    loggerProvider =
        SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter))
            .build();

    meterProvider =
        SdkMeterProvider.builder().setResource(resource).registerMetricReader(metricReader).build();

    emitter =
        new ServiceEventsOtlpEmitter(
            loggerProvider,
            meterProvider,
            "deploy-123",
            "abc123def",
            "https://github.com/org/repo",
            "com.acme.orders");
  }

  @AfterEach
  void tearDown() {
    loggerProvider.shutdown();
    meterProvider.shutdown();
  }

  @Test
  void testEmitEndpointSummary() {
    DurationMetrics duration =
        new DurationMetrics(
            Arrays.asList(100.0, 200.0), Arrays.asList(5.0, 3.0), 200.0, 100.0, 8, 1400.0);

    EndpointMetricEvent.ErrorDetail errorDetail =
        new EndpointMetricEvent.ErrorDetail("NullPointerException", "com.example.Handler.process");
    EndpointMetricEvent.ErrorBreakdownEntry breakdownEntry =
        new EndpointMetricEvent.ErrorBreakdownEntry(Arrays.asList(errorDetail), 3, "exception");
    EndpointMetricEvent.IncidentExemplarEntry exemplar =
        new EndpointMetricEvent.IncidentExemplarEntry(
            "snap-001", "exception", "high", System.currentTimeMillis());

    EndpointMetricEvent event =
        EndpointMetricEvent.builder()
            .environment("production")
            .serviceName("test-service")
            .deploymentId("deploy-123")
            .method("POST")
            .route("/api/users")
            .operation("POST /api/users")
            .pid(12345)
            .timestamp("2026-04-10T00:00:00Z")
            .count(10)
            .faults(3)
            .errors(0)
            .duration(duration)
            .errorBreakdown(Arrays.asList(breakdownEntry))
            .incidentsExemplar(Arrays.asList(exemplar))
            .build();

    emitter.emitEndpointSummary(event);

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertEquals(1, logs.size());

    LogRecordData log = logs.get(0);

    // Full spec conformance validation (scope, event.name, attributes, body structure)
    ServiceEventsOtlpSignalValidator.validateEndpointSummary(log);
    ServiceEventsOtlpSignalValidator.validateVcsAndDeploymentAttrs(log);

    // Verify specific values
    Attributes attrs = log.getAttributes();
    assertEquals("POST", attrs.get(AttributeKey.stringKey("http.request.method")));
    assertEquals("/api/users", attrs.get(AttributeKey.stringKey("url.route")));
    assertEquals(
        "POST /api/users", attrs.get(AttributeKey.stringKey("aws.service_events.operation")));
    assertEquals(10L, attrs.get(AttributeKey.longKey("aws.service_events.request.count")));
    assertEquals(3L, attrs.get(AttributeKey.longKey("aws.service_events.request.faults")));
    assertEquals(0L, attrs.get(AttributeKey.longKey("aws.service_events.request.errors")));
    assertEquals(1L, attrs.get(AttributeKey.longKey("aws.service_events.incident.count")));
    assertNull(attrs.get(AttributeKey.stringKey("aws.service_events.service_code_namespace")));
    assertEquals("abc123def", attrs.get(AttributeKey.stringKey("vcs.ref.head.revision")));
    assertEquals(
        "https://github.com/org/repo",
        attrs.get(AttributeKey.stringKey("vcs.repository.url.full")));
    assertEquals(
        "deploy-123", attrs.get(AttributeKey.stringKey("aws.service_events.deployment.id")));
  }

  @Test
  void testEmitFunctionCall() {
    DurationMetrics duration =
        new DurationMetrics(Arrays.asList(50.0), Arrays.asList(10.0), 50.0, 50.0, 10, 500.0);

    Map<String, Integer> exceptions = new HashMap<>();
    exceptions.put("IllegalArgumentException", 2);

    FunctionCallMetrics event =
        FunctionCallMetrics.builder()
            .environment("production")
            .serviceName("test-service")
            .deploymentId("deploy-123")
            .functionId("com.example.Service.process")
            .operation("POST /api/users")
            .caller("com.example.Controller.handle")
            .pid(12345)
            .timestamp("2026-04-10T00:00:00Z")
            .exceptions(exceptions)
            .duration(duration)
            .build();

    emitter.emitFunctionCall(event);

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertEquals(1, logs.size());

    LogRecordData log = logs.get(0);

    // Full spec conformance validation (scope, event.name, attributes, body structure)
    ServiceEventsOtlpSignalValidator.validateFunctionCall(log);
    ServiceEventsOtlpSignalValidator.validateVcsAndDeploymentAttrs(log);

    // Verify specific values
    Attributes attrs = log.getAttributes();
    assertEquals(
        "com.example.Service.process",
        attrs.get(AttributeKey.stringKey("aws.service_events.function_name")));
    assertEquals(
        "POST /api/users", attrs.get(AttributeKey.stringKey("aws.service_events.operation")));
    assertEquals(
        "com.example.Controller.handle",
        attrs.get(AttributeKey.stringKey("aws.service_events.caller")));
    assertEquals("1", attrs.get(AttributeKey.stringKey("aws.service_events.version")));
    assertNull(attrs.get(AttributeKey.stringKey("aws.service_events.service_code_namespace")));
    assertEquals("abc123def", attrs.get(AttributeKey.stringKey("vcs.ref.head.revision")));
  }

  @Test
  void testEmitIncidentSnapshot() {
    // Build raw record map (as IncidentSnapshotRecordBuilder produces)
    Map<String, Object> record = new LinkedHashMap<>();
    Map<String, Object> exceptionInfo = new LinkedHashMap<>();
    exceptionInfo.put("exception_type", "NullPointerException");
    exceptionInfo.put("exception_message", "null reference");
    record.put("exception_info", exceptionInfo);

    Map<String, Object> requestContext = new LinkedHashMap<>();
    requestContext.put("method", "GET");
    requestContext.put("route", "/api/data");
    record.put("request_context", requestContext);

    // Build IncidentMetadata with trace context
    IncidentMetadata incident =
        new IncidentMetadata(
            "main", // threadName
            0L, // startNs
            1000000L, // endNs
            "/api/data", // route
            "GET", // method
            "GET /api/data", // operation
            500, // statusCode
            15.5, // durationMs
            "exception", // triggerType
            "high", // severity
            "snap-001", // snapshotId
            "NullPointerException", // exceptionType
            "null reference", // exceptionMessage
            "at com.example.Foo.bar(Foo.java:42)", // stackTrace
            "0af7651916cd43dd8448eb211c80319c", // traceId
            "b7ad6b7169203331"); // spanId

    emitter.emitIncidentSnapshot(record, incident);

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertEquals(1, logs.size());

    LogRecordData log = logs.get(0);

    // Full spec conformance validation (scope, event.name, attributes, body structure)
    ServiceEventsOtlpSignalValidator.validateIncidentSnapshot(log);
    ServiceEventsOtlpSignalValidator.validateVcsAndDeploymentAttrs(log);

    // Verify specific values
    Attributes attrs = log.getAttributes();
    assertEquals("snap-001", attrs.get(AttributeKey.stringKey("aws.service_events.snapshot_id")));
    assertEquals("exception", attrs.get(AttributeKey.stringKey("aws.service_events.trigger_type")));
    assertEquals(
        "GET /api/data", attrs.get(AttributeKey.stringKey("aws.service_events.operation")));
    assertEquals(true, attrs.get(AttributeKey.booleanKey("aws.service_events.is_partial")));
    assertEquals("GET", attrs.get(AttributeKey.stringKey("http.request.method")));
    assertEquals("/api/data", attrs.get(AttributeKey.stringKey("url.route")));
    assertEquals(500L, attrs.get(AttributeKey.longKey("http.response.status_code")));
    assertEquals("http", attrs.get(AttributeKey.stringKey("aws.service_events.request.type")));
    assertEquals(
        "com.acme.orders",
        attrs.get(AttributeKey.stringKey("aws.service_events.service_code_namespace")));

    // Verify trace context is set
    assertNotNull(log.getSpanContext());
    assertTrue(log.getSpanContext().isValid());
    assertEquals("0af7651916cd43dd8448eb211c80319c", log.getSpanContext().getTraceId());
    assertEquals("b7ad6b7169203331", log.getSpanContext().getSpanId());
  }

  @Test
  void testEmitIncidentSnapshotWithoutTraceContext() {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("exception_info", Collections.singletonMap("exception_type", "RuntimeException"));

    IncidentMetadata incident =
        new IncidentMetadata(
            "main",
            0L,
            1000000L,
            "/api/data",
            "GET",
            "GET /api/data",
            200,
            10.0,
            "slow",
            "medium",
            "snap-002",
            null,
            null,
            null,
            null,
            null);

    emitter.emitIncidentSnapshot(record, incident);

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertEquals(1, logs.size());

    LogRecordData log = logs.get(0);

    // Without traceId/spanId, span context should be invalid
    assertFalse(log.getSpanContext().isValid());
  }

  @Test
  void testEmitDeploymentEvent() {
    DeploymentEvent.DeploymentContext ctx =
        new DeploymentEvent.DeploymentContext(
            "https://github.com/org/repo",
            "abc123def",
            "https://deploy.example.com/123",
            "2026-04-10T00:00:00Z",
            "deploy-123");

    DeploymentEvent event =
        DeploymentEvent.builder()
            .timestamp("2026-04-10T00:00:00Z")
            .serviceName("test-service")
            .environment("production")
            .sdkVersion("1.0.0")
            .pid(12345)
            .deploymentContext(ctx)
            .build();

    emitter.emitDeploymentEvent(event, "startup");

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertEquals(1, logs.size());

    LogRecordData log = logs.get(0);

    // Full spec conformance validation (scope, event.name, no body)
    ServiceEventsOtlpSignalValidator.validateDeploymentEventFull(log);

    // Verify specific values
    Attributes attrs = log.getAttributes();
    assertEquals("abc123def", attrs.get(AttributeKey.stringKey("vcs.ref.head.revision")));
    assertEquals(
        "https://github.com/org/repo",
        attrs.get(AttributeKey.stringKey("vcs.repository.url.full")));
    assertEquals(
        "deploy-123", attrs.get(AttributeKey.stringKey("aws.service_events.deployment.id")));
    assertEquals(
        "https://deploy.example.com/123",
        attrs.get(AttributeKey.stringKey("aws.service_events.deployment.url")));
    assertEquals(
        "2026-04-10T00:00:00Z",
        attrs.get(AttributeKey.stringKey("aws.service_events.deployment.timestamp")));
    assertNull(attrs.get(AttributeKey.stringKey("aws.service_events.service_code_namespace")));
  }

  @Test
  void testEmitEndpointErrorMetrics() {
    List<ExceptionMetricEvent> metrics = new ArrayList<>();
    metrics.add(
        ExceptionMetricEvent.builder()
            .serviceName("test-service")
            .environment("production")
            .operation("POST /api/users")
            .functionId("com.example.Handler.process")
            .errorType("NullPointerException")
            .count(5)
            .timestampMs(System.currentTimeMillis())
            .build());
    metrics.add(
        ExceptionMetricEvent.builder()
            .serviceName("test-service")
            .environment("production")
            .operation("GET /api/data")
            .functionId("com.example.Service.fetch")
            .errorType("IOException")
            .count(2)
            .timestampMs(System.currentTimeMillis())
            .build());

    emitter.emitEndpointErrorMetrics(metrics);

    // Force metric collection
    List<MetricData> metricDataList = new ArrayList<>(metricReader.collectAllMetrics());
    assertFalse(metricDataList.isEmpty(), "Should have at least one metric");

    // Find the error count metric
    MetricData errorMetric = null;
    for (MetricData md : metricDataList) {
      if ("count".equals(md.getName())) {
        errorMetric = md;
        break;
      }
    }
    assertNotNull(errorMetric, "Should have count metric");

    // Full spec conformance validation (name, unit, type, monotonic, data point attributes)
    ServiceEventsOtlpSignalValidator.validateErrorMetric(errorMetric);
  }

  @Test
  void testVcsAndDeploymentAttributesOmittedWhenEmpty() {
    // Create emitter with empty VCS/deployment fields
    ServiceEventsOtlpEmitter emptyEmitter =
        new ServiceEventsOtlpEmitter(loggerProvider, meterProvider, "", "", "", "");

    DeploymentEvent event =
        DeploymentEvent.builder()
            .timestamp("2026-04-10T00:00:00Z")
            .serviceName("test-service")
            .environment("production")
            .sdkVersion("1.0.0")
            .pid(12345)
            .build();

    emptyEmitter.emitDeploymentEvent(event, "startup");

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertEquals(1, logs.size());

    Attributes attrs = logs.get(0).getAttributes();

    // VCS and deployment attributes should NOT be present when empty
    assertNull(attrs.get(AttributeKey.stringKey("vcs.ref.head.revision")));
    assertNull(attrs.get(AttributeKey.stringKey("vcs.repository.url.full")));
    assertNull(attrs.get(AttributeKey.stringKey("aws.service_events.deployment.id")));
  }

  @Test
  void testInstrumentationScope() {
    DeploymentEvent event =
        DeploymentEvent.builder()
            .timestamp("2026-04-10T00:00:00Z")
            .serviceName("test-service")
            .environment("production")
            .sdkVersion("1.0.0")
            .pid(12345)
            .build();

    emitter.emitDeploymentEvent(event, "periodic");

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertEquals(1, logs.size());

    // Uses validator for scope check
    ServiceEventsOtlpSignalValidator.validateScope(logs.get(0));
  }
}
