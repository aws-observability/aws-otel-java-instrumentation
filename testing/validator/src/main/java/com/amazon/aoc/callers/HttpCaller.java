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

package com.amazon.aoc.callers;

import com.amazon.aoc.enums.GenericConstants;
import com.amazon.aoc.exception.BaseException;
import com.amazon.aoc.exception.ExceptionCode;
import com.amazon.aoc.helpers.RetryHelper;
import com.amazon.aoc.models.SampleAppResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.log4j.Log4j2;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Log4j2
public class HttpCaller implements ICaller {
  private String url;
  private String path;
  private String requestBody;

  /**
   * construct httpCaller.
   *
   * @param endpoint the endpoint to call, for example "http://127.0.0.1:8080"
   * @param path the path to call, for example "/test"
   */
  public HttpCaller(String endpoint, String path) {
    this.path = path;
    this.url = endpoint + path;
    log.info("validator is trying to hit this {} endpoint", this.url);
  }

  /**
   * construct httpCaller.
   *
   * @param endpoint the endpoint to call, for example "http://127.0.0.1:8080"
   * @param path the path to call, for example "/test"
   * @param requestBody the request body, for example "key=value"
   */
  public HttpCaller(String endpoint, String path, String requestBody) {
    this.path = path;
    this.url = endpoint + path + "?" + requestBody + "/";
    log.info("validator is trying to hit this {} endpoint", this.url);
  }

  @Override
  public SampleAppResponse callSampleApp() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request = new Request.Builder().url(url).build();

    AtomicReference<SampleAppResponse> sampleAppResponseAtomicReference = new AtomicReference<>();
    RetryHelper.retry(
        40,
        () -> {
          try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            log.info("response from sample app {}", responseBody);
            if (!response.isSuccessful()) {
              throw new BaseException(ExceptionCode.DATA_EMITTER_UNAVAILABLE);
            }
            SampleAppResponse sampleAppResponse = null;
            try {
              sampleAppResponse =
                  new ObjectMapper().readValue(responseBody, SampleAppResponse.class);
            } catch (JsonProcessingException ex) {
              // try to get the trace id from header
              // this is a specific logic for xray sdk, which injects trace id in header
              log.info("getting trace id from header");
              //  X-Amzn-Trace-Id: Root=1-5f84a611-f2f5df6827016222af9d8b60
              String traceId =
                  response.header(GenericConstants.HTTP_HEADER_TRACE_ID.getVal()).substring(5);
              sampleAppResponse = new SampleAppResponse();
              sampleAppResponse.setTraceId(traceId);
            }
            sampleAppResponseAtomicReference.set(sampleAppResponse);
          }
        });

    return sampleAppResponseAtomicReference.get();
  }

  @Override
  public String getCallingPath() {
    return path;
  }
}
