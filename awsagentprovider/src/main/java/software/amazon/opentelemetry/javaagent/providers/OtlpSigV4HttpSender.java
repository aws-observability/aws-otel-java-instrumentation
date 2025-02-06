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

import static software.amazon.awssdk.http.auth.aws.internal.signer.util.SignerConstant.AUTHORIZATION;
import static software.amazon.awssdk.http.auth.aws.internal.signer.util.SignerConstant.HOST;
import static software.amazon.awssdk.http.auth.aws.internal.signer.util.SignerConstant.X_AMZ_CONTENT_SHA256;
import static software.amazon.awssdk.http.auth.aws.internal.signer.util.SignerConstant.X_AMZ_DATE;

import io.opentelemetry.exporter.internal.http.HttpSender.Response;
import io.opentelemetry.exporter.internal.marshal.Marshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.internal.DaemonThreadFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;

/**
 * This class represents a HTTP sender that sends data to a specified endpoint. It is used to send
 * data to an endpoint, e.g. an OTLP endpoint.
 */
public class OtlpSigV4HttpSender {
  private static final Logger logger = Logger.getLogger(OtlpSigV4HttpSender.class.getName());

  private final OkHttpClient client;
  private final HttpUrl url;
  // @Nullable private final Compressor compressor;
  private final boolean exportAsJson;
  private final Supplier<Map<String, List<String>>> headerSupplier;
  private final MediaType mediaType;

  private final AwsV4HttpSigner signer;
  private final AwsCredentialsProvider awsCredentialsProvider;
  private final String regionName = "us-east-1"; // TODO: Fix
  private final String serviceName = "xray"; // TODO: Fix

  public OtlpSigV4HttpSender(
      String endpoint,
      long timeoutNanos,
      long connectionTimeoutNanos,
      Supplier<Map<String, List<String>>> headerSupplier,
      boolean exportAsJson) {

    int callTimeoutMillis =
        (int) Math.min(Duration.ofNanos(timeoutNanos).toMillis(), Integer.MAX_VALUE);
    int connectTimeoutMillis =
        (int) Math.min(Duration.ofNanos(connectionTimeoutNanos).toMillis(), Integer.MAX_VALUE);
    Dispatcher dispatcher =
        new Dispatcher(
            new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new DaemonThreadFactory("okhttp-dispatch", false) // TODO, remove hardcodes
                ));
    OkHttpClient.Builder builder =
        new OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
            .callTimeout(Duration.ofMillis(callTimeoutMillis));

    this.client = builder.build();
    this.url = HttpUrl.get(endpoint);
    // this.compressor = compressor;
    this.exportAsJson = exportAsJson;
    this.mediaType =
        MediaType.get("application/json"); // TODO: Fix, make dynamic relative to exportAsJson input
    this.headerSupplier = headerSupplier;

    this.signer = AwsV4HttpSigner.create();
    this.awsCredentialsProvider = DefaultCredentialsProvider.create();
  }

  public CompletableResultCode shutdown() {
    client.dispatcher().cancelAll();
    client.dispatcher().executorService().shutdownNow();
    client.connectionPool().evictAll();
    return CompletableResultCode.ofSuccess();
  }

  public void send(
      Marshaler marshaler,
      int contentLength,
      Consumer<Response> onResponse,
      Consumer<Throwable> onError) {
    Request.Builder requestBuilder = new Request.Builder().url(url);

    Map<String, List<String>> headers = headerSupplier.get();
    if (headers != null) {
      headers.forEach(
          (key, values) -> values.forEach(value -> requestBuilder.addHeader(key, value)));
    }
    RequestBody body = new RawRequestBody(marshaler, exportAsJson, contentLength, mediaType);

    // TODO: Add RequestBody compression logic
    requestBuilder.post(body);

    Request req = requestBuilder.build();
    SdkHttpRequest sdkReq = SigV4Util.toSignableRequest(req);
    logger.info("SdkHttpRequest generated: " + sdkReq.toString());
    SignedRequest signedRequest =
        signer.sign(
            r ->
                r.identity(awsCredentialsProvider.resolveCredentials())
                    .request(sdkReq)
                    .payload(SigV4Util.toContentStream(req))
                    .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, this.serviceName)
                    .putProperty(AwsV4HttpSigner.REGION_NAME, this.regionName));

    logger.info(signedRequest.toString());

    requestBuilder.removeHeader(HOST);
    requestBuilder.addHeader(X_AMZ_DATE, SigV4Util.getSingleHeaderValue(signedRequest, X_AMZ_DATE));
    requestBuilder.addHeader(HOST, SigV4Util.getSingleHeaderValue(signedRequest, HOST));
    requestBuilder.addHeader(
        AUTHORIZATION, SigV4Util.getSingleHeaderValue(signedRequest, AUTHORIZATION));
    requestBuilder.addHeader(
        X_AMZ_CONTENT_SHA256, SigV4Util.getSingleHeaderValue(signedRequest, X_AMZ_CONTENT_SHA256));

    // Rebuild the original request using the signed request headers
    Request finalSignedReq = requestBuilder.build();
    logger.info("Signed request headers - X_AMZ_DATE: " + finalSignedReq.headers().get(X_AMZ_DATE));
    logger.info("Signed request headers - HOST: " + finalSignedReq.headers().get(HOST));
    logger.info(
        "Signed request headers - AUTHORIZATION: " + finalSignedReq.headers().get(AUTHORIZATION));
    logger.info(
        "Signed request headers - X_AMZ_CONTENT_SHA256: "
            + finalSignedReq.headers().get(X_AMZ_CONTENT_SHA256));

    client
        .newCall(finalSignedReq)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                onError.accept(e);
              }

              @Override
              public void onResponse(Call call, okhttp3.Response response) {
                try (ResponseBody body = response.body()) {
                  onResponse.accept(
                      new Response() {
                        @Nullable private byte[] bodyBytes;

                        @Override
                        public int statusCode() {
                          return response.code();
                        }

                        @Override
                        public String statusMessage() {
                          return response.message();
                        }

                        @Override
                        public byte[] responseBody() throws IOException {
                          if (bodyBytes == null) {
                            bodyBytes = body.bytes();
                          }
                          return bodyBytes;
                        }
                      });
                }
              }
            });
  }

  private static class RawRequestBody extends RequestBody {

    private final Marshaler marshaler;
    private final boolean exportAsJson;
    private final int contentLength;
    private final MediaType mediaType;

    private RawRequestBody(
        Marshaler marshaler, boolean exportAsJson, int contentLength, MediaType mediaType) {
      this.marshaler = marshaler;
      this.exportAsJson = exportAsJson;
      this.contentLength = contentLength;
      this.mediaType = mediaType;
    }

    @Override
    public long contentLength() {
      return contentLength;
    }

    @Override
    public MediaType contentType() {
      return mediaType;
    }

    @Override
    public void writeTo(BufferedSink bufferedSink) throws IOException {
      if (exportAsJson) {
        marshaler.writeJsonTo(bufferedSink.outputStream());
      } else {
        marshaler.writeBinaryTo(bufferedSink.outputStream());
      }
    }
  }

  // Visible for testing
  String getUrl() {
    return url.toString();
  }
}
