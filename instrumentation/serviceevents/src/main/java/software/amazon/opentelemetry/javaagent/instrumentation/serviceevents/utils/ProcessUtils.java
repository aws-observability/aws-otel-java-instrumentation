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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils;

import java.lang.management.ManagementFactory;

/** Process-level helpers that must work on every supported runtime, including Java 8. */
public final class ProcessUtils {

  private ProcessUtils() {}

  /**
   * Return the current process id without using {@code ProcessHandle} (a Java 9+ API).
   * ServiceEvents runs on Java 8+, and {@code ProcessHandle.current().pid()} throws {@link
   * NoClassDefFoundError} on Java 8 — which, thrown from agent init, aborts the entire
   * OpenTelemetry javaagent. The runtime MXBean name is formatted as {@code "<pid>@<host>"} on the
   * HotSpot/Corretto JVMs we target, so we parse the pid out of it. Returns {@code -1} if the pid
   * cannot be determined; callers still emit their records (pid is informational only).
   */
  public static long currentPid() {
    try {
      String name = ManagementFactory.getRuntimeMXBean().getName();
      if (name != null) {
        int at = name.indexOf('@');
        if (at > 0) {
          return Long.parseLong(name.substring(0, at));
        }
      }
    } catch (RuntimeException e) {
      // Fall through to the sentinel — pid is best-effort metadata.
    }
    return -1L;
  }

  /**
   * Repeat {@code s} {@code count} times. Replacement for {@code String.repeat(int)}, which is a
   * Java 11+ API — calling it on Java 8 throws {@link NoSuchMethodError}. Used for console banner
   * separators in the debug export paths.
   */
  public static String repeat(String s, int count) {
    if (count <= 0 || s.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length() * count);
    for (int i = 0; i < count; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
