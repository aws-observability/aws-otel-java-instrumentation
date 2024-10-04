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

import static io.opentelemetry.api.internal.Utils.checkArgument;
import static java.util.Objects.requireNonNull;

import io.opentelemetry.sdk.internal.DaemonThreadFactory;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

public class ScopeBasedPeriodicMetricReaderBuilder {
  static final long DEFAULT_SCHEDULE_DELAY_MINUTES = 1;
  private final MetricExporter metricExporter;
  private final Set<String> registeredScopeNames;
  private long intervalNanos = TimeUnit.MINUTES.toNanos(DEFAULT_SCHEDULE_DELAY_MINUTES);

  @Nullable private ScheduledExecutorService executor;

  public ScopeBasedPeriodicMetricReaderBuilder(
      MetricExporter metricExporter, Set<String> registeredScopeNames) {
    this.metricExporter = metricExporter;
    this.registeredScopeNames = registeredScopeNames;
  }

  /**
   * Sets the interval of reads. If unset, defaults to {@value DEFAULT_SCHEDULE_DELAY_MINUTES}min.
   */
  public ScopeBasedPeriodicMetricReaderBuilder setInterval(long interval, TimeUnit unit) {
    requireNonNull(unit, "unit");
    checkArgument(interval > 0, "interval must be positive");
    intervalNanos = unit.toNanos(interval);
    return this;
  }

  /**
   * Sets the interval of reads. If unset, defaults to {@value DEFAULT_SCHEDULE_DELAY_MINUTES}min.
   */
  public ScopeBasedPeriodicMetricReaderBuilder setInterval(Duration interval) {
    requireNonNull(interval, "interval");
    return setInterval(interval.toNanos(), TimeUnit.NANOSECONDS);
  }

  /** Sets the {@link ScheduledExecutorService} to schedule reads on. */
  public ScopeBasedPeriodicMetricReaderBuilder setExecutor(ScheduledExecutorService executor) {
    requireNonNull(executor, "executor");
    this.executor = executor;
    return this;
  }

  /** Build a {@link ScopeBasedPeriodicMetricReader} with the configuration of this builder. */
  public ScopeBasedPeriodicMetricReader build() {
    ScheduledExecutorService executor = this.executor;
    if (executor == null) {
      executor =
          Executors.newScheduledThreadPool(
              1, new DaemonThreadFactory("AwsScopeBasedPeriodicMetricReader"));
    }
    return new ScopeBasedPeriodicMetricReader(
        metricExporter, intervalNanos, executor, registeredScopeNames);
  }
}
