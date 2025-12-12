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
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.common.BaseEmfExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.common.emitter.LogEventEmitter;

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

  public static AwsCloudWatchEmfExporterBuilder builder() {
    return new AwsCloudWatchEmfExporterBuilder();
  }

  static AwsCloudWatchEmfExporter create(
      String namespace,
      LogEventEmitter<CloudWatchLogsClient> emitter,
      boolean shouldAddApplicationSignalsDimensions) {
    return new AwsCloudWatchEmfExporter(namespace, emitter, shouldAddApplicationSignalsDimensions);
  }

  private AwsCloudWatchEmfExporter(
      String namespace,
      LogEventEmitter<CloudWatchLogsClient> emitter,
      boolean shouldAddApplicationSignalsDimensions) {
    super(namespace, emitter, shouldAddApplicationSignalsDimensions);
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
