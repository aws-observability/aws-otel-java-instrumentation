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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.ByteBuddyInstrumentationEngine;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.InstrumentationRegistry;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.ConfigurationStatus;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.ErrorCause;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationState;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.StatusEntry;

/**
 * Reports instrumentation configuration status to backend.
 *
 * <p>Runs background thread to periodically report ACTIVE statuses (every 60s). Also supports
 * immediate reporting for READY/ERROR statuses when configurations are applied.
 *
 * <p>Status reporting rules:
 *
 * <ul>
 *   <li>READY: Report once when config first applied (hitCount==0)
 *   <li>ACTIVE: Report every 60s if hit in last period
 *   <li>DISABLED: Report once when disabled (maxHits or expiry)
 *   <li>ERROR: Report once on transformation/application error
 * </ul>
 */
public class StatusReporter {
  private static final Logger logger = Logger.getLogger(StatusReporter.class.getName());
  private static final int BATCH_SIZE = 100;

  private final DynamicInstrumentationClient client;
  private final int reportIntervalSeconds;
  private final ByteBuddyInstrumentationEngine engine;

  // Track reported configurations to avoid duplicate reports for one-time statuses
  private final Set<String> reportedConfigs = ConcurrentHashMap.newKeySet();

  // Background thread for periodic reporting
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "status-reporter");
            t.setDaemon(true);
            return t;
          });

  private volatile boolean started = false;

  /**
   * Create status reporter.
   *
   * @param client HTTP client for sending reports
   * @param reportIntervalSeconds Interval for periodic reporting (default 60)
   * @param engine ByteBuddy instrumentation engine for tracking injected classloaders
   */
  public StatusReporter(
      DynamicInstrumentationClient client,
      int reportIntervalSeconds,
      ByteBuddyInstrumentationEngine engine) {
    this.client = client;
    this.reportIntervalSeconds = reportIntervalSeconds;
    this.engine = engine;
    logger.fine("StatusReporter initialized with report interval: " + reportIntervalSeconds + "s");
  }

  /** Start background thread for periodic status reporting. */
  public void start() {
    if (!started) {
      started = true;
      scheduler.scheduleAtFixedRate(
          this::reportLoop, reportIntervalSeconds, reportIntervalSeconds, TimeUnit.SECONDS);
      logger.fine("Status reporter background thread started");
    }
  }

  /** Stop background thread. */
  public void stop() {
    started = false;
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
    logger.fine("Status reporter stopped");
  }

  /**
   * Trigger immediate status report.
   *
   * <p>Called when new configurations are applied to report READY status.
   */
  public void reportNow() {
    try {
      pullAndReportStatuses(true); // is_initial_report=true
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error in immediate status report", e);
    }
  }

  /**
   * Report an error status for a failed configuration.
   *
   * @param instrumentationType Instrumentation type ("PROBE" or "BREAKPOINT")
   * @param locationHash Location hash of failed config
   * @param cause Error cause
   */
  public void reportError(String instrumentationType, String locationHash, ErrorCause cause) {
    String errorKey = getConfigKey(locationHash, ConfigurationStatus.ERROR);
    if (reportedConfigs.add(errorKey)) {
      // First time reporting this error
      logger.log(
          Level.FINE,
          "Reporting ERROR status for: {0} (cause: {1})",
          new Object[] {locationHash, cause});
      StatusEntry entry =
          new StatusEntry(
              instrumentationType, locationHash, ConfigurationStatus.ERROR, cause, Instant.now());
      sendReport(List.of(entry));
    }
  }

  /** Background loop for periodic status reporting. */
  private void reportLoop() {
    try {
      logger.fine("Status reporter loop tick");
      pullAndReportStatuses(false); // is_initial_report=false
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error in status report loop", e);
    } catch (Throwable t) {
      // Fatal error (e.g., OutOfMemoryError, NoClassDefFoundError).
      // Log the error, then re-throw to signal ScheduledExecutorService to stop scheduling.
      // Note: if t is OOM, this log call may itself fail due to allocation, but the re-throw
      // still ensures scheduling stops. The executor catches it internally and does not
      // propagate to user code.
      logger.log(Level.SEVERE, "DI: Fatal error in status reporter, reporting will stop", t);
      throw t;
    }
  }

  /**
   * Pull breakpoint states from registry and report statuses.
   *
   * @param isInitialReport True for out-of-band reports when configs are applied, False for
   *     periodic 60s reports
   */
  private void pullAndReportStatuses(boolean isInitialReport) {
    List<StatusEntry> entries = new ArrayList<>();

    // Get all states from agent classloader (has static metadata)
    Map<String, InstrumentationState> agentStates = InstrumentationRegistry.getAllStates();

    for (Map.Entry<String, InstrumentationState> agentEntry : agentStates.entrySet()) {
      String key = agentEntry.getKey();
      InstrumentationState agentState = agentEntry.getValue();

      // Get static metadata from agent state
      String locationHash = agentState.getLocationHash();
      String instrumentationType = agentState.getInstrumentationType().name();

      // Pull dynamic runtime data from app classloader
      RuntimeStateData runtimeData = pullRuntimeDataFromApp(key, isInitialReport);

      // Merge: use runtime data if available, otherwise fall back to agent state
      int hitCount = runtimeData != null ? runtimeData.hitCount : agentState.getHitCount();
      boolean disabled = runtimeData != null ? runtimeData.disabled : agentState.isDisabled();
      boolean hitInLastPeriod =
          runtimeData != null ? runtimeData.hitInLastPeriod : agentState.isHitInLastPeriod();
      String disableReason =
          runtimeData != null
              ? runtimeData.disableReason
              : (agentState.getDisableReason() != null
                  ? agentState.getDisableReason().name()
                  : null);

      // Log state data source and values
      String source = runtimeData != null ? "app CL" : "agent CL";
      logger.fine(
          String.format(
              "State for %s (from %s): hitCount=%d, disabled=%s, hitInLastPeriod=%s",
              locationHash, source, hitCount, disabled, hitInLastPeriod));

      // DISABLED: Check and report first (mutually exclusive with other statuses)
      if (disabled && !isInitialReport) {
        String disabledKey = getConfigKey(locationHash, ConfigurationStatus.DISABLED);
        if (reportedConfigs.add(disabledKey)) {
          // First time reporting disabled - report it
          entries.add(
              new StatusEntry(
                  instrumentationType, locationHash, ConfigurationStatus.DISABLED, Instant.now()));
          logger.log(
              Level.FINE,
              "Reporting DISABLED status for: {0} (reason: {1})",
              new Object[] {locationHash, disableReason});
        }
        // Skip all other status checks for this config - once disabled, no more reports
        continue;
      }

      // ACTIVE: Report if hit in last period (periodic reports only, and NOT disabled)
      if (hitInLastPeriod && !isInitialReport) {
        entries.add(
            new StatusEntry(
                instrumentationType, locationHash, ConfigurationStatus.ACTIVE, Instant.now()));
        logger.fine("Reporting ACTIVE status for: " + locationHash);
      }

      // READY: Report once (initial reports only, hitCount==0, and NOT disabled)
      if (hitCount == 0 && isInitialReport) {
        String readyKey = getConfigKey(locationHash, ConfigurationStatus.READY);
        if (reportedConfigs.add(readyKey)) {
          entries.add(
              new StatusEntry(
                  instrumentationType, locationHash, ConfigurationStatus.READY, Instant.now()));
          logger.log(Level.FINE, "Reporting READY status for: {0}", locationHash);
        }
      }
    }

    // Send in batches of 100
    if (!entries.isEmpty()) {
      logger.log(Level.FINE, "Sending {0} status entries to backend", entries.size());
      for (int i = 0; i < entries.size(); i += BATCH_SIZE) {
        List<StatusEntry> batch = entries.subList(i, Math.min(i + BATCH_SIZE, entries.size()));
        sendReport(batch);
      }
    } else {
      logger.fine("No status entries to report this cycle");
    }
  }

  /**
   * Send status report to backend.
   *
   * @param entries List of status entries
   */
  private void sendReport(List<StatusEntry> entries) {
    try {
      client.reportConfigurationStatus(entries);
      logger.log(Level.FINE, "Reported {0} configuration statuses", entries.size());
    } catch (Exception e) {
      logger.log(Level.FINE, "Error sending status report", e);
    }
  }

  /**
   * Generate unique key for a configuration.
   *
   * <p>For READY/ERROR/DISABLED (one-time reports), include status in key. For ACTIVE (continuous
   * reporting), don't include status.
   *
   * @param locationHash Location hash
   * @param status Status type
   * @return Unique key
   */
  private static String getConfigKey(String locationHash, ConfigurationStatus status) {
    if (status == ConfigurationStatus.READY
        || status == ConfigurationStatus.ERROR
        || status == ConfigurationStatus.DISABLED) {
      return locationHash + ":" + status.name();
    }
    return locationHash; // For ACTIVE - allow continuous reporting
  }

  /**
   * Pull runtime state fields from application classloader via reflection. Searches all injected
   * classloaders to find the state.
   *
   * @param key Instrumentation key
   * @param isInitialReport Whether this is initial report (for resetting hitInLastPeriod)
   * @return Runtime state data, or null if not found
   */
  private RuntimeStateData pullRuntimeDataFromApp(String key, boolean isInitialReport) {
    if (engine == null) {
      return null; // No engine, can't query app classloaders
    }

    for (ClassLoader appCL : engine.getInjectedClassLoaders()) {
      try {
        // Load InstrumentationRegistry from app classloader
        Class<?> registryClass =
            appCL.loadClass(
                "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.InstrumentationRegistry");

        // Get the specific state
        java.lang.reflect.Method getStateMethod = registryClass.getMethod("getState", String.class);
        Object appStateObj = getStateMethod.invoke(null, key);

        if (appStateObj == null) {
          continue; // Not in this classloader, try next
        }

        // Extract runtime fields via reflection
        Class<?> stateClass = appStateObj.getClass();

        int hitCount = (Integer) stateClass.getMethod("getHitCount").invoke(appStateObj);
        boolean disabled = (Boolean) stateClass.getMethod("isDisabled").invoke(appStateObj);
        boolean hitInLastPeriod =
            (Boolean) stateClass.getMethod("isHitInLastPeriod").invoke(appStateObj);

        Object disableReasonObj = stateClass.getMethod("getDisableReason").invoke(appStateObj);
        String disableReason = disableReasonObj != null ? disableReasonObj.toString() : null;

        // Reset hitInLastPeriod flag after reading (for ACTIVE status tracking)
        if (hitInLastPeriod && !isInitialReport) {
          java.lang.reflect.Method resetMethod = stateClass.getMethod("resetHitInLastPeriod");
          resetMethod.invoke(appStateObj);
        }

        return new RuntimeStateData(hitCount, disabled, disableReason, hitInLastPeriod);

      } catch (ClassNotFoundException e) {
        // This classloader doesn't have registry yet
        continue;
      } catch (Exception e) {
        logger.log(Level.FINE, "Error pulling runtime data from classloader: " + e.getMessage(), e);
        continue;
      }
    }

    return null; // Not found in any app classloader
  }

  /**
   * Container for runtime state fields pulled from application classloader. Only contains dynamic
   * fields that change during execution.
   */
  private static class RuntimeStateData {
    final int hitCount;
    final boolean disabled;
    final String disableReason;
    final boolean hitInLastPeriod;

    RuntimeStateData(
        int hitCount, boolean disabled, String disableReason, boolean hitInLastPeriod) {
      this.hitCount = hitCount;
      this.disabled = disabled;
      this.disableReason = disableReason;
      this.hitInLastPeriod = hitInLastPeriod;
    }
  }
}
