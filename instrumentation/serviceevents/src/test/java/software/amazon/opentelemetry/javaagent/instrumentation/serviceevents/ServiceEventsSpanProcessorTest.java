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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ServiceEventsSpanProcessorTest {

  @Test
  void extractFunctionId_standardStackTrace() {
    String stackTrace =
        "com.amazon.indico.exception.InvalidSharingException: Invalid sharing configuration\n"
            + "\tat com.amazon.indico.controller.EventController.shareEvent(EventController.java:55)\n"
            + "\tat sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)\n";
    assertEquals(
        "com.amazon.indico.controller.EventController.shareEvent",
        ServiceEventsSpanProcessor.extractFunctionIdFromStackTrace(stackTrace));
  }

  @Test
  void extractFunctionId_runtimeException() {
    String stackTrace =
        "java.lang.NullPointerException: null\n"
            + "\tat com.example.service.OrderService.processOrder(OrderService.java:123)\n"
            + "\tat com.example.controller.OrderController.create(OrderController.java:45)\n";
    assertEquals(
        "com.example.service.OrderService.processOrder",
        ServiceEventsSpanProcessor.extractFunctionIdFromStackTrace(stackTrace));
  }

  @Test
  void extractFunctionId_nullInput() {
    assertNull(ServiceEventsSpanProcessor.extractFunctionIdFromStackTrace(null));
  }

  @Test
  void extractFunctionId_emptyInput() {
    assertNull(ServiceEventsSpanProcessor.extractFunctionIdFromStackTrace(""));
  }

  @Test
  void extractFunctionId_noAtLine() {
    assertNull(
        ServiceEventsSpanProcessor.extractFunctionIdFromStackTrace(
            "java.lang.RuntimeException: boom"));
  }

  @Test
  void extractFunctionId_tablessAtLine() {
    // Some formatters use newline + "at " without a tab
    String stackTrace =
        "java.lang.RuntimeException: error\n"
            + "at com.example.MyClass.myMethod(MyClass.java:10)\n";
    assertEquals(
        "com.example.MyClass.myMethod",
        ServiceEventsSpanProcessor.extractFunctionIdFromStackTrace(stackTrace));
  }

  @Test
  void extractFunctionId_doesNotMatchAtInMessage() {
    // "at " in the exception message must not be mistaken for a stack frame
    String stackTrace =
        "java.lang.IllegalArgumentException: invalid value at position 3\n"
            + "\tat com.example.Foo.bar(Foo.java:1)\n";
    assertEquals(
        "com.example.Foo.bar",
        ServiceEventsSpanProcessor.extractFunctionIdFromStackTrace(stackTrace));
  }

  @Test
  void extractFunctionId_messageOnlyWithAtInside() {
    // No actual stack frames — only "at " inside the message. Should return null.
    assertNull(
        ServiceEventsSpanProcessor.extractFunctionIdFromStackTrace(
            "java.lang.IllegalArgumentException: invalid value at position 3"));
  }

  // --- routeFromOperation: parity with Python's _route_from_operation and JS's routeFromOperation.

  @Test
  void routeFromOperation_stripsMethodPrefix() {
    // "METHOD /route" — common case: strip the method prefix, keep the route.
    assertEquals(
        "/users/{id}", ServiceEventsSpanProcessor.routeFromOperation("GET /users/{id}", "GET"));
  }

  @Test
  void routeFromOperation_barePathNoMethodPrefix() {
    // "/route" — bare path (stable-only semconv, no method prefix). Use verbatim.
    assertEquals("/health", ServiceEventsSpanProcessor.routeFromOperation("/health", "GET"));
  }

  @Test
  void routeFromOperation_internalOperation() {
    assertNull(ServiceEventsSpanProcessor.routeFromOperation("InternalOperation", "GET"));
  }

  @Test
  void routeFromOperation_unknownOperation() {
    assertNull(ServiceEventsSpanProcessor.routeFromOperation("UnknownOperation", "GET"));
  }

  @Test
  void routeFromOperation_operationEqualsMethod() {
    // Span name was just the bare HTTP method — no resolvable route.
    assertNull(ServiceEventsSpanProcessor.routeFromOperation("GET", "GET"));
  }

  @Test
  void routeFromOperation_nullOrEmptyOperation() {
    assertNull(ServiceEventsSpanProcessor.routeFromOperation(null, "GET"));
    assertNull(ServiceEventsSpanProcessor.routeFromOperation("", "GET"));
  }

  @Test
  void routeFromOperation_methodPrefixWithEmptyRoute() {
    // "GET " (method prefix, empty route) is not a resolvable route.
    assertNull(ServiceEventsSpanProcessor.routeFromOperation("GET ", "GET"));
  }

  @Test
  void routeFromOperation_unprefixedNonPathOperation() {
    // No method prefix and doesn't start with "/" (e.g. a lambda handler name) — no route.
    assertNull(ServiceEventsSpanProcessor.routeFromOperation("FunctionHandler", "GET"));
  }
}
