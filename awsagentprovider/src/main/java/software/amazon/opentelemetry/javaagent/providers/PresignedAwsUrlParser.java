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

import static io.opentelemetry.semconv.HttpAttributes.HTTP_REQUEST_METHOD;
import static io.opentelemetry.semconv.UrlAttributes.URL_FULL;
import static io.opentelemetry.semconv.incubating.HttpIncubatingAttributes.HTTP_METHOD;
import static io.opentelemetry.semconv.incubating.HttpIncubatingAttributes.HTTP_URL;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.getKeyValueWithFallback;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.isKeyPresentWithFallback;

import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Recognizes a SigV4/SigV4a presigned AWS URL from a span's URL.
 *
 * <p>Detection relies only on non-sensitive signals. A presigned (query-authenticated) request
 * carries all six SigV4 query parameters: {@code X-Amz-Algorithm}, {@code X-Amz-Credential}, {@code
 * X-Amz-Signature}, {@code X-Amz-Date}, {@code X-Amz-Expires}, and {@code X-Amz-SignedHeaders}. Of
 * these, only the {@code X-Amz-Algorithm} value is inspected (against an allowlist); {@code
 * X-Amz-Credential} and {@code X-Amz-Signature} are required to be present with a non-empty value
 * but their values are never read, because the agent's default URL sanitization replaces them with
 * {@code REDACTED} before metric attribution runs. {@code X-Amz-Date}, {@code X-Amz-Expires}, and
 * {@code X-Amz-SignedHeaders} are never redacted, so requiring them provides cheap verification.
 * The signing service is identified downstream from the endpoint hostname, not from the credential
 * scope.
 */
final class PresignedAwsUrlParser {
  private static final String X_AMZ_ALGORITHM = "X-Amz-Algorithm";
  private static final String X_AMZ_CREDENTIAL = "X-Amz-Credential";
  private static final String X_AMZ_SIGNATURE = "X-Amz-Signature";
  private static final String X_AMZ_DATE = "X-Amz-Date";
  private static final String X_AMZ_EXPIRES = "X-Amz-Expires";
  private static final String X_AMZ_SIGNED_HEADERS = "X-Amz-SignedHeaders";
  private static final Set<String> SIGV4_ALGORITHMS =
      new HashSet<>(Arrays.asList("AWS4-HMAC-SHA256", "AWS4-ECDSA-P256-SHA256"));

  private PresignedAwsUrlParser() {}

  static Optional<PresignedAwsUrl> parse(SpanData span) {
    if (!isKeyPresentWithFallback(span, URL_FULL, HTTP_URL)) {
      return Optional.empty();
    }

    String url = getKeyValueWithFallback(span, URL_FULL, HTTP_URL);
    String httpMethod = getKeyValueWithFallback(span, HTTP_REQUEST_METHOD, HTTP_METHOD);
    return parse(url, httpMethod);
  }

  static Optional<PresignedAwsUrl> parse(String url, String httpMethod) {
    if (url == null || url.isEmpty()) {
      return Optional.empty();
    }

    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      return Optional.empty();
    }

    if (uri.getHost() == null || uri.getHost().isEmpty()) {
      return Optional.empty();
    }

    Map<String, List<String>> queryParameters = parseQueryParameters(uri.getRawQuery());
    if (!isPresignedSigV4Request(queryParameters)) {
      return Optional.empty();
    }

    return Optional.of(new PresignedAwsUrl(uri, Optional.ofNullable(httpMethod), queryParameters));
  }

  /**
   * A request is a presigned SigV4/SigV4a request when it carries the signing algorithm,
   * credential, and signature parameters together with the presigned query parameters that AWS
   * always includes ({@code X-Amz-Date}, {@code X-Amz-Expires}, {@code X-Amz-SignedHeaders}). Only
   * the algorithm value is inspected against an allowlist; the credential and signature must be
   * present with a value but the value itself is not read, because sanitization replaces it with a
   * non-empty {@code REDACTED}. Empty values are rejected as malformed. The date, expiry, and
   * signed-headers parameters are never redacted, so requiring them (non-empty) is a cheap way to
   * reject partial or spoofed URLs without reading any sensitive value.
   *
   * <p>Query-string (presigned) SigV4 authentication parameters are defined here: <a
   * href="https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html">Authenticating
   * Requests: Using Query Parameters (AWS Signature Version 4)</a>. Per <a
   * href="https://docs.aws.amazon.com/prescriptive-guidance/latest/presigned-url-best-practices/overview.html">Overview
   * of presigned URLs</a>, the {@code X-Amz-Expires} parameter is what distinguishes a presigned
   * URL from other signed requests.
   */
  private static boolean isPresignedSigV4Request(Map<String, List<String>> queryParameters) {
    Optional<String> algorithm = getFirstValue(queryParameters, X_AMZ_ALGORITHM);
    return algorithm.isPresent()
        && SIGV4_ALGORITHMS.contains(algorithm.get())
        && hasNonEmptyValue(queryParameters, X_AMZ_CREDENTIAL)
        && hasNonEmptyValue(queryParameters, X_AMZ_SIGNATURE)
        && hasNonEmptyValue(queryParameters, X_AMZ_DATE)
        && hasNonEmptyValue(queryParameters, X_AMZ_EXPIRES)
        && hasNonEmptyValue(queryParameters, X_AMZ_SIGNED_HEADERS);
  }

  private static boolean hasNonEmptyValue(Map<String, List<String>> queryParameters, String name) {
    return getFirstValue(queryParameters, name).filter(value -> !value.isEmpty()).isPresent();
  }

  private static Optional<String> getFirstValue(
      Map<String, List<String>> queryParameters, String name) {
    List<String> values = queryParameters.get(name);
    if (values == null || values.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(values.get(0));
  }

  private static Map<String, List<String>> parseQueryParameters(String rawQuery) {
    Map<String, List<String>> queryParameters = new HashMap<>();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return queryParameters;
    }

    String[] pairs = rawQuery.split("&");
    for (String pair : pairs) {
      int delimiterIndex = pair.indexOf('=');
      String name = delimiterIndex >= 0 ? pair.substring(0, delimiterIndex) : pair;
      String value = delimiterIndex >= 0 ? pair.substring(delimiterIndex + 1) : "";
      queryParameters.computeIfAbsent(decode(name), unused -> new ArrayList<>()).add(decode(value));
    }
    return queryParameters;
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
