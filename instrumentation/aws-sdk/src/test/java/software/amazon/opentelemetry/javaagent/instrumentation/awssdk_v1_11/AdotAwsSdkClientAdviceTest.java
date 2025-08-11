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

package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.handlers.RequestHandler2;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdotAwsSdkClientAdviceTest {

  private AdotAwsSdkClientInstrumentation.AdotAwsSdkClientAdvice advice;
  private List<RequestHandler2> handlers;

  @BeforeEach
  void setUp() {
    advice = new AdotAwsSdkClientInstrumentation.AdotAwsSdkClientAdvice();
    handlers = new ArrayList<>();
  }

  @Test
  void testAddHandlerWhenHandlersIsNull() {
    AdotAwsSdkClientInstrumentation.AdotAwsSdkClientAdvice.addHandler(null);
    assertThat(handlers).hasSize(0);
  }

  @Test
  void testAddHandlerWhenNoOtelHandler() {
    RequestHandler2 someOtherHandler = mock(RequestHandler2.class);
    handlers.add(someOtherHandler);

    AdotAwsSdkClientInstrumentation.AdotAwsSdkClientAdvice.addHandler(handlers);

    assertThat(handlers).hasSize(1);
    assertThat(handlers).containsExactly(someOtherHandler);
  }

  @Test
  void testAddHandlerWhenOtelHandlerPresent() {
    RequestHandler2 otelHandler = mock(RequestHandler2.class);
    when(otelHandler.toString())
        .thenReturn(
            "io.opentelemetry.javaagent.instrumentation.awssdk.v1_11.TracingRequestHandler");
    handlers.add(otelHandler);

    AdotAwsSdkClientInstrumentation.AdotAwsSdkClientAdvice.addHandler(handlers);

    assertThat(handlers).hasSize(2);
    assertThat(handlers.get(0)).isEqualTo(otelHandler);
    assertThat(handlers.get(1)).isInstanceOf(AdotAwsSdkTracingRequestHandler.class);
  }

  @Test
  void testAddHandlerWhenAdotHandlerAlreadyPresent() {
    RequestHandler2 otelHandler = mock(RequestHandler2.class);
    when(otelHandler.toString())
        .thenReturn(
            "io.opentelemetry.javaagent.instrumentation.awssdk.v1_11.TracingRequestHandler");
    handlers.add(otelHandler);
    handlers.add(new AdotAwsSdkTracingRequestHandler());

    AdotAwsSdkClientInstrumentation.AdotAwsSdkClientAdvice.addHandler(handlers);

    assertThat(handlers).hasSize(2);
    assertThat(handlers.get(0)).isEqualTo(otelHandler);
    assertThat(handlers.get(1)).isInstanceOf(AdotAwsSdkTracingRequestHandler.class);
  }
}
