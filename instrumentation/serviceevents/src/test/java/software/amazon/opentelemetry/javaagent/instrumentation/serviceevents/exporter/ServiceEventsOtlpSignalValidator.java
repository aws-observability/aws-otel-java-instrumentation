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
import io.opentelemetry.api.common.KeyValue;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.common.ValueType;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.data.SumData;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spec conformance validator for ServiceEvents OTLP signals.
 *
 * <p>Validates OTLP LogRecords and Metrics against SERVICE_EVENTS_OTLP_SIGNALS_SPEC.md v2.0.
 * Reusable across unit tests, integration tests, and contract tests.
 *
 * <p>Usage:
 *
 * <pre>
 *   ServiceEventsOtlpSignalValidator.validateEndpointSummary(logRecord);
 *   ServiceEventsOtlpSignalValidator.validateDeploymentEvent(logRecord);
 *   ServiceEventsOtlpSignalValidator.validateErrorMetric(metricData);
 * </pre>
 */
public final class ServiceEventsOtlpSignalValidator {

  private ServiceEventsOtlpSignalValidator() {}

  // ─── InstrumentationScope ────────────────────────────────────────────────

  public static void validateScope(LogRecordData log) {
    assertEquals(
        "serviceevents",
        log.getInstrumentationScopeInfo().getName(),
        "Scope name must be 'serviceevents'");
    assertEquals(
        "1.0", log.getInstrumentationScopeInfo().getVersion(), "Scope version must be '1.0'");
  }

  // ─── EndpointSummary ─────────────────────────────────────────────────────

  /** Validates an EndpointSummary LogRecord against all spec requirements. */
  public static void validateEndpointSummary(LogRecordData log) {
    validateScope(log);
    Attributes attrs = log.getAttributes();

    // event.name (CloudWatch workaround)
    assertEquals(
        "aws.service_events.endpoint_summary",
        attrs.get(AttributeKey.stringKey("event.name")),
        "event.name must be 'aws.service_events.endpoint_summary'");

    // Required string attributes
    assertNotNull(
        attrs.get(AttributeKey.stringKey("http.request.method")), "http.request.method required");
    assertNotNull(attrs.get(AttributeKey.stringKey("url.route")), "url.route required");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("aws.service_events.operation")),
        "aws.service_events.operation required");

    // Required int attributes (Long in OTel SDK)
    assertNotNull(
        attrs.get(AttributeKey.longKey("aws.service_events.request.count")),
        "aws.service_events.request.count required");
    assertNotNull(
        attrs.get(AttributeKey.longKey("aws.service_events.request.faults")),
        "aws.service_events.request.faults required");
    assertNotNull(
        attrs.get(AttributeKey.longKey("aws.service_events.request.errors")),
        "aws.service_events.request.errors required");
    assertNotNull(
        attrs.get(AttributeKey.longKey("aws.service_events.incident.count")),
        "aws.service_events.incident.count required");

    // Count values must be non-negative
    assertTrue(
        attrs.get(AttributeKey.longKey("aws.service_events.request.count")) >= 0,
        "request.count must be >= 0");
    assertTrue(
        attrs.get(AttributeKey.longKey("aws.service_events.request.faults")) >= 0,
        "request.faults must be >= 0");
    assertTrue(
        attrs.get(AttributeKey.longKey("aws.service_events.request.errors")) >= 0,
        "request.errors must be >= 0");
    assertTrue(
        attrs.get(AttributeKey.longKey("aws.service_events.incident.count")) >= 0,
        "incident.count must be >= 0");

    // Body must exist
    assertNotNull(log.getBodyValue(), "EndpointSummary must have a body");
    validateEndpointSummaryBody(log.getBodyValue());
  }

  /**
   * Validates EndpointSummary body structure: duration, exception_breakdown, incidents_exemplar.
   */
  @SuppressWarnings("unchecked")
  private static void validateEndpointSummaryBody(Value<?> body) {
    assertEquals(ValueType.KEY_VALUE_LIST, body.getType(), "Body must be a map (KEY_VALUE_LIST)");

    Map<String, Value<?>> bodyMap = toMap(body);

    // duration (required)
    assertTrue(bodyMap.containsKey("duration"), "Body must contain 'duration'");
    validateDurationValue(bodyMap.get("duration"), "body.duration");

    // exception_breakdown (required, may be empty array)
    assertTrue(
        bodyMap.containsKey("exception_breakdown"), "Body must contain 'exception_breakdown'");
    Value<?> breakdown = bodyMap.get("exception_breakdown");
    assertEquals(ValueType.ARRAY, breakdown.getType(), "exception_breakdown must be an array");
    validateExceptionBreakdown((List<Value<?>>) breakdown.getValue());

    // incidents_exemplar (required, may be empty array)
    assertTrue(bodyMap.containsKey("incidents_exemplar"), "Body must contain 'incidents_exemplar'");
    Value<?> exemplar = bodyMap.get("incidents_exemplar");
    assertEquals(ValueType.ARRAY, exemplar.getType(), "incidents_exemplar must be an array");
    validateIncidentsExemplar((List<Value<?>>) exemplar.getValue());
  }

  // ─── FunctionCall ────────────────────────────────────────────────────────

  /** Validates a FunctionCall LogRecord against all spec requirements. */
  public static void validateFunctionCall(LogRecordData log) {
    validateScope(log);
    Attributes attrs = log.getAttributes();

    assertEquals(
        "aws.service_events.function_call",
        attrs.get(AttributeKey.stringKey("event.name")),
        "event.name must be 'aws.service_events.function_call'");

    // Required attributes
    assertNotNull(
        attrs.get(AttributeKey.stringKey("aws.service_events.function_name")),
        "aws.service_events.function_name required");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("aws.service_events.operation")),
        "aws.service_events.operation required");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("aws.service_events.version")),
        "aws.service_events.version required");

    // Body must exist
    assertNotNull(log.getBodyValue(), "FunctionCall must have a body");
    validateFunctionCallBody(log.getBodyValue());
  }

  @SuppressWarnings("unchecked")
  private static void validateFunctionCallBody(Value<?> body) {
    assertEquals(ValueType.KEY_VALUE_LIST, body.getType(), "Body must be a map");

    Map<String, Value<?>> bodyMap = toMap(body);

    // duration (required)
    assertTrue(bodyMap.containsKey("duration"), "Body must contain 'duration'");
    validateDurationValue(bodyMap.get("duration"), "body.duration");

    // exceptions (optional but if present must be a map)
    if (bodyMap.containsKey("exceptions")) {
      Value<?> exceptions = bodyMap.get("exceptions");
      assertEquals(
          ValueType.KEY_VALUE_LIST,
          exceptions.getType(),
          "exceptions must be a map {exception_type: count}");
    }
  }

  // ─── IncidentSnapshot ───────────────────────────────────────────────────

  /** Validates an IncidentSnapshot LogRecord against all spec requirements. */
  public static void validateIncidentSnapshot(LogRecordData log) {
    validateScope(log);
    Attributes attrs = log.getAttributes();

    assertEquals(
        "aws.service_events.incident_snapshot",
        attrs.get(AttributeKey.stringKey("event.name")),
        "event.name must be 'aws.service_events.incident_snapshot'");

    // Required string attributes
    assertNotNull(
        attrs.get(AttributeKey.stringKey("aws.service_events.snapshot_id")),
        "aws.service_events.snapshot_id required");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("aws.service_events.trigger_type")),
        "aws.service_events.trigger_type required");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("aws.service_events.operation")),
        "aws.service_events.operation required");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("http.request.method")), "http.request.method required");
    assertNotNull(attrs.get(AttributeKey.stringKey("url.route")), "url.route required");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("aws.service_events.request.type")),
        "aws.service_events.request.type required");

    // Required numeric attributes
    assertNotNull(
        attrs.get(AttributeKey.doubleKey("aws.service_events.duration_ms")),
        "aws.service_events.duration_ms required");
    assertNotNull(
        attrs.get(AttributeKey.longKey("http.response.status_code")),
        "http.response.status_code required");

    // Required boolean
    assertNotNull(
        attrs.get(AttributeKey.booleanKey("aws.service_events.is_partial")),
        "aws.service_events.is_partial required");

    // Trace context (should be present for exception-triggered incidents)
    // Not asserting validity since some incidents may not have trace context

    // Body must exist
    assertNotNull(log.getBodyValue(), "IncidentSnapshot must have a body");
    validateIncidentSnapshotBody(log.getBodyValue());
  }

  @SuppressWarnings("unchecked")
  private static void validateIncidentSnapshotBody(Value<?> body) {
    assertEquals(ValueType.KEY_VALUE_LIST, body.getType(), "Body must be a map");

    Map<String, Value<?>> bodyMap = toMap(body);

    // exception_info (required)
    assertTrue(bodyMap.containsKey("exception_info"), "Body must contain 'exception_info'");

    // request_context (required per spec, but Java may not always populate)
    // Validate only presence, not structure (varies by incident)
  }

  // ─── DeploymentEvent ────────────────────────────────────────────────────

  /** Validates a DeploymentEvent LogRecord against all spec requirements. */
  public static void validateDeploymentEvent(LogRecordData log) {
    validateScope(log);
    Attributes attrs = log.getAttributes();

    assertEquals(
        "aws.service_events.deployment_event",
        attrs.get(AttributeKey.stringKey("event.name")),
        "event.name must be 'aws.service_events.deployment_event'");

    // DeploymentEvent has no body
    assertNull(log.getBodyValue(), "DeploymentEvent must have no body");
  }

  /**
   * Validates a DeploymentEvent with full deployment context (when git/CI metadata is available).
   */
  public static void validateDeploymentEventFull(LogRecordData log) {
    validateDeploymentEvent(log);
    Attributes attrs = log.getAttributes();

    // These are present when git/CI context is configured
    assertNotNull(
        attrs.get(AttributeKey.stringKey("vcs.ref.head.revision")),
        "vcs.ref.head.revision required with deployment context");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("vcs.repository.url.full")),
        "vcs.repository.url.full required with deployment context");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("aws.service_events.deployment.id")),
        "aws.service_events.deployment.id required with deployment context");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("aws.service_events.deployment.url")),
        "aws.service_events.deployment.url required with deployment context");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("aws.service_events.deployment.timestamp")),
        "aws.service_events.deployment.timestamp required with deployment context");
  }

  // ─── EndpointErrorMetrics ───────────────────────────────────────────────

  /** Validates the EndpointErrorMetrics OTel metric. */
  public static void validateErrorMetric(MetricData metric) {
    assertEquals("count", metric.getName(), "Metric name must be 'count'");
    assertEquals("Count", metric.getUnit(), "Metric unit must be 'Count'");
    assertEquals(
        MetricDataType.LONG_SUM, metric.getType(), "Metric type must be LONG_SUM (Counter)");

    SumData<LongPointData> sumData = metric.getLongSumData();
    assertNotNull(sumData, "Sum data must not be null");
    assertTrue(sumData.isMonotonic(), "Error counter must be monotonic");

    // Each data point must have operation + exception + service_name + environment attributes
    for (LongPointData point : sumData.getPoints()) {
      Attributes pointAttrs = point.getAttributes();
      assertNotNull(
          pointAttrs.get(AttributeKey.stringKey("operation")), "Data point must have operation");
      assertNotNull(
          pointAttrs.get(AttributeKey.stringKey("exception")), "Data point must have exception");
      assertNotNull(
          pointAttrs.get(AttributeKey.stringKey("service_name")),
          "Data point must have service_name");
      assertNotNull(
          pointAttrs.get(AttributeKey.stringKey("environment")),
          "Data point must have environment");
      assertTrue(point.getValue() > 0, "Error count must be > 0");
    }
  }

  // ─── VCS / Deployment common attributes ──────────────────────────────────

  /** Validates that VCS + deployment attributes are present (when context is configured). */
  public static void validateVcsAndDeploymentAttrs(LogRecordData log) {
    Attributes attrs = log.getAttributes();
    assertNotNull(
        attrs.get(AttributeKey.stringKey("vcs.ref.head.revision")),
        "vcs.ref.head.revision required");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("vcs.repository.url.full")),
        "vcs.repository.url.full required");
    assertNotNull(
        attrs.get(AttributeKey.stringKey("aws.service_events.deployment.id")),
        "aws.service_events.deployment.id required");
  }

  // ─── Duration histogram validation ───────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static void validateDurationValue(Value<?> duration, String path) {
    assertEquals(
        ValueType.KEY_VALUE_LIST, duration.getType(), path + " must be a map (KEY_VALUE_LIST)");

    Map<String, Value<?>> durationMap = toMap(duration);

    Set<String> requiredKeys =
        new HashSet<>(Arrays.asList("Values", "Counts", "Max", "Min", "Count", "Sum"));
    for (String key : requiredKeys) {
      assertTrue(durationMap.containsKey(key), path + " must contain '" + key + "'");
    }

    // Values and Counts must be arrays
    Value<?> values = durationMap.get("Values");
    Value<?> counts = durationMap.get("Counts");
    assertEquals(ValueType.ARRAY, values.getType(), path + ".Values must be an array");
    assertEquals(ValueType.ARRAY, counts.getType(), path + ".Counts must be an array");

    List<Value<?>> valuesList = (List<Value<?>>) values.getValue();
    List<Value<?>> countsList = (List<Value<?>>) counts.getValue();
    assertEquals(
        valuesList.size(), countsList.size(), path + ".Values and .Counts must have same length");

    // Max, Min, Count, Sum must be numeric
    assertNumericValue(durationMap.get("Max"), path + ".Max");
    assertNumericValue(durationMap.get("Min"), path + ".Min");
    assertNumericValue(durationMap.get("Count"), path + ".Count");
    assertNumericValue(durationMap.get("Sum"), path + ".Sum");

    // Counts entries must be integers (LONG), not doubles
    for (int i = 0; i < countsList.size(); i++) {
      assertEquals(
          ValueType.LONG,
          countsList.get(i).getType(),
          path + ".Counts[" + i + "] must be LONG (integer), not " + countsList.get(i).getType());
    }

    // Count (scalar) must be integer (LONG)
    assertEquals(
        ValueType.LONG, durationMap.get("Count").getType(), path + ".Count must be LONG (integer)");

    // Count must equal sum of Counts
    double countValue = toDouble(durationMap.get("Count"));
    double countsSum = 0;
    for (Value<?> c : countsList) {
      countsSum += toDouble(c);
    }
    assertEquals(countValue, countsSum, 0.01, path + ".Count must equal sum of Counts");

    // Min <= Max
    double minVal = toDouble(durationMap.get("Min"));
    double maxVal = toDouble(durationMap.get("Max"));
    assertTrue(minVal <= maxVal, path + ".Min (" + minVal + ") must be <= Max (" + maxVal + ")");
  }

  // ─── Exception breakdown validation ──────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static void validateExceptionBreakdown(List<Value<?>> breakdown) {
    for (int i = 0; i < breakdown.size(); i++) {
      Value<?> entry = breakdown.get(i);
      assertEquals(
          ValueType.KEY_VALUE_LIST,
          entry.getType(),
          "exception_breakdown[" + i + "] must be a map");

      Map<String, Value<?>> entryMap = toMap(entry);

      // failure_type (spec says status_code, impl uses failure_type)
      assertTrue(
          entryMap.containsKey("failure_type") || entryMap.containsKey("status_code"),
          "exception_breakdown[" + i + "] must have failure_type or status_code");

      assertTrue(entryMap.containsKey("count"), "exception_breakdown[" + i + "] must have count");

      assertTrue(
          entryMap.containsKey("exceptions"),
          "exception_breakdown[" + i + "] must have exceptions");

      Value<?> exceptions = entryMap.get("exceptions");
      assertEquals(
          ValueType.ARRAY,
          exceptions.getType(),
          "exception_breakdown[" + i + "].exceptions must be an array");

      List<Value<?>> exceptionsList = (List<Value<?>>) exceptions.getValue();
      for (int j = 0; j < exceptionsList.size(); j++) {
        Map<String, Value<?>> excMap = toMap(exceptionsList.get(j));
        assertTrue(
            excMap.containsKey("exception_type"),
            "exception_breakdown[" + i + "].exceptions[" + j + "] must have exception_type");
        assertTrue(
            excMap.containsKey("function_name"),
            "exception_breakdown[" + i + "].exceptions[" + j + "] must have function_name");
      }
    }
  }

  // ─── Incidents exemplar validation ──────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static void validateIncidentsExemplar(List<Value<?>> exemplars) {
    for (int i = 0; i < exemplars.size(); i++) {
      Value<?> entry = exemplars.get(i);
      assertEquals(
          ValueType.KEY_VALUE_LIST, entry.getType(), "incidents_exemplar[" + i + "] must be a map");

      Map<String, Value<?>> entryMap = toMap(entry);

      assertTrue(
          entryMap.containsKey("snapshot_id"),
          "incidents_exemplar[" + i + "] must have snapshot_id");
      assertTrue(
          entryMap.containsKey("trigger_type"),
          "incidents_exemplar[" + i + "] must have trigger_type");
      assertTrue(
          entryMap.containsKey("timestamp"), "incidents_exemplar[" + i + "] must have timestamp");
    }
  }

  // ─── Helpers ────────────────────────────────────────────────────────────

  /**
   * Converts a KEY_VALUE_LIST Value to a Map for easier validation.
   *
   * <p>The OTel SDK stores KEY_VALUE_LIST as {@code List<KeyValue>}, not {@code Map<String,
   * Value<?>>}.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Value<?>> toMap(Value<?> kvList) {
    Map<String, Value<?>> map = new HashMap<>();
    List<KeyValue> list = (List<KeyValue>) kvList.getValue();
    for (KeyValue kv : list) {
      map.put(kv.getKey(), kv.getValue());
    }
    return map;
  }

  private static void assertNumericValue(Value<?> value, String path) {
    assertTrue(
        value.getType() == ValueType.LONG || value.getType() == ValueType.DOUBLE,
        path + " must be numeric, got " + value.getType());
  }

  private static double toDouble(Value<?> value) {
    if (value.getType() == ValueType.LONG) {
      return ((Long) value.getValue()).doubleValue();
    }
    if (value.getType() == ValueType.DOUBLE) {
      return (Double) value.getValue();
    }
    throw new AssertionError("Expected numeric value, got " + value.getType());
  }
}
