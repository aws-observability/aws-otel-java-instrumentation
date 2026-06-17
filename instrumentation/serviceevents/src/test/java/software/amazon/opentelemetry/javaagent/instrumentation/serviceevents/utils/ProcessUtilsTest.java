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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProcessUtilsTest {

  @Test
  void currentPidReturnsPositivePidForThisJvm() {
    // The running JVM always has a pid; the ManagementFactory-based resolution must succeed
    // (and must NOT use ProcessHandle, a Java 9+ API that breaks the agent on Java 8).
    long pid = ProcessUtils.currentPid();
    assertTrue(pid > 0, "expected a positive pid, got " + pid);
  }

  @Test
  void repeatBuildsTheExpectedString() {
    assertEquals("===", ProcessUtils.repeat("=", 3));
    assertEquals("abab", ProcessUtils.repeat("ab", 2));
  }

  @Test
  void repeatHandlesZeroNegativeAndEmpty() {
    assertEquals("", ProcessUtils.repeat("=", 0));
    assertEquals("", ProcessUtils.repeat("=", -5));
    assertEquals("", ProcessUtils.repeat("", 10));
  }
}
