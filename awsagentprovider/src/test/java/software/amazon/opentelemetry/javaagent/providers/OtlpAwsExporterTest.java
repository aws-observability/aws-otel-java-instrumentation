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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
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
import software.amazon.awssdk.http.auth.spi.signer.SignRequest;
import software.amazon.awssdk.http.auth.spi.signer.SignRequest.Builder;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.logs.OtlpAwsLogsExporterBuilder;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.traces.OtlpAwsSpanExporterBuilder;

@ExtendWith(MockitoExtension.class)
public abstract class OtlpAwsExporterTest {
  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String X_AMZ_DATE_HEADER = "X-Amz-Date";
  private static final String X_AMZ_SECURITY_TOKEN_HEADER = "X-Amz-Security-Token";

  private static final String EXPECTED_AUTH_HEADER =
      "AWS4-HMAC-SHA256 Credential=test_key/some_date/us-east-1/xray/aws4_request";
  private static final String EXPECTED_AUTH_X_AMZ_DATE = "some_date";
  private static final String EXPECTED_AUTH_SECURITY_TOKEN = "test_token";

  private AwsCredentials credentials;
  private SignedRequest signedRequest;
  private String endpoint;
  private OtlpAwsExporterWrapper builder;

  ArgumentCaptor<Supplier<Map<String, String>>> headersCaptor =
      ArgumentCaptor.forClass(Supplier.class);
  private MockedStatic<DefaultCredentialsProvider> mockDefaultCredentialsProvider;
  private MockedStatic<AwsV4HttpSigner> mockAwsV4HttpSigner;

  @Mock private DefaultCredentialsProvider credentialsProvider;
  @Mock private AwsV4HttpSigner signer;

  protected void init(String endpoint, OtlpAwsExporterWrapper builder) {
    this.endpoint = endpoint;
    this.builder = builder;
  }

  @BeforeEach
  void setup() {
    this.credentials = AwsBasicCredentials.create("test_access_key", "test_secret_key");
    this.signedRequest =
        SignedRequest.builder()
            .request(
                SdkHttpFullRequest.builder()
                    .method(SdkHttpMethod.POST)
                    .uri(URI.create(this.endpoint))
                    .putHeader(AUTHORIZATION_HEADER, EXPECTED_AUTH_HEADER)
                    .putHeader(X_AMZ_DATE_HEADER, EXPECTED_AUTH_X_AMZ_DATE)
                    .putHeader(X_AMZ_SECURITY_TOKEN_HEADER, EXPECTED_AUTH_SECURITY_TOKEN)
                    .build())
            .build();
    this.mockDefaultCredentialsProvider = mockStatic(DefaultCredentialsProvider.class);
    this.mockDefaultCredentialsProvider
        .when(DefaultCredentialsProvider::create)
        .thenReturn(credentialsProvider);

    this.mockAwsV4HttpSigner = mockStatic(AwsV4HttpSigner.class);
    this.mockAwsV4HttpSigner.when(AwsV4HttpSigner::create).thenReturn(this.signer);
  }

  @AfterEach
  void afterEach() {
    reset(this.signer, this.credentialsProvider);
    this.mockDefaultCredentialsProvider.close();
    this.mockAwsV4HttpSigner.close();
  }

  @Test
  void testAwsExporterAddsSigV4Headers() {
    when(this.credentialsProvider.resolveCredentials()).thenReturn(this.credentials);
    when(this.signer.sign((Consumer<SignRequest.Builder<AwsCredentialsIdentity>>) any()))
        .thenReturn(this.signedRequest);

    this.builder.export();

    Map<String, String> headers = this.headersCaptor.getValue().get();

    assertTrue(headers.containsKey(X_AMZ_DATE_HEADER));
    assertTrue(headers.containsKey(AUTHORIZATION_HEADER));
    assertTrue(headers.containsKey(X_AMZ_SECURITY_TOKEN_HEADER));

    assertEquals(EXPECTED_AUTH_HEADER, headers.get(AUTHORIZATION_HEADER));
    assertEquals(EXPECTED_AUTH_X_AMZ_DATE, headers.get(X_AMZ_DATE_HEADER));
    assertEquals(EXPECTED_AUTH_SECURITY_TOKEN, headers.get(X_AMZ_SECURITY_TOKEN_HEADER));
  }

  @Test
  void testAwsExporterExportCorrectlyAddsDifferentSigV4Headers() {
    for (int i = 0; i < 10; i += 1) {
      String newAuthHeader = EXPECTED_AUTH_HEADER + i;
      String newXAmzDate = EXPECTED_AUTH_X_AMZ_DATE + i;
      String newXAmzSecurityToken = EXPECTED_AUTH_SECURITY_TOKEN + i;

      SignedRequest newSignedRequest =
          SignedRequest.builder()
              .request(
                  SdkHttpFullRequest.builder()
                      .method(SdkHttpMethod.POST)
                      .uri(URI.create(this.endpoint))
                      .putHeader(AUTHORIZATION_HEADER, newAuthHeader)
                      .putHeader(X_AMZ_DATE_HEADER, newXAmzDate)
                      .putHeader(X_AMZ_SECURITY_TOKEN_HEADER, newXAmzSecurityToken)
                      .build())
              .build();

      when(this.credentialsProvider.resolveCredentials()).thenReturn(this.credentials);
      doReturn(newSignedRequest).when(this.signer).sign(any(Consumer.class));

      this.builder.export();

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
  void testAwsExporterDoesNotAddSigV4HeadersIfFailureToRetrieveCredentials() {

    when(this.credentialsProvider.resolveCredentials())
        .thenThrow(SdkClientException.builder().message("bad credentials").build());

    this.builder.export();

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

    builder.export();

    Map<String, String> headers = this.headersCaptor.getValue().get();

    assertFalse(headers.containsKey(X_AMZ_DATE_HEADER));
    assertFalse(headers.containsKey(AUTHORIZATION_HEADER));
    assertFalse(headers.containsKey(X_AMZ_SECURITY_TOKEN_HEADER));
  }

  static class OtlpAwsSpanExporterTest extends OtlpAwsExporterTest {
    private static final String XRAY_OTLP_ENDPOINT =
        "https://xray.us-east-1.amazonaws.com/v1/traces";

    @Mock private OtlpHttpSpanExporterBuilder mockBuilder;
    @Mock private OtlpHttpSpanExporter mockExporter;

    @BeforeEach
    @Override
    void setup() {
      when(this.mockExporter.toBuilder()).thenReturn(mockBuilder);
      when(this.mockBuilder.setEndpoint(any())).thenReturn(mockBuilder);
      when(this.mockBuilder.setMemoryMode(any())).thenReturn(this.mockBuilder);
      when(this.mockBuilder.setHeaders(this.headersCaptor.capture())).thenReturn(mockBuilder);
      when(this.mockBuilder.build()).thenReturn(this.mockExporter);
      OtlpAwsExporterWrapper mocker = new MockOtlpAwsSpanExporterWrapper(this.mockExporter);
      this.init(XRAY_OTLP_ENDPOINT, mocker);
      super.setup();
      when(this.mockExporter.export(any())).thenReturn(CompletableResultCode.ofSuccess());
    }

    private static final class MockOtlpAwsSpanExporterWrapper implements OtlpAwsExporterWrapper {
      private final SpanExporter exporter;

      private MockOtlpAwsSpanExporterWrapper(OtlpHttpSpanExporter mockExporter) {
        this.exporter =
            OtlpAwsSpanExporterBuilder.create(
                    mockExporter, OtlpAwsSpanExporterTest.XRAY_OTLP_ENDPOINT)
                .build();
      }

      @Override
      public void export() {
        this.exporter.export(List.of());
      }
    }
  }

  static class OtlpAwsLogsExporterTest extends OtlpAwsExporterTest {
    private static final String LOGS_OTLP_ENDPOINT = "https://logs.us-east-1.amazonaws.com/v1/logs";

    @Mock private OtlpHttpLogRecordExporterBuilder mockBuilder;
    @Mock private OtlpHttpLogRecordExporter mockExporter;

    @BeforeEach
    @Override
    void setup() {
      when(this.mockExporter.toBuilder()).thenReturn(mockBuilder);
      when(this.mockBuilder.setEndpoint(any())).thenReturn(mockBuilder);
      when(this.mockBuilder.setMemoryMode(any())).thenReturn(this.mockBuilder);
      when(this.mockBuilder.setHeaders(this.headersCaptor.capture())).thenReturn(mockBuilder);
      when(this.mockBuilder.build()).thenReturn(this.mockExporter);
      OtlpAwsExporterWrapper mocker = new MockOtlpAwsLogsExporterWrapper(this.mockExporter);
      this.init(LOGS_OTLP_ENDPOINT, mocker);
      super.setup();
      when(this.mockExporter.export(any())).thenReturn(CompletableResultCode.ofSuccess());
    }

    private static final class MockOtlpAwsLogsExporterWrapper implements OtlpAwsExporterWrapper {
      private final LogRecordExporter exporter;

      private MockOtlpAwsLogsExporterWrapper(OtlpHttpLogRecordExporter mockExporter) {
        this.exporter =
            OtlpAwsLogsExporterBuilder.create(
                    mockExporter, OtlpAwsLogsExporterTest.LOGS_OTLP_ENDPOINT)
                .build();
      }

      @Override
      public void export() {
        this.exporter.export(List.of());
      }
    }
  }
}
