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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignRequest;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

class AwsAuthHeaderSupplierChinaTest {
  private static final String AUTHORIZATION = "Authorization";

  @ParameterizedTest
  @MethodSource("chinaEndpoints")
  void testSignsChinaEndpointWithExpectedRegionServiceAndUri(
      String endpoint, String expectedRegion, String expectedService) {
    DefaultCredentialsProvider credentialsProvider = mock(DefaultCredentialsProvider.class);
    AwsV4HttpSigner signer = mock(AwsV4HttpSigner.class);
    ArgumentCaptor<Consumer<SignRequest.Builder<AwsCredentialsIdentity>>> requestCaptor =
        ArgumentCaptor.forClass(Consumer.class);

    SignedRequest signedRequest =
        SignedRequest.builder()
            .request(
                SdkHttpFullRequest.builder()
                    .method(SdkHttpMethod.POST)
                    .uri(URI.create(endpoint))
                    .putHeader(AUTHORIZATION, "AWS4-HMAC-SHA256 test-signature")
                    .build())
            .build();

    when(credentialsProvider.resolveCredentials())
        .thenReturn(AwsBasicCredentials.create("test-access-key", "test-secret-key"));
    when(signer.sign(requestCaptor.capture())).thenReturn(signedRequest);

    try (MockedStatic<DefaultCredentialsProvider> credentialsProviderFactory =
            mockStatic(DefaultCredentialsProvider.class);
        MockedStatic<AwsV4HttpSigner> signerFactory = mockStatic(AwsV4HttpSigner.class)) {
      credentialsProviderFactory
          .when(DefaultCredentialsProvider::create)
          .thenReturn(credentialsProvider);
      signerFactory.when(AwsV4HttpSigner::create).thenReturn(signer);

      TestExporter exporter = new TestExporter(endpoint, expectedService);
      exporter.setData(new byte[] {1, 2, 3});
      Map<String, String> headers = exporter.headerSupplier.get();

      assertTrue(headers.containsKey(AUTHORIZATION));

      @SuppressWarnings("unchecked")
      SignRequest.Builder<AwsCredentialsIdentity> builder =
          mock(SignRequest.Builder.class, RETURNS_SELF);
      requestCaptor.getValue().accept(builder);

      verify(builder).putProperty(AwsV4HttpSigner.REGION_NAME, expectedRegion);
      verify(builder).putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, expectedService);
      ArgumentCaptor<SdkHttpRequest> httpRequestCaptor =
          ArgumentCaptor.forClass(SdkHttpRequest.class);
      verify(builder).request(httpRequestCaptor.capture());
      assertEquals(URI.create(endpoint), httpRequestCaptor.getValue().getUri());
    }
  }

  private static Stream<Arguments> chinaEndpoints() {
    return Stream.of(
        Arguments.of("https://xray.cn-north-1.amazonaws.com.cn/v1/traces", "cn-north-1", "xray"),
        Arguments.of(
            "https://xray.cn-northwest-1.amazonaws.com.cn/v1/traces", "cn-northwest-1", "xray"),
        Arguments.of("https://logs.cn-north-1.amazonaws.com.cn/v1/logs", "cn-north-1", "logs"),
        Arguments.of(
            "https://logs.cn-northwest-1.amazonaws.com.cn/v1/logs", "cn-northwest-1", "logs"),
        Arguments.of(
            "https://monitoring.cn-north-1.amazonaws.com.cn/v1/metrics",
            "cn-north-1",
            "monitoring"),
        Arguments.of(
            "https://monitoring.cn-northwest-1.amazonaws.com.cn/v1/metrics",
            "cn-northwest-1",
            "monitoring"));
  }

  private static final class TestExporter extends BaseOtlpAwsExporter {
    private final String serviceName;

    private TestExporter(String endpoint, String serviceName) {
      super(endpoint, CompressionMethod.NONE);
      this.serviceName = serviceName;
    }

    private void setData(byte[] payload) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      output.write(payload, 0, payload.length);
      data.set(output);
    }

    @Override
    public String serviceName() {
      return serviceName;
    }
  }
}
