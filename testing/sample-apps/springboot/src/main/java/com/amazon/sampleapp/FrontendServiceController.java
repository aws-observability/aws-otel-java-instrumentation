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

import io.opentelemetry.api.trace.Span;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;

@Controller
public class FrontendServiceController {
  private static final Logger logger = LoggerFactory.getLogger(FrontendServiceController.class);
  private final HttpClient httpClient;
  private final S3Client s3;
  private AtomicBoolean shouldSendLocalRootClientCall = new AtomicBoolean(false);

  @Bean
  private void runLocalRootClientCallRecurringService() { // run the service
    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    Runnable runnableTask =
        () -> {
          if (shouldSendLocalRootClientCall.get()) {
            shouldSendLocalRootClientCall.set(false);
            HttpRequest request =
                HttpRequest.newBuilder()
                    .uri(URI.create("http://local-root-client-call"))
                    .GET()
                    .build();
            try {
              HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
            }
          }
        };
    // Run with initial 0.1s delay, every 1 second
    executorService.scheduleAtFixedRate(runnableTask, 100, 1000, TimeUnit.MILLISECONDS);
  }

  @Autowired
  public FrontendServiceController(HttpClient httpClient, S3Client s3) {
    this.httpClient = httpClient;
    this.s3 = s3;
  }

  @GetMapping("/")
  @ResponseBody
  public String healthcheck() {
    return "healthcheck";
  }

  // test aws calls instrumentation
  @GetMapping("/aws-sdk-call")
  @ResponseBody
  public String awssdkCall() {
    GetBucketLocationRequest bucketLocationRequest =
        GetBucketLocationRequest.builder().bucket("e2e-test-bucket-name").build();
    try {
      s3.getBucketLocation(bucketLocationRequest);
    } catch (Exception e) {
      // e2e-test-bucket-name does not exist, so this is expected.
      logger.error("Could not retrieve http request:" + e.getLocalizedMessage());
    }
    return getXrayTraceId();
  }

  // test http instrumentation (java client)
  @GetMapping("/outgoing-http-call")
  @ResponseBody
  public String httpCall() {
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create("https://www.amazon.com")).GET().build();

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int statusCode = response.statusCode();

      logger.info("outgoing-http-call status code: " + statusCode);
    } catch (Exception e) {
      logger.error("Could not complete http request:" + e.getMessage());
    }

    return getXrayTraceId();
  }

  // RemoteService must also be deployed to use this API
  @GetMapping("/remote-service")
  @ResponseBody
  public String downstreamService(@RequestParam("ip") String ip) {
    // Ensure IP doesn't have extra slashes anywhere
    ip = ip.replace("/", "");
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://" + ip + ":8080/healthcheck"))
            .GET()
            .build();

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int statusCode = response.statusCode();

      logger.info("Remote service call status code: " + statusCode);
      return getXrayTraceId();
    } catch (Exception e) {
      logger.error("Could not complete http request to remote service:" + e.getMessage());
    }

    return getXrayTraceId();
  }

  // Test Local Root Client Span generation
  @GetMapping("/client-call")
  @ResponseBody
  public String asyncService() {
    logger.info("Client-call received");
    shouldSendLocalRootClientCall.set(true);
    // This API is used to trigger the http://local-root-client-call call on running on the executor
    // recurring service, which will generate a local root client span. The E2E testing will attempt
    // to validate the span
    // generated by the /local-root-client-call, not this /client-call API call. Therefore, the
    // traceId of this API call is not needed and we return an invalid traceId to indicate that the
    // call was received but to not use this
    // traceId.
    return "{\"traceId\": \"1-00000000-000000000000000000000000\"}";
  }

  // get x-ray trace id
  private String getXrayTraceId() {
    String traceId = Span.current().getSpanContext().getTraceId();
    String xrayTraceId = "1-" + traceId.substring(0, 8) + "-" + traceId.substring(8);

    return String.format("{\"traceId\": \"%s\"}", xrayTraceId);
  }
}
