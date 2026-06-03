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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationType;

/**
 * Background threads that poll the API for PROBE and BREAKPOINT configurations separately.
 *
 * <p>This class manages two independent polling threads:
 *
 * <ul>
 *   <li>PROBE thread: Polls every 10 minutes (configurable)
 *   <li>BREAKPOINT thread: Polls every 1 minute (configurable)
 * </ul>
 *
 * <p>Each poller uses exponential backoff for initial fetch attempts (10s, 30s, 120s). If the API
 * endpoint remains unreachable after 3 attempts, the poller enters a degraded polling mode (every
 * 300s) and keeps retrying indefinitely until the agent becomes available.
 *
 * <p>PROBE and BREAKPOINT configurations are cached separately and merged atomically before
 * application via the bytecode engine.
 */
public final class ConfigurationPoller {
  private static final Logger logger = Logger.getLogger(ConfigurationPoller.class.getName());

  // Staleness thresholds
  private static final int PROBE_STALENESS_THRESHOLD = 30 * 60; // 30 minutes
  private static final int BREAKPOINT_STALENESS_THRESHOLD = 5 * 60; // 5 minutes
  private static final int BASE_BACKOFF_INTERVAL = 10; // seconds
  private static final int MAX_INITIAL_FETCH_ATTEMPTS = 3;
  private static final int DEGRADED_POLL_INTERVAL =
      300; // 5 minutes — used when API endpoint is unreachable

  private final DynamicInstrumentationClient client;
  private final Random random = new Random();

  // Thread management
  private Thread probeThread;
  private Thread breakpointThread;
  private volatile boolean running;
  private CountDownLatch stopLatch;

  // Synchronization for PROBE/BREAKPOINT cache and registry updates
  private final Object configLock = new Object();

  // Configuration caching for PROBE/BREAKPOINT
  private List<InstrumentationConfiguration> cachedProbeConfigs = new ArrayList<>();
  private List<InstrumentationConfiguration> cachedBreakpointConfigs = new ArrayList<>();

  // State tracking for removal detection (PROBE/BREAKPOINT only)
  private Set<String> previousConfigKeys = new HashSet<>();

  // Fingerprint of last successfully applied config set (locationHash:createdAt per config).
  // Used to skip redundant retransformation when API returns Changed:true but configs are
  // identical.
  private Set<String> lastAppliedFingerprint = new HashSet<>();

  // State tracking
  private Long probeLastSyncTime;
  private Long breakpointLastSyncTime;
  private Long probeLastSuccessTime;
  private Long breakpointLastSuccessTime;

  public ConfigurationPoller(DynamicInstrumentationClient client) {
    this.client = client;
    logger.fine("Initialized ConfigurationPoller");
  }

  /** Start all polling threads. */
  public void start() {
    if (running) {
      logger.warning("Configuration poller already running");
      return;
    }

    running = true;
    stopLatch = new CountDownLatch(2);

    // Start PROBE polling thread
    probeThread = new Thread(this::pollProbesLoop, "ProbePoller");
    probeThread.setDaemon(true);
    probeThread.start();
    logger.log(
        Level.FINE,
        "Started PROBE polling thread (interval: {0}s)",
        client.getConfig().getProbePollIntervalSeconds());

    // Start BREAKPOINT polling thread
    breakpointThread = new Thread(this::pollBreakpointsLoop, "BreakpointPoller");
    breakpointThread.setDaemon(true);
    breakpointThread.start();
    logger.log(
        Level.FINE,
        "Started BREAKPOINT polling thread (interval: {0}s)",
        client.getConfig().getBreakpointPollIntervalSeconds());

    logger.fine("Configuration poller started");
  }

  /** Stop all polling threads and wait for completion. */
  public void stop() {
    if (!running) {
      logger.warning("Configuration poller not running");
      return;
    }

    logger.fine("Stopping configuration poller...");
    running = false;

    try {
      if (!stopLatch.await(10, TimeUnit.SECONDS)) {
        logger.warning("Polling threads did not stop within timeout");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.warning("Interrupted while waiting for polling threads to stop");
    }

    logger.fine("Configuration poller stopped");
  }

  /** PROBE polling loop with exponential backoff for initial fetch. */
  private void pollProbesLoop() {
    logger.log(
        Level.FINE,
        "Starting PROBE polling loop for {0}:{1}",
        new Object[] {client.getServiceName(), client.getEnvironment()});

    boolean isFirstFetch = true;
    int attempt = 0;

    try {
      while (running) {
        try {
          long waitMs =
              calculateWaitInterval(
                  isFirstFetch, attempt, client.getConfig().getProbePollIntervalSeconds());

          if (waitMs > 0) {
            Thread.sleep(waitMs);
          }

          if (!running) break;

          logger.fine("Fetching PROBE configuration");
          ApiResponse response =
              client.fetchConfigurationByType(InstrumentationType.PROBE, probeLastSyncTime);

          if (response == null) {
            if (isFirstFetch) {
              attempt++;
              checkDegradedMode("PROBE", attempt);
              logger.log(
                  Level.WARNING, "[PROBE] Initial fetch attempt {0} failed, will retry", attempt);
              continue;
            } else {
              logger.warning("[PROBE] Fetch failed, continuing with cached configuration");
              checkStaleness();
              continue;
            }
          }

          if (response.getSyncedAt() != null) {
            probeLastSyncTime = response.getSyncedAt();
          }

          if (response.isChanged()) {
            List<InstrumentationConfiguration> probeConfigs = client.parseConfigurations(response);
            applyMergedConfiguration(probeConfigs, null);
            probeLastSuccessTime = System.currentTimeMillis();
          }

          if (isFirstFetch) {
            logger.fine("Initial PROBE configuration fetch successful");
            isFirstFetch = false;
            attempt = 0;
          }

        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unexpected error during PROBE configuration polling", e);
          if (isFirstFetch) {
            attempt++;
            checkDegradedMode("PROBE", attempt);
          }
        } catch (Throwable t) {
          if (t instanceof ThreadDeath) throw (ThreadDeath) t;
          // Fatal error (e.g., OutOfMemoryError, NoClassDefFoundError).
          // Log and stop this thread gracefully — DI polling will cease.
          logger.log(Level.SEVERE, "DI: Fatal error in PROBE polling thread, DI will stop", t);
          break;
        }
      }
    } finally {
      logger.fine("PROBE polling loop ended");
      stopLatch.countDown();
    }
  }

  /** BREAKPOINT polling loop with exponential backoff for initial fetch. */
  private void pollBreakpointsLoop() {
    logger.log(
        Level.FINE,
        "Starting BREAKPOINT polling loop for {0}:{1}",
        new Object[] {client.getServiceName(), client.getEnvironment()});

    boolean isFirstFetch = true;
    int attempt = 0;

    try {
      while (running) {
        try {
          long waitMs =
              calculateWaitInterval(
                  isFirstFetch, attempt, client.getConfig().getBreakpointPollIntervalSeconds());

          if (waitMs > 0) {
            Thread.sleep(waitMs);
          }

          if (!running) break;

          logger.fine("Fetching BREAKPOINT configuration");
          ApiResponse response =
              client.fetchConfigurationByType(
                  InstrumentationType.BREAKPOINT, breakpointLastSyncTime);

          if (response == null) {
            if (isFirstFetch) {
              attempt++;
              checkDegradedMode("BREAKPOINT", attempt);
              logger.log(
                  Level.WARNING,
                  "[BREAKPOINT] Initial fetch attempt {0} failed, will retry",
                  attempt);
              continue;
            } else {
              logger.warning("[BREAKPOINT] Fetch failed, continuing with cached configuration");
              checkStaleness();
              continue;
            }
          }

          if (response.getSyncedAt() != null) {
            breakpointLastSyncTime = response.getSyncedAt();
          }

          if (response.isChanged()) {
            List<InstrumentationConfiguration> breakpointConfigs =
                client.parseConfigurations(response);
            applyMergedConfiguration(null, breakpointConfigs);
            breakpointLastSuccessTime = System.currentTimeMillis();
          }

          if (isFirstFetch) {
            logger.fine("Initial BREAKPOINT configuration fetch successful");
            isFirstFetch = false;
            attempt = 0;
          }

        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unexpected error during BREAKPOINT configuration polling", e);
          if (isFirstFetch) {
            attempt++;
            checkDegradedMode("BREAKPOINT", attempt);
          }
        } catch (Throwable t) {
          if (t instanceof ThreadDeath) throw (ThreadDeath) t;
          // Fatal error (e.g., OutOfMemoryError, NoClassDefFoundError).
          // Log and stop this thread gracefully — DI polling will cease.
          logger.log(Level.SEVERE, "DI: Fatal error in BREAKPOINT polling thread, DI will stop", t);
          break;
        }
      }
    } finally {
      logger.fine("BREAKPOINT polling loop ended");
      stopLatch.countDown();
    }
  }

  /** Calculate wait interval with exponential backoff for initial fetches. */
  private long calculateWaitInterval(boolean isFirstFetch, int attempt, int regularInterval) {
    if (isFirstFetch) {
      if (attempt >= MAX_INITIAL_FETCH_ATTEMPTS) {
        // Degraded mode: API endpoint unreachable, poll slowly until it becomes available
        double jitter = random.nextDouble() * DEGRADED_POLL_INTERVAL * 0.25;
        return (long) ((DEGRADED_POLL_INTERVAL + jitter) * 1000);
      }
      int[] intervals = {
        BASE_BACKOFF_INTERVAL, BASE_BACKOFF_INTERVAL * 3, BASE_BACKOFF_INTERVAL * 12
      };
      int baseInterval = intervals[Math.min(attempt, intervals.length - 1)];
      double jitter = random.nextDouble() * BASE_BACKOFF_INTERVAL * 0.5;
      return (long) ((baseInterval + jitter) * 1000);
    } else {
      double jitter = random.nextDouble() * regularInterval * 0.25;
      return (long) ((regularInterval + jitter) * 1000);
    }
  }

  /**
   * Log a warning when entering degraded polling mode after repeated initial-fetch failures.
   *
   * <p>Previously this was a circuit breaker that stopped polling permanently. Now the poller
   * transitions to a degraded polling interval ({@value #DEGRADED_POLL_INTERVAL}s) and keeps
   * retrying indefinitely until the API endpoint becomes available.
   *
   * @param pollerName name of the poller (for logging)
   * @param attempt current attempt number
   */
  private void checkDegradedMode(String pollerName, int attempt) {
    if (attempt == MAX_INITIAL_FETCH_ATTEMPTS) {
      logger.warning(
          "["
              + pollerName
              + "] Dynamic Instrumentation API endpoint unreachable after "
              + MAX_INITIAL_FETCH_ATTEMPTS
              + " attempts, entering degraded polling mode (every "
              + DEGRADED_POLL_INTERVAL
              + "s). Will resume normal polling when the endpoint becomes available. "
              + "Verify the API endpoint (OTEL_AWS_DYNAMIC_INSTRUMENTATION_API_URL) is reachable "
              + "or set OTEL_AWS_DYNAMIC_INSTRUMENTATION_ENABLED=false to disable.");
    }
  }

  /**
   * Atomically merge and apply PROBE/BREAKPOINT configurations.
   *
   * @param newProbeConfigs New PROBE configurations (null if not updated)
   * @param newBreakpointConfigs New BREAKPOINT configurations (null if not updated)
   */
  void applyMergedConfiguration(
      List<InstrumentationConfiguration> newProbeConfigs,
      List<InstrumentationConfiguration> newBreakpointConfigs) {

    try {
      synchronized (configLock) {
        if (newProbeConfigs != null) {
          cachedProbeConfigs = newProbeConfigs;
          logger.log(Level.FINE, "Updated PROBE cache: {0} configs", newProbeConfigs.size());
        }

        if (newBreakpointConfigs != null) {
          cachedBreakpointConfigs = newBreakpointConfigs;
          logger.log(
              Level.FINE, "Updated BREAKPOINT cache: {0} configs", newBreakpointConfigs.size());
        }

        List<InstrumentationConfiguration> allConfigs = new ArrayList<>();
        allConfigs.addAll(cachedProbeConfigs);
        allConfigs.addAll(cachedBreakpointConfigs);

        Set<String> currentConfigKeys =
            allConfigs.stream()
                .map(
                    config ->
                        config.isLineLevel()
                            ? config.getInstrumentationKey()
                            : config.getMethodKey())
                .collect(Collectors.toSet());

        Set<String> removedKeys = new HashSet<>(previousConfigKeys);
        removedKeys.removeAll(currentConfigKeys);

        // Compute fingerprint using locationHash + createdAt to detect actual changes.
        // Aligns with InstrumentationRegistry.hasConfigChanged() from PR #56.
        Set<String> currentFingerprint =
            allConfigs.stream()
                .map(
                    config ->
                        config.getLocationHash()
                            + ":"
                            + Objects.toString(config.getCreatedAt(), ""))
                .collect(Collectors.toSet());

        if (removedKeys.isEmpty() && currentFingerprint.equals(lastAppliedFingerprint)) {
          logger.fine("Configuration unchanged, skipping retransformation");
          previousConfigKeys = currentConfigKeys;
          return;
        }

        logger.log(
            Level.FINE,
            "Merged configuration: {0} PROBE + {1} BREAKPOINT = {2} total",
            new Object[] {
              cachedProbeConfigs.size(), cachedBreakpointConfigs.size(), allConfigs.size()
            });

        if (!removedKeys.isEmpty()) {
          logger.log(Level.FINE, "Detected {0} removed configurations", removedKeys.size());
          software
              .amazon
              .opentelemetry
              .javaagent
              .providers
              .dynamicInstrumentation
              .DynamicInstrumentationManager
              .getInstance()
              .removeInstrumentations(removedKeys);
        }

        software
            .amazon
            .opentelemetry
            .javaagent
            .providers
            .dynamicInstrumentation
            .DynamicInstrumentationManager
            .getInstance()
            .applyConfigurations(allConfigs);

        lastAppliedFingerprint = currentFingerprint;
        previousConfigKeys = currentConfigKeys;
      }
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Error applying configuration", e);
    }
  }

  /** Check and log staleness warnings for PROBE and BREAKPOINT. */
  private void checkStaleness() {
    long now = System.currentTimeMillis();

    if (probeLastSuccessTime != null) {
      long probeAgeSeconds = (now - probeLastSuccessTime) / 1000;
      if (probeAgeSeconds > PROBE_STALENESS_THRESHOLD) {
        logger.log(
            Level.WARNING,
            "[PROBE] Configurations are stale: {0}s old (threshold: {1}s)",
            new Object[] {probeAgeSeconds, PROBE_STALENESS_THRESHOLD});
      }
    }

    if (breakpointLastSuccessTime != null) {
      long breakpointAgeSeconds = (now - breakpointLastSuccessTime) / 1000;
      if (breakpointAgeSeconds > BREAKPOINT_STALENESS_THRESHOLD) {
        logger.log(
            Level.WARNING,
            "[BREAKPOINT] Configurations are stale: {0}s old (threshold: {1}s)",
            new Object[] {breakpointAgeSeconds, BREAKPOINT_STALENESS_THRESHOLD});
      }
    }
  }

  /** Check if poller is running. */
  public boolean isRunning() {
    return running;
  }
}
