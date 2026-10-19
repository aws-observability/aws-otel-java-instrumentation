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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.common.ComponentLoader;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.common.export.HttpResponse;
import io.opentelemetry.sdk.common.export.HttpSender;
import io.opentelemetry.sdk.common.export.HttpSenderConfig;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import io.opentelemetry.sdk.common.export.MessageWriter;
import io.opentelemetry.sdk.resources.Resource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignRequest;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

@ExtendWith(MockitoExtension.class)
public abstract class AbstractOtlpAwsExporterTest<T> {
  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String X_AMZ_DATE_HEADER = "X-Amz-Date";
  private static final String X_AMZ_SECURITY_TOKEN_HEADER = "X-Amz-Security-Token";

  protected static final Resource TEST_RESOURCE =
      Resource.create(
          Attributes.builder()
              .put("service.name", "test-service")
              .put("test.resource.count", 3L)
              .put("test.resource.enabled", true)
              .build());

  private AwsCredentials credentials;
  private SignedRequest signedRequest;
  protected final String endpoint;
  private final String region;
  private final String service;
  protected String compression;
  protected String otlpCompression = "none";
  protected ComponentLoader componentLoader;
  private final List<CapturedRequest> requests = new ArrayList<>();
  private final List<byte[]> signedPayloads = new ArrayList<>();

  private MockedStatic<DefaultCredentialsProvider> mockDefaultCredentialsProvider;
  private MockedStatic<AwsV4HttpSigner> mockAwsV4HttpSigner;
  @Mock private DefaultCredentialsProvider credentialsProvider;
  @Mock private AwsV4HttpSigner signer;

  protected AbstractOtlpAwsExporterTest(String endpoint, String region, String service) {
    this.endpoint = endpoint;
    this.region = region;
    this.service = service;
  }

  protected abstract T createOtlpData(int index);

  protected abstract CompletableResultCode export(Collection<T> data);

  protected abstract void validateOtlpPayload(byte[] payload, Collection<T> data) throws Exception;

  protected abstract void shutdownExporter();

  @BeforeEach
  protected void setup() {
    credentials = AwsSessionCredentials.create("test_access_key", "test_secret_key", "test_token0");
    signedRequest = createSignedRequest(0);
    mockDefaultCredentialsProvider = mockStatic(DefaultCredentialsProvider.class);
    mockDefaultCredentialsProvider
        .when(DefaultCredentialsProvider::create)
        .thenReturn(credentialsProvider);
    mockAwsV4HttpSigner = mockStatic(AwsV4HttpSigner.class);
    mockAwsV4HttpSigner.when(AwsV4HttpSigner::create).thenReturn(signer);
    lenient().when(credentialsProvider.resolveCredentials()).thenAnswer(invocation -> credentials);
    lenient()
        .when(signer.sign(any(Consumer.class)))
        .thenAnswer(
            invocation -> {
              signedPayloads.add(assertSigningRequest(invocation.getArgument(0)));
              return signedRequest;
            });

    componentLoader = mock(ComponentLoader.class);
    HttpSenderProvider senderProvider = mock(HttpSenderProvider.class);
    lenient()
        .when(componentLoader.load(HttpSenderProvider.class))
        .thenReturn(List.of(senderProvider));
    lenient()
        .when(senderProvider.createSender(any()))
        .thenAnswer(
            invocation -> {
              HttpSenderConfig config = invocation.getArgument(0);
              HttpSender sender = mock(HttpSender.class);
              lenient().when(sender.shutdown()).thenReturn(CompletableResultCode.ofSuccess());
              lenient()
                  .doAnswer(
                      send -> {
                        MessageWriter writer = send.getArgument(0);
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        String encoding = "";
                        if (config.getCompressor() == null) {
                          writer.writeMessage(buffer);
                        } else {
                          encoding = config.getCompressor().getEncoding();
                          try (OutputStream compressed = config.getCompressor().compress(buffer)) {
                            writer.writeMessage(compressed);
                          }
                        }
                        Map<String, List<String>> headers =
                            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                        headers.putAll(config.getHeadersSupplier().get());
                        requests.add(
                            new CapturedRequest(
                                config.getEndpoint(),
                                config.getContentType(),
                                encoding,
                                headers,
                                buffer.toByteArray()));
                        HttpResponse response = mock(HttpResponse.class);
                        when(response.getStatusCode()).thenReturn(200);
                        Consumer<HttpResponse> onResponse = send.getArgument(1);
                        onResponse.accept(response);
                        return null;
                      })
                  .when(sender)
                  .send(any(), any(), any());
              return sender;
            });
  }

  @AfterEach
  protected void afterEach() {
    try {
      shutdownExporter();
    } finally {
      mockDefaultCredentialsProvider.close();
      mockAwsV4HttpSigner.close();
    }
  }

  @Test
  protected void testAwsExporterAddsSigV4Headers() throws Exception {
    T data = createOtlpData(0);
    assertTrue(export(List.of(data)).isSuccess());
    assertEquals(1, requests.size());
    assertOtlpRequest(0, List.of(data));
    assertSigningHeaders(requests.get(0));
    verify(credentialsProvider).resolveCredentials();
    verify(signer).sign(any(Consumer.class));
  }

  @Test
  protected void testAwsExporterExportCorrectlyAddsDifferentSigV4Headers() throws Exception {
    for (int i = 0; i < 10; i++) {
      credentials =
          AwsSessionCredentials.create("test_access_key", "test_secret_key", "test_token" + i);
      signedRequest = createSignedRequest(i);
      T data = createOtlpData(i);
      assertTrue(export(List.of(data)).isSuccess());
      assertEquals(i + 1, requests.size());
      assertOtlpRequest(i, List.of(data));
      assertSigningHeaders(requests.get(i));
    }
    assertEquals(
        10,
        requests.stream()
            .map(request -> request.headers.get(AUTHORIZATION_HEADER))
            .distinct()
            .count());
    verify(credentialsProvider, times(10)).resolveCredentials();
    verify(signer, times(10)).sign(any(Consumer.class));
  }

  @Test
  protected void testAwsExporterDoesNotAddSigV4HeadersIfFailureToRetrieveCredentials()
      throws Exception {
    when(credentialsProvider.resolveCredentials())
        .thenThrow(SdkClientException.builder().message("bad credentials").build());
    T data = createOtlpData(0);
    assertTrue(export(List.of(data)).isSuccess());
    assertEquals(1, requests.size());
    assertOtlpRequest(0, List.of(data));
    assertFalse(requests.get(0).headers.containsKey(AUTHORIZATION_HEADER));
    assertFalse(requests.get(0).headers.containsKey(X_AMZ_DATE_HEADER));
    assertFalse(requests.get(0).headers.containsKey(X_AMZ_SECURITY_TOKEN_HEADER));
    verifyNoInteractions(signer);
  }

  @Test
  protected void testAwsExporterDoesNotAddSigV4HeadersIfFailureToSignHeaders() throws Exception {
    when(signer.sign(any(Consumer.class)))
        .thenThrow(SdkClientException.builder().message("bad signature").build());
    T data = createOtlpData(0);
    assertTrue(export(List.of(data)).isSuccess());
    assertEquals(1, requests.size());
    assertOtlpRequest(0, List.of(data));
    assertFalse(requests.get(0).headers.containsKey(AUTHORIZATION_HEADER));
    assertFalse(requests.get(0).headers.containsKey(X_AMZ_DATE_HEADER));
    assertFalse(requests.get(0).headers.containsKey(X_AMZ_SECURITY_TOKEN_HEADER));
    verify(credentialsProvider).resolveCredentials();
    verify(signer).sign(any(Consumer.class));
  }

  @ParameterizedTest
  @MethodSource("compressionCases")
  protected void testSignedPayloadMatchesTheBytesSentAcrossMultipleExports(
      String compression, String encoding) throws Exception {
    this.compression = compression;
    otlpCompression = encoding.isEmpty() ? "none" : encoding;
    List<List<T>> expectedData = new ArrayList<>();
    for (int scopeCount = 1; scopeCount <= 10; scopeCount++) {
      List<T> data = new ArrayList<>();
      for (int i = 0; i < scopeCount; i++) {
        data.add(createOtlpData(i));
      }
      expectedData.add(data);
      assertTrue(export(data).isSuccess());
      assertEquals(scopeCount, requests.size());
      assertEquals(scopeCount, signedPayloads.size());
    }
    for (int i = 0; i < expectedData.size(); i++) {
      assertOtlpRequest(i, expectedData.get(i));
      assertSigningHeaders(requests.get(i));
      assertEquals(encoding, requests.get(i).encoding);
      assertArrayEquals(
          signedPayloads.get(i),
          requests.get(i).payload,
          "payload mismatch for " + (i + 1) + " scopes");
    }
    verify(credentialsProvider, times(10)).resolveCredentials();
    verify(signer, times(10)).sign(any(Consumer.class));
  }

  private byte[] assertSigningRequest(
      Consumer<SignRequest.Builder<AwsCredentialsIdentity>> configure) throws Exception {
    @SuppressWarnings("unchecked")
    SignRequest.Builder<AwsCredentialsIdentity> builder =
        mock(SignRequest.Builder.class, RETURNS_SELF);
    configure.accept(builder);
    verify(builder).identity(credentials);
    verify(builder).putProperty(AwsV4HttpSigner.REGION_NAME, region);
    verify(builder).putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, service);
    ArgumentCaptor<SdkHttpRequest> request = ArgumentCaptor.forClass(SdkHttpRequest.class);
    verify(builder).request(request.capture());
    assertEquals(SdkHttpMethod.POST, request.getValue().method());
    assertEquals(URI.create(endpoint), request.getValue().getUri());
    assertEquals(
        List.of("application/x-protobuf"), request.getValue().headers().get("Content-Type"));
    ArgumentCaptor<ContentStreamProvider> payload =
        ArgumentCaptor.forClass(ContentStreamProvider.class);
    verify(builder).payload(payload.capture());
    try (java.io.InputStream input = payload.getValue().newStream()) {
      return input.readAllBytes();
    }
  }

  private void assertSigningHeaders(CapturedRequest request) {
    signedRequest
        .request()
        .headers()
        .forEach((name, values) -> assertEquals(values, request.headers.get(name)));
    assertTrue(
        request
            .headers
            .get(AUTHORIZATION_HEADER)
            .get(0)
            .contains("/" + region + "/" + service + "/aws4_request"));
  }

  private void assertOtlpRequest(int index, Collection<T> data) throws Exception {
    CapturedRequest request = requests.get(index);
    assertEquals(URI.create(endpoint), request.endpoint);
    assertEquals("application/x-protobuf", request.contentType);
    assertTrue(request.payload.length > 0);
    byte[] payload = request.payload;
    if ("gzip".equals(request.encoding)) {
      try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
        payload = gzip.readAllBytes();
      }
    } else {
      assertEquals("", request.encoding);
    }
    validateOtlpPayload(payload, data);
  }

  protected static void assertResource(io.opentelemetry.proto.resource.v1.Resource actual) {
    assertAttributes(TEST_RESOURCE.getAttributes(), actual.getAttributesList());
    assertEquals(0, actual.getDroppedAttributesCount());
  }

  protected static void assertScope(
      InstrumentationScopeInfo expected,
      io.opentelemetry.proto.common.v1.InstrumentationScope actual) {
    assertEquals(expected.getName(), actual.getName());
    assertEquals(expected.getVersion(), actual.getVersion());
  }

  protected static void assertAttributes(Attributes expected, List<KeyValue> actual) {
    Map<String, Object> expectedValues = new HashMap<>();
    expected.forEach((key, value) -> expectedValues.put(key.getKey(), value));
    Map<String, Object> actualValues = new HashMap<>();
    for (KeyValue attribute : actual) {
      Object value;
      switch (attribute.getValue().getValueCase()) {
        case STRING_VALUE:
          value = attribute.getValue().getStringValue();
          break;
        case INT_VALUE:
          value = attribute.getValue().getIntValue();
          break;
        case BOOL_VALUE:
          value = attribute.getValue().getBoolValue();
          break;
        case DOUBLE_VALUE:
          value = attribute.getValue().getDoubleValue();
          break;
        default:
          throw new AssertionError("Unexpected attribute type: " + attribute);
      }
      assertNull(
          actualValues.put(attribute.getKey(), value), "duplicate attribute " + attribute.getKey());
    }
    assertEquals(expectedValues, actualValues);
  }

  private static Stream<Arguments> compressionCases() {
    return Stream.of(
        Arguments.of(null, ""),
        Arguments.of("gzip", "gzip"),
        Arguments.of("GZIP", "gzip"),
        Arguments.of("unknown", ""));
  }

  private SignedRequest createSignedRequest(int index) {
    return SignedRequest.builder()
        .request(
            SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(URI.create(endpoint))
                .putHeader(
                    AUTHORIZATION_HEADER,
                    "AWS4-HMAC-SHA256 Credential="
                        + credentials.accessKeyId()
                        + "/20260101/"
                        + region
                        + "/"
                        + service
                        + "/aws4_request, Signature="
                        + String.format("%064x", index))
                .putHeader(X_AMZ_DATE_HEADER, String.format("20260101T0000%02dZ", index))
                .putHeader(
                    X_AMZ_SECURITY_TOKEN_HEADER,
                    ((AwsSessionCredentials) credentials).sessionToken())
                .build())
        .build();
  }

  private static final class CapturedRequest {
    final URI endpoint;
    final String contentType;
    final String encoding;
    final Map<String, List<String>> headers;
    final byte[] payload;

    CapturedRequest(
        URI endpoint,
        String contentType,
        String encoding,
        Map<String, List<String>> headers,
        byte[] payload) {
      this.endpoint = endpoint;
      this.contentType = contentType;
      this.encoding = encoding;
      this.headers = headers;
      this.payload = payload;
    }
  }
}
