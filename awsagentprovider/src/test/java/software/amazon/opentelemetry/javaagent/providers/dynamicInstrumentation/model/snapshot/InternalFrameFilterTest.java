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

class InternalFrameFilterTest {

  private static StackTraceElement frame(String className, String methodName) {
    return new StackTraceElement(className, methodName, className + ".java", 1);
  }

  @Test
  void filtersAdotFrames() {
    assertTrue(
        InternalFrameFilter.isInternal(
            frame(
                "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.Snapshot",
                "create")));
    assertTrue(
        InternalFrameFilter.isInternal(
            frame(
                "software.amazon.opentelemetry.javaagent.bootstrap.di.DIDataStore",
                "captureLocals")));
    assertTrue(
        InternalFrameFilter.isInternal(
            frame(
                "software.amazon.opentelemetry.javaagent.providers.AwsSpanMetricsProcessor",
                "onEnd")));
  }

  @Test
  void filtersOpenTelemetryFrames() {
    assertTrue(InternalFrameFilter.isInternal(frame("io.opentelemetry.sdk.trace.SdkSpan", "end")));
    assertTrue(
        InternalFrameFilter.isInternal(
            frame("io.opentelemetry.javaagent.tooling.AgentInstaller", "install")));
    assertTrue(InternalFrameFilter.isInternal(frame("io.opentelemetry.api.trace.Span", "current")));
  }

  @Test
  void keepsUserFrames() {
    assertFalse(
        InternalFrameFilter.isInternal(frame("com.example.myapp.OrderService", "processOrder")));
    assertFalse(
        InternalFrameFilter.isInternal(
            frame("org.springframework.web.servlet.DispatcherServlet", "doDispatch")));
  }

  @Test
  void keepsJdkFrames() {
    assertFalse(InternalFrameFilter.isInternal(frame("java.lang.Thread", "run")));
    assertFalse(
        InternalFrameFilter.isInternal(
            frame("java.util.concurrent.ThreadPoolExecutor", "runWorker")));
  }

  @Test
  void keepsThirdPartyFrames() {
    assertFalse(
        InternalFrameFilter.isInternal(
            frame("com.amazonaws.services.s3.AmazonS3Client", "putObject")));
    assertFalse(
        InternalFrameFilter.isInternal(
            frame("org.apache.http.impl.client.CloseableHttpClient", "execute")));
  }

  @Test
  void filtersByteBuddyFrames() {
    assertTrue(
        InternalFrameFilter.isInternal(frame("net.bytebuddy.dynamic.DynamicType$Builder", "make")));
    assertTrue(
        InternalFrameFilter.isInternal(frame("net.bytebuddy.asm.Advice$Dispatcher", "apply")));
  }

  @Test
  void handlesEmptyClassName() {
    StackTraceElement element = new StackTraceElement("", "run", "Unknown.java", -1);
    assertFalse(InternalFrameFilter.isInternal(element));
  }
}
