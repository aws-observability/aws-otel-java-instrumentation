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

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.opentelemetry.javaagent.providers.PresignedUrlAttributor.PresignedUrlAttribution;

/**
 * S3 attribution is driven purely by the endpoint hostname (the signing service cannot be read from
 * the redacted credential scope). Tests use realistic sanitized URLs (redacted credential and
 * signature).
 */
class S3PresignedUrlAttributorTest {

  /**
   * Every supported bucket endpoint form resolves to the bucket. Covers virtual-hosted and
   * path-style, plus regional, legacy regional, dual-stack, transfer acceleration, FIPS (incl. FIPS
   * dual-stack), and China. Access points and other specialized endpoints are intentionally
   * excluded (see the fail-closed cases). References:
   *
   * <ul>
   *   <li>https://docs.aws.amazon.com/general/latest/gr/s3.html
   *   <li>https://docs.aws.amazon.com/AmazonS3/latest/userguide/VirtualHosting.html
   *   <li>https://docs.aws.amazon.com/AmazonS3/latest/userguide/dual-stack-endpoints.html
   *   <li>https://docs.aws.amazon.com/AmazonS3/latest/userguide/transfer-acceleration.html
   * </ul>
   */
  @ParameterizedTest
  @CsvSource({
    // Virtual-hosted style: host, path, expected bucket
    "example-bucket.s3.amazonaws.com,/object,example-bucket",
    "example-bucket.s3.us-west-2.amazonaws.com,/object,example-bucket",
    "example-bucket.s3-us-west-2.amazonaws.com,/object,example-bucket",
    "example.s3.bucket.s3.us-west-2.amazonaws.com,/object,example.s3.bucket",
    "example-bucket.s3.cn-north-1.amazonaws.com.cn,/object,example-bucket",
    "example-bucket.s3.dualstack.us-west-2.amazonaws.com,/object,example-bucket",
    "example-bucket.s3-accelerate.amazonaws.com,/object,example-bucket",
    "example-bucket.s3-accelerate.dualstack.amazonaws.com,/object,example-bucket",
    "example-bucket.s3-fips.us-west-2.amazonaws.com,/object,example-bucket",
    "example-bucket.s3-fips.dualstack.us-east-1.amazonaws.com,/object,example-bucket",
    // Path-style: bucket is the first path segment
    "s3.amazonaws.com,/example-bucket/object,example-bucket",
    "s3.us-west-2.amazonaws.com,/example-bucket/object,example-bucket",
    "s3.cn-north-1.amazonaws.com.cn,/example-bucket/object,example-bucket",
    "s3-fips.us-west-2.amazonaws.com,/example-bucket/object,example-bucket",
    "s3-fips.dualstack.us-east-1.amazonaws.com,/example-bucket/object,example-bucket",
  })
  void resolvesBucketForEndpointVariant(String host, String path, String expectedBucket) {
    PresignedUrlAttribution attribution = attribute(presignedUrl("GET", host, path));

    assertThat(attribution.getRemoteService()).isEqualTo("AWS::S3");
    assertThat(attribution.getRemoteResource()).isPresent();
    assertThat(attribution.getRemoteResource().get().getType()).isEqualTo("AWS::S3::Bucket");
    assertThat(attribution.getRemoteResource().get().getIdentifier()).isEqualTo(expectedBucket);
  }

  /**
   * The remote operation is derived from the HTTP method, whether an object key is present (bucket-
   * vs object-level), and the S3 subresource/multipart query parameters. See the S3 REST API:
   * https://docs.aws.amazon.com/AmazonS3/latest/API/API_Operations.html
   */
  @ParameterizedTest
  @CsvSource({
    // method, path, extra query params, expected operation
    "GET,/object,'',GetObject",
    "PUT,/object,'',PutObject",
    "HEAD,/object,'',HeadObject",
    "DELETE,/object,'',DeleteObject",
    "PATCH,/object,'',UnknownRemoteOperation",
    // ListObjectsV2 is bucket-level only
    "GET,/,&list-type=2,ListObjectsV2",
    "GET,/object,&list-type=2,GetObject",
    "PUT,/object,&list-type=2,PutObject",
    // Multipart
    "PUT,/object,&partNumber=1&uploadId=upload,UploadPart",
    "PUT,/object,&uploadId=upload,PutObject",
    "GET,/object,&uploadId=upload,ListParts",
    "POST,/object,&uploadId=upload,CompleteMultipartUpload",
    "DELETE,/object,&uploadId=upload,AbortMultipartUpload",
    "POST,/object,&uploads,CreateMultipartUpload",
    "GET,/,&uploads,ListMultipartUploads",
    "GET,/object,&uploads,GetObject",
    // ACL / tagging (object- and bucket-level)
    "GET,/object,&acl,GetObjectAcl",
    "PUT,/object,&acl,PutObjectAcl",
    "GET,/,&acl,GetBucketAcl",
    "PUT,/,&acl,PutBucketAcl",
    "GET,/object,&tagging,GetObjectTagging",
    "PUT,/object,&tagging,PutObjectTagging",
    "DELETE,/object,&tagging,DeleteObjectTagging",
    "GET,/,&tagging,GetBucketTagging",
    "PUT,/,&tagging,PutBucketTagging",
    "DELETE,/,&tagging,DeleteBucketTagging",
    // Object-only subresources
    "GET,/object,&retention,GetObjectRetention",
    "PUT,/object,&retention,PutObjectRetention",
    "GET,/object,&legal-hold,GetObjectLegalHold",
    "PUT,/object,&legal-hold,PutObjectLegalHold",
    "GET,/object,&torrent,GetObjectTorrent",
  })
  void resolvesOperation(String method, String path, String extraQuery, String expectedOperation) {
    assertThat(
            attribute(
                    presignedUrl(
                        method, "example-bucket.s3.us-west-2.amazonaws.com", path, extraQuery))
                .getRemoteOperation())
        .isEqualTo(expectedOperation);
  }

  /**
   * Path-style operation detection: the first path segment is the bucket, so {@code hasObjectKey}
   * (and thus bucket- vs object-level classification) is computed differently than for
   * virtual-hosted hosts.
   */
  @ParameterizedTest
  @CsvSource({
    // method, path, extra query params, expected operation
    "GET,/example-bucket,&list-type=2,ListObjectsV2",
    "GET,/example-bucket/object,'',GetObject",
    "DELETE,/example-bucket/object,'',DeleteObject",
    "GET,/example-bucket,&acl,GetBucketAcl",
    "GET,/example-bucket/object,&acl,GetObjectAcl",
  })
  void resolvesPathStyleOperation(
      String method, String path, String extraQuery, String expectedOperation) {
    assertThat(
            attribute(presignedUrl(method, "s3.us-west-2.amazonaws.com", path, extraQuery))
                .getRemoteOperation())
        .isEqualTo(expectedOperation);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Access point host (bucket not identifiable from the endpoint form)
        "example-bucket.s3-accesspoint.us-west-2.amazonaws.com",
        // Custom CNAME
        "s3.mycompany.com",
        // Non-S3 AWS service endpoint
        "sqs.us-west-2.amazonaws.com",
      })
  void failsClosedForUnrecognizedEndpoint(String host) {
    assertThat(S3PresignedUrlAttributor.attribute(presignedUrl("GET", host, "/object"))).isEmpty();
  }

  @Test
  void usesUnknownOperationForAmbiguousBucketOperation() {
    PresignedUrlAttribution attribution =
        attribute(presignedUrl("GET", "example-bucket.s3.us-west-2.amazonaws.com", "/"));

    assertThat(attribution.getRemoteService()).isEqualTo("AWS::S3");
    assertThat(attribution.getRemoteOperation()).isEqualTo("UnknownRemoteOperation");
    assertThat(attribution.getRemoteResource()).isPresent();
  }

  @Test
  void missingHttpMethodUsesUnknownOperation() {
    PresignedUrlAttribution attribution =
        attribute(presignedUrl(null, "example-bucket.s3.us-west-2.amazonaws.com", "/object"));

    assertThat(attribution.getRemoteService()).isEqualTo("AWS::S3");
    assertThat(attribution.getRemoteOperation()).isEqualTo("UnknownRemoteOperation");
  }

  @Test
  void pathStyleWithoutBucketAttributesS3WithoutResource() {
    PresignedUrlAttribution attribution =
        attribute(presignedUrl("GET", "s3.us-west-2.amazonaws.com", "/"));

    assertThat(attribution.getRemoteService()).isEqualTo("AWS::S3");
    assertThat(attribution.getRemoteResource()).isEmpty();
  }

  /** Unwraps an attribution expected to be present. */
  private static PresignedUrlAttribution attribute(PresignedAwsUrl url) {
    Optional<PresignedUrlAttribution> attribution = S3PresignedUrlAttributor.attribute(url);
    assertThat(attribution).isPresent();
    return attribution.get();
  }

  private static PresignedAwsUrl presignedUrl(String method, String host, String path) {
    return presignedUrl(method, host, path, "");
  }

  /** Builds a presigned URL with sanitized (redacted) credential and signature values. */
  private static PresignedAwsUrl presignedUrl(
      String method, String host, String path, String extraQueryParameters) {
    return PresignedAwsUrlParser.parse(
            "https://"
                + host
                + path
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=REDACTED"
                + "&X-Amz-Signature=REDACTED"
                + "&X-Amz-Date=20260710T120000Z"
                + "&X-Amz-Expires=3600"
                + "&X-Amz-SignedHeaders=host"
                + extraQueryParameters,
            method)
        .get();
  }
}
