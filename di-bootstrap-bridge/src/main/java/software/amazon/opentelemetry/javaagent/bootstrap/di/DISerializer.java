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

package software.amazon.opentelemetry.javaagent.bootstrap.di;

import java.util.Map;

/** Interface for serializing captured values. Implemented in agent classloader. */
public interface DISerializer {
  Map<String, SerializedValue> serialize(
      Map<String, Object> values,
      int maxDepth,
      int maxFields,
      int maxCollWidth,
      int maxCollDepth,
      int maxStrLen,
      long timeoutMs);
}
