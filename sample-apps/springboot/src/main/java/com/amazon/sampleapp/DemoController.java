package com.amazon.sampleapp;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.io.UncheckedIOException;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.s3.S3Client;

@Controller
public class DemoController {
  private static final Logger logger = LoggerFactory.getLogger(DemoController.class);
  private static final boolean shouldSampleAppLog =
      System.getenv().getOrDefault("SAMPLE_APP_LOG_LEVEL", "INFO").equals("INFO");

  private final Call.Factory httpClient;
  private final S3Client s3;

  @Autowired
  public DemoController(Call.Factory httpClient, S3Client s3) {
    this.httpClient = httpClient;
    this.s3 = s3;
  }

  // healthcheck
  @GetMapping("/")
  @ResponseBody
  public String healthCheck() {
    return "healthcheck";
  }

  // test http instrumentation (okhttp lib)
  @GetMapping("/outgoing-http-call")
  @ResponseBody
  public String httpCall() throws IOException {
    if (shouldSampleAppLog) {
      logger.info("Executing outgoing-http-call");
    }

    try (Response response =
        httpClient.newCall(new Request.Builder().url("https://aws.amazon.com").build()).execute()) {
    } catch (IOException e) {
      throw new UncheckedIOException("Could not fetch endpoint", e);
    }

    return getXrayTraceId();
  }

  // test aws calls instrumentation
  @GetMapping("/aws-sdk-call")
  @ResponseBody
  public String awssdkCall() {
    if (shouldSampleAppLog) {
      logger.info("Executing aws-sdk-call");
    }

    s3.listBuckets();

    return getXrayTraceId();
  }

  @GetMapping(value = "/getSampled")
  @ResponseBody
  public String getSampled(
      @RequestHeader("user") String userAttribute,
      @RequestHeader("service_name") String name,
      @RequestHeader("required") String required) {
    if (shouldSampleAppLog) {
      logger.info("Executing aws-sdk-all");
    }
    Attributes attributes =
        Attributes.of(
            AttributeKey.stringKey("http.method"), "GET",
            AttributeKey.stringKey("http.url"), "http://localhost:8080/getSampled",
            AttributeKey.stringKey("user"), userAttribute,
            AttributeKey.stringKey("http.route"), "/getSampled",
            AttributeKey.stringKey("required"), required,
            AttributeKey.stringKey("http.target"), "/getSampled");
    Tracer tracer = DemoApplication.openTelemetry.getTracer(name);
    Span span =
        tracer
            .spanBuilder(name)
            .setSpanKind(SpanKind.SERVER)
            .setAllAttributes(attributes)
            .startSpan();
    span.setAttribute("http.status_code", 200);
    span.setAttribute("http.client_ip", "127.0.0.1");

    Boolean isSampled = span.getSpanContext().isSampled();
    span.end();
    return String.valueOf(isSampled);
  }

  @PostMapping("/getSampled")
  @ResponseBody
  public String postSampled(
      @RequestHeader("user") String userAttribute,
      @RequestHeader("service_name") String name,
      @RequestHeader("required") String required) {
    if (shouldSampleAppLog) {
      logger.info("Executing aws-sdk-all");
    }
    Attributes attributes =
        Attributes.of(
            AttributeKey.stringKey("http.method"), "POST",
            AttributeKey.stringKey("http.url"), "http://localhost:8080/getSampled",
            AttributeKey.stringKey("user"), userAttribute,
            AttributeKey.stringKey("http.route"), "/getSampled",
            AttributeKey.stringKey("required"), required,
            AttributeKey.stringKey("http.target"), "/getSampled");
    Tracer tracer = DemoApplication.openTelemetry.getTracer("/postSampled");
    Span span =
        tracer
            .spanBuilder("/postSampled")
            .setSpanKind(SpanKind.SERVER)
            .setAllAttributes(attributes)
            .startSpan();
    span.setAttribute("http.status_code", 200);
    span.setAttribute("http.client_ip", "127.0.0.1");

    Boolean isSampled = span.getSpanContext().isSampled();
    span.end();
    return String.valueOf(isSampled);
  }

  @GetMapping("/importantEndpoint")
  @ResponseBody
  public String importantEndpoint() {
    if (shouldSampleAppLog) {
      logger.info("Executing aws-sdk-all");
    }
    Attributes attributes =
        Attributes.of(
            AttributeKey.stringKey("http.method"), "GET",
            AttributeKey.stringKey("http.url"), "http://localhost:8080/importantEndpoint",
            AttributeKey.stringKey("http.route"), "/importantEndpoint",
            AttributeKey.stringKey("http.client_ip"), "127.0.0.1",
            AttributeKey.stringKey("http.target"), "/importantEndpoint");
    Tracer tracer = DemoApplication.openTelemetry.getTracer("/importantEndpoint");
    Span span =
        tracer
            .spanBuilder("/importantEndpoint")
            .setSpanKind(SpanKind.SERVER)
            .setAllAttributes(attributes)
            .startSpan();
    span.setAttribute("http.status_code", 200);
    Boolean isSampled = span.getSpanContext().isSampled();
    span.end();
    return String.valueOf(isSampled);
  }

  // get x-ray trace id
  private String getXrayTraceId() {
    String traceId = Span.current().getSpanContext().getTraceId();
    String xrayTraceId = "1-" + traceId.substring(0, 8) + "-" + traceId.substring(8);
    return String.format("{\"traceId\": \"%s\"}", xrayTraceId);
  }
}
