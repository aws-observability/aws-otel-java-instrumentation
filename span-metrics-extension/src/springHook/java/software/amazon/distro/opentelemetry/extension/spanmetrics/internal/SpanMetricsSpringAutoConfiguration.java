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

package software.amazon.distro.opentelemetry.extension.spanmetrics.internal;

import io.opentelemetry.api.OpenTelemetry;
import java.util.logging.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot hook: supplies the starter's OpenTelemetry bean to the holder, since the starter does
 * not publish it to GlobalOpenTelemetry. Resolved at ApplicationReadyEvent to avoid depending on
 * auto-configuration ordering. Loaded only under Spring Boot; ignored elsewhere.
 *
 * <p>This class is internal and not part of the public API.
 */
@AutoConfiguration
public class SpanMetricsSpringAutoConfiguration {

  private static final Logger logger =
      Logger.getLogger(SpanMetricsSpringAutoConfiguration.class.getName());

  @Bean
  ApplicationListener<ApplicationReadyEvent> spanMetricsOpenTelemetryBinder(
      ObjectProvider<OpenTelemetry> openTelemetryProvider) {
    return event -> {
      OpenTelemetry openTelemetry = openTelemetryProvider.getIfAvailable();
      if (openTelemetry != null) {
        OpenTelemetryHolder.set(openTelemetry);
        logger.info("Span metrics: bound to Spring Boot OpenTelemetry bean");
      } else {
        logger.warning("Span metrics: no OpenTelemetry bean found; span metrics disabled");
      }
    };
  }
}
