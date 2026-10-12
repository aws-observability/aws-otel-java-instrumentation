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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.sdk.resources.Resource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.FrameInfo;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.OtlpProfileBuilder;

/**
 * Tests for {@link OtlpHttpProfilesExporter} using a {@link MockWebServer} (okhttp 5.x
 * mockwebserver3, matching the okhttp version resolved on the agent runtime/test classpath).
 */
class OtlpHttpProfilesExporterTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  private static MockResponse response(int code) {
    return new MockResponse.Builder().code(code).build();
  }

  private static byte[] sampleRequestBytes() {
    OtlpProfileBuilder builder =
        new OtlpProfileBuilder(1_700_000_000_000_000_000L, 60_000_000_000L, 10_000_000L);
    builder.addSample(
        Arrays.asList(new FrameInfo("com.example.Svc", "handle", "Svc.java", 12)),
        1_700_000_000_001_000_000L,
        "http-nio-8080-exec-1",
        "GET /orders",
        "0af7651916cd43dd8448eb211c80319c",
        "b7ad6b7169203331");
    return builder.toExportRequest(Resource.getDefault()).toByteArray();
  }

  /** A malformed endpoint makes export a no-op instead of throwing on every window. */
  @Test
  void export_invalidEndpoint_isNoOpAndDoesNotThrow() {
    for (String bad : new String[] {"not a url", "", "localhost:4318/v1development/profiles"}) {
      OtlpHttpProfilesExporter exporter = new OtlpHttpProfilesExporter(bad, "gzip", 1000);
      assertFalse(exporter.export(new byte[] {1, 2, 3}), bad);
      exporter.shutdown();
    }
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void export_postsProtobufToVerbatimUrl_uncompressed() throws Exception {
    server.enqueue(response(200));
    String url = server.url("/v1development/profiles").toString();

    OtlpHttpProfilesExporter exporter = new OtlpHttpProfilesExporter(url, "none", 10_000);
    byte[] payload = sampleRequestBytes();
    assertTrue(exporter.export(payload));

    RecordedRequest recorded = server.takeRequest();
    assertEquals("POST", recorded.getMethod());
    // The exporter posts to the URL verbatim — no path is appended.
    assertEquals("/v1development/profiles", recorded.getUrl().encodedPath());
    assertEquals("application/x-protobuf", recorded.getHeaders().get("Content-Type"));
    // Uncompressed: no gzip header, body parses directly.
    assertFalse("gzip".equalsIgnoreCase(recorded.getHeaders().get("Content-Encoding")));

    byte[] body = recorded.getBody().toByteArray();
    ExportProfilesServiceRequest parsed = ExportProfilesServiceRequest.parseFrom(body);
    assertEquals(1, parsed.getResourceProfilesCount());
    exporter.shutdown();
  }

  @Test
  void export_gzipCompressesBody_andParsesBack() throws Exception {
    server.enqueue(response(200));
    String url = server.url("/v1development/profiles").toString();

    OtlpHttpProfilesExporter exporter = new OtlpHttpProfilesExporter(url, "gzip", 10_000);
    assertTrue(exporter.isGzip());
    byte[] payload = sampleRequestBytes();
    assertTrue(exporter.export(payload));

    RecordedRequest recorded = server.takeRequest();
    assertEquals("POST", recorded.getMethod());
    assertEquals("application/x-protobuf", recorded.getHeaders().get("Content-Type"));
    assertEquals("gzip", recorded.getHeaders().get("Content-Encoding"));

    byte[] compressed = recorded.getBody().toByteArray();
    byte[] decompressed = gunzip(compressed);
    // gunzipped body parses back into the exact request bytes.
    ExportProfilesServiceRequest parsed = ExportProfilesServiceRequest.parseFrom(decompressed);
    assertEquals(ExportProfilesServiceRequest.parseFrom(payload), parsed);
    exporter.shutdown();
  }

  @Test
  void export_customPath_isUsedVerbatim() throws Exception {
    server.enqueue(response(200));
    String url = server.url("/some/other/route").toString();

    OtlpHttpProfilesExporter exporter = new OtlpHttpProfilesExporter(url, "none", 10_000);
    assertTrue(exporter.export(sampleRequestBytes()));

    RecordedRequest recorded = server.takeRequest();
    assertEquals("/some/other/route", recorded.getUrl().encodedPath());
    exporter.shutdown();
  }

  @Test
  void export_retriesOnceOnServerError_thenReturnsFalse() {
    // Two 5xx responses: initial attempt + one retry both fail.
    server.enqueue(response(503));
    server.enqueue(response(503));
    String url = server.url("/v1development/profiles").toString();

    OtlpHttpProfilesExporter exporter = new OtlpHttpProfilesExporter(url, "none", 10_000);
    assertFalse(exporter.export(sampleRequestBytes()));
    assertEquals(2, server.getRequestCount(), "should attempt exactly twice (initial + one retry)");
    exporter.shutdown();
  }

  @Test
  void export_retriesOn429_thenSucceeds() {
    // 429 Too Many Requests is transient/retryable: the retry (200) succeeds.
    server.enqueue(response(429));
    server.enqueue(response(200));
    String url = server.url("/v1development/profiles").toString();

    OtlpHttpProfilesExporter exporter = new OtlpHttpProfilesExporter(url, "none", 10_000);
    assertTrue(exporter.export(sampleRequestBytes()), "429 then 200 must succeed on the retry");
    assertEquals(2, server.getRequestCount(), "429 must trigger a retry (initial + one retry)");
    exporter.shutdown();
  }

  @Test
  void export_doesNotRetryOnDeterministic4xx() {
    // 400 Bad Request is deterministic — re-sending the identical body fails the same way, so the
    // exporter must NOT retry (unlike 408/429).
    server.enqueue(response(400));
    String url = server.url("/v1development/profiles").toString();

    OtlpHttpProfilesExporter exporter = new OtlpHttpProfilesExporter(url, "none", 10_000);
    assertFalse(exporter.export(sampleRequestBytes()));
    assertEquals(1, server.getRequestCount(), "deterministic 4xx must not retry");
    exporter.shutdown();
  }

  @Test
  void export_emptyPayload_returnsFalseWithoutRequest() {
    OtlpHttpProfilesExporter exporter =
        new OtlpHttpProfilesExporter(
            server.url("/v1development/profiles").toString(), "gzip", 10_000);
    assertFalse(exporter.export(new byte[0]));
    assertEquals(0, server.getRequestCount());
    exporter.shutdown();
  }

  @Test
  void defaultConstructor_uses64MiBPayloadLimit() {
    OtlpHttpProfilesExporter exporter =
        new OtlpHttpProfilesExporter(
            server.url("/v1development/profiles").toString(), "gzip", 10_000);
    assertEquals(64L * 1024L * 1024L, exporter.getMaxPayloadBytes());
    assertEquals(OtlpHttpProfilesExporter.DEFAULT_MAX_PAYLOAD_BYTES, exporter.getMaxPayloadBytes());
    exporter.shutdown();
  }

  @Test
  void export_overPayloadLimit_dropsWithoutRequest() {
    // A tiny 10-byte limit is smaller than any real request, so the payload-size guard drops the
    // window before any HTTP call — no 413 round-trip, no silent whole-window loss.
    OtlpHttpProfilesExporter exporter =
        new OtlpHttpProfilesExporter(
            server.url("/v1development/profiles").toString(), "gzip", 10_000, 10L);
    byte[] payload = sampleRequestBytes();
    assertTrue(payload.length > 10L, "sample payload must exceed the tiny test limit");
    assertFalse(exporter.export(payload), "over-limit payload must be dropped, not POSTed");
    assertEquals(0, server.getRequestCount(), "guard must drop before any HTTP request");
    exporter.shutdown();
  }

  @Test
  void export_underPayloadLimit_sendsNormally() {
    server.enqueue(response(200));
    // A generous limit above the request size does not interfere — the window is exported.
    OtlpHttpProfilesExporter exporter =
        new OtlpHttpProfilesExporter(
            server.url("/v1development/profiles").toString(), "none", 10_000, 10L * 1024 * 1024);
    assertTrue(exporter.export(sampleRequestBytes()));
    assertEquals(1, server.getRequestCount());
    exporter.shutdown();
  }

  @Test
  void export_zeroPayloadLimit_disablesGuard() {
    server.enqueue(response(200));
    // A limit <= 0 disables the guard: the payload is sent regardless of size.
    OtlpHttpProfilesExporter exporter =
        new OtlpHttpProfilesExporter(
            server.url("/v1development/profiles").toString(), "none", 10_000, 0L);
    assertEquals(0L, exporter.getMaxPayloadBytes());
    assertTrue(exporter.export(sampleRequestBytes()));
    assertEquals(1, server.getRequestCount());
    exporter.shutdown();
  }

  private static byte[] gunzip(byte[] input) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(input))) {
      byte[] buf = new byte[4096];
      int n;
      while ((n = gis.read(buf)) != -1) {
        out.write(buf, 0, n);
      }
    }
    return out.toByteArray();
  }
}
