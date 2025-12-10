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

package software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.common.emitter.ConsoleEmitter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.common.emitter.LogEventEmitter;

public class ConsoleEmfExporterTest extends BaseEmfExporterTest<PrintStream> {
  private LogEventEmitter<PrintStream> testMockEmitter;
  private ConsoleEmfExporter testExporter;
  private ByteArrayOutputStream outputStream;
  private ConsoleEmitter consoleEmitter;

  @BeforeEach
  void setUp() {
    super.setup();
    this.testMockEmitter = mock(ConsoleEmitter.class);
    this.testExporter =
        ConsoleEmfExporter.builder()
            .setNamespace(NAMESPACE)
            .setEmitter(this.testMockEmitter)
            .build();
    this.outputStream = new ByteArrayOutputStream();
    this.consoleEmitter = new ConsoleEmitter(new PrintStream(this.outputStream));
  }

  @Override
  protected LogEventEmitter<PrintStream> createEmitter() {
    return mock(ConsoleEmitter.class);
  }

  @Override
  protected MetricExporter buildExporter(boolean shouldAddAppSignals) {
    return ConsoleEmfExporter.builder()
        .setNamespace(NAMESPACE)
        .setEmitter(mockEmitter)
        .setShouldAddApplicationSignalsDimensions(shouldAddAppSignals)
        .build();
  }

  @Test
  void testFlush() {
    PrintStream mockPrintStream = mock(PrintStream.class);
    ConsoleEmitter testEmitter = new ConsoleEmitter(mockPrintStream);
    ConsoleEmfExporter exporter =
        ConsoleEmfExporter.builder().setNamespace(NAMESPACE).setEmitter(testEmitter).build();

    assertTrue(exporter.flush().isSuccess());
    verify(mockPrintStream, times(1)).flush();
  }

  @Test
  void testShutdown() {
    assertTrue(this.testExporter.shutdown().isSuccess());
  }

  @Test
  void testIntegrationWithMetricsData() {
    MetricData mockMetricData = mock(MetricData.class);
    when(mockMetricData.getData()).thenReturn(null);

    CompletableResultCode result =
        this.testExporter.export(Collections.singletonList(mockMetricData));

    assertTrue(result.isSuccess());
  }

  @Test
  void testIntegrationExportWithEmptyMetrics() {
    CompletableResultCode result = this.testExporter.export(Collections.emptyList());

    assertTrue(result.isSuccess());
  }

  @Test
  void testExportFailureHandling() {
    LogEventEmitter<PrintStream> failingEmitter = mock(ConsoleEmitter.class);
    doThrow(new IllegalStateException("Test exception")).when(failingEmitter).emit(any());
    ConsoleEmfExporter failingExporter =
        ConsoleEmfExporter.builder().setNamespace(NAMESPACE).setEmitter(failingEmitter).build();

    MetricData mockMetricData = mock(MetricData.class);
    when(mockMetricData.getData()).thenThrow(new IllegalStateException("Test exception"));

    CompletableResultCode result =
        failingExporter.export(Collections.singletonList(mockMetricData));

    assertFalse(result.isSuccess());
  }

  @Test
  void testExportLogEventSuccess() {
    String testMessage =
        "{\"_aws\":{\"Timestamp\":1640995200000,\"CloudWatchMetrics\":[{\"Namespace\":\"TestNamespace\",\"Dimensions\":[[\"Service\"]],\"Metrics\":[{\"Name\":\"TestMetric\",\"Unit\":\"Count\"}]}]},\"Service\":\"test-service\",\"TestMetric\":42}";
    Map<String, Object> logEvent = createLogEvent(testMessage, 1640995200000L);

    this.consoleEmitter.emit(logEvent);

    String capturedOutput = this.outputStream.toString().trim();
    assertEquals(testMessage, capturedOutput);
  }

  @Test
  void testExportLogEventEmptyMessage() {
    Map<String, Object> logEvent = createLogEvent("", 1640995200000L);

    this.consoleEmitter.emit(logEvent);

    String capturedOutput = this.outputStream.toString().trim();
    assertEquals("", capturedOutput);
  }

  @Test
  void testExportLogEventMissingMessage() {
    Map<String, Object> logEvent = createLogEvent(null, 1640995200000L);
    logEvent.remove("message");

    this.consoleEmitter.emit(logEvent);

    String capturedOutput = this.outputStream.toString().trim();
    assertEquals("", capturedOutput);
  }

  @Test
  void testExportLogEventWithNullMessage() {
    Map<String, Object> logEvent = createLogEvent(null, 1640995200000L);

    this.consoleEmitter.emit(logEvent);

    String capturedOutput = this.outputStream.toString().trim();
    assertEquals("", capturedOutput);
  }

  @Test
  void testExportLogEventPrintException() {
    Map<String, Object> logEvent = createLogEvent("test message", 1640995200000L);
    PrintStream failingPrintStream = mock(PrintStream.class);
    doThrow(new IllegalStateException("Print failed"))
        .when(failingPrintStream)
        .println(anyString());
    ConsoleEmitter failingEmitter = new ConsoleEmitter(failingPrintStream);

    assertDoesNotThrow(() -> failingEmitter.emit(logEvent));
  }
}
