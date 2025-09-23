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

import io.opentelemetry.sdk.common.CompletableResultCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.BaseEmfExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.LogEventEmitter;

/**
 * OpenTelemetry metrics exporter for CloudWatch EMF format.
 *
 * <p>This exporter converts OTel metrics into CloudWatch EMF logs which are then sent to CloudWatch
 * Logs. CloudWatch Logs automatically extracts the metrics from the EMF logs.
 *
 * <p><a
 * href="https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_Embedded_Metric_Format_Specification.html">...</a>
 */
public class AwsCloudWatchEmfExporter extends BaseEmfExporter {
  private static final Logger logger = Logger.getLogger(AwsCloudWatchEmfExporter.class.getName());

  private final LogEventEmitter emitter;

  /**
   * Initialize the CloudWatch EMF exporter.
   *
   * @param namespace CloudWatch namespace for metrics (default: "default")
   * @param logGroupName CloudWatch log group name
   * @param logStreamName CloudWatch log stream name (auto-generated if null)
   * @param awsRegion AWS region
   */
  public AwsCloudWatchEmfExporter(
      String namespace, String logGroupName, String logStreamName, String awsRegion) {
    super(namespace);
    this.emitter = new CloudWatchLogsClientWrapper(logGroupName, logStreamName, awsRegion);
  }

  public AwsCloudWatchEmfExporter(String namespace, LogEventEmitter emitter) {
    super(namespace);
    this.emitter = emitter;
  }

  @Override
  public CompletableResultCode flush() {
    if (emitter instanceof CloudWatchLogsClientWrapper) {
      ((CloudWatchLogsClientWrapper) emitter).flushPendingEvents();
    }
    logger.fine("AwsCloudWatchEmfExporter force flushes the buffered metrics");
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    this.flush();
    logger.fine("AwsCloudWatchEmfExporter shutdown called");
    return CompletableResultCode.ofSuccess();
  }

  @Override
  protected void emit(Map<String, Object> logEvent) {
    this.emitter.emit(logEvent);
  }

  private static StandardRetryStrategy createExponentialBackoffRetryStrategy() {
    // TODO: Add support for Retry-After header:
    // https://opentelemetry.io/docs/specs/otlp/#otlphttp-throttling
    BackoffStrategy backoffStrategy =
        attempt -> {
          // Exponential base-2 backoff: 1s, 2s, 4s, 8s, 16s, 32s, 64s max
          long exponentialDelay =
              Math.min(
                  Duration.ofSeconds(1).toMillis() * (1L << (attempt - 1)),
                  Duration.ofSeconds(64).toMillis());
          return Duration.ofMillis(exponentialDelay);
        };

    return StandardRetryStrategy.builder().backoffStrategy(backoffStrategy).maxAttempts(7).build();
  }

  /**
   * CloudWatch Logs client for batching and sending log events.
   *
   * <p>This class handles the batching logic and CloudWatch Logs API interactions for sending EMF
   * logs while respecting CloudWatch Logs constraints.
   */
  private static class CloudWatchLogsClientWrapper implements LogEventEmitter {
    private static final Logger logger = Logger.getLogger(AwsCloudWatchEmfExporter.class.getName());

    // Constants for CloudWatch Logs limits
    // http://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/cloudwatch_limits_cwl.html
    // http://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutLogEvents.html
    private static final int CW_MAX_EVENT_PAYLOAD_BYTES = 256 * 1024; // 256KB
    private static final int CW_MAX_REQUEST_EVENT_COUNT = 10000;
    private static final int CW_PER_EVENT_HEADER_BYTES = 26;
    private static final long BATCH_FLUSH_INTERVAL = 60 * 1000; // 60 seconds
    private static final int CW_MAX_REQUEST_PAYLOAD_BYTES = 1024 * 1024; // 1MB
    private static final String CW_TRUNCATED_SUFFIX = "[Truncated...]";
    // None of the log events in the batch can be older than 14 days
    private static final long CW_EVENT_TIMESTAMP_LIMIT_PAST = 14 * 24 * 60 * 60 * 1000L;
    // None of the log events in the batch can be more than 2 hours in the future
    private static final long CW_EVENT_TIMESTAMP_LIMIT_FUTURE = 2 * 60 * 60 * 1000L;

    private CloudWatchLogsClient logsClient;
    private final String logGroupName;
    private final String logStreamName;
    private final String awsRegion;
    private LogEventBatch eventBatch;

    /**
     * Initialize the CloudWatch Logs client wrapper.
     *
     * @param logGroupName CloudWatch log group name
     * @param logStreamName CloudWatch log stream name (auto-generated if null)
     * @param awsRegion AWS region
     */
    private CloudWatchLogsClientWrapper(
        String logGroupName, String logStreamName, String awsRegion) {
      this.logGroupName = logGroupName;
      this.logStreamName = logStreamName != null ? logStreamName : generateLogStreamName();
      this.awsRegion = awsRegion;
    }

    private CloudWatchLogsClient getLogsClient() {
      if (this.logsClient == null) {
        // TODO: Add support for Retry-After header:
        // https://opentelemetry.io/docs/specs/otlp/#otlphttp-throttling
        // Current implementation uses exponential backoff but doesn't respect server-provided retry
        // delays
        this.logsClient =
            CloudWatchLogsClient.builder()
                .overrideConfiguration(
                    config -> config.retryStrategy(createExponentialBackoffRetryStrategy()).build())
                .region(Region.of(this.awsRegion))
                .build();
      }
      return this.logsClient;
    }

    /** Generate a unique log stream name. */
    private String generateLogStreamName() {
      String uniqueId = UUID.randomUUID().toString().substring(0, 8);
      return "otel-java-" + uniqueId;
    }

    /** Create log group if it doesn't exist. */
    private void createLogGroupIfNeeded() {
      try {
        CreateLogGroupRequest request =
            CreateLogGroupRequest.builder().logGroupName(this.logGroupName).build();

        this.getLogsClient().createLogGroup(request);
        logger.info("Created log group: " + this.logGroupName);
      } catch (ResourceAlreadyExistsException e) {
        logger.fine("Log group " + this.logGroupName + " already exists");
      } catch (AwsServiceException e) {
        logger.severe("Failed to create log group " + this.logGroupName + ": " + e.getMessage());
        throw e;
      }
    }

    /** Create log stream if it doesn't exist. */
    private void createLogStreamIfNeeded() {
      try {
        CreateLogStreamRequest request =
            CreateLogStreamRequest.builder()
                .logGroupName(this.logGroupName)
                .logStreamName(this.logStreamName)
                .build();

        this.getLogsClient().createLogStream(request);
        logger.info("Created log stream: " + this.logStreamName);
      } catch (ResourceAlreadyExistsException e) {
        logger.fine("Log stream " + this.logStreamName + " already exists");
      } catch (AwsServiceException e) {
        logger.severe("Failed to create log stream " + this.logStreamName + ": " + e.getMessage());
        throw e;
      }
    }

    @Override
    public void emit(Map<String, Object> logEvent) {
      try {
        if (!isValidLogEvent(logEvent)) {
          throw new IllegalArgumentException("Log event validation failed");
        }

        String message = (String) logEvent.get("message");
        Long timestamp = (Long) logEvent.get("timestamp");
        int eventSize = message.length() + CW_PER_EVENT_HEADER_BYTES;

        if (eventBatch == null) {
          eventBatch = new LogEventBatch();
        }

        LogEventBatch currentBatch = eventBatch;

        if (willEventBatchExceedLimit(currentBatch, eventSize)
            || !isBatchActive(currentBatch, timestamp)) {
          this.sendLogBatch(currentBatch.getLogEvents());
          eventBatch = new LogEventBatch();
          currentBatch = eventBatch;
        }

        currentBatch.addEvent(message, timestamp, eventSize);

      } catch (Exception error) {
        logger.severe("Failed to process log event: " + error.getMessage());
        throw error;
      }
    }

    private void flushPendingEvents() {
      if (eventBatch != null && !eventBatch.getLogEvents().isEmpty()) {
        LogEventBatch currentBatch = eventBatch;
        this.sendLogBatch(currentBatch.getLogEvents());
        eventBatch = new LogEventBatch();
      }
      logger.fine("CloudWatchLogClient flushed the buffered log events");
    }

    /**
     * Send a batch of log events to CloudWatch Logs. Creates log group and stream if they don't
     * exist.
     *
     * @param batch The event batch to send
     */
    private void sendLogBatch(List<InputLogEvent> batch) {
      if (batch.isEmpty()) {
        return;
      }
      batch.sort(Comparator.comparing(InputLogEvent::timestamp));

      PutLogEventsRequest request =
          PutLogEventsRequest.builder()
              .logGroupName(this.logGroupName)
              .logStreamName(this.logStreamName)
              .logEvents(batch)
              .build();

      long startTime = System.currentTimeMillis();

      try {
        this.getLogsClient().putLogEvents(request);

        long elapsedMs = System.currentTimeMillis() - startTime;
        int batchSizeKB =
            batch.stream().mapToInt(logEvent -> logEvent.message().length()).sum() / 1024;

        logger.fine(
            "Successfully sent "
                + batch.size()
                + " log events ("
                + batchSizeKB
                + " KB) in "
                + elapsedMs
                + " ms");

      } catch (ResourceNotFoundException e) {
        logger.info("Log group or stream not found, creating resources and retrying");
        try {
          createLogGroupIfNeeded();
          createLogStreamIfNeeded();

          // Retry the PutLogEvents call
          this.getLogsClient().putLogEvents(request);

          long elapsedMs = System.currentTimeMillis() - startTime;
          int batchSizeKB =
              batch.stream().mapToInt(logEvent -> logEvent.message().length()).sum() / 1024;
          logger.fine(
              "Successfully sent "
                  + batch.size()
                  + " log events ("
                  + batchSizeKB
                  + " KB) in "
                  + elapsedMs
                  + " ms after creating resources");

        } catch (AwsServiceException retryError) {
          logger.severe(
              "Failed to send log events after creating resources: " + retryError.getMessage());
          throw retryError;
        }
      } catch (AwsServiceException e) {
        logger.severe("Failed to send log events: " + e.getMessage());
        throw e;
      }
    }

    /**
     * Validate the log event according to CloudWatch Logs constraints. Truncates the log event
     * message to CW_MAX_EVENT_PAYLOAD_BYTES if it exceeds size limits.
     *
     * @param logEvent The log event to validate
     * @return True if the log event is valid, false otherwise
     */
    private boolean isValidLogEvent(Map<String, Object> logEvent) {
      String message = (String) logEvent.get("message");
      Long timestamp = (Long) logEvent.get("timestamp");

      if (timestamp == null) {
        timestamp = System.currentTimeMillis();
      }

      // Check empty message
      if (message == null || message.trim().isEmpty()) {
        logger.severe("Empty log event message");
        return false;
      }

      // Check message size
      int messageSize = message.length() + CW_PER_EVENT_HEADER_BYTES;
      if (messageSize > CW_MAX_EVENT_PAYLOAD_BYTES) {
        logger.warning(
            "Log event size "
                + messageSize
                + " exceeds maximum allowed size "
                + CW_MAX_EVENT_PAYLOAD_BYTES
                + ". Truncating.");
        int maxMessageSize =
            CW_MAX_EVENT_PAYLOAD_BYTES - CW_PER_EVENT_HEADER_BYTES - CW_TRUNCATED_SUFFIX.length();
        logEvent.put("message", message.substring(0, maxMessageSize) + CW_TRUNCATED_SUFFIX);
      }

      // Check timestamp constraints
      long currentTime = System.currentTimeMillis();
      long timeDiff = currentTime - timestamp;

      // Check if too old or too far in the future
      if (timeDiff > CW_EVENT_TIMESTAMP_LIMIT_PAST
          || timeDiff < -1 * CW_EVENT_TIMESTAMP_LIMIT_FUTURE) {
        logger.severe(
            "Log event timestamp "
                + timestamp
                + " is either older than 14 days or more than 2 hours in the future. "
                + "Current time: "
                + currentTime);
        return false;
      }

      return true;
    }

    /**
     * Will adding the next log event exceed CloudWatch Logs limits?
     *
     * @param batch The current batch of events
     * @param nextEventSize Size of the next event in bytes
     * @return True if adding the next event would exceed limits
     */
    private boolean willEventBatchExceedLimit(LogEventBatch batch, int nextEventSize) {
      int currentBatchSize = 0;
      for (InputLogEvent event : batch.getLogEvents()) {
        currentBatchSize += event.message().length() + CW_PER_EVENT_HEADER_BYTES;
      }

      return batch.size() >= CW_MAX_REQUEST_EVENT_COUNT
          || currentBatchSize + nextEventSize > CW_MAX_REQUEST_PAYLOAD_BYTES;
    }

    /**
     * Has the log event batch spanned for more than 24 hours?
     *
     * @param batch The log event batch
     * @param targetTimestamp The timestamp of the event to add
     * @return True if the batch is active and can accept the event
     */
    private boolean isBatchActive(LogEventBatch batch, long targetTimestamp) {
      // New log event batch
      if (batch.getMinTimestampMs() == 0 || batch.getMaxTimestampMs() == 0) {
        return true;
      }

      // Check if adding the event would make the batch span more than 24 hours
      if (targetTimestamp - batch.getMinTimestampMs() > 24 * 3600 * 1000L) {
        return false;
      }

      if (batch.getMaxTimestampMs() - targetTimestamp > 24 * 3600 * 1000L) {
        return false;
      }

      // Flush the event batch when reached 60s interval
      return System.currentTimeMillis() - batch.getCreatedTimestampMs() < BATCH_FLUSH_INTERVAL;
    }

    /**
     * Container for a batch of CloudWatch log events with metadata.
     *
     * <p>Tracks the log events, total byte size, and timestamps for efficient batching and
     * validation.
     */
    private static class LogEventBatch {
      private final List<InputLogEvent> logEvents = new ArrayList<>();
      private int byteTotal = 0;
      private long minTimestampMs = 0;
      private long maxTimestampMs = 0;
      private final long createdTimestampMs = System.currentTimeMillis();

      private void addEvent(String message, Long timestamp, int eventSize) {
        if (timestamp == null) {
          timestamp = System.currentTimeMillis();
        }

        InputLogEvent inputLogEvent =
            InputLogEvent.builder().message(message).timestamp(timestamp).build();

        logEvents.add(inputLogEvent);
        byteTotal += eventSize;

        if (minTimestampMs == 0 || timestamp < minTimestampMs) {
          minTimestampMs = timestamp;
        }
        if (maxTimestampMs == 0 || timestamp > maxTimestampMs) {
          maxTimestampMs = timestamp;
        }
      }

      private List<InputLogEvent> getLogEvents() {
        return logEvents;
      }

      private int getByteTotal() {
        return byteTotal;
      }

      private long getMinTimestampMs() {
        return minTimestampMs;
      }

      private long getMaxTimestampMs() {
        return maxTimestampMs;
      }

      private long getCreatedTimestampMs() {
        return createdTimestampMs;
      }

      private boolean isEmpty() {
        return logEvents.isEmpty();
      }

      private int size() {
        return logEvents.size();
      }

      private void clear() {
        logEvents.clear();
        byteTotal = 0;
        minTimestampMs = 0;
        maxTimestampMs = 0;
      }
    }
  }
}
