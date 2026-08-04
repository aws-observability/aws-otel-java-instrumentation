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

package software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.app;

import io.grpc.stub.StreamObserver;
import software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.app.grpc.EchoReply;
import software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.app.grpc.EchoRequest;
import software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.app.grpc.EchoerGrpc;

/** Trivial unary echo service implementation. */
public final class EchoerImpl extends EchoerGrpc.EchoerImplBase {

  @Override
  public void echo(EchoRequest request, StreamObserver<EchoReply> responseObserver) {
    EchoReply reply = EchoReply.newBuilder().setMessage(request.getMessage()).build();
    responseObserver.onNext(reply);
    responseObserver.onCompleted();
  }
}
