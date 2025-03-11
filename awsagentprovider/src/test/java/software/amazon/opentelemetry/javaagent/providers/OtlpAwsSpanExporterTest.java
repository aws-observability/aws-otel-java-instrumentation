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

package software.amazon.opentelemetry.javaagent.providers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignRequest.Builder;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

@ExtendWith(MockitoExtension.class)
public class OtlpAwsSpanExporterTest {
  private static final String XRAY_OTLP_ENDPOINT = "https://xray.us-east-1.amazonaws.com/v1/traces";
  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String X_AMZ_DATE_HEADER = "X-Amz-Date";
  private static final String X_AMZ_SECURITY_TOKEN_HEADER = "X-Amz-Security-Token";

  private static final String EXPECTED_AUTH_HEADER =
      "AWS4-HMAC-SHA256 Credential=test_key/some_date/us-east-1/xray/aws4_request";
  private static final String EXPECTED_AUTH_X_AMZ_DATE = "some_date";
  private static final String EXPECTED_AUTH_SECURITY_TOKEN = "test_token";

  AwsCredentials credentials = AwsBasicCredentials.create("test_access_key", "test_secret_key");
  SignedRequest signedRequest =
      SignedRequest.builder()
          .request(
              SdkHttpFullRequest.builder()
                  .method(SdkHttpMethod.POST)
                  .uri(URI.create(XRAY_OTLP_ENDPOINT))
                  .putHeader(AUTHORIZATION_HEADER, EXPECTED_AUTH_HEADER)
                  .putHeader(X_AMZ_DATE_HEADER, EXPECTED_AUTH_X_AMZ_DATE)
                  .putHeader(X_AMZ_SECURITY_TOKEN_HEADER, EXPECTED_AUTH_SECURITY_TOKEN)
                  .build())
          .build();

  private MockedStatic<DefaultCredentialsProvider> mockDefaultCredentialsProvider;
  private MockedStatic<AwsV4HttpSigner> mockAwsV4HttpSigner;

  @Mock private DefaultCredentialsProvider credentialsProvider;
  @Mock private AwsV4HttpSigner signer;

  private ArgumentCaptor<Supplier<Map<String, String>>> headersCaptor;

  @BeforeEach
  void setup() {
    this.mockDefaultCredentialsProvider = mockStatic(DefaultCredentialsProvider.class);
    this.mockDefaultCredentialsProvider
        .when(DefaultCredentialsProvider::create)
        .thenReturn(credentialsProvider);

    this.mockAwsV4HttpSigner = mockStatic(AwsV4HttpSigner.class);
    this.mockAwsV4HttpSigner.when(AwsV4HttpSigner::create).thenReturn(this.signer);

    this.headersCaptor = ArgumentCaptor.forClass(Supplier.class);
  }

  @AfterEach
  void afterEach() {
    reset(this.signer, this.credentialsProvider);
    this.mockDefaultCredentialsProvider.close();
    this.mockAwsV4HttpSigner.close();
  }

  @Test
  void testAwsSpanExporterAddsSigV4Headers() {

    SpanExporter exporter = new OtlpAwsSpanExporter(XRAY_OTLP_ENDPOINT);
    when(this.credentialsProvider.resolveCredentials()).thenReturn(this.credentials);
    when(this.signer.sign((Consumer<Builder<AwsCredentialsIdentity>>) any()))
        .thenReturn(this.signedRequest);

    exporter.export(List.of());

    Map<String, String> headers = this.headersCaptor.getValue().get();

    assertTrue(headers.containsKey(X_AMZ_DATE_HEADER));
    assertTrue(headers.containsKey(AUTHORIZATION_HEADER));
    assertTrue(headers.containsKey(X_AMZ_SECURITY_TOKEN_HEADER));

    assertEquals(EXPECTED_AUTH_HEADER, headers.get(AUTHORIZATION_HEADER));
    assertEquals(EXPECTED_AUTH_X_AMZ_DATE, headers.get(X_AMZ_DATE_HEADER));
    assertEquals(EXPECTED_AUTH_SECURITY_TOKEN, headers.get(X_AMZ_SECURITY_TOKEN_HEADER));
  }

  @Test
  void testAwsSpanExporterExportCorrectlyAddsDifferentSigV4Headers() {
    SpanExporter exporter = new OtlpAwsSpanExporter(XRAY_OTLP_ENDPOINT);

    for (int i = 0; i < 10; i += 1) {
      String newAuthHeader = EXPECTED_AUTH_HEADER + i;
      String newXAmzDate = EXPECTED_AUTH_X_AMZ_DATE + i;
      String newXAmzSecurityToken = EXPECTED_AUTH_SECURITY_TOKEN + i;

      SignedRequest newSignedRequest =
          SignedRequest.builder()
              .request(
                  SdkHttpFullRequest.builder()
                      .method(SdkHttpMethod.POST)
                      .uri(URI.create(XRAY_OTLP_ENDPOINT))
                      .putHeader(AUTHORIZATION_HEADER, newAuthHeader)
                      .putHeader(X_AMZ_DATE_HEADER, newXAmzDate)
                      .putHeader(X_AMZ_SECURITY_TOKEN_HEADER, newXAmzSecurityToken)
                      .build())
              .build();

      when(this.credentialsProvider.resolveCredentials()).thenReturn(this.credentials);
      doReturn(newSignedRequest).when(this.signer).sign(any(Consumer.class));

      exporter.export(List.of());

      Map<String, String> headers = this.headersCaptor.getValue().get();

      assertTrue(headers.containsKey(X_AMZ_DATE_HEADER));
      assertTrue(headers.containsKey(AUTHORIZATION_HEADER));
      assertTrue(headers.containsKey(X_AMZ_SECURITY_TOKEN_HEADER));

      assertEquals(newAuthHeader, headers.get(AUTHORIZATION_HEADER));
      assertEquals(newXAmzDate, headers.get(X_AMZ_DATE_HEADER));
      assertEquals(newXAmzSecurityToken, headers.get(X_AMZ_SECURITY_TOKEN_HEADER));
    }
  }

  @Test
  void testAwsSpanExporterDoesNotAddSigV4HeadersIfFailureToRetrieveCredentials() {

    when(this.credentialsProvider.resolveCredentials())
        .thenThrow(SdkClientException.builder().message("bad credentials").build());

    SpanExporter exporter = new OtlpAwsSpanExporter(XRAY_OTLP_ENDPOINT);

    exporter.export(List.of());

    Supplier<Map<String, String>> headersSupplier = headersCaptor.getValue();
    Map<String, String> headers = headersSupplier.get();

    assertFalse(headers.containsKey(X_AMZ_DATE_HEADER));
    assertFalse(headers.containsKey(AUTHORIZATION_HEADER));
    assertFalse(headers.containsKey(X_AMZ_SECURITY_TOKEN_HEADER));

    verifyNoInteractions(this.signer);
  }

  @Test
  void testAwsSpanExporterDoesNotAddSigV4HeadersIfFailureToSignHeaders() {

    when(this.credentialsProvider.resolveCredentials()).thenReturn(this.credentials);
    when(this.signer.sign((Consumer<Builder<AwsCredentialsIdentity>>) any()))
        .thenThrow(SdkClientException.builder().message("bad signature").build());

    SpanExporter exporter = new OtlpAwsSpanExporter(XRAY_OTLP_ENDPOINT);

    exporter.export(List.of());

    Map<String, String> headers = this.headersCaptor.getValue().get();

    assertFalse(headers.containsKey(X_AMZ_DATE_HEADER));
    assertFalse(headers.containsKey(AUTHORIZATION_HEADER));
    assertFalse(headers.containsKey(X_AMZ_SECURITY_TOKEN_HEADER));
  }
}
