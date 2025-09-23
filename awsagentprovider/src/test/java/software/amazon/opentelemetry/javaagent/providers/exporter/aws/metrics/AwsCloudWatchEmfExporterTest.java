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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.LogEventEmitter;

public class AwsCloudWatchEmfExporterTest extends BaseEmfExporterTest {
  private static final String NAMESPACE = "test-namespace";

  @Override
  protected MetricExporter createExporter() {
    return new AwsCloudWatchEmfExporter(NAMESPACE, mockEmitter);
  }

  @Override
  protected Optional<Map<String, Object>> validateEmfStructure(
      Map<String, Object> logEvent, String metricName) {
    Optional<Map<String, Object>> emfLogOpt = super.validateEmfStructure(logEvent, metricName);

    if (emfLogOpt.isPresent()) {
      Map<String, Object> emfLog = emfLogOpt.get();
      Map<String, Object> aws = (Map<String, Object>) emfLog.get("_aws");
      List<Map<String, Object>> cloudWatchMetrics =
          (List<Map<String, Object>>) aws.get("CloudWatchMetrics");
      assertEquals(NAMESPACE, cloudWatchMetrics.get(0).get("Namespace"));
    }

    return emfLogOpt;
  }

  @Test
  void testBatchActiveNewBatch() {
    LogEventEmitter mockEmitter = mock(LogEventEmitter.class);
    AwsCloudWatchEmfExporter exporter = new AwsCloudWatchEmfExporter(NAMESPACE, mockEmitter);

    long currentTime = System.currentTimeMillis();
    Map<String, Object> logEvent = new HashMap<>();
    logEvent.put("message", "test");
    logEvent.put("timestamp", currentTime);

    // Send multiple events with same timestamp - should be batched together
    exporter.emit(logEvent);
    exporter.emit(logEvent);
    exporter.emit(logEvent);

    // Should only call emit once per event since batch is active
    verify(mockEmitter, times(3)).emit(logEvent);
  }

  @Test
  void testBatchInactiveAfter24Hours() {
    LogEventEmitter mockEmitter = mock(LogEventEmitter.class);
    AwsCloudWatchEmfExporter exporter = new AwsCloudWatchEmfExporter(NAMESPACE, mockEmitter);

    long baseTime = System.currentTimeMillis();
    Map<String, Object> firstEvent = new HashMap<>();
    firstEvent.put("message", "test1");
    firstEvent.put("timestamp", baseTime);

    Map<String, Object> secondEvent = new HashMap<>();
    secondEvent.put("message", "test2");
    secondEvent.put("timestamp", baseTime + (25L * 60 * 60 * 1000)); // 25 hours later

    exporter.emit(firstEvent);
    exporter.emit(secondEvent);

    // Should trigger 2 separate batch sends due to 24-hour span limit
    verify(mockEmitter, times(1)).emit(firstEvent);
    verify(mockEmitter, times(1)).emit(secondEvent);
  }

  @ParameterizedTest
  @MethodSource("batchLimitScenarios")
  void testEventBatchLimits(
      Map<String, Object> logEvent, int eventCount, boolean shouldExceedLimit) {
    LogEventEmitter mockEmitter = mock(LogEventEmitter.class);
    AwsCloudWatchEmfExporter exporter = new AwsCloudWatchEmfExporter(NAMESPACE, mockEmitter);

    for (int i = 0; i < eventCount; i++) {
      exporter.emit(logEvent);
    }

    if (shouldExceedLimit) {
      verify(mockEmitter, atLeast(2)).emit(logEvent);
    } else {
      verify(mockEmitter, times(eventCount)).emit(logEvent);
    }
  }

  @ParameterizedTest
  @MethodSource("invalidLogEvents")
  void testValidateLogEventInvalid(Map<String, Object> logEvent) {
    AwsCloudWatchEmfExporter exporter =
        new AwsCloudWatchEmfExporter(NAMESPACE, "test-log-group", "test-stream", "us-east-1");

    assertThrows(IllegalArgumentException.class, () -> exporter.emit(logEvent));
  }

  static Stream<Arguments> batchLimitScenarios() {
    Map<String, Object> smallEvent = new HashMap<>();
    smallEvent.put("message", "test");
    smallEvent.put("timestamp", System.currentTimeMillis());

    Map<String, Object> largeEvent = new HashMap<>();
    largeEvent.put("message", "x".repeat(1024 * 1024));
    largeEvent.put("timestamp", System.currentTimeMillis());

    return Stream.of(
        Arguments.of(smallEvent, 10001, true), // count limit exceeded
        Arguments.of(largeEvent, 2, true), // size limit exceeded
        Arguments.of(smallEvent, 10, false) // within limits
        );
  }

  static Stream<Arguments> invalidLogEvents() {
    long currentTime = System.currentTimeMillis();
    Map<String, Object> oldTimestampEvent = new HashMap<>();
    oldTimestampEvent.put("message", "{\"test\":\"data\"}");
    oldTimestampEvent.put("timestamp", currentTime - (15L * 24 * 60 * 60 * 1000));

    Map<String, Object> futureTimestampEvent = new HashMap<>();
    futureTimestampEvent.put("message", "{\"test\":\"data\"}");
    futureTimestampEvent.put("timestamp", currentTime + (3L * 60 * 60 * 1000));

    Map<String, Object> emptyMessageEvent = new HashMap<>();
    emptyMessageEvent.put("message", "");
    emptyMessageEvent.put("timestamp", currentTime);

    Map<String, Object> whitespaceMessageEvent = new HashMap<>();
    whitespaceMessageEvent.put("message", "   ");
    whitespaceMessageEvent.put("timestamp", currentTime);

    Map<String, Object> missingMessageEvent = new HashMap<>();
    missingMessageEvent.put("timestamp", currentTime);

    return Stream.of(
        Arguments.of(oldTimestampEvent),
        Arguments.of(futureTimestampEvent),
        Arguments.of(emptyMessageEvent),
        Arguments.of(whitespaceMessageEvent),
        Arguments.of(missingMessageEvent));
  }
}
