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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.config.ServiceEventsConfig;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.EndpointFilter;

/**
 * Registers the {@link ServiceEventsSpanProcessor} with the OTel SDK tracer provider.
 *
 * <p>This provider is discovered via SPI ({@code
 * META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider})
 * and adds the span processor that feeds endpoint and incident data from OTel spans into
 * ServiceEvents telemetry.
 */
public class ServiceEventsTracerCustomizerProvider implements AutoConfigurationCustomizerProvider {

  // Lazy logger: this class is loaded during agent init via SPI, before logging is ready.
  private static volatile Logger logger;

  private static Logger logger() {
    Logger local = logger;
    if (local == null) {
      local = Logger.getLogger(ServiceEventsTracerCustomizerProvider.class.getName());
      logger = local;
    }
    return local;
  }

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    ServiceEventsConfig config = ServiceEventsConfig.fromEnv();
    if (!config.isEnabled()) {
      logger()
          .info(
              "[SERVICE_EVENTS] ServiceEventsSpanProcessor not registered (serviceevents disabled)");
      return;
    }

    EndpointFilter endpointFilter =
        new EndpointFilter(
            config.getEndpointIncludePatterns(), config.getEndpointExcludePatterns());

    autoConfiguration.addTracerProviderCustomizer(
        (tracerProviderBuilder, configProps) -> {
          logger()
              .info("[SERVICE_EVENTS] Registering ServiceEventsSpanProcessor with TracerProvider");
          tracerProviderBuilder.addSpanProcessor(new ServiceEventsSpanProcessor(endpointFilter));
          return tracerProviderBuilder;
        });
  }
}
