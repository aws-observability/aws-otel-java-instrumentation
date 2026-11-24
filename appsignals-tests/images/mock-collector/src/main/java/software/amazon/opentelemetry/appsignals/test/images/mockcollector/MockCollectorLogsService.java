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

package software.amazon.opentelemetry.appsignals.test.images.mockcollector;

import com.google.common.collect.ImmutableList;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Mock collector service for OTLP HTTP log records. Accepts POST /v1/logs (protobuf) and stores
 * requests for later retrieval.
 */
class MockCollectorLogsService {

  protected final HttpService HTTP_INSTANCE = new HttpService();

  private final BlockingQueue<ExportLogsServiceRequest> exportRequests =
      new LinkedBlockingDeque<>();

  List<ExportLogsServiceRequest> getRequests() {
    return ImmutableList.copyOf(exportRequests);
  }

  void clearRequests() {
    exportRequests.clear();
  }

  class HttpService {
    @Post("/v1/logs")
    @RequestConverter(ExportLogsServiceRequestConverter.class)
    public void consumeLogs(ExportLogsServiceRequest request) {
      exportRequests.add(request);
    }
  }

  static class ExportLogsServiceRequestConverter implements RequestConverterFunction {

    @Override
    public @Nullable Object convertRequest(
        ServiceRequestContext ctx,
        AggregatedHttpRequest request,
        Class<?> expectedResultType,
        @Nullable ParameterizedType expectedParameterizedResultType)
        throws Exception {
      if (expectedResultType == ExportLogsServiceRequest.class) {
        try (var content = request.content()) {
          byte[] payload =
              MockCollectorHttpUtil.decodeIfCompressed(
                  content.array(), request.headers().get("content-encoding"));
          return ExportLogsServiceRequest.parseFrom(payload);
        }
      }
      return RequestConverterFunction.fallthrough();
    }
  }
}
