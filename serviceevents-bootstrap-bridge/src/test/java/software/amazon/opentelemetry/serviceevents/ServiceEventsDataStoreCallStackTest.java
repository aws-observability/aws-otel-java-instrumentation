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

package software.amazon.opentelemetry.serviceevents;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests call-stack lifecycle (ORR #10). {@code clearCallStack()} uses {@link ThreadLocal#remove()}
 * so a pooled thread does not retain its list (and the old-classloader Strings it pins) across
 * redeploys. The leak-prevention itself is a {@code .remove()}-vs-{@code .clear()} code property;
 * this test locks in the observable behavior (clearing leaves the stack empty and reusable).
 */
class ServiceEventsDataStoreCallStackTest {

  @AfterEach
  void resetState() {
    ServiceEventsDataStore.clearCallStack();
  }

  @Test
  void clearLeavesStackEmptyAndReusable() {
    ServiceEventsDataStore.pushCallStack("a");
    ServiceEventsDataStore.pushCallStack("b");

    ServiceEventsDataStore.clearCallStack();

    // After clear the stack is empty: no caller.
    assertNull(ServiceEventsDataStore.getCurrentCaller());

    // Re-initializes lazily on next use (ThreadLocal.initialValue) and works normally.
    ServiceEventsDataStore.pushCallStack("c");
    ServiceEventsDataStore.pushCallStack("d");
    // getCurrentCaller returns the second-from-top.
    org.junit.jupiter.api.Assertions.assertEquals("c", ServiceEventsDataStore.getCurrentCaller());
  }

  @Test
  void clearOnEmptyStackIsSafe() {
    ServiceEventsDataStore.clearCallStack();
    ServiceEventsDataStore.clearCallStack();
    assertNull(ServiceEventsDataStore.getCurrentCaller());
  }
}
