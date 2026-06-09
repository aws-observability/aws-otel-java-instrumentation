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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.bootstrap.di.ThrowableData;

class CapturedThrowableTest {

  @Test
  void fromThrowable_capturesTypeAndMessage() {
    RuntimeException ex = new RuntimeException("something went wrong");
    CapturedThrowable ct = CapturedThrowable.fromThrowable(ex, 200, 20);

    assertEquals("java.lang.RuntimeException", ct.getType());
    assertEquals("something went wrong", ct.getMessage());
    assertFalse(ct.getStacktrace().isEmpty());
  }

  @Test
  void fromThrowable_truncatesLongMessage() {
    RuntimeException ex = new RuntimeException("a".repeat(300));
    CapturedThrowable ct = CapturedThrowable.fromThrowable(ex, 50, 20);

    assertEquals(50, ct.getMessage().length());
  }

  @Test
  void fromThrowable_enforcesMaxStackFrames() {
    RuntimeException ex = new RuntimeException("deep stack");
    CapturedThrowable ct = CapturedThrowable.fromThrowable(ex, 200, 3);

    assertTrue(ct.getStacktrace().size() <= 3);
  }

  @Test
  void fromThrowable_returnsNullForNullInput() {
    assertNull(CapturedThrowable.fromThrowable(null, 200, 20));
  }

  @Test
  void fromThrowableData_reconstructsThrowable() {
    StackTraceElement[] stack = {
      new StackTraceElement("com.example.OrderService", "processOrder", "OrderService.java", 42),
      new StackTraceElement("com.example.AppController", "handleRequest", "AppController.java", 15)
    };
    ThrowableData data =
        new ThrowableData("java.lang.IllegalArgumentException", "bad input", stack);

    CapturedThrowable ct = CapturedThrowable.fromThrowableData(data, 200, 20);

    assertNotNull(ct);
    assertEquals("java.lang.IllegalArgumentException", ct.getType());
    assertEquals("bad input", ct.getMessage());
    assertEquals(2, ct.getStacktrace().size());
    assertEquals("OrderService.java", ct.getStacktrace().get(0).getFileName());
    assertEquals("processOrder", ct.getStacktrace().get(0).getFunction());
    assertEquals(42, ct.getStacktrace().get(0).getLineNumber());
    assertEquals("AppController.java", ct.getStacktrace().get(1).getFileName());
    assertEquals("handleRequest", ct.getStacktrace().get(1).getFunction());
    assertEquals(15, ct.getStacktrace().get(1).getLineNumber());
  }

  @Test
  void fromThrowableData_truncatesMessage() {
    ThrowableData data =
        new ThrowableData("java.lang.RuntimeException", "a".repeat(300), new StackTraceElement[0]);

    CapturedThrowable ct = CapturedThrowable.fromThrowableData(data, 50, 20);

    assertNotNull(ct);
    assertEquals(50, ct.getMessage().length());
  }

  @Test
  void fromThrowableData_enforcesMaxStackFrames() {
    StackTraceElement[] stack = new StackTraceElement[30];
    for (int i = 0; i < 30; i++) {
      stack[i] =
          new StackTraceElement(
              "com.example.Class" + i, "method" + i, "Class" + i + ".java", i + 1);
    }
    ThrowableData data = new ThrowableData("java.lang.Exception", "too deep", stack);

    CapturedThrowable ct = CapturedThrowable.fromThrowableData(data, 200, 5);

    assertNotNull(ct);
    assertEquals(5, ct.getStacktrace().size());
    assertEquals("Class0.java", ct.getStacktrace().get(0).getFileName());
    assertEquals("Class4.java", ct.getStacktrace().get(4).getFileName());
  }

  @Test
  void fromThrowableData_filtersInternalFrames() {
    StackTraceElement[] stack = {
      new StackTraceElement("com.example.MyService", "doWork", "MyService.java", 10),
      new StackTraceElement(
          "io.opentelemetry.javaagent.shaded.Something", "intercept", "Something.java", 5),
      new StackTraceElement(
          "net.bytebuddy.implementation.Something", "invoke", "Something.java", 20),
      new StackTraceElement("com.example.AppController", "handle", "AppController.java", 30)
    };
    ThrowableData data = new ThrowableData("java.lang.RuntimeException", "test", stack);

    CapturedThrowable ct = CapturedThrowable.fromThrowableData(data, 200, 20);

    assertNotNull(ct);
    assertEquals(2, ct.getStacktrace().size());
    assertEquals("MyService.java", ct.getStacktrace().get(0).getFileName());
    assertEquals("AppController.java", ct.getStacktrace().get(1).getFileName());
  }

  @Test
  void fromThrowableData_returnsNullForNull() {
    assertNull(CapturedThrowable.fromThrowableData(null, 200, 20));
  }

  @Test
  void fromThrowableData_handlesNullMessage() {
    ThrowableData data =
        new ThrowableData("java.lang.NullPointerException", null, new StackTraceElement[0]);

    CapturedThrowable ct = CapturedThrowable.fromThrowableData(data, 200, 20);

    assertNotNull(ct);
    assertEquals("java.lang.NullPointerException", ct.getType());
    assertNull(ct.getMessage());
    assertTrue(ct.getStacktrace().isEmpty());
  }

  @Test
  void fromThrowableData_handlesNullStackTrace() {
    ThrowableData data = new ThrowableData("java.lang.RuntimeException", "no stack", null);

    CapturedThrowable ct = CapturedThrowable.fromThrowableData(data, 200, 20);

    assertNotNull(ct);
    assertEquals("no stack", ct.getMessage());
    assertTrue(ct.getStacktrace().isEmpty());
  }
}
