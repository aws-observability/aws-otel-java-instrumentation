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
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Minimal HTTP application with no OpenTelemetry dependencies. The OpenTelemetry javaagent (plus the
 * span-metrics extension) is attached at container runtime by the test harness; the agent
 * auto-instruments both the built-in HTTP server and the JDBC calls, so no manual tracing code is
 * required here.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /ping} - returns 200.
 *   <li>{@code GET /db} - runs a trivial in-memory H2 query so a DB CLIENT span is produced.
 * </ul>
 */
public final class JavaagentApp {

  private static final int PORT = 8080;

  private JavaagentApp() {}

  public static void main(String[] args) throws Exception {
    // Shared in-memory H2 database, kept alive for the process lifetime.
    Connection keepAlive = openConnection();
    initSchema(keepAlive);

    HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
    server.createContext("/ping", JavaagentApp::handlePing);
    server.createContext("/db", JavaagentApp::handleDb);
    server.setExecutor(null);
    server.start();

    System.out.println("SpanMetrics e2e application started on port " + PORT);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  server.stop(0);
                  try {
                    keepAlive.close();
                  } catch (Exception ignored) {
                    // ignore
                  }
                }));
  }

  private static void handlePing(HttpExchange exchange) throws IOException {
    respond(exchange, 200, "pong");
  }

  private static void handleDb(HttpExchange exchange) throws IOException {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM test_items")) {
      rs.next();
      respond(exchange, 200, "count=" + rs.getInt(1));
    } catch (Exception e) {
      respond(exchange, 500, "error: " + e.getMessage());
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

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
