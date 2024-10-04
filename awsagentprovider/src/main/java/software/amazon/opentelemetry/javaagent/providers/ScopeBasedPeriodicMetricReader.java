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

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * The {@code ScopeBasedPeriodicMetricReader} class is a customized implementation to extend the
 * functionality of the {@link io.opentelemetry.sdk.metrics.export.PeriodicMetricReader}. Due to the
 * fact that {@link io.opentelemetry.sdk.metrics.export.PeriodicMetricReader} is a final class and
 * cannot be directly extended, this class duplicates and modifies the relevant code to support
 * scope-based metric reading.
 *
 * <p>Source code based on opentelemetry-java v1.34.1.
 */
public class ScopeBasedPeriodicMetricReader implements MetricReader {
  private static final Logger logger =
      Logger.getLogger(ScopeBasedPeriodicMetricReader.class.getName());

  private final MetricExporter exporter;
  private final long intervalNanos;
  private final ScheduledExecutorService scheduler;
  private final Scheduled scheduled;
  private final Object lock = new Object();
  private volatile CollectionRegistration collectionRegistration = CollectionRegistration.noop();

  @Nullable private volatile ScheduledFuture<?> scheduledFuture;

  public static ScopeBasedPeriodicMetricReaderBuilder create(
      MetricExporter exporter, Set<String> registeredScopeNames) {
    return new ScopeBasedPeriodicMetricReaderBuilder(exporter, registeredScopeNames);
  }

  ScopeBasedPeriodicMetricReader(
      MetricExporter exporter,
      long intervalNanos,
      ScheduledExecutorService scheduler,
      Set<String> registeredScopeNames) {
    this.exporter = exporter;
    this.intervalNanos = intervalNanos;
    this.scheduler = scheduler;
    this.scheduled = new Scheduled(registeredScopeNames);
  }

  /**
   * This method is a direct copy from the{@link
   * io.opentelemetry.sdk.metrics.export.PeriodicMetricReader} class and has not been modified.
   */
  @Override
  public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
    return exporter.getAggregationTemporality(instrumentType);
  }

  /**
   * This method is a direct copy from the{@link
   * io.opentelemetry.sdk.metrics.export.PeriodicMetricReader} class and has not been modified.
   */
  @Override
  public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
    return exporter.getDefaultAggregation(instrumentType);
  }

  /**
   * This method is a direct copy from the{@link
   * io.opentelemetry.sdk.metrics.export.PeriodicMetricReader} class and has not been modified.
   */
  @Override
  public MemoryMode getMemoryMode() {
    return exporter.getMemoryMode();
  }

  /**
   * This method is a direct copy from the{@link
   * io.opentelemetry.sdk.metrics.export.PeriodicMetricReader} class and has not been modified.
   */
  @Override
  public CompletableResultCode forceFlush() {
    return scheduled.doRun();
  }

  /**
   * This method is a direct copy from the{@link
   * io.opentelemetry.sdk.metrics.export.PeriodicMetricReader} class and has not been modified.
   */
  @Override
  public CompletableResultCode shutdown() {
    CompletableResultCode result = new CompletableResultCode();
    ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
    if (scheduledFuture != null) {
      scheduledFuture.cancel(false);
    }
    scheduler.shutdown();
    try {
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
      CompletableResultCode flushResult = scheduled.doRun();
      flushResult.join(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      // force a shutdown if the export hasn't finished.
      scheduler.shutdownNow();
      // reset the interrupted status
      Thread.currentThread().interrupt();
    } finally {
      CompletableResultCode shutdownResult = scheduled.shutdown();
      shutdownResult.whenComplete(
          () -> {
            if (!shutdownResult.isSuccess()) {
              result.fail();
            } else {
              result.succeed();
            }
          });
    }
    return result;
  }

  /**
   * This method is a direct copy from the{@link
   * io.opentelemetry.sdk.metrics.export.PeriodicMetricReader} class and has not been modified.
   */
  @Override
  public void register(CollectionRegistration collectionRegistration) {
    this.collectionRegistration = collectionRegistration;
    start();
  }

  @Override
  public String toString() {
    return "ScopeBasedPeriodicMetricReader{"
        + "exporter="
        + exporter
        + ", intervalNanos="
        + intervalNanos
        + '}';
  }

  /**
   * This method is a direct copy from the{@link
   * io.opentelemetry.sdk.metrics.export.PeriodicMetricReader} class and has not been modified.
   */
  void start() {
    synchronized (lock) {
      if (scheduledFuture != null) {
        return;
      }
      scheduledFuture =
          scheduler.scheduleAtFixedRate(
              scheduled, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
    }
  }

  private final class Scheduled implements Runnable {
    private final AtomicBoolean exportAvailable = new AtomicBoolean(true);
    private final Set<String> registeredScopeNames;

    private Scheduled(Set<String> registeredScopeNames) {
      this.registeredScopeNames = registeredScopeNames;
    }

    @Override
    public void run() {
      // Ignore the CompletableResultCode from doRun() in order to keep run() asynchronous
      doRun();
    }

    // Runs a collect + export cycle.
    CompletableResultCode doRun() {
      CompletableResultCode flushResult = new CompletableResultCode();
      if (exportAvailable.compareAndSet(true, false)) {
        try {
          Collection<MetricData> metricData = collectionRegistration.collectAllMetrics();
          if (metricData.isEmpty()) {
            logger.log(Level.FINE, "No metric data to export - skipping export.");
            flushResult.succeed();
            exportAvailable.set(true);
          } else {
            List<MetricData> exportingMetrics = new LinkedList<>();
            for (MetricData metricDatum : metricData) {
              String scopeName = metricDatum.getInstrumentationScopeInfo().getName();
              if (registeredScopeNames.contains(scopeName)) {
                exportingMetrics.add(metricDatum);
              }
            }
            CompletableResultCode result = exporter.export(exportingMetrics);
            result.whenComplete(
                () -> {
                  if (!result.isSuccess()) {
                    logger.log(Level.FINE, "Exporter failed");
                  }
                  flushResult.succeed();
                  exportAvailable.set(true);
                });
          }
        } catch (Throwable t) {
          exportAvailable.set(true);
          logger.log(Level.WARNING, "Exporter threw an Exception", t);
          flushResult.fail();
        }
      } else {
        logger.log(Level.FINE, "Exporter busy. Dropping metrics.");
        flushResult.fail();
      }
      return flushResult;
    }

    CompletableResultCode shutdown() {
      return exporter.shutdown();
    }
  }
}
