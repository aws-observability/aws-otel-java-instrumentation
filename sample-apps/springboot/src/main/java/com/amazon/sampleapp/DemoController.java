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
