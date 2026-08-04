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

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.app.grpc.EchoRequest;
import software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.app.grpc.EchoerGrpc;

/**
 * Minimal HTTP application with no OpenTelemetry dependencies. The OpenTelemetry javaagent (plus the
 * span-metrics extension) is attached at container runtime by the test harness; the agent
 * auto-instruments the embedded Jetty servlet container, the JDBC calls, the gRPC round trip and the
 * Kafka client, so no manual tracing code is required here.
 *
 * <p>HTTP is served by embedded Jetty (jakarta.servlet based) rather than {@code
 * com.sun.net.httpserver.HttpServer}, because the javaagent instruments Jetty/servlets out of the box
 * and thus produces SERVER spans named by the servlet route (e.g. {@code "GET /ping"}) carrying {@code
 * http.request.method}, {@code http.route} and {@code http.response.status_code}. Each endpoint is
 * registered under a distinct servlet mapping so {@code http.route} is per-path (not a catch-all).
 *
 * <p>Endpoints (port 8080):
 *
 * <ul>
 *   <li>{@code GET /ping} - returns 200 "pong".
 *   <li>{@code GET /db} - runs a trivial in-memory H2 query so a DB CLIENT span is produced.
 *   <li>{@code GET /error} - returns HTTP 500.
 *   <li>{@code GET /grpc} - in-process gRPC round trip so rpc.* CLIENT/SERVER spans are produced.
 *   <li>{@code GET /kafka} - produce + consume one record so messaging.* spans are produced.
 * </ul>
 */
public final class JavaagentApp {

  private static final int PORT = 8080;
  private static final int GRPC_PORT = 50051;
  private static final String KAFKA_TOPIC = "span-metrics-topic";

  private static volatile EchoerGrpc.EchoerBlockingStub grpcStub;

  private JavaagentApp() {}

  public static void main(String[] args) throws Exception {
    // Shared in-memory H2 database, kept alive for the process lifetime.
    Connection keepAlive = openConnection();
    initSchema(keepAlive);

    // Start the in-process gRPC server (localhost:50051) once at startup.
    Server grpcServer =
        Grpc.newServerBuilderForPort(GRPC_PORT, InsecureServerCredentials.create())
            .addService(new EchoerImpl())
            .build()
            .start();
    ManagedChannel grpcChannel =
        Grpc.newChannelBuilder("localhost:" + GRPC_PORT, InsecureChannelCredentials.create())
            .build();
    grpcStub = EchoerGrpc.newBlockingStub(grpcChannel);

    // Embedded Jetty servlet container. Distinct servlet mappings per path so the agent derives a
    // per-path http.route (e.g. "/ping"), not a single catch-all "/*".
    org.eclipse.jetty.server.Server server = new org.eclipse.jetty.server.Server(PORT);
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
    context.setContextPath("/");
    context.addServlet(new ServletHolder(new PingServlet()), "/ping");
    context.addServlet(new ServletHolder(new DbServlet()), "/db");
    context.addServlet(new ServletHolder(new ErrorServlet()), "/error");
    context.addServlet(new ServletHolder(new GrpcServlet()), "/grpc");
    context.addServlet(new ServletHolder(new KafkaServlet()), "/kafka");
    server.setHandler(context);
    server.start();

    System.out.println("SpanMetrics e2e application started on port " + PORT);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    server.stop();
                  } catch (Exception ignored) {
                    // ignore
                  }
                  grpcServer.shutdownNow();
                  grpcChannel.shutdownNow();
                  try {
                    keepAlive.close();
                  } catch (Exception ignored) {
                    // ignore
                  }
                }));

    server.join();
  }

  /** {@code GET /ping} -> 200 "pong". */
  static final class PingServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      respond(resp, 200, "pong");
    }
  }

  /** {@code GET /db} -> H2 JDBC query producing a DB CLIENT span. */
  static final class DbServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      try (Connection connection = openConnection();
          Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM test_items")) {
        rs.next();
        respond(resp, 200, "count=" + rs.getInt(1));
      } catch (Exception e) {
        respond(resp, 500, "error: " + e.getMessage());
      }
    }
  }

  /** {@code GET /error} -> HTTP 500. */
  static final class ErrorServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      respond(resp, 500, "error");
    }
  }

  /** {@code GET /grpc} -> in-process gRPC round trip producing rpc.* spans. */
  static final class GrpcServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      try {
        // One unary client call -> agent emits grpc CLIENT + SERVER spans
        // (rpc.system=grpc, rpc.service=echo.Echoer, rpc.method=Echo).
        grpcStub.echo(EchoRequest.newBuilder().setMessage("grpc").build());
        respond(resp, 200, "grpc-ok");
      } catch (Exception e) {
        respond(resp, 500, "error: " + e.getMessage());
      }
    }
  }

  /** {@code GET /kafka} -> produce + consume one record producing messaging.* spans. */
  static final class KafkaServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      String bootstrapServers =
          System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
      try {
        ensureTopic(bootstrapServers);

        // Produce one record -> agent emits a PRODUCER span.
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        producerProps.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.setProperty(
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.setProperty(
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.setProperty(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
          producer.send(new ProducerRecord<>(KAFKA_TOPIC, "span-metrics"));
          producer.flush();
        }

        // Consume it -> agent emits a CONSUMER span. Short poll so the HTTP call returns promptly.
        Properties consumerProps = new Properties();
        consumerProps.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.setProperty(
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.setProperty(
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
          consumer.subscribe(List.of(KAFKA_TOPIC));
          ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(5000));
          // touch the records so the consumer-side instrumentation runs.
          records.forEach(r -> {});
        }

        respond(resp, 200, "kafka-ok");
      } catch (Exception e) {
        respond(resp, 500, "error: " + e.getMessage());
      }
    }
  }

  /** Create the topic explicitly (broker auto-create may be disabled). Ignore if it exists. */
  private static void ensureTopic(String bootstrapServers) {
    Properties adminProps = new Properties();
    adminProps.setProperty("bootstrap.servers", bootstrapServers);
    try (Admin admin = Admin.create(adminProps)) {
      admin
          .createTopics(Collections.singletonList(new NewTopic(KAFKA_TOPIC, 1, (short) 1)))
          .all()
          .get();
    } catch (Exception ignored) {
      // Topic likely already exists.
    }
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        "jdbc:h2:mem:spanmetrics;DB_CLOSE_DELAY=-1", "sa", "");
  }

  private static void initSchema(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS test_items (id INT PRIMARY KEY, name VARCHAR)");
      statement.execute("MERGE INTO test_items (id, name) VALUES (1, 'span-metrics')");
    }
  }

  private static void respond(HttpServletResponse resp, int status, String body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("text/plain; charset=utf-8");
    resp.getWriter().write(body);
  }
}
