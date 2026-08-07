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

import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.UNKNOWN_REMOTE_OPERATION;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.opentelemetry.javaagent.providers.PresignedUrlAttributor.PresignedUrlAttribution;
import software.amazon.opentelemetry.javaagent.providers.PresignedUrlAttributor.RemoteResource;

/**
 * Derives {@code AWS::S3} attribution from a presigned S3 URL by recognizing S3 endpoint hostnames.
 *
 * <p>Because the signing service cannot be read from the (redacted) credential scope, S3 is
 * identified purely from the endpoint host. Only the standard virtual-hosted and path-style S3
 * endpoint forms are recognized. Anything else — custom CNAMEs, access points, unknown endpoints —
 * fails closed (returns empty) so we never mis-attribute a non-S3 or unverifiable request.
 *
 * <p>The remote operation is derived from the HTTP method, whether an object key is present
 * (bucket- vs object-level), and the S3 subresource/multipart query parameters. Operation names
 * follow the S3 REST API. References:
 *
 * <ul>
 *   <li>Endpoints: https://docs.aws.amazon.com/general/latest/gr/s3.html
 *   <li>Virtual-hosted vs path-style:
 *       https://docs.aws.amazon.com/AmazonS3/latest/userguide/VirtualHosting.html
 *   <li>S3 REST API operations: https://docs.aws.amazon.com/AmazonS3/latest/API/API_Operations.html
 * </ul>
 */
final class S3PresignedUrlAttributor {
  private static final String NORMALIZED_S3_SERVICE_NAME = "AWS::S3";
  private static final String S3_BUCKET_RESOURCE_TYPE = NORMALIZED_S3_SERVICE_NAME + "::Bucket";

  // Standard S3 endpoint host forms, including global, regional, legacy regional, dual-stack,
  // transfer acceleration, FIPS (incl. FIPS dual-stack), and China (.com.cn). The optional segment
  // after "s3" covers the mutually exclusive endpoint styles.
  //
  // The legacy "-<label>" alternative is intentionally broad: besides legacy regional hosts
  // (s3-us-west-2) it also matches other s3-prefixed AWS hosts such as s3-website-<region>. This is
  // accepted deliberately as low risk — all such hosts are S3-owned domains anchored to
  // amazonaws.com, and presigned object requests do not target website/other endpoints.
  // https://docs.aws.amazon.com/general/latest/gr/s3.html
  // https://docs.aws.amazon.com/AmazonS3/latest/userguide/dual-stack-endpoints.html
  private static final String S3_ENDPOINT_SUFFIX =
      "s3(?:"
          + "\\.(?:dualstack\\.)?[a-z0-9-]+" // s3.<region> | s3.dualstack.<region>
          + "|-fips(?:\\.dualstack)?\\.[a-z0-9-]+" // s3-fips.<region> | s3-fips.dualstack.<region>
          + "|-accelerate(?:\\.dualstack)?" // s3-accelerate | s3-accelerate.dualstack
          + "|-[a-z0-9-]+" // s3-<region> (legacy regional)
          + ")?\\.amazonaws\\.com(?:\\.cn)?";
  private static final Pattern VIRTUAL_HOSTED_S3_ENDPOINT =
      Pattern.compile("^(.+)\\." + S3_ENDPOINT_SUFFIX + "$", Pattern.CASE_INSENSITIVE);
  private static final Pattern PATH_STYLE_S3_ENDPOINT =
      Pattern.compile("^" + S3_ENDPOINT_SUFFIX + "$", Pattern.CASE_INSENSITIVE);

  private S3PresignedUrlAttributor() {}

  static Optional<PresignedUrlAttribution> attribute(PresignedAwsUrl presignedAwsUrl) {
    String host = presignedAwsUrl.getHost();
    boolean pathStyle = PATH_STYLE_S3_ENDPOINT.matcher(host).matches();

    Optional<String> bucket;
    if (pathStyle) {
      bucket = getPathStyleBucket(presignedAwsUrl);
    } else {
      bucket = getVirtualHostedStyleBucket(host);
      if (!bucket.isPresent()) {
        // Not a recognized S3 endpoint (custom CNAME, access point, unknown host). Fail closed:
        // the signing service cannot be recovered from a redacted credential scope.
        return Optional.empty();
      }
    }

    Optional<RemoteResource> remoteResource =
        bucket.map(b -> new RemoteResource(S3_BUCKET_RESOURCE_TYPE, b));
    return Optional.of(
        new PresignedUrlAttribution(
            NORMALIZED_S3_SERVICE_NAME,
            getRemoteOperation(presignedAwsUrl, pathStyle),
            remoteResource));
  }

  private static String getRemoteOperation(PresignedAwsUrl presignedAwsUrl, boolean pathStyle) {
    Optional<String> httpMethod = presignedAwsUrl.getHttpMethod();
    if (!httpMethod.isPresent()) {
      return UNKNOWN_REMOTE_OPERATION;
    }

    String normalizedMethod = httpMethod.get().toUpperCase(Locale.ROOT);
    boolean hasObjectKey = hasObjectKey(presignedAwsUrl, pathStyle);

    // ListObjectsV2 is a bucket-level GET (no object key).
    // https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html
    if ("GET".equals(normalizedMethod)
        && !hasObjectKey
        && "2".equals(presignedAwsUrl.getFirstQueryParameterValue("list-type").orElse(null))) {
      return "ListObjectsV2";
    }
    // S3 multipart REST API operations:
    // https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateMultipartUpload.html
    // https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPart.html
    // https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListParts.html
    // https://docs.aws.amazon.com/AmazonS3/latest/API/API_CompleteMultipartUpload.html
    // https://docs.aws.amazon.com/AmazonS3/latest/API/API_AbortMultipartUpload.html
    // https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListMultipartUploads.html
    if (presignedAwsUrl.getFirstQueryParameterValue("uploadId").isPresent()) {
      if ("PUT".equals(normalizedMethod)
          && presignedAwsUrl.getFirstQueryParameterValue("partNumber").isPresent()) {
        return "UploadPart";
      }
      if ("GET".equals(normalizedMethod)) {
        return "ListParts";
      }
      if ("POST".equals(normalizedMethod)) {
        return "CompleteMultipartUpload";
      }
      if ("DELETE".equals(normalizedMethod)) {
        return "AbortMultipartUpload";
      }
    }

    if (presignedAwsUrl.getFirstQueryParameterValue("uploads").isPresent()) {
      if ("POST".equals(normalizedMethod) && hasObjectKey) {
        return "CreateMultipartUpload";
      }
      if ("GET".equals(normalizedMethod) && !hasObjectKey) {
        return "ListMultipartUploads";
      }
    }

    // Subresource operations selected by a query parameter. They are object-level when an object
    // key is present and bucket-level otherwise.
    // https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectAcl.html
    // https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectTagging.html
    if (presignedAwsUrl.getFirstQueryParameterValue("acl").isPresent()) {
      if ("GET".equals(normalizedMethod)) {
        return hasObjectKey ? "GetObjectAcl" : "GetBucketAcl";
      }
      if ("PUT".equals(normalizedMethod)) {
        return hasObjectKey ? "PutObjectAcl" : "PutBucketAcl";
      }
    }
    if (presignedAwsUrl.getFirstQueryParameterValue("tagging").isPresent()) {
      if ("GET".equals(normalizedMethod)) {
        return hasObjectKey ? "GetObjectTagging" : "GetBucketTagging";
      }
      if ("PUT".equals(normalizedMethod)) {
        return hasObjectKey ? "PutObjectTagging" : "PutBucketTagging";
      }
      if ("DELETE".equals(normalizedMethod)) {
        return hasObjectKey ? "DeleteObjectTagging" : "DeleteBucketTagging";
      }
    }

    // Object-only subresources. These operate on an object, so they require an object key.
    // https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectRetention.html
    // https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectLegalHold.html
    // https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectTorrent.html
    if (hasObjectKey) {
      if (presignedAwsUrl.getFirstQueryParameterValue("retention").isPresent()) {
        if ("GET".equals(normalizedMethod)) {
          return "GetObjectRetention";
        }
        if ("PUT".equals(normalizedMethod)) {
          return "PutObjectRetention";
        }
      }
      if (presignedAwsUrl.getFirstQueryParameterValue("legal-hold").isPresent()) {
        if ("GET".equals(normalizedMethod)) {
          return "GetObjectLegalHold";
        }
        if ("PUT".equals(normalizedMethod)) {
          return "PutObjectLegalHold";
        }
      }
      if ("GET".equals(normalizedMethod)
          && presignedAwsUrl.getFirstQueryParameterValue("torrent").isPresent()) {
        return "GetObjectTorrent";
      }
    }

    if (!hasObjectKey) {
      return UNKNOWN_REMOTE_OPERATION;
    }

    switch (normalizedMethod) {
      case "GET":
        return "GetObject";
      case "HEAD":
        return "HeadObject";
      case "PUT":
        return "PutObject";
      case "DELETE":
        return "DeleteObject";
      default:
        return UNKNOWN_REMOTE_OPERATION;
    }
  }

  private static boolean hasObjectKey(PresignedAwsUrl presignedAwsUrl, boolean pathStyle) {
    String[] pathSegments = getPathSegments(presignedAwsUrl.getPath());
    if (pathStyle) {
      // Path-style URLs carry the bucket as the first path segment, so an object key requires a
      // second segment.
      return pathSegments.length > 1;
    }
    return pathSegments.length > 0;
  }

  private static Optional<String> getPathStyleBucket(PresignedAwsUrl presignedAwsUrl) {
    String[] pathSegments = getPathSegments(presignedAwsUrl.getPath());
    if (pathSegments.length == 0) {
      return Optional.empty();
    }
    return Optional.of(pathSegments[0]);
  }

  private static Optional<String> getVirtualHostedStyleBucket(String host) {
    Matcher matcher = VIRTUAL_HOSTED_S3_ENDPOINT.matcher(host);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(matcher.group(1));
  }

  private static String[] getPathSegments(String path) {
    String normalizedPath = path == null ? "" : path;
    while (normalizedPath.startsWith("/")) {
      normalizedPath = normalizedPath.substring(1);
    }
    if (normalizedPath.isEmpty()) {
      return new String[0];
    }
    return normalizedPath.split("/");
  }
}
