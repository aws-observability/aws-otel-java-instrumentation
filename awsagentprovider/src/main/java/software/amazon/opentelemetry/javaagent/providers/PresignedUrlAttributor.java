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

import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Optional;

/**
 * Derives Application Signals attribution from a presigned AWS URL. Parses the span's URL once,
 * then lets each service-specific attributor try to claim it based on the endpoint hostname (the
 * signing service cannot be read from the credential scope because it is redacted). If none claims
 * the URL — custom CNAMEs, unknown endpoints, or non-presigned URLs — attribution falls back to the
 * existing behavior.
 */
final class PresignedUrlAttributor {
  private PresignedUrlAttributor() {}

  static Optional<PresignedUrlAttribution> attribute(SpanData span) {
    return PresignedAwsUrlParser.parse(span).flatMap(PresignedUrlAttributor::attribute);
  }

  private static Optional<PresignedUrlAttribution> attribute(PresignedAwsUrl presignedAwsUrl) {
    // Only S3 is supported today. Additional services (e.g. SQS, execute-api) can be tried here in
    // turn, each claiming the URL only when it recognizes the endpoint.
    return S3PresignedUrlAttributor.attribute(presignedAwsUrl);
  }

  /**
   * The Application Signals remote attribution derived from a presigned AWS URL. A resource is
   * present only when the service-specific attributor can identify it confidently.
   */
  static final class PresignedUrlAttribution {
    private final String remoteService;
    private final String remoteOperation;
    private final Optional<RemoteResource> remoteResource;

    PresignedUrlAttribution(
        String remoteService, String remoteOperation, Optional<RemoteResource> remoteResource) {
      this.remoteService = remoteService;
      this.remoteOperation = remoteOperation;
      this.remoteResource = remoteResource;
    }

    String getRemoteService() {
      return remoteService;
    }

    String getRemoteOperation() {
      return remoteOperation;
    }

    Optional<RemoteResource> getRemoteResource() {
      return remoteResource;
    }
  }

  static final class RemoteResource {
    private final String type;
    private final String identifier;

    RemoteResource(String type, String identifier) {
      this.type = type;
      this.identifier = identifier;
    }

    String getType() {
      return type;
    }

    String getIdentifier() {
      return identifier;
    }
  }
}
