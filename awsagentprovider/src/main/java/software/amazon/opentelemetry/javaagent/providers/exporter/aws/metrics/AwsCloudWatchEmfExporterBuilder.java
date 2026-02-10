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

import static java.util.Objects.requireNonNull;

import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.common.emitter.CloudWatchLogsClientEmitter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.common.emitter.LogEventEmitter;

public class AwsCloudWatchEmfExporterBuilder {
  private String namespace;
  private String logGroupName;
  private String logStreamName;
  private String awsRegion;
  private LogEventEmitter<CloudWatchLogsClient> emitter;
  private boolean shouldAddApplicationSignalsDimensions = false;

  public AwsCloudWatchEmfExporterBuilder setNamespace(String namespace) {
    this.namespace = namespace;
    return this;
  }

  public AwsCloudWatchEmfExporterBuilder setLogGroupName(String logGroupName) {
    this.logGroupName = logGroupName;
    return this;
  }

  public AwsCloudWatchEmfExporterBuilder setLogStreamName(String logStreamName) {
    this.logStreamName = logStreamName;
    return this;
  }

  public AwsCloudWatchEmfExporterBuilder setAwsRegion(String awsRegion) {
    this.awsRegion = awsRegion;
    return this;
  }

  public AwsCloudWatchEmfExporterBuilder setEmitter(LogEventEmitter<CloudWatchLogsClient> emitter) {
    this.emitter = emitter;
    return this;
  }

  public AwsCloudWatchEmfExporterBuilder setShouldAddApplicationSignalsDimensions(
      boolean shouldAddApplicationSignalsDimensions) {
    this.shouldAddApplicationSignalsDimensions = shouldAddApplicationSignalsDimensions;
    return this;
  }

  public AwsCloudWatchEmfExporter build() {

    if (this.emitter == null) {
      requireNonNull(logGroupName, "Must set logGroupName when emitter is not provided");
      requireNonNull(logStreamName, "Must set logStreamName when emitter is not provided");
      requireNonNull(awsRegion, "Must set awsRegion when emitter is not provided");
      this.emitter = new CloudWatchLogsClientEmitter(logGroupName, logStreamName, awsRegion);
    }
    return AwsCloudWatchEmfExporter.create(
        this.namespace, this.emitter, this.shouldAddApplicationSignalsDimensions);
  }
}
