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

import static io.opentelemetry.semconv.ServiceAttributes.SERVICE_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.UNKNOWN_SERVICE;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.resources.Resource;

public class AwsResourceAttributeConfigurator {
  // As per
  // https://github.com/open-telemetry/opentelemetry-java/tree/main/sdk-extensions/autoconfigure#opentelemetry-resource
  // If service name is not specified, SDK defaults the service name to unknown_service:java
  private static final String OTEL_UNKNOWN_SERVICE = "unknown_service:java";

  public static void setServiceAttribute(
      Resource resource, AttributesBuilder attributesBuilder, Runnable handleUnknownService) {
    String service = resource.getAttribute(AWS_LOCAL_SERVICE);
    if (service == null) {
      service = resource.getAttribute(SERVICE_NAME);
      // In practice the service name is never null, but we can be defensive here.
      if (service == null || service.equals(OTEL_UNKNOWN_SERVICE)) {
        service = UNKNOWN_SERVICE;
        handleUnknownService.run();
      }
    }
    attributesBuilder.put(AWS_LOCAL_SERVICE, service);
  }
}
