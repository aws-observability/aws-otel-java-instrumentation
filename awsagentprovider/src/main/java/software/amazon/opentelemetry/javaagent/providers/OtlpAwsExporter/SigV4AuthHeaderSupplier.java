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

package software.amazon.opentelemetry.javaagent.providers.OtlpAwsExporter;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;

final class SigV4AuthHeaderSupplier<SignalDataType> implements Supplier<Map<String, String>> {
  AbstractOtlpAwsExporter<SignalDataType> exporter;
  Logger logger;

  public SigV4AuthHeaderSupplier(AbstractOtlpAwsExporter<SignalDataType> exporter) {
    this.exporter = exporter;
    this.logger = Logger.getLogger(exporter.getClass().getName());
  }

  @Override
  public Map<String, String> get() {
    try {
      byte[] data = exporter.data.get();

      SdkHttpRequest httpRequest =
          SdkHttpFullRequest.builder()
              .uri(URI.create(exporter.endpoint))
              .method(SdkHttpMethod.POST)
              .putHeader("Content-Type", "application/x-protobuf")
              .contentStreamProvider(() -> new ByteArrayInputStream(data))
              .build();

      AwsCredentials credentials = DefaultCredentialsProvider.create().resolveCredentials();

      SignedRequest signedRequest =
          AwsV4HttpSigner.create()
              .sign(
                  b ->
                      b.identity(credentials)
                          .request(httpRequest)
                          .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, exporter.serviceName())
                          .putProperty(AwsV4HttpSigner.REGION_NAME, exporter.awsRegion)
                          .payload(() -> new ByteArrayInputStream(data)));

      Map<String, String> result = new HashMap<>();

      Map<String, List<String>> headers = signedRequest.request().headers();
      headers.forEach(
          (key, values) -> {
            if (!values.isEmpty()) {
              result.put(key, values.get(0));
            }
          });

      return result;

    } catch (Exception e) {
      logger.log(
          Level.WARNING,
          String.format(
              "Failed to sign/authenticate the given exported Span request to OTLP CloudWatch endpoint with error: %s",
              e.getMessage()));

      return Collections.emptyMap();
    }
  }
}
