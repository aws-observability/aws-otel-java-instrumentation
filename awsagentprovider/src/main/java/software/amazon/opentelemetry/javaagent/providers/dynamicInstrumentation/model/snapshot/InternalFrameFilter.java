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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot;

/**
 * Filters stack trace elements that belong to ADOT or OpenTelemetry internals. These frames provide
 * no debugging value to customers and waste the limited stack frame slots in DI snapshots.
 */
final class InternalFrameFilter {

  private static final String[] INTERNAL_PREFIXES = {
    "software.amazon.opentelemetry.", "io.opentelemetry.", "net.bytebuddy.",
  };

  private InternalFrameFilter() {}

  static boolean isInternal(StackTraceElement element) {
    String className = element.getClassName();
    if (className == null) {
      return false;
    }
    for (String prefix : INTERNAL_PREFIXES) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
