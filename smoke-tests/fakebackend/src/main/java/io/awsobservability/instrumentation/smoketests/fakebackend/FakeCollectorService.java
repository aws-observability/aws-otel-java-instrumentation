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

package io.awsobservability.instrumentation.smoketests.fakebackend;

import com.google.common.collect.ImmutableList;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

class FakeCollectorService extends TraceServiceGrpc.TraceServiceImplBase {

  private final BlockingQueue<ExportTraceServiceRequest> exportRequests =
      new LinkedBlockingDeque<>();

  List<ExportTraceServiceRequest> getRequests() {
    return ImmutableList.copyOf(exportRequests);
  }

  void clearRequests() {
    exportRequests.clear();
  }

  @Override
  public void export(
      ExportTraceServiceRequest request,
      StreamObserver<ExportTraceServiceResponse> responseObserver) {
    exportRequests.add(request);
    responseObserver.onNext(ExportTraceServiceResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }
}
