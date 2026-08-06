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

package software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.internal;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.AlwaysRecordSampler;
import software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.SpanMetricsProcessor;

/**
 * Discovered by SDK autoconfigure (javaagent, Spring starter, or plain autoconfigure): wraps the
 * resolved sampler and registers the span processor.
 *
 * <p>This class is internal and not part of the public API.
 */
public final class SpanMetricsCustomizerProvider implements AutoConfigurationCustomizerProvider {

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    autoConfiguration.addSamplerCustomizer(
        (sampler, config) -> AlwaysRecordSampler.create(sampler));
    autoConfiguration.addTracerProviderCustomizer(
        (tracerProvider, config) -> tracerProvider.addSpanProcessor(new SpanMetricsProcessor()));
  }
}
