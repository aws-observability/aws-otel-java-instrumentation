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
import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Mock collector service for OTLP HTTP profiles. Accepts {@code POST /v1development/profiles}
 * (protobuf, optional gzip) and stores requests for later retrieval.
 *
 * <p>Mirrors {@link MockCollectorLogsService}. The ADOT Java profiler can export the native OTLP
 * profiles signal ({@code ExportProfilesServiceRequest}) over either HTTP/protobuf (default) or
 * gRPC (when {@code OTEL_EXPORTER_OTLP_PROTOCOL=grpc}). This mock serves the HTTP route only, so
 * the Docker contract suite exercises the HTTP transport; the gRPC exporter is covered by the
 * non-Docker {@code OtlpGrpcProfilesExporterTest} (MockWebServer HTTP/2). Add a gRPC {@code
 * ServiceImplBase} here if a gRPC contract cell is ever needed.
 */
class MockCollectorProfilesService {

  protected final HttpService HTTP_INSTANCE = new HttpService();

  private final BlockingQueue<ExportProfilesServiceRequest> exportRequests =
      new LinkedBlockingDeque<>();

  List<ExportProfilesServiceRequest> getRequests() {
    return ImmutableList.copyOf(exportRequests);
  }

  void clearRequests() {
    exportRequests.clear();
  }

  class HttpService {
    @Post("/v1development/profiles")
    @RequestConverter(ExportProfilesServiceRequestConverter.class)
    public void consumeProfiles(ExportProfilesServiceRequest request) {
      exportRequests.add(request);
    }
  }

  static class ExportProfilesServiceRequestConverter implements RequestConverterFunction {

    @Override
    public @Nullable Object convertRequest(
        ServiceRequestContext ctx,
        AggregatedHttpRequest request,
        Class<?> expectedResultType,
        @Nullable ParameterizedType expectedParameterizedResultType)
        throws Exception {
      if (expectedResultType == ExportProfilesServiceRequest.class) {
        try (var content = request.content()) {
          byte[] payload =
              MockCollectorHttpUtil.decodeIfCompressed(
                  content.array(), request.headers().get("content-encoding"));
          return ExportProfilesServiceRequest.parseFrom(payload);
        }
      }
      return RequestConverterFunction.fallthrough();
    }
  }
}
