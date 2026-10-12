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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import io.opentelemetry.sdk.resources.Resource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wire-level tests that assert on the HTTP headers a server actually receives, rather than on which
 * exporter class the customizer returned.
 *
 * <p>These exist because upstream includes values from both {@code addHeader(...)} and {@code
 * setHeaders(Supplier)} when keys collide, so layering SigV4 on top of a configured bearer token
 * produces two {@code Authorization} values. These tests measure that exactly one {@code
 * Authorization} header reaches the server in each authentication mode.
 */
class OtlpAwsMetricExporterWireTest {

  private static final String ACCESS_KEY_ID_PROPERTY = "aws.accessKeyId";
  private static final String SECRET_ACCESS_KEY_PROPERTY = "aws.secretAccessKey";

  private HttpServer server;
  private final AtomicReference<List<String>> authorizationValues = new AtomicReference<>();
  private final AtomicReference<byte[]> requestBody = new AtomicReference<>();
  private CountDownLatch requestReceived;
  private String previousAccessKeyId;
  private String previousSecretAccessKey;

  @BeforeEach
  void setUp() throws Exception {
    this.requestReceived = new CountDownLatch(1);
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    this.server.createContext(
        "/v1/metrics",
        exchange -> {
          this.authorizationValues.set(exchange.getRequestHeaders().get("Authorization"));
          this.requestBody.set(readFully(exchange.getRequestBody()));
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
          this.requestReceived.countDown();
        });
    this.server.start();

    // Fake static credentials so the real signer produces a real signature without contacting AWS.
    this.previousAccessKeyId = System.getProperty(ACCESS_KEY_ID_PROPERTY);
    this.previousSecretAccessKey = System.getProperty(SECRET_ACCESS_KEY_PROPERTY);
    System.setProperty(ACCESS_KEY_ID_PROPERTY, "AKIAFAKEACCESSKEY123");
    System.setProperty(SECRET_ACCESS_KEY_PROPERTY, "fakeSecretAccessKeyForUnitTestsOnly");
  }

  @AfterEach
  void tearDown() {
    restoreProperty(ACCESS_KEY_ID_PROPERTY, this.previousAccessKeyId);
    restoreProperty(SECRET_ACCESS_KEY_PROPERTY, this.previousSecretAccessKey);
    if (this.server != null) {
      this.server.stop(0);
    }
  }

  private static void restoreProperty(String key, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previousValue);
    }
  }

  private String endpoint() {
    return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1/metrics";
  }

  private List<String> exportAndCaptureAuthorization(MetricExporter exporter) throws Exception {
    exporter.export(Collections.emptyList()).join(10, TimeUnit.SECONDS);
    assertTrue(this.requestReceived.await(10, TimeUnit.SECONDS), "no request reached the server");
    return this.authorizationValues.get();
  }

  /**
   * Bearer mode: the user configured an Authorization header, so the customizer leaves the upstream
   * exporter unwrapped. Only the bearer value should reach the server.
   */
  @Test
  void testBearerTokenModeSendsExactlyOneAuthorizationHeader() throws Exception {
    OtlpHttpMetricExporter exporter =
        OtlpHttpMetricExporter.builder()
            .setEndpoint(endpoint())
            .addHeader("Authorization", "Bearer FAKE_METRICS_API_KEY")
            .build();

    List<String> received = exportAndCaptureAuthorization(exporter);

    assertNotNull(received, "no Authorization header received");
    assertEquals(1, received.size(), "expected exactly one Authorization header, got " + received);
    assertTrue(
        received.get(0).startsWith("Bearer "),
        "expected the bearer token to be preserved, got " + received.get(0));

    exporter.shutdown().join(5, TimeUnit.SECONDS);
  }

  /**
   * SigV4 mode: no Authorization header is configured, so the SigV4 wrapper applies. Only the SigV4
   * value should reach the server.
   */
  @Test
  void testSigV4ModeSendsExactlyOneAuthorizationHeader() throws Exception {
    OtlpHttpMetricExporter parentExporter =
        OtlpHttpMetricExporter.builder().setEndpoint(endpoint()).build();
    MetricExporter exporter =
        OtlpAwsMetricExporterBuilder.create(parentExporter, endpoint()).build();

    List<String> received = exportAndCaptureAuthorization(exporter);

    assertNotNull(received, "no Authorization header received");
    assertEquals(1, received.size(), "expected exactly one Authorization header, got " + received);
    assertTrue(
        received.get(0).startsWith("AWS4-HMAC-SHA256 "),
        "expected a SigV4 Authorization header, got " + received.get(0));
    assertTrue(
        received.get(0).contains("/monitoring/aws4_request"),
        "expected the monitoring signing service in the credential scope, got " + received.get(0));

    exporter.shutdown().join(5, TimeUnit.SECONDS);
  }

  /**
   * Regression guard for the duplicate-header problem: if a bearer token and the SigV4 wrapper are
   * combined, upstream sends two Authorization values. This documents the behavior the precedence
   * check in the customizer exists to prevent.
   */
  @Test
  void testCombiningBearerAndSigV4ProducesTwoAuthorizationHeaders() throws Exception {
    OtlpHttpMetricExporter parentExporter =
        OtlpHttpMetricExporter.builder()
            .setEndpoint(endpoint())
            .addHeader("Authorization", "Bearer FAKE_METRICS_API_KEY")
            .build();
    MetricExporter exporter =
        OtlpAwsMetricExporterBuilder.create(parentExporter, endpoint()).build();

    List<String> received = exportAndCaptureAuthorization(exporter);

    assertNotNull(received, "no Authorization header received");
    assertEquals(
        2, received.size(), "expected the documented duplicate-header behavior, got " + received);
  }

  /**
   * The SigV4 signature must cover the bytes that actually reach the server.
   *
   * <p>{@code export()} signs bytes produced by {@link MetricsRequestMarshaler}, while the wrapped
   * upstream exporter serializes the metrics a second time for the request itself. Under the
   * default {@code REUSABLE_DATA} memory mode that second serialization uses {@code
   * LowAllocationMetricsRequestMarshaler}, which groups metrics by instrumentation scope in a
   * differently sized identity-keyed map and so can emit the scope blocks in a different order. The
   * payloads stay semantically equal but differ byte-wise, the signature no longer matches the
   * request body, and CloudWatch rejects the export with 403 SignatureDoesNotMatch. Pinning {@code
   * IMMUTABLE_DATA} on the parent builder is what keeps both serializations on one marshaler.
   *
   * <p>Two notes on the shape of this test. It sweeps a range of scope counts because whether the
   * two marshalers actually disagree depends both on how many scopes there are and on identity hash
   * codes assigned at JVM start. Measured against this upstream version, one scope and eleven or
   * more scopes happen to agree, two through four are roughly a coin flip, and five through ten
   * disagree consistently, so a single fixed count can silently land in a range where the defect is
   * invisible. It also asserts on bytes rather than on a 200 response, because the local test
   * server does not verify signatures and so cannot reject a mismatched one.
   */
  @Test
  void testSignedPayloadMatchesTheBytesSentAcrossInstrumentationScopeCounts() throws Exception {
    OtlpHttpMetricExporter parentExporter =
        OtlpHttpMetricExporter.builder().setEndpoint(endpoint()).build();
    MetricExporter exporter =
        OtlpAwsMetricExporterBuilder.create(parentExporter, endpoint()).build();

    List<Integer> divergentScopeCounts = new ArrayList<>();
    for (int scopeCount = 2; scopeCount <= 10; scopeCount++) {
      List<MetricData> metrics = metricsWithDistinctScopes(scopeCount);

      byte[] sentBody = exportAndCaptureBody(exporter, metrics);
      assertNotNull(sentBody, "no request body captured for " + scopeCount + " scopes");

      // The bytes export() hands to the signer.
      ByteArrayOutputStream signedPayload = new ByteArrayOutputStream();
      MetricsRequestMarshaler.create(metrics).writeBinaryTo(signedPayload);

      if (!sha256Hex(signedPayload.toByteArray()).equals(sha256Hex(sentBody))) {
        divergentScopeCounts.add(scopeCount);
      }
    }

    assertEquals(
        Collections.emptyList(),
        divergentScopeCounts,
        "the signed payload and the payload sent to the server differ at these instrumentation "
            + "scope counts, so the SigV4 signature cannot match the request body. Both "
            + "serializations must use the same marshaler.");

    exporter.shutdown().join(5, TimeUnit.SECONDS);
  }

  /** Exports once and returns the request body the server received. */
  private byte[] exportAndCaptureBody(MetricExporter exporter, List<MetricData> metrics)
      throws Exception {
    this.requestReceived = new CountDownLatch(1);
    this.requestBody.set(null);

    exporter.export(metrics).join(10, TimeUnit.SECONDS);
    assertTrue(this.requestReceived.await(10, TimeUnit.SECONDS), "no request reached the server");

    return this.requestBody.get();
  }

  /**
   * Builds one metric per instrumentation scope, each scope distinctly named, so the marshalers
   * have a non-trivial set of scope blocks to group and order.
   */
  private static List<MetricData> metricsWithDistinctScopes(int scopeCount) {
    List<MetricData> metrics = new ArrayList<>(scopeCount);
    for (int i = 0; i < scopeCount; i++) {
      metrics.add(
          ImmutableMetricData.createDoubleGauge(
              Resource.empty(),
              InstrumentationScopeInfo.create("test.scope." + i),
              "test.metric." + i,
              "",
              "1",
              ImmutableGaugeData.create(
                  Collections.singletonList(
                      ImmutableDoublePointData.create(0L, 1L, Attributes.empty(), i)))));
    }
    return metrics;
  }

  private static byte[] readFully(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int read;
    while ((read = in.read(chunk)) != -1) {
      out.write(chunk, 0, read);
    }
    return out.toByteArray();
  }

  private static String sha256Hex(byte[] data) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }
}
