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

package software.amazon.opentelemetry;

import static spark.Spark.awaitInitialization;
import static spark.Spark.get;
import static spark.Spark.ipAddress;
import static spark.Spark.port;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.appsignals.sampleapp.grpc.base.EchoReply;
import software.amazon.appsignals.sampleapp.grpc.base.EchoRequest;
import software.amazon.appsignals.sampleapp.grpc.base.EchoerGrpc;

public class Main {

  public static void main(String[] args) {
    String target = System.getenv("GRPC_CLIENT_TARGET");

    Logger logger = LoggerFactory.getLogger(Main.class);

    ManagedChannel channel =
        Grpc.newChannelBuilder(target, InsecureChannelCredentials.create()).build();

    EchoerGrpc.EchoerBlockingStub blockingStub = EchoerGrpc.newBlockingStub(channel);

    port(Integer.parseInt("8080"));
    ipAddress("0.0.0.0");
    get(
        "/success",
        (req, res) -> {
          EchoReply reply = null;
          try {
            reply =
                blockingStub.echoSuccess(EchoRequest.newBuilder().setMessage("success").build());
          } catch (StatusRuntimeException e) {
            logger.info(e.getStatus().getDescription());
            return "";
          }
          res.status(200);
          res.body(reply.getMessage());
          return res.body();
        });
    get(
        "/fault",
        (req, res) -> {
          try {
            EchoReply reply =
                blockingStub.echoFault(EchoRequest.newBuilder().setMessage("fault").build());

          } catch (StatusRuntimeException e) {
            if (e.getStatus() == Status.UNAVAILABLE) {
              res.status(500);
              res.body(e.getMessage());
            }
          }
          return res.body();
        });
    get(
        "/error",
        (req, res) -> {
          try {
            EchoReply reply =
                blockingStub.echoError(EchoRequest.newBuilder().setMessage("error").build());
          } catch (StatusRuntimeException e) {
            if (e.getStatus() == Status.INTERNAL) {
              res.status(400);
              res.body(e.getMessage());
            }
          }
          return res.body();
        });

    awaitInitialization();
    logger.info("Routes ready.");
  }
}
