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

import static software.amazon.awssdk.http.auth.aws.internal.signer.util.SignerConstant.HOST;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import okhttp3.Request;
import okio.BufferedSink;
import okio.Okio;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;

public final class SigV4Util {
  private static final Logger logger = Logger.getLogger(SigV4Util.class.getName());

  private final AwsCredentialsProvider awsCredentialsProvider;
  private final AwsV4HttpSigner aws4Signer;

  private static final String region =
      "us-east-1"; // TODO: Make this dynamic based on the OTLP endpoint passed in

  SigV4Util() {
    this.awsCredentialsProvider = DefaultCredentialsProvider.create();
    this.aws4Signer = AwsV4HttpSigner.create();
  }

  // public Request sign(Request request) {
  //   try {
  //     ContentStreamProvider contentStreamProvider = toContentStream(request);

  //   }

  // }

  public static ContentStreamProvider toContentStream(final Request request) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BufferedSink sink = Okio.buffer(Okio.sink(baos));
    try {
      request.body().writeTo(sink);
    } catch (Exception e) {
      logger.warning("Failed to write request body to buffer");
      throw new RuntimeException(e);
    }
    try {
      sink.flush();
    } catch (IOException e) {
      logger.warning("Failed to flush request body buffer");
      throw new RuntimeException(e);
    }

    final byte[] body = baos.toByteArray();
    return (body.length != 0)
        ? ContentStreamProvider.fromByteArray(body)
        : ContentStreamProvider.fromUtf8String("");
  }

  public static SdkHttpRequest toSignableRequest(final Request request) {

    // make sure the request contains the minimal required set of information
    checkNotNull(request.url().toString(), "The request URI must not be null");
    checkNotNull(request.method(), "The request method must not be null");

    logger.info("Headers before: " + request.headers().toMultimap().toString());

    // convert the headers to the internal API format
    final Map<String, List<String>> headersInternal = request.headers().toMultimap();

    // we don't want to add the Host header as the Signer always adds the host header.
    headersInternal.remove(HOST);

    // convert the parameters to the internal API format
    URI uri;
    try {
      uri = new URI(request.url().toString());
    } catch (URISyntaxException e) {
      logger.warning("Failed to parse request URI");
      logger.info("URL: " + request.url().toString());
      uri = null;
    }
    // final Map<String, List<String>> parametersInternal =
    // extractParametersFromQueryString(uri.getQuery());

    if (uri == null) {
      return null;
    }
    final URI endpointUri = URI.create(uri.getScheme() + "://" + uri.getHost());

    // create the HTTP AWS SdkHttpRequest and carry over information
    return SdkHttpRequest.builder()
        .uri(endpointUri)
        .encodedPath(uri.getPath())
        .method(SdkHttpMethod.fromValue(request.method()))
        .headers(headersInternal)
        // .rawQueryParameters(parametersInternal)
        .build();
  }

  public static String getSingleHeaderValue(SignedRequest s, final String headerName) {
    final Map<String, List<String>> headers = s.request().headers();
    final Set<String> headerValues =
        new HashSet<>(
            headers.containsKey(headerName) ? headers.get(headerName) : Collections.emptySet());
    if (headerValues.size() != 1) {
      throw new IllegalArgumentException(
          String.format("Expected 1 header %s but found %d", headerName, headerValues.size()));
    }
    return headerValues.iterator().next();
  }

  private static void checkNotNull(final Object obj, final String errMsg) {
    if (obj == null) {
      throw new IllegalArgumentException(errMsg);
    }
  }
}
