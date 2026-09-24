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

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Best-effort OTLP/gRPC profiles exporter — the gRPC counterpart of {@link
 * OtlpHttpProfilesExporter}, selected when {@code OTEL_EXPORTER_OTLP_PROTOCOL=grpc}.
 *
 * <p>Sends a unary gRPC {@code ProfilesService/Export} call carrying the serialized {@code
 * ExportProfilesServiceRequest}. Rather than pull in the grpc-java runtime (+ grpc-netty) or couple
 * to OpenTelemetry's unstable {@code *.internal} gRPC-over-OkHttp classes, this hand-rolls the
 * (small, well-specified) gRPC unary wire format directly over OkHttp's HTTP/2 support — matching
 * the dependency-light, self-contained approach of the sibling HTTP exporter. The message is
 * length- prefix framed (1 compression-flag byte + 4-byte big-endian length + protobuf), POSTed
 * with {@code Content-Type: application/grpc} to the fixed RPC path, and success is read from the
 * {@code grpc-status} trailer (or, for a Trailers-Only response, the initial headers).
 *
 * <p><b>HTTP/2 requirement.</b> gRPC runs only over HTTP/2. For a plaintext {@code http://} target
 * this uses OkHttp {@link Protocol#H2_PRIOR_KNOWLEDGE} (h2c — no HTTP/1.1 upgrade dance); for
 * {@code https://} it lets OkHttp negotiate {@code h2} via ALPN.
 *
 * <p><b>Payload-size guard.</b> Same as the HTTP exporter: the <em>uncompressed</em> serialized
 * size is compared against {@code maxPayloadBytes} and an over-limit window is dropped with a
 * {@code WARN} rather than sent and rejected. A limit {@code <= 0} disables the guard.
 */
public final class OtlpGrpcProfilesExporter implements ProfilesExporter {

  private static final Logger logger = Logger.getLogger(OtlpGrpcProfilesExporter.class.getName());

  /**
   * The unary RPC path for the OTLP profiles collector service (from {@code
   * profiles_service.proto}).
   */
  static final String EXPORT_PATH =
      "/opentelemetry.proto.collector.profiles.v1development.ProfilesService/Export";

  private static final MediaType GRPC = MediaType.parse("application/grpc");
  private static final int MAX_ATTEMPTS = 2; // initial try + one retry
  private static final long RETRY_BACKOFF_MS = 250L;

  /**
   * Cap on the collector response we buffer while draining for trailers. An {@code
   * ExportProfilesServiceResponse} is tiny (empty or a small {@code partial_success}); this bounds
   * a faulty/hostile endpoint from exhausting the app heap. OTLP recommends a 4 MiB inbound
   * default.
   */
  private static final long MAX_RESPONSE_BYTES = 4L * 1024L * 1024L;

  // gRPC status codes (grpc-status trailer). 0 = OK; 8 = RESOURCE_EXHAUSTED; 14 = UNAVAILABLE.
  private static final int GRPC_OK = 0;
  private static final int GRPC_RESOURCE_EXHAUSTED = 8;
  private static final int GRPC_UNAVAILABLE = 14;

  private final OkHttpClient client;
  private final String endpoint; // original resolved endpoint (for logging)
  private final HttpUrl rpcUrl; // endpoint authority + EXPORT_PATH
  private final boolean gzip;
  private final long maxPayloadBytes;

  /** Convenience constructor using {@link OtlpHttpProfilesExporter#DEFAULT_MAX_PAYLOAD_BYTES}. */
  public OtlpGrpcProfilesExporter(String endpoint, String compression, long timeoutMs) {
    this(endpoint, compression, timeoutMs, OtlpHttpProfilesExporter.DEFAULT_MAX_PAYLOAD_BYTES);
  }

  /**
   * @param endpoint the resolved gRPC target (scheme + host:port; any path is replaced by the RPC
   *     method path)
   * @param compression {@code "gzip"} enables gRPC message compression; anything else disables it
   * @param timeoutMs per-call timeout in milliseconds
   * @param maxPayloadBytes drop (with a {@code WARN}) any request whose uncompressed serialized
   *     size exceeds this; {@code <= 0} disables the guard
   */
  public OtlpGrpcProfilesExporter(
      String endpoint, String compression, long timeoutMs, long maxPayloadBytes) {
    this.endpoint = endpoint;
    this.gzip = "gzip".equalsIgnoreCase(compression);
    this.maxPayloadBytes = maxPayloadBytes;

    HttpUrl base = HttpUrl.parse(endpoint);
    HttpUrl url = base == null ? null : base.newBuilder().encodedPath(EXPORT_PATH).build();
    this.rpcUrl = url;

    OkHttpClient.Builder builder =
        new OkHttpClient.Builder().callTimeout(Duration.ofMillis(Math.max(1L, timeoutMs)));
    // gRPC needs HTTP/2. Plaintext h2c requires prior-knowledge (no upgrade); for TLS we leave the
    // default protocol list so OkHttp negotiates h2 via ALPN. NOTE: a TLS profiles endpoint MUST
    // ALPN-advertise h2 — this is inherent to gRPC-over-TLS (no client can force it). If the TLS
    // front-end offers only http/1.1, ALPN falls back and the application/grpc POST fails; use a
    // gateway that advertises h2 (or a cleartext h2c target).
    if (base != null && !base.isHttps()) {
      builder.protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE));
    }
    this.client = builder.build();

    if (url == null) {
      logger.warning(
          "Invalid gRPC profiles endpoint '" + endpoint + "'; profiles export will be a no-op");
    }
  }

  @Override
  public String getEndpoint() {
    return endpoint;
  }

  /**
   * Send the serialized {@code ExportProfilesServiceRequest} as a unary gRPC {@code Export} call.
   * Best-effort with one retry.
   *
   * @return {@code true} if the server returned {@code grpc-status: 0}, {@code false} otherwise
   */
  @Override
  public boolean export(byte[] payload) {
    if (payload == null || payload.length == 0 || rpcUrl == null) {
      return false;
    }

    // Payload-size guard on the uncompressed body (backends typically cap the decompressed size).
    if (ProfilesExportSupport.exceedsPayloadLimit(payload, maxPayloadBytes)) {
      logger.warning(
          "Profiles payload "
              + payload.length
              + " bytes exceeds the "
              + maxPayloadBytes
              + "-byte limit (OTEL_AWS_PROFILER_MAX_PAYLOAD_BYTES); dropping this window instead of"
              + " sending a request the backend would reject. Reduce OTEL_AWS_PROFILER_WINDOW_SECONDS"
              + " and/or set OTEL_AWS_PROFILER_AGGREGATION_MODE=sum to shrink the payload.");
      return false;
    }

    // Compress the message (not the frame) when gzip is enabled; the 1-byte frame flag then marks
    // it.
    byte[] message = payload;
    boolean compressed = false;
    if (gzip) {
      try {
        message = ProfilesExportSupport.gzip(payload);
        compressed = true;
      } catch (IOException e) {
        logger.log(Level.WARNING, "Failed to gzip profiles payload; sending uncompressed", e);
        message = payload;
        compressed = false;
      }
    }

    byte[] frame = frameMessage(message, compressed);
    Request.Builder requestBuilder =
        new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(frame, GRPC))
            .header("Content-Type", "application/grpc")
            .header("te", "trailers")
            .header("grpc-accept-encoding", "gzip");
    if (compressed) {
      requestBuilder.header("grpc-encoding", "gzip");
    }
    Request request = requestBuilder.build();

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      boolean retryable = false;
      try (Response response = client.newCall(request).execute()) {
        // grpc-status may arrive in the initial headers (Trailers-Only responses) or in the HTTP/2
        // trailers. Read the header first, then drain the body (required before trailers()), then
        // fall back to the trailer.
        String statusHeader = response.header("grpc-status");
        try (ResponseBody body = response.body()) {
          if (body != null) {
            // Bounded drain: buffer at most MAX_RESPONSE_BYTES so a faulty/hostile endpoint can't
            // exhaust the app heap. request(n) reads ahead at most n bytes; if it can satisfy
            // cap+1, the body is over the cap — refuse it (non-retryable). Otherwise readByteArray
            // drains the (<= cap) body so response.trailers() is available.
            BufferedSource source = body.source();
            if (source.request(MAX_RESPONSE_BYTES + 1)) {
              logger.warning(
                  "Profiles gRPC response from "
                      + endpoint
                      + " exceeds "
                      + MAX_RESPONSE_BYTES
                      + " bytes; treating as failed (not retryable)");
              return false;
            }
            source.readByteArray();
          }
        }
        if (statusHeader == null) {
          try {
            statusHeader = response.trailers().get("grpc-status");
          } catch (IOException | IllegalStateException ignore) {
            // trailers unavailable — fall through to the HTTP-status heuristic below
          }
        }

        if (response.isSuccessful()) {
          int grpcStatus = parseGrpcStatus(statusHeader);
          if (grpcStatus == GRPC_OK) {
            return true;
          }
          if (grpcStatus == GRPC_UNAVAILABLE || grpcStatus == GRPC_RESOURCE_EXHAUSTED) {
            retryable = true;
            logger.log(
                Level.FINE,
                "Profiles gRPC export attempt {0} got grpc-status {1}",
                new Object[] {attempt, grpcStatus});
          } else {
            logger.warning(
                "Profiles gRPC export rejected with grpc-status "
                    + grpcStatus
                    + " ("
                    + safeMessage(response)
                    + ") to "
                    + endpoint);
            return false;
          }
        } else {
          // Transport/proxy-level failure (a compliant gRPC server returns HTTP 200). Mirror the
          // HTTP exporter: retry 5xx/408/429, treat other codes as non-retryable.
          int code = response.code();
          if (code >= 500 || code == 408 || code == 429) {
            retryable = true;
            logger.log(
                Level.FINE,
                "Profiles gRPC export attempt {0} returned HTTP {1}",
                new Object[] {attempt, code});
          } else {
            logger.warning(
                "Profiles gRPC export rejected with HTTP "
                    + code
                    + " (not retryable) to "
                    + endpoint);
            return false;
          }
        }
      } catch (IOException e) {
        retryable = true;
        logger.log(
            Level.FINE,
            "Profiles gRPC export attempt " + attempt + " failed: " + e.getMessage(),
            e);
      }

      if (retryable && attempt < MAX_ATTEMPTS) {
        try {
          Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break; // stop retrying if the rotation thread is shutting down
        }
      }
    }
    logger.warning(
        "Profiles gRPC export failed after " + MAX_ATTEMPTS + " attempt(s) to " + endpoint);
    return false;
  }

  /** Length-prefix framing: 1 compression-flag byte + 4-byte big-endian length + message. */
  static byte[] frameMessage(byte[] message, boolean compressed) {
    int len = message.length;
    byte[] frame = new byte[5 + len];
    frame[0] = (byte) (compressed ? 1 : 0);
    frame[1] = (byte) (len >>> 24);
    frame[2] = (byte) (len >>> 16);
    frame[3] = (byte) (len >>> 8);
    frame[4] = (byte) len;
    System.arraycopy(message, 0, frame, 5, len);
    return frame;
  }

  /**
   * Parse a {@code grpc-status} value; returns -1 when absent/unparseable (treated as an error).
   */
  private static int parseGrpcStatus(String status) {
    if (status == null || status.isEmpty()) {
      return -1;
    }
    try {
      return Integer.parseInt(status.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static String safeMessage(Response response) {
    String msg = response.header("grpc-message");
    if (msg == null) {
      try {
        msg = response.trailers().get("grpc-message");
      } catch (IOException | IllegalStateException ignore) {
        msg = null;
      }
    }
    return msg == null ? "no grpc-message" : msg;
  }

  /** Drain the OkHttp dispatcher/connection pool. Safe to call more than once. */
  @Override
  public void shutdown() {
    try {
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
      if (client.cache() != null) {
        client.cache().close();
      }
    } catch (Exception e) {
      logger.log(Level.FINE, "Error shutting down gRPC profiles exporter", e);
    }
  }
}
