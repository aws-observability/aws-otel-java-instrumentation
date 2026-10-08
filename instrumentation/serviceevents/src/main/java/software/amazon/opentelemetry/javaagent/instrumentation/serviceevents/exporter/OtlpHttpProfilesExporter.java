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
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Best-effort OTLP profiles HTTP exporter.
 *
 * <p>POSTs a serialized {@code ExportProfilesServiceRequest} protobuf to a fixed, fully-resolved
 * endpoint URL with {@code Content-Type: application/x-protobuf} and optional gzip. The URL is
 * resolved by {@code ServiceEventsConfig} and handed in verbatim — this exporter never appends a
 * path. Exports are best-effort with a single retry; failures are logged and swallowed so the
 * profiler never disrupts the application.
 *
 * <p><b>Payload-size guard.</b> Before POSTing, the exporter compares the <em>uncompressed</em>
 * serialized request size against a configurable byte limit ({@code maxPayloadBytes}, default 64
 * MiB, well above typical backend caps; backends typically enforce theirs on the decompressed body,
 * so gzip does not help). An over-limit window is dropped with a {@code WARN} rather than POSTed
 * blindly and rejected with an HTTP 413 that silently loses the whole window — so the drop is
 * observable and actionable. A limit {@code <= 0} disables the guard.
 */
public final class OtlpHttpProfilesExporter implements ProfilesExporter {

  private static final Logger logger = Logger.getLogger(OtlpHttpProfilesExporter.class.getName());

  private static final MediaType PROTOBUF = MediaType.parse("application/x-protobuf");
  private static final int MAX_ATTEMPTS = 2; // initial try + one retry
  private static final long RETRY_BACKOFF_MS = 250L; // default pause before a retry
  private static final long MAX_RETRY_BACKOFF_MS = 5000L; // cap when honoring Retry-After

  /**
   * Default uncompressed payload-size limit: 64 MiB. A loose backstop against pathologically large
   * windows — a backend may enforce its own (smaller) cap server-side, and this default sits well
   * above it so normal large windows are never dropped client-side.
   */
  public static final long DEFAULT_MAX_PAYLOAD_BYTES = 64L * 1024L * 1024L;

  private final OkHttpClient client;
  private final String endpoint;
  private final boolean gzip;
  private final long maxPayloadBytes;

  /**
   * Convenience constructor using {@link #DEFAULT_MAX_PAYLOAD_BYTES} as the payload-size limit.
   *
   * @param endpoint the fully-resolved profiles endpoint URL (used verbatim; no path is appended)
   * @param compression {@code "gzip"} enables gzip request compression; anything else disables it
   * @param timeoutMs per-call timeout in milliseconds
   */
  public OtlpHttpProfilesExporter(String endpoint, String compression, long timeoutMs) {
    this(endpoint, compression, timeoutMs, DEFAULT_MAX_PAYLOAD_BYTES);
  }

  /**
   * @param endpoint the fully-resolved profiles endpoint URL (used verbatim; no path is appended)
   * @param compression {@code "gzip"} enables gzip request compression; anything else disables it
   * @param timeoutMs per-call timeout in milliseconds
   * @param maxPayloadBytes drop (with a {@code WARN}) any request whose <em>uncompressed</em>
   *     serialized size exceeds this many bytes, instead of POSTing it and eating a 413; {@code <=
   *     0} disables the guard
   */
  public OtlpHttpProfilesExporter(
      String endpoint, String compression, long timeoutMs, long maxPayloadBytes) {
    this.endpoint = endpoint;
    this.gzip = "gzip".equalsIgnoreCase(compression);
    this.maxPayloadBytes = maxPayloadBytes;
    this.client =
        new OkHttpClient.Builder().callTimeout(Duration.ofMillis(Math.max(1L, timeoutMs))).build();
  }

  /** The resolved endpoint URL this exporter POSTs to. */
  @Override
  public String getEndpoint() {
    return endpoint;
  }

  /** Whether request bodies are gzip-compressed. */
  public boolean isGzip() {
    return gzip;
  }

  /** The uncompressed payload-size limit in bytes ({@code <= 0} means the guard is disabled). */
  public long getMaxPayloadBytes() {
    return maxPayloadBytes;
  }

  /**
   * POST the serialized {@code ExportProfilesServiceRequest} bytes. Best-effort with one retry.
   *
   * @return {@code true} if the collector accepted the request (2xx), {@code false} otherwise
   */
  @Override
  public boolean export(byte[] payload) {
    if (payload == null || payload.length == 0) {
      return false;
    }

    // Payload-size guard: the cap is enforced on the uncompressed body, so measure the serialized
    // request (payload) — not the gzipped body. Drop over-limit windows deliberately with a WARN
    // rather than POST and eat a 413 that silently loses the whole window.
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

    byte[] body = payload;
    if (gzip) {
      try {
        body = ProfilesExportSupport.gzip(payload);
      } catch (IOException e) {
        logger.log(Level.WARNING, "Failed to gzip profiles payload; sending uncompressed", e);
        body = payload;
      }
    }

    Request.Builder requestBuilder =
        new Request.Builder()
            .url(endpoint)
            .post(RequestBody.create(body, PROTOBUF))
            .header("Content-Type", "application/x-protobuf");
    if (gzip && body != payload) {
      requestBuilder.header("Content-Encoding", "gzip");
    }
    Request request = requestBuilder.build();

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      long backoffMs = RETRY_BACKOFF_MS;
      try (Response response = client.newCall(request).execute()) {
        if (response.isSuccessful()) {
          return true;
        }
        int code = response.code();
        logger.log(
            Level.FINE,
            "Profiles export attempt {0} returned HTTP {1}",
            new Object[] {attempt, code});
        // Retry transient responses — 5xx, plus 408 (Request Timeout) and 429 (Too Many Requests) —
        // honoring Retry-After when present. Other 4xx are deterministic (400/413/415): re-sending
        // the identical body fails the same way, so don't retry.
        if (!(code >= 500 || code == 408 || code == 429)) {
          logger.warning(
              "Profiles export rejected with HTTP " + code + " (not retryable) to " + endpoint);
          return false;
        }
        long retryAfterMs = parseRetryAfterMs(response.header("Retry-After"));
        if (retryAfterMs > 0) {
          backoffMs = Math.min(retryAfterMs, MAX_RETRY_BACKOFF_MS);
        }
      } catch (IOException e) {
        logger.log(
            Level.FINE, "Profiles export attempt " + attempt + " failed: " + e.getMessage(), e);
      }
      // Back off before a retry (reached only for a retryable 5xx/408/429 or an IOException).
      if (attempt < MAX_ATTEMPTS) {
        try {
          Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break; // stop retrying if the rotation thread is shutting down
        }
      }
    }
    logger.warning("Profiles export failed after " + MAX_ATTEMPTS + " attempt(s) to " + endpoint);
    return false;
  }

  /**
   * Parse a {@code Retry-After} header (delta-seconds form) to milliseconds; 0 when absent or in
   * the HTTP-date form (not honored — the default backoff applies instead).
   */
  private static long parseRetryAfterMs(String header) {
    if (header == null || header.isEmpty()) {
      return 0L;
    }
    try {
      long seconds = Long.parseLong(header.trim());
      return seconds > 0 ? seconds * 1000L : 0L;
    } catch (NumberFormatException e) {
      return 0L;
    }
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
      logger.log(Level.FINE, "Error shutting down profiles exporter", e);
    }
  }
}
