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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.module.SimpleSerializers;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import io.netty.buffer.ByteBufOutputStream;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.io.IOException;
import java.io.OutputStream;
import org.curioswitch.common.protobuf.json.MessageMarshaller;

public class Main {

  private static final JsonMapper OBJECT_MAPPER;

  static {
    var marshaller =
        MessageMarshaller.builder()
            .register(ExportTraceServiceRequest.getDefaultInstance())
            .register(ExportMetricsServiceRequest.getDefaultInstance())
            .register(ExportLogsServiceRequest.getDefaultInstance())
            .build();

    var mapper = JsonMapper.builder();
    var module = new SimpleModule();
    var serializers = new SimpleSerializers();
    serializers.addSerializer(
        new StdSerializer<>(ExportTraceServiceRequest.class) {
          @Override
          public void serialize(
              ExportTraceServiceRequest value, JsonGenerator gen, SerializerProvider provider)
              throws IOException {
            marshaller.writeValue(value, gen);
          }
        });
    serializers.addSerializer(
        new StdSerializer<>(ExportMetricsServiceRequest.class) {
          @Override
          public void serialize(
              ExportMetricsServiceRequest value, JsonGenerator gen, SerializerProvider provider)
              throws IOException {
            marshaller.writeValue(value, gen);
          }
        });
    serializers.addSerializer(
        new StdSerializer<>(ExportLogsServiceRequest.class) {
          @Override
          public void serialize(
              ExportLogsServiceRequest value, JsonGenerator gen, SerializerProvider provider)
              throws IOException {
            marshaller.writeValue(value, gen);
          }
        });
    module.setSerializers(serializers);
    mapper.addModule(module);
    OBJECT_MAPPER = mapper.build();
  }

  public static void main(String[] args) {
    var traceCollector = new MockCollectorTraceService();
    var metricsCollector = new MockCollectorMetricsService();
    var server =
        Server.builder()
            .http(4317)
            .service(
                GrpcService.builder()
                    .addService(traceCollector)
                    .addService(metricsCollector)
                    .build())
            .service(
                "/clear",
                (ctx, req) -> {
                  traceCollector.clearRequests();
                  metricsCollector.clearRequests();
                  return HttpResponse.of(HttpStatus.OK);
                })
            .service(
                "/get-traces",
                (ctx, req) -> {
                  var requests = traceCollector.getRequests();
                  var buf = new ByteBufOutputStream(ctx.alloc().buffer());
                  OBJECT_MAPPER.writeValue((OutputStream) buf, requests);
                  return HttpResponse.of(
                      HttpStatus.OK, MediaType.JSON, HttpData.wrap(buf.buffer()));
                })
            .service(
                "/get-metrics",
                (ctx, req) -> {
                  var requests = metricsCollector.getRequests();
                  var buf = new ByteBufOutputStream(ctx.alloc().buffer());
                  OBJECT_MAPPER.writeValue((OutputStream) buf, requests);
                  return HttpResponse.of(
                      HttpStatus.OK, MediaType.JSON, HttpData.wrap(buf.buffer()));
                })
            .service("/health", HealthCheckService.of())
            .annotatedService(metricsCollector.HTTP_INSTANCE)
            .build();

    server.start().join();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop().join()));
  }
}
