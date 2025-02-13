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

import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.function.Supplier;
import javax.annotation.concurrent.Immutable;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;

/**
 *
 *
 * <p>This exporter is NOT meant for generic use since the payload is prefixed with AWS X-Ray
 * specific information.
 */
@Immutable
public class OtlpAwsSpanExporter implements SpanExporter {
  private static final Logger logger = LoggerFactory.getLogger(OtlpAwsSpanExporter.class);
  private static final AwsV4HttpSigner signer = AwsV4HttpSigner.create();

  private final OtlpHttpSpanExporter parentExporter;
  private final String awsRegion;
  private final String endpoint;
  private Collection<SpanData> spanData;

  public OtlpAwsSpanExporter(String endpoint) {
    this.parentExporter =
        OtlpHttpSpanExporter.builder()
            .setEndpoint(endpoint)
            .setHeaders(new SigV4AuthHeaderSupplier())
            .build();

    this.awsRegion = endpoint.split("\\.")[1];
    this.endpoint = endpoint;
    this.spanData = new ArrayList<>();
  }

  @Override
  public CompletableResultCode export(@NotNull Collection<SpanData> spans) {
    this.spanData = spans;
    return this.parentExporter.export(spans);
  }

  @Override
  public CompletableResultCode flush() {
    return this.parentExporter.flush();
  }

  @Override
  public CompletableResultCode shutdown() {
    return this.parentExporter.shutdown();
  }

  @Override
  public String toString() {
    return this.parentExporter.toString();
  }

  private final class SigV4AuthHeaderSupplier implements Supplier<Map<String, String>> {

    @Override
    public Map<String, String> get() {
      try {
        ByteArrayOutputStream encodedSpans = new ByteArrayOutputStream();
        TraceRequestMarshaler.create(OtlpAwsSpanExporter.this.spanData)
            .writeBinaryTo(encodedSpans);

        SdkHttpRequest httpRequest =
            SdkHttpFullRequest.builder()
                .uri(URI.create(OtlpAwsSpanExporter.this.endpoint))
                .method(SdkHttpMethod.POST)
                .putHeader("Content-Type", "application/x-protobuf")
                .contentStreamProvider(() -> new ByteArrayInputStream(encodedSpans.toByteArray()))
                .build();

        AwsCredentials credentials = DefaultCredentialsProvider.create().resolveCredentials();

        SignedRequest signedRequest =
            signer.sign(
                b ->
                    b.identity(credentials)
                        .request(httpRequest)
                        .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "xray")
                        .putProperty(
                            AwsV4HttpSigner.REGION_NAME, OtlpAwsSpanExporter.this.awsRegion)
                        .payload(() -> new ByteArrayInputStream(encodedSpans.toByteArray())));

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
        logger.error(
            "Failed to sign/authenticate the given exported Span request to OTLP CloudWatch endpoint with error: {}",
            e.getMessage());

        return new HashMap<>();
      }
    }
  }
}
