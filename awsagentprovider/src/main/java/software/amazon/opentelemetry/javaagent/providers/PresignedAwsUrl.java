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

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed SigV4 presigned AWS URL. Wraps the standard {@link URI} and delegates URL parts (host,
 * path) to it, adding only the request context that {@link URI} cannot express: the HTTP method
 * (which comes from the span, not the URL) and the parsed query parameters (the JDK does not parse
 * the query string into a map).
 *
 * <p>The signing service is intentionally not carried here: it is derived by the SigV4 credential
 * scope, which the agent's URL sanitization redacts. Service identity is instead determined from
 * the endpoint hostname by the service-specific attributor.
 */
final class PresignedAwsUrl {
  private final URI uri;
  private final Optional<String> httpMethod;
  private final Map<String, List<String>> queryParameters;

  PresignedAwsUrl(URI uri, Optional<String> httpMethod, Map<String, List<String>> queryParameters) {
    this.uri = uri;
    this.httpMethod = httpMethod;
    this.queryParameters = Collections.unmodifiableMap(queryParameters);
  }

  Optional<String> getHttpMethod() {
    return httpMethod;
  }

  String getHost() {
    return uri.getHost();
  }

  String getPath() {
    String rawPath = uri.getRawPath();
    if (rawPath == null || rawPath.isEmpty()) {
      return "/";
    }
    return rawPath;
  }

  Optional<String> getFirstQueryParameterValue(String name) {
    List<String> values = queryParameters.get(name);
    if (values == null || values.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(values.get(0));
  }
}
