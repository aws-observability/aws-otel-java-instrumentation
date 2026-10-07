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

  /**
   * Unary gRPC RPC path the ADOT profiler's {@code OtlpGrpcProfilesExporter} POSTs to (matching
   * OTLP's {@code profiles.v1development} package). Registered as a raw service in {@link Main} so
   * both HTTP and gRPC profiles land in the same queue.
   */
  static final String GRPC_EXPORT_PATH =
      "/opentelemetry.proto.collector.profiles.v1development.ProfilesService/Export";

  protected final HttpService HTTP_INSTANCE = new HttpService();

  private final BlockingQueue<ExportProfilesServiceRequest> exportRequests =
      new LinkedBlockingDeque<>();

  List<ExportProfilesServiceRequest> getRequests() {
    return ImmutableList.copyOf(exportRequests);
  }

  void clearRequests() {
    exportRequests.clear();
  }

  /**
   * Decode one gRPC-framed {@code ExportProfilesServiceRequest} and store it. gRPC length-prefixed
   * framing is {@code [1-byte compressed flag][4-byte big-endian length][message]}; when the flag
   * is set the message is gzip-compressed (the exporter sets {@code grpc-encoding: gzip}). Shares
   * the same queue as the HTTP route so {@code getRequests()} returns profiles from both transports.
   */
  void consumeGrpcFramed(byte[] frame) throws Exception {
    if (frame == null || frame.length < 5) {
      return;
    }
    boolean compressed = (frame[0] & 0xFF) != 0;
    int length =
        ((frame[1] & 0xFF) << 24)
            | ((frame[2] & 0xFF) << 16)
            | ((frame[3] & 0xFF) << 8)
            | (frame[4] & 0xFF);
    int end = Math.min(frame.length, 5 + length);
    byte[] message = java.util.Arrays.copyOfRange(frame, 5, end);
    byte[] payload = MockCollectorHttpUtil.decodeIfCompressed(message, compressed ? "gzip" : null);
    exportRequests.add(ExportProfilesServiceRequest.parseFrom(payload));
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
