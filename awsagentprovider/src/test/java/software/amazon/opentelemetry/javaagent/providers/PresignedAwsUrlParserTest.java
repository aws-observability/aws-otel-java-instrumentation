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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The parser detects presigned SigV4/SigV4a requests from non-sensitive signals only. It must work
 * with the agent's default URL sanitization, which replaces the {@code X-Amz-Credential} and {@code
 * X-Amz-Signature} values with {@code REDACTED}; therefore these tests use redacted values. The
 * non-redacted presigned parameters ({@code X-Amz-Date}, {@code X-Amz-Expires}, {@code
 * X-Amz-SignedHeaders}) are required, so valid URLs include them.
 */
class PresignedAwsUrlParserTest {

  private static final String OBJECT_URL =
      "https://example-bucket.s3.us-west-2.amazonaws.com/object";
  private static final String CREDENTIAL_AND_SIGNATURE =
      "&X-Amz-Credential=REDACTED&X-Amz-Signature=REDACTED";
  private static final String PRESIGN_PARAMS =
      "&X-Amz-Date=20260710T120000Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host";

  @Test
  void detectsSigV4PresignedRequest() {
    Optional<PresignedAwsUrl> parsed =
        PresignedAwsUrlParser.parse(
            presignedUrl("example-bucket.s3.us-west-2.amazonaws.com", "/photos/seed.jpg"), "GET");

    assertThat(parsed).isPresent();
    assertThat(parsed.get().getHttpMethod()).contains("GET");
    assertThat(parsed.get().getHost()).isEqualTo("example-bucket.s3.us-west-2.amazonaws.com");
    assertThat(parsed.get().getPath()).isEqualTo("/photos/seed.jpg");
  }

  @Test
  void detectsSigV4aPresignedRequest() {
    assertThat(
            PresignedAwsUrlParser.parse(
                presignedUrl(
                    "example-bucket.s3.amazonaws.com", "/object", "AWS4-ECDSA-P256-SHA256"),
                "GET"))
        .isPresent();
  }

  @Test
  void detectsRequestWithNonRedactedCredentialAndSignature() {
    // Detection must also work before sanitization (e.g. when redaction is disabled), where the
    // credential and signature carry real values.
    Optional<PresignedAwsUrl> parsed =
        PresignedAwsUrlParser.parse(
            "https://example-bucket.s3.us-west-2.amazonaws.com/object"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=AKIAEXAMPLE%2F20260710%2Fus-west-2%2Fs3%2Faws4_request"
                + "&X-Amz-Signature=1234567890abcdef"
                + PRESIGN_PARAMS,
            "GET");

    assertThat(parsed).isPresent();
  }

  @Test
  void parsesUrlWithValuelessQueryParameterAndEmptyPath() {
    Optional<PresignedAwsUrl> parsed =
        PresignedAwsUrlParser.parse(
            "https://example-bucket.s3.amazonaws.com"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + CREDENTIAL_AND_SIGNATURE
                + PRESIGN_PARAMS
                + "&x-id",
            "GET");

    assertThat(parsed).isPresent();
    assertThat(parsed.get().getPath()).isEqualTo("/");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedOrNonPresignedUrls")
  void rejectsMalformedOrNonPresignedUrl(String description, String url) {
    assertThat(PresignedAwsUrlParser.parse(url, "GET")).isEmpty();
  }

  private static Stream<Arguments> malformedOrNonPresignedUrls() {
    return Stream.of(
        arguments("null url", null),
        arguments("empty url", ""),
        arguments("unparseable uri", "http://exa mple.com/object"),
        arguments("plain url without SigV4 parameters", "https://example.com/object"),
        arguments(
            "cloudfront signed url",
            "https://d111111abcdef8.cloudfront.net/image.jpg"
                + "?Policy=policy&Signature=sig&Key-Pair-Id=key"),
        arguments(
            "missing algorithm",
            OBJECT_URL + "?" + CREDENTIAL_AND_SIGNATURE.substring(1) + PRESIGN_PARAMS),
        arguments(
            "unsupported algorithm",
            OBJECT_URL + "?X-Amz-Algorithm=AWS5-FAKE" + CREDENTIAL_AND_SIGNATURE + PRESIGN_PARAMS),
        arguments(
            "missing credential",
            OBJECT_URL
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=REDACTED"
                + PRESIGN_PARAMS),
        arguments(
            "missing signature",
            OBJECT_URL
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=REDACTED"
                + PRESIGN_PARAMS),
        arguments(
            "empty credential",
            OBJECT_URL
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=&X-Amz-Signature=REDACTED"
                + PRESIGN_PARAMS),
        arguments(
            "empty signature",
            OBJECT_URL
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=REDACTED&X-Amz-Signature="
                + PRESIGN_PARAMS),
        arguments(
            "missing date",
            OBJECT_URL
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + CREDENTIAL_AND_SIGNATURE
                + "&X-Amz-Expires=3600&X-Amz-SignedHeaders=host"),
        arguments(
            "missing expires",
            OBJECT_URL
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + CREDENTIAL_AND_SIGNATURE
                + "&X-Amz-Date=20260710T120000Z&X-Amz-SignedHeaders=host"),
        arguments(
            "missing signed headers",
            OBJECT_URL
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + CREDENTIAL_AND_SIGNATURE
                + "&X-Amz-Date=20260710T120000Z&X-Amz-Expires=3600"),
        arguments(
            "empty presigned parameter value",
            OBJECT_URL
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + CREDENTIAL_AND_SIGNATURE
                + "&X-Amz-Date=&X-Amz-Expires=3600&X-Amz-SignedHeaders=host"),
        arguments(
            "url without host",
            "/object?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + CREDENTIAL_AND_SIGNATURE
                + PRESIGN_PARAMS));
  }

  private static String presignedUrl(String host, String path) {
    return presignedUrl(host, path, "AWS4-HMAC-SHA256");
  }

  /** Builds a presigned URL with sanitized (redacted) credential and signature values. */
  private static String presignedUrl(String host, String path, String algorithm) {
    return "https://"
        + host
        + path
        + "?X-Amz-Algorithm="
        + algorithm
        + CREDENTIAL_AND_SIGNATURE
        + PRESIGN_PARAMS;
  }
}
