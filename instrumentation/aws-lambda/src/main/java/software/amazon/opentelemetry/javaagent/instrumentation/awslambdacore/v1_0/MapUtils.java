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

package software.amazon.opentelemetry.javaagent.instrumentation.awslambdacore.v1_0;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public final class MapUtils {
  public static Map<String, String> lowercaseMap(Map<String, String> source) {
    return emptyIfNull(source).entrySet().stream()
        .filter(e -> e.getKey() != null)
        .collect(Collectors.toMap(e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue));
  }

  public static Map<String, String> emptyIfNull(Map<String, String> map) {
    return map == null ? Collections.emptyMap() : map;
  }

  private MapUtils() {}
}
