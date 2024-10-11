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
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.View;
import java.time.Duration;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SDKMeterProviderBuilder {
  static void configureMetricFilter(
      ConfigProperties configProps,
      SdkMeterProviderBuilder sdkMeterProviderBuilder,
      Set<String> registeredScopeNames,
      Logger logger) {
    Set<String> exporterNames =
        DefaultConfigProperties.getSet(configProps, "otel.metrics.exporter");
    if (exporterNames.contains("none")) {
      for (String scope : registeredScopeNames) {
        sdkMeterProviderBuilder.registerView(
            InstrumentSelector.builder().setMeterName(scope).build(),
            View.builder().setAggregation(Aggregation.defaultAggregation()).build());

        logger.log(Level.FINE, "Registered scope {0}", scope);
      }
      sdkMeterProviderBuilder.registerView(
          InstrumentSelector.builder().setName("*").build(),
          View.builder().setAggregation(Aggregation.drop()).build());
    }
  }

  static Duration getMetricExportInterval(
      ConfigProperties configProps, Duration exportIntervalEnvVar, Logger logger) {
    Duration exportInterval =
        configProps.getDuration("otel.metric.export.interval", exportIntervalEnvVar);
    logger.log(Level.FINE, String.format("Metrics export interval: %s", exportInterval));
    // Cap export interval to 60 seconds. This is currently required for metrics-trace correlation
    // to work correctly.
    if (exportInterval.compareTo(exportIntervalEnvVar) > 0) {
      exportInterval = exportIntervalEnvVar;
      logger.log(Level.INFO, String.format("Metrics export interval capped to %s", exportInterval));
    }
    return exportInterval;
  }
}
