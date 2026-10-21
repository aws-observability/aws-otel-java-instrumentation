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

package software.amazon.opentelemetry.javaagent.providers;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.util.Collection;
import java.util.Map;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.AwsCloudWatchEmfExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.ConsoleEmfExporter;

/** Selects the EMF destination for the OpenTelemetry {@code awsemf} metric exporter. */
public final class AwsEmfMetricExporterProvider implements ConfigurableMetricExporterProvider {
  private static final String LOG_GROUP_HEADER = "x-aws-log-group";
  private static final String LOG_STREAM_HEADER = "x-aws-log-stream";
  private static final String METRICS_NAMESPACE_HEADER = "x-aws-metric-namespace";
  private static final Logger logger =
      Logger.getLogger(AwsEmfMetricExporterProvider.class.getName());

  @Override
  public String getName() {
    return "awsemf";
  }

  @Override
  public MetricExporter createExporter(ConfigProperties config) {
    Map<String, String> headers;
    try {
      headers = config.getMap("otel.exporter.otlp.logs.headers");
    } catch (ConfigurationException e) {
      logger.warning("Improper EMF Exporter configuration: " + e.getMessage());
      return DisabledEmfExporter.INSTANCE;
    }
    String region = config.getString("aws.region", config.getString("aws.default.region"));
    String namespace = headers.get(METRICS_NAMESPACE_HEADER);
    boolean addApplicationSignalsDimensions =
        config.getBoolean("otel.metrics.add.application.signals.dimensions", true);

    if (region == null || region.trim().isEmpty()) {
      logger.warning(
          "awsemf requires an AWS region. Set AWS_REGION or AWS_DEFAULT_REGION "
              + "(aws.region or aws.default.region).");
      return DisabledEmfExporter.INSTANCE;
    }

    if (headers.containsKey(LOG_GROUP_HEADER) && headers.containsKey(LOG_STREAM_HEADER)) {
      logger.info(
          "Using the CloudWatch EMF metrics exporter; destination=CloudWatch Logs; authentication=AWS SDK SigV4.");
      return AwsCloudWatchEmfExporter.builder()
          .setNamespace(namespace)
          .setLogGroupName(headers.get(LOG_GROUP_HEADER))
          .setLogStreamName(headers.get(LOG_STREAM_HEADER))
          .setAwsRegion(region)
          .setShouldAddApplicationSignalsDimensions(addApplicationSignalsDimensions)
          .build();
    } else if (config.getString("aws.lambda.function.name") != null) {
      logger.info(
          "Using the console EMF metrics exporter; destination=standard output; authentication=none because the exporter makes no network request.");
      return ConsoleEmfExporter.builder()
          .setNamespace(namespace)
          .setShouldAddApplicationSignalsDimensions(addApplicationSignalsDimensions)
          .build();
    }

    logger.warning(
        "awsemf requires "
            + LOG_GROUP_HEADER
            + " and "
            + LOG_STREAM_HEADER
            + " in OTEL_EXPORTER_OTLP_LOGS_HEADERS (otel.exporter.otlp.logs.headers) outside Lambda.");
    return DisabledEmfExporter.INSTANCE;
  }

  private enum DisabledEmfExporter implements MetricExporter {
    INSTANCE;

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
      return AggregationTemporality.DELTA;
    }

    @Override
    public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
      return Aggregation.drop();
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
