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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.logs.TestLogRecordData;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.logs.OtlpAwsLogRecordExporterBuilder;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.metrics.OtlpAwsMetricExporterBuilder;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.traces.OtlpAwsSpanExporterBuilder;

/** Verifies real AWS SDK signatures over the endpoint and payload the Java OTLP sender sends. */
class OtlpAwsExporterProviderWireTest {
  private static final AwsSessionCredentials CREDENTIALS =
      AwsSessionCredentials.create("test-access-key", "test-secret-key", "test-session-token");
  private HttpServer server;
  private final AtomicReference<Headers> receivedHeaders = new AtomicReference<>();
  private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
  private final AtomicReference<String> receivedPath = new AtomicReference<>();
  private CountDownLatch received;
  private DefaultCredentialsProvider credentialsProvider;
  private MockedStatic<DefaultCredentialsProvider> credentialsFactory;

  @BeforeEach
  void setUp() throws Exception {
    received = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          receivedHeaders.set(exchange.getRequestHeaders());
          receivedBody.set(exchange.getRequestBody().readAllBytes());
          receivedPath.set(exchange.getRequestURI().getRawPath());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
          received.countDown();
        });
    server.start();
    credentialsProvider = mock(DefaultCredentialsProvider.class);
    when(credentialsProvider.resolveCredentials()).thenReturn(CREDENTIALS);
    credentialsFactory = mockStatic(DefaultCredentialsProvider.class);
    credentialsFactory.when(DefaultCredentialsProvider::create).thenReturn(credentialsProvider);
  }

  @AfterEach
  void tearDown() {
    if (credentialsFactory != null) {
      credentialsFactory.close();
    }
    if (server != null) {
      server.stop(0);
    }
  }

  private String origin() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @ParameterizedTest
  @CsvSource({
    "traces,none,false", "metrics,none,false", "logs,none,false",
    "traces,none,true", "metrics,none,true", "logs,none,true",
    "traces,gzip,true", "metrics,gzip,true", "logs,gzip,true"
  })
  void spiExportersPreserveOtlpConfigurationAndSignTheBytesSent(
      String signal, String compression, boolean signalEndpoint) throws Exception {
    Map<String, String> properties = OtlpAwsExporterProviderTest.properties();
    properties.put("otel.exporter.otlp.endpoint", origin() + "/Root");
    properties.put("otel.exporter.otlp.compression", compression);
    properties.put("otel.exporter.otlp.headers", "x-test-label=hello%20world");
    properties.put("otel.exporter.otlp.sigv4.service", "osis");
    String path = "/Root/v1/" + signal;
    if (signalEndpoint) {
      path = "/Custom/" + signal.toUpperCase(java.util.Locale.ROOT);
      properties.put("otel.exporter.otlp." + signal + ".endpoint", origin() + path);
    }
    if (signal.equals("logs")) {
      properties.put(
          "otel.exporter.otlp.logs.headers",
          "x-test-label=hello%20world,x-aws-log-group=test-group,x-aws-log-stream=test-stream");
    }
    Object exporter = OtlpAwsExporterProviderTest.createExporter(signal, properties);
    try {
      assertThat(export(exporter).join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(receivedPath.get()).isEqualTo(path);
      Headers headers = receivedHeaders.get();
      assertThat(headers.getFirst("x-test-label")).isEqualTo("hello world");
      if (signal.equals("logs")) {
        assertThat(headers.getFirst("x-aws-log-group")).isEqualTo("test-group");
        assertThat(headers.getFirst("x-aws-log-stream")).isEqualTo("test-stream");
      }
      if (compression.equals("gzip")) {
        assertThat(headers.getFirst("Content-Encoding")).isEqualTo("gzip");
        try (GZIPInputStream input =
            new GZIPInputStream(new ByteArrayInputStream(receivedBody.get()))) {
          assertThat(input.readAllBytes()).isNotEmpty();
        }
      } else {
        assertThat(headers.getFirst("Content-Encoding")).isNull();
        assertThat(receivedBody.get()).isNotEmpty();
      }
      verifySignature(origin() + path, "us-east-1", "osis");
    } finally {
      OtlpAwsExporterProviderTest.shutdown(exporter);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"traces", "metrics", "logs"})
  void signingFailurePreservesExistingExporterBehavior(String signal) throws Exception {
    Map<String, String> properties = OtlpAwsExporterProviderTest.properties();
    properties.put("otel.exporter.otlp.endpoint", origin());
    when(credentialsProvider.resolveCredentials())
        .thenThrow(SdkClientException.builder().message("no credentials").build());
    Object exporter = OtlpAwsExporterProviderTest.createExporter(signal, properties);
    try {
      assertThat(export(exporter).join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(receivedHeaders.get().getFirst("Authorization")).isNull();
      assertThat(receivedHeaders.get().getFirst("X-Amz-Security-Token")).isNull();
    } finally {
      OtlpAwsExporterProviderTest.shutdown(exporter);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"traces", "metrics", "logs"})
  void javaBuildersOverrideRegionServiceAndCredentials(String signal) throws Exception {
    String endpoint = origin() + "/Custom/" + signal;
    StaticCredentialsProvider credentials = StaticCredentialsProvider.create(CREDENTIALS);
    Object exporter;
    switch (signal) {
      case "traces":
        exporter =
            OtlpAwsSpanExporterBuilder.create(OtlpHttpSpanExporter.builder(), endpoint)
                .setAwsRegion("eu-west-1")
                .setAwsService("osis")
                .setCredentialsProvider(credentials)
                .setCompression("gzip")
                .build();
        break;
      case "metrics":
        exporter =
            OtlpAwsMetricExporterBuilder.create(OtlpHttpMetricExporter.builder(), endpoint)
                .setAwsRegion("eu-west-1")
                .setAwsService("osis")
                .setCredentialsProvider(credentials)
                .setCompression("gzip")
                .build();
        break;
      default:
        exporter =
            OtlpAwsLogRecordExporterBuilder.create(OtlpHttpLogRecordExporter.builder(), endpoint)
                .setAwsRegion("eu-west-1")
                .setAwsService("osis")
                .setCredentialsProvider(credentials)
                .setCompression("gzip")
                .build();
    }
    try {
      assertThat(export(exporter).join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      verifySignature(endpoint, "eu-west-1", "osis");
      verifyNoInteractions(credentialsProvider);
    } finally {
      OtlpAwsExporterProviderTest.shutdown(exporter);
    }
  }

  private void verifySignature(String endpoint, String region, String service) {
    Headers headers = receivedHeaders.get();
    assertThat(headers.get("Authorization")).hasSize(1);
    assertThat(headers.getFirst("X-Amz-Security-Token")).isEqualTo("test-session-token");
    Instant signedAt =
        Instant.from(
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
                .parse(headers.getFirst("X-Amz-Date")));
    SignedRequest expected =
        AwsV4HttpSigner.create()
            .sign(
                request ->
                    request
                        .identity(CREDENTIALS)
                        .request(
                            SdkHttpFullRequest.builder()
                                .uri(URI.create(endpoint))
                                .method(SdkHttpMethod.POST)
                                .putHeader("Content-Type", headers.getFirst("Content-Type"))
                                .build())
                        .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                        .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, service)
                        .putProperty(
                            AwsV4HttpSigner.SIGNING_CLOCK, Clock.fixed(signedAt, ZoneOffset.UTC))
                        .payload(() -> new ByteArrayInputStream(receivedBody.get())));
    assertThat(headers.getFirst("Authorization"))
        .isEqualTo(expected.request().firstMatchingHeader("Authorization").orElseThrow());
  }

  private static CompletableResultCode export(Object exporter) {
    if (exporter instanceof SpanExporter) {
      return ((SpanExporter) exporter)
          .export(
              List.of(
                  TestSpanData.builder()
                      .setName("test-span")
                      .setKind(SpanKind.INTERNAL)
                      .setStatus(StatusData.ok())
                      .setStartEpochNanos(1)
                      .setEndEpochNanos(2)
                      .setHasEnded(true)
                      .build()));
    }
    if (exporter instanceof MetricExporter) {
      return ((MetricExporter) exporter)
          .export(
              List.of(
                  ImmutableMetricData.createDoubleGauge(
                      Resource.empty(),
                      InstrumentationScopeInfo.create("test-scope"),
                      "test.metric",
                      "",
                      "1",
                      ImmutableGaugeData.create(
                          List.of(
                              ImmutableDoublePointData.create(0, 1, Attributes.empty(), 42))))));
    }
    return ((LogRecordExporter) exporter)
        .export(List.of(TestLogRecordData.builder().setBody("test log").build()));
  }
}
