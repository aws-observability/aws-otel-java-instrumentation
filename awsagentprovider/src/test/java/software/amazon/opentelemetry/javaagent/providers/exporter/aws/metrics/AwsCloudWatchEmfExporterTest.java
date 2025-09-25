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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.emitter.CloudWatchLogsClientEmitter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.emitter.LogEventEmitter;

public class AwsCloudWatchEmfExporterTest extends BaseEmfExporterTest<CloudWatchLogsClient> {
  private static final String LOG_GROUP_NAME = "test-log-group";
  private static final String LOG_STREAM_NAME = "test-stream";
  private static final String REGION = "us-east-1";

  private AwsCloudWatchEmfExporter mockExporter;
  private CloudWatchLogsClientEmitter testMockEmitter;
  private CloudWatchLogsClient mockClient;
  private CloudWatchLogsClientEmitter wrapper;
  private long currentTime;

  @BeforeEach
  void setUp() {
    super.setup();
    this.currentTime = System.currentTimeMillis();
    this.testMockEmitter = mock(CloudWatchLogsClientEmitter.class);
    this.mockExporter = new AwsCloudWatchEmfExporter(NAMESPACE, this.testMockEmitter);
    this.mockClient = mock(CloudWatchLogsClient.class);
    this.wrapper = spy(new CloudWatchLogsClientEmitter(LOG_GROUP_NAME, LOG_STREAM_NAME, REGION));
    doReturn(this.mockClient).when(this.wrapper).getEmitter();
  }

  @Override
  protected LogEventEmitter<CloudWatchLogsClient> createEmitter() {
    return mock(CloudWatchLogsClientEmitter.class);
  }

  @Override
  protected MetricExporter createExporter() {
    return new AwsCloudWatchEmfExporter(NAMESPACE, this.mockEmitter);
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
  void testShutdown() {
    AwsCloudWatchEmfExporter spyExporter = spy(this.mockExporter);
    doNothing().when(this.testMockEmitter).flushEvents();

    CompletableResultCode result = spyExporter.shutdown();

    assertTrue(result.isSuccess());
    verify(spyExporter).flush();
  }

  @Test
  void testLogEventBatch() {
    long laterTime = this.currentTime + 1000;

    this.wrapper.emit(createLogEvent("first message", this.currentTime));
    this.wrapper.emit(createLogEvent("second message", laterTime));

    // Verify both events are batched together before flush
    verify(this.mockClient, never()).putLogEvents(any(PutLogEventsRequest.class));

    this.wrapper.flushEvents();

    ArgumentCaptor<PutLogEventsRequest> requestCaptor =
        ArgumentCaptor.forClass(PutLogEventsRequest.class);
    verify(this.mockClient).putLogEvents(requestCaptor.capture());

    PutLogEventsRequest request = requestCaptor.getValue();
    assertEquals(2, request.logEvents().size());
    assertEquals("first message", request.logEvents().get(0).message());
    assertEquals("second message", request.logEvents().get(1).message());
    assertEquals(this.currentTime, request.logEvents().get(0).timestamp());
    assertEquals(laterTime, request.logEvents().get(1).timestamp());
  }

  @Test
  void testLogEventsSortedByTimestamp() {
    ArgumentCaptor<PutLogEventsRequest> requestCaptor =
        ArgumentCaptor.forClass(PutLogEventsRequest.class);

    // Add events in non-chronological order
    this.wrapper.emit(createLogEvent("third", this.currentTime + 2000));
    this.wrapper.emit(createLogEvent("first", this.currentTime));
    this.wrapper.emit(createLogEvent("second", this.currentTime + 1000));
    this.wrapper.flushEvents();

    // Verify putLogEvents was called with sorted events
    verify(this.mockClient).putLogEvents(requestCaptor.capture());
    PutLogEventsRequest request = requestCaptor.getValue();

    assertEquals(3, request.logEvents().size());
    assertEquals("first", request.logEvents().get(0).message());
    assertEquals("second", request.logEvents().get(1).message());
    assertEquals("third", request.logEvents().get(2).message());
  }

  @Test
  void testCreateLogStreamIfNeededAlreadyExists() {
    when(this.mockClient.putLogEvents(any(PutLogEventsRequest.class)))
        .thenThrow(ResourceNotFoundException.builder().build())
        .thenReturn(null);
    when(this.mockClient.createLogStream(any(CreateLogStreamRequest.class)))
        .thenThrow(ResourceAlreadyExistsException.builder().build());

    // Should make a call to create a Log Stream if it does not exist.
    assertDoesNotThrow(
        () -> {
          this.wrapper.emit(createLogEvent("test", this.currentTime));
          this.wrapper.flushEvents();
        });

    verify(this.mockClient).createLogStream(any(CreateLogStreamRequest.class));
  }

  @Test
  void testCreateLogGroupIfNeededAlreadyExists() {
    when(this.mockClient.putLogEvents(any(PutLogEventsRequest.class)))
        .thenThrow(ResourceNotFoundException.builder().build())
        .thenReturn(null);
    when(this.mockClient.createLogGroup(any(CreateLogGroupRequest.class)))
        .thenThrow(ResourceAlreadyExistsException.builder().build());

    // Should make a call to create a Log Group if it does not exist.
    assertDoesNotThrow(
        () -> {
          this.wrapper.emit(createLogEvent("test", this.currentTime));
          this.wrapper.flushEvents();
        });

    verify(this.mockClient).createLogGroup(any(CreateLogGroupRequest.class));
  }

  @Test
  void testBatchActiveNewBatch() {
    Map<String, Object> logEvent = createLogEvent("test", this.currentTime);

    // Should batch multiple events with the same timestamp together
    this.wrapper.emit(logEvent);
    this.wrapper.emit(logEvent);
    this.wrapper.emit(logEvent);

    // Should only call emit once per event since batch is active
    verify(this.wrapper, times(3)).emit(logEvent);
  }

  @Test
  void testSendLogEventForceBatchSend() {
    // Send events up to the limit (should all be batched)
    for (int i = 0; i < 10000; i++) {
      this.wrapper.emit(createLogEvent("test message " + i, this.currentTime));
    }

    // At this point, no batch should have been sent yet
    verify(this.mockClient, never()).putLogEvents(any(PutLogEventsRequest.class));

    // Send one more event and should trigger batch send due to count limit
    this.wrapper.emit(createLogEvent("final message", this.currentTime));

    verify(this.mockClient, times(1)).putLogEvents(any(PutLogEventsRequest.class));
  }

  @Test
  void testLogEventBatchClear() {
    this.wrapper.emit(createLogEvent("test", this.currentTime));

    this.wrapper.flushEvents();
    verify(this.mockClient, times(1)).putLogEvents(any(PutLogEventsRequest.class));

    // Add another event after flush - should create new batch
    this.wrapper.emit(createLogEvent("new test", this.currentTime + 1000));

    // Flush again - should send the new event
    this.wrapper.flushEvents();
    verify(this.mockClient, times(2)).putLogEvents(any(PutLogEventsRequest.class));
  }

  @Test
  void testBatch24HourBoundaryEdgeCases() {
    long baseTime = this.currentTime - (25L * 60 * 60 * 1000); // 25 hours ago

    this.wrapper.emit(createLogEvent("first", baseTime));

    // Should still batch the events together at exactly 24 hours
    long exactly24Hours = baseTime + (24L * 60 * 60 * 1000);
    this.wrapper.emit(createLogEvent("boundary", exactly24Hours));

    // Should still be batched - no putLogEvents call yet
    verify(this.mockClient, never()).putLogEvents(any(PutLogEventsRequest.class));

    // Add event just over 24 hour boundary (should trigger new batch)
    long over24Hours = baseTime + (24L * 60 * 60 * 1000 + 1);
    this.wrapper.emit(createLogEvent("over", over24Hours));

    verify(this.mockClient, times(1)).putLogEvents(any(PutLogEventsRequest.class));
  }

  @Test
  void testBatchInactiveAfter24Hours() {
    long futureTime = this.currentTime + (25L * 60 * 60 * 1000); // 25 hours later
    Map<String, Object> firstEvent = createLogEvent("test1", this.currentTime);
    Map<String, Object> secondEvent = createLogEvent("test2", futureTime);

    this.testMockEmitter.emit(firstEvent);
    this.testMockEmitter.emit(secondEvent);

    // Should trigger 2 separate batch sends due to 24-hour span limit
    verify(this.testMockEmitter, times(1)).emit(firstEvent);
    verify(this.testMockEmitter, times(1)).emit(secondEvent);
  }

  @ParameterizedTest
  @MethodSource("batchLimitScenarios")
  void testEventBatchLimits(
      Map<String, Object> logEvent, int eventCount, boolean shouldExceedLimit) {
    for (int i = 0; i < eventCount; i++) {
      this.testMockEmitter.emit(logEvent);
    }

    if (shouldExceedLimit) {
      verify(this.testMockEmitter, atLeast(2)).emit(logEvent);
    } else {
      verify(this.testMockEmitter, times(eventCount)).emit(logEvent);
    }
  }

  @ParameterizedTest
  @MethodSource("invalidLogEvents")
  void testValidateLogEventInvalid(Map<String, Object> logEvent) {
    assertThrows(IllegalArgumentException.class, () -> this.wrapper.emit(logEvent));
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
