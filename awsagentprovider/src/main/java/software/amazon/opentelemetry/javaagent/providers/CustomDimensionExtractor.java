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

import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_APPLICATION_SIGNALS_CUSTOM_DIM_PREFIX;

import io.opentelemetry.api.common.Attributes;
import java.util.HashMap;
import java.util.Map;

/** Utility class for extracting custom dimension values from span attributes */
final class CustomDimensionExtractor {

  private CustomDimensionExtractor() {}

  /**
   * Extract custom dimension values from span attributes Format:
   * aws.application_signals.custom.dim.CarrierId → {"CarrierId": "Fedex"}
   *
   * @param spanAttributes The span's attributes
   * @return Map of custom dimension name to value
   */
  static Map<String, String> extract(Attributes spanAttributes) {
    Map<String, String> customDims = new HashMap<>();

    spanAttributes.forEach(
        (key, value) -> {
          String keyName = key.getKey();
          if (keyName.startsWith(AWS_APPLICATION_SIGNALS_CUSTOM_DIM_PREFIX) && value != null) {
            String dimName = keyName.substring(AWS_APPLICATION_SIGNALS_CUSTOM_DIM_PREFIX.length());
            if (!dimName.isEmpty()) {
              customDims.put(dimName, value.toString());
            }
          }
        });

    return customDims;
  }
}
