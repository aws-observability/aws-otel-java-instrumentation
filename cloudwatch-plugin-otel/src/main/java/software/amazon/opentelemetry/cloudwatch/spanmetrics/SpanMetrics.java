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

package software.amazon.opentelemetry.cloudwatch.spanmetrics;

import io.opentelemetry.api.OpenTelemetry;
import software.amazon.opentelemetry.cloudwatch.spanmetrics.internal.OpenTelemetryHolder;

/**
 * Entry point for manual SDK setups. After building the {@link OpenTelemetry} instance, call {@link
 * #bind(OpenTelemetry)} so {@link SpanMetricsProcessor} can obtain a Meter. Auto-configured and
 * javaagent setups do this automatically and do not need to call it.
 */
public final class SpanMetrics {

  /** Supplies the fully-built OpenTelemetry instance the span processor records into. */
  public static void bind(OpenTelemetry openTelemetry) {
    OpenTelemetryHolder.set(openTelemetry);
  }

  private SpanMetrics() {}
}
