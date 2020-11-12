package com.amazon.sampleapp;

import io.opentelemetry.trace.TracingContextUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import software.amazon.awssdk.services.s3.S3Client;

@Controller
public class DemoController {
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
    return "healthchecck";
  }

  // test http instrumentation (okhttp lib)
  @GetMapping("/outgoing-http-call")
  @ResponseBody
  public String httpCall() {
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
    s3.listBuckets();

    return getXrayTraceId();
  }

  // get x-ray trace id
  private String getXrayTraceId() {
    String traceId = TracingContextUtils.getCurrentSpan().getContext().getTraceIdAsHexString();
    String xrayTraceId = "1-" + traceId.substring(0, 8) + "-" + traceId.substring(8);

    return String.format("{\"traceId\": \"%s\"}", xrayTraceId);
  }
}
