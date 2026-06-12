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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors;

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
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsOtlpEmitter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsOtlpSignalValidator;
import software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore;

/**
 * Integration tests verifying that collectors emit OTLP signals through the emitter when connected
 * to real InMemory exporters. Covers plan verification items 2 (collector calls emitter) and 3
 * (InMemory exporters capture correct OTLP output).
 */
class OtlpCollectorIntegrationTest {

  private InMemoryLogRecordExporter logExporter;
  private InMemoryMetricReader metricReader;
  private SdkLoggerProvider loggerProvider;
  private SdkMeterProvider meterProvider;
  private ServiceEventsOtlpEmitter otlpEmitter;

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

    otlpEmitter =
        new ServiceEventsOtlpEmitter(
            loggerProvider,
            meterProvider,
            "deploy-int-test",
            "deadbeef",
            "https://github.com/test/repo",
            "com.test.svc");
  }

  @AfterEach
  void tearDown() {
    loggerProvider.shutdown();
    meterProvider.shutdown();
  }

  @Test
  void testDeploymentEventCollectorEmitsViaOtlp() throws InterruptedException {
    DeploymentEventCollector collector =
        new DeploymentEventCollector(
            1000,
            "production",
            "my-service",
            "deploy-123",
            "2026-04-10T00:00:00Z",
            "https://deploy.example.com",
            "abc123",
            "https://github.com/org/repo",
            otlpEmitter);

    collector.start();
    // Wait for first collect cycle
    Thread.sleep(2000);
    collector.stop();

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertFalse(logs.isEmpty(), "DeploymentEventCollector should emit at least one OTLP log");

    LogRecordData log = logs.get(0);

    // Full spec conformance validation
    ServiceEventsOtlpSignalValidator.validateDeploymentEvent(log);
    ServiceEventsOtlpSignalValidator.validateVcsAndDeploymentAttrs(log);

    // Verify specific values
    Attributes attrs = log.getAttributes();
    assertEquals("deadbeef", attrs.get(AttributeKey.stringKey("vcs.ref.head.revision")));
    assertEquals(
        "https://github.com/test/repo",
        attrs.get(AttributeKey.stringKey("vcs.repository.url.full")));
    assertEquals(
        "deploy-int-test", attrs.get(AttributeKey.stringKey("aws.service_events.deployment.id")));
  }

  @Test
  void testFunctionCallCollectorEmitsViaOtlp() throws InterruptedException {
    // Populate data store with test function call data
    ServiceEventsDataStore.recordMethodInvocation(
        "POST /api/users", "com.example.UserService.create", 5_000_000L, null, null);
    ServiceEventsDataStore.recordMethodInvocation(
        "POST /api/users",
        "com.example.UserService.create",
        3_000_000L,
        "com.example.Controller.handle",
        null);

    FunctionCallCollector collector =
        new FunctionCallCollector(
            1000,
            "production",
            "my-service",
            "deploy-123",
            "",
            "",
            "abc123",
            "https://github.com/org/repo",
            otlpEmitter);

    collector.start();
    Thread.sleep(2000);
    collector.stop();

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertFalse(logs.isEmpty(), "FunctionCallCollector should emit at least one OTLP log");

    // Find the function call log
    LogRecordData functionLog = null;
    for (LogRecordData log : logs) {
      if ("aws.service_events.function_call"
          .equals(log.getAttributes().get(AttributeKey.stringKey("event.name")))) {
        functionLog = log;
        break;
      }
    }
    assertNotNull(functionLog, "Should have an aws.service_events.function_call log record");

    // Full spec conformance validation (scope, event.name, attributes, body)
    ServiceEventsOtlpSignalValidator.validateFunctionCall(functionLog);

    Attributes attrs = functionLog.getAttributes();
    assertEquals(
        "com.example.UserService.create",
        attrs.get(AttributeKey.stringKey("aws.service_events.function_name")));
    assertEquals(
        "POST /api/users", attrs.get(AttributeKey.stringKey("aws.service_events.operation")));
  }

  @Test
  void testEndpointCollectorEmitsViaOtlp() throws InterruptedException {
    // Populate data store with test endpoint data
    ServiceEventsDataStore.recordEndpointRequest(
        "GET /api/data",
        "/api/data",
        "GET",
        200,
        10_000_000L, // 10ms
        null,
        null,
        "GET /api/data");

    ServiceEventsDataStore.recordEndpointRequest(
        "GET /api/data",
        "/api/data",
        "GET",
        500,
        50_000_000L, // 50ms
        "NullPointerException",
        "com.example.DataService.fetch",
        "GET /api/data");

    EndpointCollector collector =
        new EndpointCollector(
            1000,
            "production",
            "my-service",
            "deploy-123",
            "",
            "",
            "abc123",
            "https://github.com/org/repo",
            otlpEmitter,
            false);

    collector.start();
    Thread.sleep(2000);
    collector.stop();

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertFalse(logs.isEmpty(), "EndpointCollector should emit at least one OTLP log");

    // Find the endpoint summary log
    LogRecordData endpointLog = null;
    for (LogRecordData log : logs) {
      if ("aws.service_events.endpoint_summary"
          .equals(log.getAttributes().get(AttributeKey.stringKey("event.name")))) {
        endpointLog = log;
        break;
      }
    }
    assertNotNull(endpointLog, "Should have an aws.service_events.endpoint_summary log record");

    // Full spec conformance validation (scope, event.name, attributes, body structure)
    ServiceEventsOtlpSignalValidator.validateEndpointSummary(endpointLog);

    Attributes attrs = endpointLog.getAttributes();
    assertEquals("GET", attrs.get(AttributeKey.stringKey("http.request.method")));
    assertEquals("/api/data", attrs.get(AttributeKey.stringKey("url.route")));
    assertEquals(
        "GET /api/data", attrs.get(AttributeKey.stringKey("aws.service_events.operation")));
    assertEquals(2L, attrs.get(AttributeKey.longKey("aws.service_events.request.count")));
    assertEquals(1L, attrs.get(AttributeKey.longKey("aws.service_events.request.faults")));

    // Verify error metrics with full spec validation
    Collection<MetricData> metrics = metricReader.collectAllMetrics();
    MetricData errorMetric = null;
    for (MetricData md : metrics) {
      if ("count".equals(md.getName())) {
        errorMetric = md;
      }
    }
    assertNotNull(errorMetric, "Should have count metric");
    ServiceEventsOtlpSignalValidator.validateErrorMetric(errorMetric);
  }

  @Test
  void testCollectorsFallbackToConsoleWhenNoOtlpEmitter() throws InterruptedException {
    // When otlpEmitter is null, collectors fall back to console output (no crash).
    DeploymentEventCollector collector =
        new DeploymentEventCollector(
            1000, "production", "my-service", "deploy-123", "", "", "", "", null);

    collector.start();
    Thread.sleep(2000);
    collector.stop();

    // No OTLP logs should be captured (console output only)
    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertTrue(logs.isEmpty(), "Should not emit OTLP logs when emitter is null");
  }

  @Test
  void testEndpointCollectorNoDataProducesNoLogs() throws InterruptedException {
    // Drain any existing data
    ServiceEventsDataStore.getAndSwapEndpointAggregations();

    EndpointCollector collector =
        new EndpointCollector(
            1000, "production", "my-service", "deploy-123", "", "", "", "", otlpEmitter, false);

    collector.start();
    Thread.sleep(2000);
    collector.stop();

    // With no data, endpoint collector should produce no logs
    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertTrue(logs.isEmpty(), "Should not emit OTLP logs when there is no endpoint data");
  }

  @Test
  void testEndpointCollectorSuppressSummaryWhenAppSignalsOn() throws InterruptedException {
    // Drain any existing data
    ServiceEventsDataStore.getAndSwapEndpointAggregations();

    // Record a successful + a faulted request so both EndpointSummary and
    // per-exception-type error metrics would normally emit.
    ServiceEventsDataStore.recordEndpointRequest(
        "GET /api/data", "/api/data", "GET", 200, 10_000_000L, null, null, "GET /api/data");
    ServiceEventsDataStore.recordEndpointRequest(
        "GET /api/data",
        "/api/data",
        "GET",
        500,
        50_000_000L,
        "NullPointerException",
        "com.example.DataService.fetch",
        "GET /api/data");

    EndpointCollector collector =
        new EndpointCollector(
            1000,
            "production",
            "my-service",
            "deploy-123",
            "",
            "",
            "abc123",
            "https://github.com/org/repo",
            otlpEmitter,
            /* suppressEndpointSummary= */ true);

    collector.start();
    Thread.sleep(2000);
    collector.stop();

    // No aws.service_events.endpoint_summary LogRecord should have been emitted.
    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    for (LogRecordData log : logs) {
      assertFalse(
          "aws.service_events.endpoint_summary"
              .equals(log.getAttributes().get(AttributeKey.stringKey("event.name"))),
          "EndpointSummary should be suppressed when App Signals is on");
    }

    // Error metrics still emit (ServiceEvents-specific per-exception-type breakdown
    // that App Signals does not carry).
    Collection<MetricData> metrics = metricReader.collectAllMetrics();
    MetricData errorMetric = null;
    for (MetricData md : metrics) {
      if ("count".equals(md.getName())) {
        errorMetric = md;
      }
    }
    assertNotNull(errorMetric, "Should still emit per-exception-type error metrics");
  }
}
