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

package software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.AlwaysRecordSampler;
import software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.SpanMetrics;
import software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.SpanMetricsProcessor;

/**
 * Plain Java application that builds an {@link OpenTelemetrySdk} entirely by hand, wiring in the
 * span-metrics extension directly:
 *
 * <ul>
 *   <li>the sampler is wrapped with {@link AlwaysRecordSampler} so 100% of spans are recorded while
 *       trace export still honors the configured sampling ratio;
 *   <li>a {@link SpanMetricsProcessor} is registered to derive metrics from every recorded span;
 *   <li>a {@link BatchSpanProcessor} exports sampled spans over OTLP/gRPC; and
 *   <li>a {@link SdkMeterProvider} exports the derived metrics over OTLP/gRPC.
 * </ul>
 *
 * <p>After the SDK is built we call {@link SpanMetrics#bind(io.opentelemetry.api.OpenTelemetry)} so
 * the span processor can obtain a Meter.
 *
 * <p>Endpoints: {@code GET /ping} (returns 200) and {@code GET /db} (trivial in-memory H2 query
 * wrapped in a CLIENT span with {@code db.*} attributes).
 */
public final class ManualApp {

  private static final int PORT = 8080;

  private static Tracer tracer;

  private ManualApp() {}

  public static void main(String[] args) throws Exception {
    String endpoint = otlpEndpoint();

    OtlpGrpcSpanExporter spanExporter =
        OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build();
    OtlpGrpcMetricExporter metricExporter =
        OtlpGrpcMetricExporter.builder().setEndpoint(endpoint).build();

    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .setSampler(
                AlwaysRecordSampler.create(
                    Sampler.parentBased(Sampler.traceIdRatioBased(0.05))))
            .addSpanProcessor(new SpanMetricsProcessor())
            .addSpanProcessor(
                BatchSpanProcessor.builder(spanExporter)
                    .setScheduleDelay(Duration.ofMillis(0))
                    .build())
            .build();

    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder()
            .registerMetricReader(
                PeriodicMetricReader.builder(metricExporter)
                    .setInterval(Duration.ofMillis(1000))
                    .build())
            .build();

    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .build();

    // Supply the built SDK so SpanMetricsProcessor can obtain a Meter.
    SpanMetrics.bind(sdk);
    tracer = sdk.getTracer("cloudwatch-plugin-otel-e2e-manual-app");

    Connection keepAlive = openConnection();
    initSchema(keepAlive);

    HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
    server.createContext("/ping", ManualApp::handlePing);
    server.createContext("/db", ManualApp::handleDb);
    server.createContext("/error", ManualApp::handleError);
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

  private static String otlpEndpoint() {
    String endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
    if (endpoint == null || endpoint.isEmpty()) {
      endpoint = "http://localhost:4317";
    }
    return endpoint;
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
