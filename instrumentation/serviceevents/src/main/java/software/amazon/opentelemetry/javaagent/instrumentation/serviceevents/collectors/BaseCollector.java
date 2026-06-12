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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsOtlpEmitter;

/**
 * Base class for periodic telemetry collectors.
 *
 * <p>Provides common functionality for background collection threads, start/stop lifecycle, and
 * periodic flushing.
 */
public abstract class BaseCollector {

  private static final Logger logger = Logger.getLogger(BaseCollector.class.getName());

  private static final int MIN_FLUSH_INTERVAL_MS = 1000;
  private static final int MAX_FLUSH_INTERVAL_MS = 300000;

  protected volatile int flushIntervalMs;
  protected final String name;
  protected final ServiceEventsOtlpEmitter otlpEmitter;

  private Thread collectorThread;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final CountDownLatch stopLatch = new CountDownLatch(1);

  /**
   * Initialize the base collector with OTLP emitter.
   *
   * @param flushIntervalMs How often to collect data (milliseconds)
   * @param name Name of the collector for logging
   * @param otlpEmitter Optional OTLP emitter for sending OTLP signals
   */
  protected BaseCollector(int flushIntervalMs, String name, ServiceEventsOtlpEmitter otlpEmitter) {
    this.flushIntervalMs = flushIntervalMs;
    this.name = name;
    this.otlpEmitter = otlpEmitter;
  }

  /** Start the collector background thread. */
  public void start() {
    if (running.get()) {
      logger.warning(name + " already running");
      return;
    }

    logger.info("Starting " + name + " (interval: " + flushIntervalMs + "ms)");
    running.set(true);

    collectorThread =
        new Thread(
            () -> {
              runCollectionLoop();
            },
            name);
    collectorThread.setDaemon(true);
    collectorThread.start();
  }

  /** Stop the collector background thread. */
  public void stop() {
    if (!running.get()) {
      return;
    }

    logger.info("Stopping " + name);
    running.set(false);
    stopLatch.countDown();

    if (collectorThread != null && collectorThread.isAlive()) {
      try {
        collectorThread.join(5000);
        if (collectorThread.isAlive()) {
          logger.warning(name + " thread did not stop cleanly");
          collectorThread.interrupt();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Main collection loop (runs in background thread). */
  private void runCollectionLoop() {
    logger.fine(name + " collection loop started");

    try {
      while (running.get()) {
        try {
          collect();
        } catch (Throwable e) {
          logger.log(Level.SEVERE, "Error in " + name + " collection", e);
        }

        try {
          stopLatch.await(flushIntervalMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }

      // Final collection on shutdown
      try {
        logger.fine(name + " performing final collection");
        collect();
      } catch (Throwable e) {
        logger.log(Level.SEVERE, "Error in " + name + " final collection", e);
      }

    } finally {
      logger.fine(name + " collection loop stopped");
    }
  }

  /** Check if collector is running. */
  public boolean isRunning() {
    return running.get();
  }

  /** Get current flush interval in milliseconds. */
  public int getFlushIntervalMs() {
    return flushIntervalMs;
  }

  /**
   * Update the flush interval at runtime.
   *
   * <p>Values are clamped to [{@value #MIN_FLUSH_INTERVAL_MS}, {@value #MAX_FLUSH_INTERVAL_MS}].
   * The new interval takes effect on the next sleep cycle.
   *
   * @param newInterval new flush interval in milliseconds
   */
  public void setFlushIntervalMs(int newInterval) {
    if (newInterval < MIN_FLUSH_INTERVAL_MS) {
      logger.log(
          Level.WARNING,
          "{0}: flush interval {1}ms below minimum, clamping to {2}ms",
          new Object[] {name, newInterval, MIN_FLUSH_INTERVAL_MS});
      newInterval = MIN_FLUSH_INTERVAL_MS;
    } else if (newInterval > MAX_FLUSH_INTERVAL_MS) {
      logger.log(
          Level.WARNING,
          "{0}: flush interval {1}ms above maximum, clamping to {2}ms",
          new Object[] {name, newInterval, MAX_FLUSH_INTERVAL_MS});
      newInterval = MAX_FLUSH_INTERVAL_MS;
    }
    if (newInterval != this.flushIntervalMs) {
      logger.log(
          Level.INFO,
          "{0}: flush interval changed from {1}ms to {2}ms",
          new Object[] {name, this.flushIntervalMs, newInterval});
      this.flushIntervalMs = newInterval;
    }
  }

  /**
   * Collect and export telemetry data.
   *
   * <p>This method is called periodically by the background thread. Subclasses must implement this
   * method to define collection behavior.
   */
  protected abstract void collect();
}
