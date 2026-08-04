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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import software.amazon.distro.opentelemetry.extension.spanmetrics.SpanMetrics;

/**
 * Plain Java application wired through {@link AutoConfiguredOpenTelemetrySdk}. The span-metrics
 * extension jar is baked onto the classpath, so its {@code AutoConfigurationCustomizerProvider} SPI
 * fires during autoconfigure to wrap the sampler and register the span processor. After the SDK is
 * built we call {@link SpanMetrics#bind(OpenTelemetry)} so the processor can obtain a Meter (this
 * step is automatic only under the javaagent hook).
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /ping} - creates a SERVER span and returns 200.
 *   <li>{@code GET /db} - creates a SERVER span and issues a trivial in-memory H2 query wrapped in a
 *       CLIENT span carrying {@code db.*} attributes.
 * </ul>
 */
public final class AutoconfigureApp {

  private static final int PORT = 8080;

  private static Tracer tracer;

  private AutoconfigureApp() {}

  public static void main(String[] args) throws Exception {
    OpenTelemetrySdk sdk =
        AutoConfiguredOpenTelemetrySdk.builder().setResultAsGlobal().build().getOpenTelemetrySdk();
    // Manual autoconfigure setups must bind the built SDK so the span processor can obtain a Meter.
    SpanMetrics.bind(sdk);
    tracer = sdk.getTracer("aws-otel-span-metrics-e2e-autoconfigure-app");

    Connection keepAlive = openConnection();
    initSchema(keepAlive);

    HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
    server.createContext("/ping", AutoconfigureApp::handlePing);
    server.createContext("/db", AutoconfigureApp::handleDb);
    server.createContext("/error", AutoconfigureApp::handleError);
    server.setExecutor(null);
    server.start();

    System.out.println("SpanMetrics e2e application started on port " + PORT);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  server.stop(0);
                  sdk.close();
                  try {
                    keepAlive.close();
                  } catch (Exception ignored) {
                    // ignore
                  }
                }));
  }

  private static void handlePing(HttpExchange exchange) throws IOException {
    Span span =
        tracer
            .spanBuilder("GET /ping")
            .setSpanKind(SpanKind.SERVER)
            .setAttribute("http.request.method", "GET")
            .setAttribute("http.route", "/ping")
            .setAttribute("http.response.status_code", 200L)
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      respond(exchange, 200, "pong");
    } finally {
      span.end();
    }
  }

  private static void handleError(HttpExchange exchange) throws IOException {
    Span span =
        tracer
            .spanBuilder("GET /error")
            .setSpanKind(SpanKind.SERVER)
            .setAttribute("http.request.method", "GET")
            .setAttribute("http.route", "/error")
            .setAttribute("http.response.status_code", 500L)
            .setAttribute("error.type", "500")
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "synthetic error");
      respond(exchange, 500, "error");
    } finally {
      span.end();
    }
  }

  private static void handleDb(HttpExchange exchange) throws IOException {
    Span span = tracer.spanBuilder("GET /db").setSpanKind(SpanKind.SERVER).startSpan();
    try (Scope scope = span.makeCurrent()) {
      int count = queryCount();
      respond(exchange, 200, "count=" + count);
    } catch (Exception e) {
      respond(exchange, 500, "error: " + e.getMessage());
    } finally {
      span.end();
    }
  }

  private static int queryCount() throws Exception {
    Span dbSpan =
        tracer
            .spanBuilder("SELECT test_items")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("db.system.name", "h2")
            .setAttribute("db.operation.name", "SELECT")
            .setAttribute("db.collection.name", "test_items")
            .startSpan();
    try (Scope scope = dbSpan.makeCurrent();
        Connection connection = openConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM test_items")) {
      rs.next();
      return rs.getInt(1);
    } finally {
      dbSpan.end();
    }
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection("jdbc:h2:mem:spanmetrics;DB_CLOSE_DELAY=-1", "sa", "");
  }

  private static void initSchema(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS test_items (id INT PRIMARY KEY, name VARCHAR)");
      statement.execute("MERGE INTO test_items (id, name) VALUES (1, 'span-metrics')");
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
