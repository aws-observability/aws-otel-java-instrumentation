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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Headers;
import okhttp3.Protocol;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OtlpGrpcProfilesExporter} — the hand-rolled OTLP/gRPC-over-OkHttp exporter.
 *
 * <p>The {@link #frameMessage_wrapsWithFiveBytePrefix()} case is a pure unit test of the gRPC
 * length-prefix framing. The rest drive a real {@link MockWebServer} over cleartext HTTP/2
 * (prior-knowledge, matching what the exporter uses for {@code http://} targets) and assert the
 * request shape (RPC path, {@code application/grpc}, framing, {@code grpc-encoding}) and the
 * success/error/retry decisions read from {@code grpc-status}.
 */
class OtlpGrpcProfilesExporterTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    // gRPC needs HTTP/2; the exporter uses h2c prior-knowledge for http:// — configure the server
    // to match.
    server.setProtocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE));
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  private static byte[] emptyFrame() {
    return new byte[] {0, 0, 0, 0, 0}; // 1 flag byte + 4-byte length 0 (an empty gRPC message)
  }

  private static Buffer framedBody() {
    Buffer b = new Buffer();
    b.write(emptyFrame());
    return b;
  }

  @Test
  void frameMessage_wrapsWithFiveBytePrefix() {
    byte[] msg = "hello-profiles".getBytes(StandardCharsets.UTF_8);

    byte[] frame = OtlpGrpcProfilesExporter.frameMessage(msg, false);
    assertEquals(5 + msg.length, frame.length);
    assertEquals(0, frame[0], "uncompressed flag");
    assertEquals(msg.length, bigEndianLen(frame), "big-endian length prefix");
    assertArrayEquals(msg, Arrays.copyOfRange(frame, 5, frame.length));

    byte[] compressed = OtlpGrpcProfilesExporter.frameMessage(msg, true);
    assertEquals(1, compressed[0], "compressed flag");
  }

  @Test
  void export_grpcStatusZeroInTrailers_returnsTrue_andSendsGrpcFraming() throws Exception {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body(framedBody())
            .trailers(Headers.of("grpc-status", "0"))
            .build());

    byte[] payload = "profile-request-bytes".getBytes(StandardCharsets.UTF_8);
    OtlpGrpcProfilesExporter exporter =
        new OtlpGrpcProfilesExporter(server.url("/").toString(), "none", 10_000);
    assertTrue(exporter.export(payload), "grpc-status 0 in trailers -> success");
    exporter.shutdown();

    RecordedRequest recorded = server.takeRequest();
    assertEquals("POST", recorded.getMethod());
    assertEquals(OtlpGrpcProfilesExporter.EXPORT_PATH, recorded.getUrl().encodedPath());
    assertEquals("application/grpc", recorded.getHeaders().get("Content-Type"));

    // Body is the gRPC length-prefixed frame: flag 0, big-endian length, then the verbatim payload.
    byte[] frame = recorded.getBody().toByteArray();
    assertEquals(0, frame[0], "uncompressed flag");
    assertEquals(payload.length, bigEndianLen(frame));
    assertArrayEquals(payload, Arrays.copyOfRange(frame, 5, frame.length));
  }

  @Test
  void export_grpcStatusZeroInHeaders_trailersOnly_returnsTrue() {
    // Trailers-Only response: grpc-status is in the initial headers, no body.
    server.enqueue(new MockResponse.Builder().code(200).addHeader("grpc-status", "0").build());

    OtlpGrpcProfilesExporter exporter =
        new OtlpGrpcProfilesExporter(server.url("/").toString(), "none", 10_000);
    assertTrue(exporter.export("x".getBytes(StandardCharsets.UTF_8)));
    exporter.shutdown();
  }

  @Test
  void export_nonZeroGrpcStatus_returnsFalse() {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body(framedBody())
            .trailers(Headers.of("grpc-status", "3", "grpc-message", "bad argument"))
            .build());

    OtlpGrpcProfilesExporter exporter =
        new OtlpGrpcProfilesExporter(server.url("/").toString(), "none", 10_000);
    assertFalse(exporter.export("x".getBytes(StandardCharsets.UTF_8)), "grpc-status 3 -> failure");
    exporter.shutdown();
  }

  @Test
  void export_retriesOnUnavailable_thenSucceeds() throws Exception {
    // First attempt UNAVAILABLE (14, retryable), second OK.
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body(framedBody())
            .trailers(Headers.of("grpc-status", "14"))
            .build());
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body(framedBody())
            .trailers(Headers.of("grpc-status", "0"))
            .build());

    OtlpGrpcProfilesExporter exporter =
        new OtlpGrpcProfilesExporter(server.url("/").toString(), "none", 10_000);
    assertTrue(
        exporter.export("x".getBytes(StandardCharsets.UTF_8)), "retry after 14 then succeed");
    exporter.shutdown();

    assertEquals(OtlpGrpcProfilesExporter.EXPORT_PATH, server.takeRequest().getUrl().encodedPath());
    assertEquals(
        OtlpGrpcProfilesExporter.EXPORT_PATH,
        server.takeRequest().getUrl().encodedPath(),
        "a second request was sent on retry");
  }

  @Test
  void export_gzip_setsCompressionFlagAndEncodingHeader() throws Exception {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body(framedBody())
            .trailers(Headers.of("grpc-status", "0"))
            .build());

    OtlpGrpcProfilesExporter exporter =
        new OtlpGrpcProfilesExporter(server.url("/").toString(), "gzip", 10_000);
    assertTrue(exporter.export("some-compressible-payload".getBytes(StandardCharsets.UTF_8)));
    exporter.shutdown();

    RecordedRequest recorded = server.takeRequest();
    assertEquals("gzip", recorded.getHeaders().get("grpc-encoding"));
    byte[] frame = recorded.getBody().toByteArray();
    assertEquals(1, frame[0], "gzip -> compression flag set");
  }

  /** Decode the 4-byte big-endian message length from a gRPC frame (bytes 1..4). */
  private static int bigEndianLen(byte[] frame) {
    return ((frame[1] & 0xFF) << 24)
        | ((frame[2] & 0xFF) << 16)
        | ((frame[3] & 0xFF) << 8)
        | (frame[4] & 0xFF);
  }
}
