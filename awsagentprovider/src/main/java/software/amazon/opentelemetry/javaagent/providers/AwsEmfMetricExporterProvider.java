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

import static software.amazon.opentelemetry.javaagent.providers.AwsApplicationSignalsCustomizerProvider.*;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
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
  private static final Logger logger =
      Logger.getLogger(AwsEmfMetricExporterProvider.class.getName());

  @Override
  public String getName() {
    return "awsemf";
  }

  @Override
  public MetricExporter createExporter(ConfigProperties config) {
    Map<String, String> headers =
        AwsApplicationSignalsConfigUtils.parseOtlpHeaders(
            config.getString(OTEL_EXPORTER_OTLP_LOGS_HEADERS));
    String region = config.getString(AWS_REGION, config.getString(AWS_DEFAULT_REGION));
    String namespace = headers.get(AWS_EMF_METRICS_NAMESPACE);
    boolean addApplicationSignalsDimensions = shouldAddApplicationSignalsDimensionsEnabled(config);

    if (region == null) {
      logger.warning(
          String.format(
              "Improper EMF Exporter configuration: AWS region not found in environment variables please set %s or %s",
              AWS_REGION, AWS_DEFAULT_REGION));
    } else if (headers.containsKey(AWS_OTLP_LOGS_GROUP_HEADER)
        && headers.containsKey(AWS_OTLP_LOGS_STREAM_HEADER)) {
      logger.info(
          "Using the CloudWatch EMF metrics exporter; destination=CloudWatch Logs; authentication=AWS SDK SigV4.");
      return AwsCloudWatchEmfExporter.builder()
          .setNamespace(namespace)
          .setLogGroupName(headers.get(AWS_OTLP_LOGS_GROUP_HEADER))
          .setLogStreamName(headers.get(AWS_OTLP_LOGS_STREAM_HEADER))
          .setAwsRegion(region)
          .setShouldAddApplicationSignalsDimensions(addApplicationSignalsDimensions)
          .build();
    } else if (isLambdaEnvironment(config)) {
      logger.info(
          "Using the console EMF metrics exporter; destination=standard output; authentication=none because the exporter makes no network request.");
      return ConsoleEmfExporter.builder()
          .setNamespace(namespace)
          .setShouldAddApplicationSignalsDimensions(addApplicationSignalsDimensions)
          .build();
    } else {
      logger.warning(
          String.format(
              "Improper EMF Exporter configuration: Please configure the environment variable OTEL_EXPORTER_OTLP_LOGS_HEADERS to have values for %s, %s, and %s",
              AWS_OTLP_LOGS_GROUP_HEADER, AWS_OTLP_LOGS_STREAM_HEADER, AWS_EMF_METRICS_NAMESPACE));
    }

    // Keep invalid EMF configuration nonfatal without creating an unintended OTLP exporter.
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
