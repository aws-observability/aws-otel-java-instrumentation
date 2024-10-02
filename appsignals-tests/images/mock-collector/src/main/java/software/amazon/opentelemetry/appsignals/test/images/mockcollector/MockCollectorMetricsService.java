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
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

class MockCollectorMetricsService extends MetricsServiceGrpc.MetricsServiceImplBase {

  protected final HttpService HTTP_INSTANCE = new HttpService();

  private final BlockingQueue<ExportMetricsServiceRequest> exportRequests =
      new LinkedBlockingDeque<>();

  List<ExportMetricsServiceRequest> getRequests() {
    return ImmutableList.copyOf(exportRequests);
  }

  void clearRequests() {
    exportRequests.clear();
  }

  @Override
  public void export(
      ExportMetricsServiceRequest request,
      StreamObserver<ExportMetricsServiceResponse> responseObserver) {
    exportRequests.add(request);
    responseObserver.onNext(ExportMetricsServiceResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  class HttpService {
    @Post("/v1/metrics")
    @RequestConverter(ExportMetricsServiceRequestConverter.class)
    public void consumeMetrics(ExportMetricsServiceRequest request) {
      exportRequests.add(request);
    }
  }

  static class ExportMetricsServiceRequestConverter implements RequestConverterFunction {

    @Override
    public @Nullable Object convertRequest(
        ServiceRequestContext ctx,
        AggregatedHttpRequest request,
        Class<?> expectedResultType,
        @Nullable ParameterizedType expectedParameterizedResultType)
        throws Exception {
      if (expectedResultType == ExportMetricsServiceRequest.class) {
        try (var content = request.content()) {
          return ExportMetricsServiceRequest.parseFrom(content.array());
        }
      }
      return RequestConverterFunction.fallthrough();
    }
  }
}
