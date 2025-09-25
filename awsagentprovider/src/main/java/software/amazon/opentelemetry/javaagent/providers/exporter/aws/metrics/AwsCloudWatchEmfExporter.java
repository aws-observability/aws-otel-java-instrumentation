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
import java.util.logging.Logger;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.BaseEmfExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.emitter.CloudWatchLogsClientEmitter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.emitter.LogEventEmitter;

/**
 * EMF metrics exporter for sending data directly to CloudWatch Logs.
 *
 * <p>This exporter converts OTel metrics into CloudWatch EMF logs which are then sent to CloudWatch
 * Logs. CloudWatch Logs automatically extracts the metrics from the EMF logs.
 *
 * <p><a
 * href="https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_Embedded_Metric_Format_Specification.html">...</a>
 */
public class AwsCloudWatchEmfExporter extends BaseEmfExporter<CloudWatchLogsClient> {
  private static final Logger logger = Logger.getLogger(AwsCloudWatchEmfExporter.class.getName());

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
    super(namespace, new CloudWatchLogsClientEmitter(logGroupName, logStreamName, awsRegion));
  }

  /**
   * Initialize the CloudWatch EMF exporter with a custom emitter.
   *
   * @param namespace CloudWatch namespace for metrics
   * @param emitter Custom log emitter
   */
  public AwsCloudWatchEmfExporter(String namespace, LogEventEmitter<CloudWatchLogsClient> emitter) {
    super(namespace, emitter);
  }

  @Override
  public CompletableResultCode flush() {
    this.emitter.flushEvents();
    logger.fine("AwsCloudWatchEmfExporter force flushes the buffered metrics");
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    this.flush();
    logger.fine("AwsCloudWatchEmfExporter shutdown called");
    return CompletableResultCode.ofSuccess();
  }
}
