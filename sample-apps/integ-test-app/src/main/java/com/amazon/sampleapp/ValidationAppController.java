package com.amazon.sampleapp;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ValidationAppController {
  private static final Logger logger = LoggerFactory.getLogger(ValidationAppController.class);
  private final Tracer tracer;
  private final SdkTracerProvider tracerProvider;

  @Autowired
  public ValidationAppController(OpenTelemetry openTelemetry, SdkTracerProvider tracerProvider) {
    this.tracer = openTelemetry.getTracer(getClass().getName());
    this.tracerProvider = tracerProvider;
  }

  @GetMapping("/test")
  public String createTrace() {
    // Create a span for testing with various attributes
    Span parentSpan = tracer.spanBuilder("test_parent_span").startSpan();
    try (Scope scope = parentSpan.makeCurrent()) {
      // Set attributes and events (matching Python example)
      parentSpan.setAttribute("service.name", "validation-app");
      parentSpan.setAttribute("test.attribute", "test_value");
      parentSpan.addEvent(
          "test-event", Attributes.of(AttributeKey.stringKey("event.data"), "some data"));

      // Get the trace ID
      String traceId = parentSpan.getSpanContext().getTraceId();

      // Add a child span
      Span childSpan = tracer.spanBuilder("test_child_span").startSpan();
      try (Scope childScope = childSpan.makeCurrent()) {
        childSpan.setAttribute("child.attribute", "child_value");
        logger.info("Created spans with attributes and events");
      } finally {
        childSpan.end();
      }

      tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);

      return formatTraceId(traceId);
    } finally {
      parentSpan.end();
    }
  }

  private String formatTraceId(String traceId) {
    // Format trace ID to match X-Ray format
    return String.format(
        "1-%s-%s", traceId.substring(0, 8), traceId.substring(8));
  }
}
