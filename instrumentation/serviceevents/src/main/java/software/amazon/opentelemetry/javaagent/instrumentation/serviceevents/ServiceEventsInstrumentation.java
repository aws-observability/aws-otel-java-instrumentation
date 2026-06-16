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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.BaseCollector;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.DeploymentEventCollector;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.EndpointCollector;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.FunctionCallCollector;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.config.ServiceEventsConfig;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.FunctionMetricsBridgeImpl;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsCloudWatchLogFileExporter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsCloudWatchMetricFileExporter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsOtlpEmitter;
import software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.logs.OtlpAwsLogRecordExporterBuilder;

/**
 * Main entry point for ServiceEvents instrumentation.
 *
 * <p>Manages lifecycle of bytecode instrumentation, monitors, and framework instrumentations.
 */
public class ServiceEventsInstrumentation {

  // Lazy logger: this class is loaded during agent init (from ServiceEventsInstrumentationModule's
  // static block), before the OTel agent's InternalLogger is initialized.
  private static volatile Logger logger;

  private static Logger logger() {
    Logger local = logger;
    if (local == null) {
      local = Logger.getLogger(ServiceEventsInstrumentation.class.getName());
      logger = local;
    }
    return local;
  }

  private static final String AWS_OTLP_LOGS_ENDPOINT_PATTERN =
      "^https://logs\\.([a-z0-9-]+)\\.amazonaws\\.com/v1/logs$";

  private final ServiceEventsConfig config;
  private ServiceEventsOtlpEmitter otlpEmitter;
  private final List<BaseCollector> collectors = new ArrayList<>();
  private boolean initialized = false;

  // Singleton instance for easy access
  private static ServiceEventsInstrumentation instance;

  /**
   * Initialize ServiceEvents instrumentation with configuration.
   *
   * @param config ServiceEventsConfig instance with all settings
   */
  public ServiceEventsInstrumentation(ServiceEventsConfig config) {
    this.config = config;
  }

  /** Get or create the default instance. */
  public static synchronized ServiceEventsInstrumentation getInstance() {
    if (instance == null) {
      instance = new ServiceEventsInstrumentation(ServiceEventsConfig.fromEnv());
    }
    return instance;
  }

  /**
   * Initialize with custom config. If the singleton already exists, the supplied {@code config} is
   * ignored (the existing instance is returned); a warning is logged so the no-op is visible rather
   * than silent.
   */
  public static synchronized ServiceEventsInstrumentation getInstance(ServiceEventsConfig config) {
    if (instance == null) {
      instance = new ServiceEventsInstrumentation(config);
    } else {
      logger()
          .warning("ServiceEventsInstrumentation already initialized; ignoring supplied config");
    }
    return instance;
  }

  /** Reset the singleton (for testing). */
  public static synchronized void reset() {
    if (instance != null) {
      instance.shutdown();
      instance = null;
    }
  }

  /**
   * Initialize ServiceEvents instrumentation.
   *
   * <p>Steps: 1. Configure logging level from config 2. Initialize monitor state (singleton for
   * aggregation) 3. Initialize framework instrumentations 4. Start periodic collectors
   */
  public synchronized void initialize() {
    if (initialized) {
      logger().warning("ServiceEvents instrumentation already initialized, skipping");
      return;
    }

    if (!config.isEnabled()) {
      logger().info("ServiceEvents instrumentation disabled via configuration");
      return;
    }

    try {
      logger().info("Initializing ServiceEvents instrumentation");

      // Initialize dedicated OTLP providers for ServiceEvents telemetry signals.
      // Fully isolated from OTel application logs/metrics and Application Signals.
      // When OUTPUT_FILE is set, exporters are file-backed (CloudWatch-faithful NDJSON)
      // instead of OTLP HTTP; LOGS_ENDPOINT + METRICS_ENDPOINT are ignored for the duration.
      if (config.getOutputFile() != null && !config.getOutputFile().isEmpty()) {
        logger()
            .info(
                "ServiceEvents OUTPUT_FILE mode: "
                    + config.getOutputFile()
                    + " (LOGS_ENDPOINT and METRICS_ENDPOINT ignored)");
      }
      otlpEmitter = createOtlpEmitter();

      // Initialize DeploymentEventCollector (emits once at startup, then every 24h).
      DeploymentEventCollector deploymentEventCollector =
          new DeploymentEventCollector(
              config.getDeploymentEventFlushInterval(),
              config.getEnvironment(),
              config.getServiceName(),
              config.getDeploymentId(),
              config.getDeploymentTimestamp(),
              config.getDeploymentUrl(),
              config.getGitCommitSha(),
              config.getGitRepoUrl(),
              otlpEmitter);
      collectors.add(deploymentEventCollector);
      deploymentEventCollector.start();
      logger()
          .info(
              "Started DeploymentEventCollector (interval: "
                  + config.getDeploymentEventFlushInterval()
                  + "ms)");

      // Initialize FunctionCallCollector (only when bytecode instrumentation is enabled,
      // since FunctionCallCollector processes data from MethodAdvice — without it there
      // is no function call data to collect)
      FunctionCallCollector functionCallCollector = null;
      if (config.isBytecodeEnabled()) {
        // Apply sampling startup defaults from env BEFORE the collector begins
        // ticking — ensures the very first flush already reflects the configured
        // mode and (for "auto") the tier thresholds.
        software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore.setSamplingMode(
            config.getSamplingMode());
        software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore.setSamplingThresholds(
            config.getSampleTier1Threshold(),
            config.getSampleTier2Threshold(),
            config.getSampleTier2Rate(),
            config.getSampleTier3Rate());
        // Read back the effective mode after setSamplingMode's internal
        // validation. If the operator-supplied value was rejected (e.g. the
        // removed "adaptive", or OTEL_AWS_SERVICE_EVENTS_SAMPLING_MODE=fast),
        // the effective mode falls back to the default ("always"); surfacing the
        // mismatch here helps operators debug misconfiguration.
        String requestedMode = config.getSamplingMode();
        String effectiveMode =
            software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore.getSamplingMode();
        String modeReport =
            requestedMode != null && requestedMode.equalsIgnoreCase(effectiveMode)
                ? effectiveMode
                : effectiveMode + " (config requested '" + requestedMode + "', invalid)";
        logger()
            .info(
                "ServiceEvents sampling: mode="
                    + modeReport
                    + ", tier1="
                    + config.getSampleTier1Threshold()
                    + ", tier2="
                    + config.getSampleTier2Threshold()
                    + ", tier2Rate="
                    + config.getSampleTier2Rate()
                    + ", tier3Rate="
                    + config.getSampleTier3Rate());

        // Wire the OTel histogram bridge for direct in-line metric recording at
        // __exit__. Gated on `otlpEmitter != null && !output_file` (network OTLP
        // mode only). In `output_file` mode, the CloudWatch metric file exporter
        // only serializes Sum metrics — histogram data points would be silently
        // dropped; the SEH → FunctionCallCollector path still writes
        // `aws.service_events.function_call` LogRecords to the same file via the
        // OTLP log exporter (CloudWatch-faithful local mirror), so this is the
        // correct boundary.
        //
        // Per-data-point base attributes are intentionally minimal:
        //   - service.name, environment, aws.service_events.version,
        //     aws.service_events.deployment.id, vcs.ref.head.revision,
        //     vcs.repository.url.full
        // are all process-constants and ride on the dedicated MeterProvider's
        // Resource (sent once per OTLP batch). This shrinks per-call attribute
        // building on the hot path and avoids redundant wire bytes.
        // Wired in BOTH network and output_file mode: the file metric exporter now emits
        // canonical OTLP metrics JSON (incl. ExponentialHistogram), so the histogram is no
        // longer dropped locally. With the bridge wired, methodExit records into the
        // histogram and feeds the (now-empty) MethodAggregationStore, so FunctionCallCollector
        // no-ops automatically (same as network mode). The aws.service_events.function_call
        // LogRecord therefore no longer appears in either mode; service.function.duration is
        // the single source of truth.
        if (otlpEmitter != null) {
          try {
            Attributes baseAttrs =
                Attributes.builder().put("Telemetry.Source", "ServiceEvents").build();
            FunctionMetricsBridgeImpl bridge = otlpEmitter.buildFunctionMetricsBridge(baseAttrs);
            software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore
                .setFunctionMetricsBridge(bridge);
            logger()
                .info(
                    "Wired FunctionMetricsBridge: service.function.duration (ExponentialHistogram) "
                        + "records directly from methodExit");
          } catch (Throwable e) {
            logger()
                .log(
                    Level.WARNING,
                    "Failed to wire FunctionMetricsBridge — falling back to FunctionCallCollector "
                        + "(aws.service_events.function_call LogRecord) path",
                    e);
          }
        }

        functionCallCollector =
            new FunctionCallCollector(
                config.getFunctionCallFlushInterval(),
                config.getEnvironment(),
                config.getServiceName(),
                config.getDeploymentId(),
                config.getDeploymentTimestamp(),
                config.getDeploymentUrl(),
                config.getGitCommitSha(),
                config.getGitRepoUrl(),
                otlpEmitter);
        collectors.add(functionCallCollector);
        functionCallCollector.start();
        logger()
            .info(
                "Started FunctionCallCollector (interval: "
                    + config.getFunctionCallFlushInterval()
                    + "ms)");
      } else {
        logger().info("FunctionCallCollector disabled (BYTECODE_ENABLED=false)");
      }

      // Initialize EndpointCollector. suppressEndpointSummary mirrors
      // OTEL_AWS_APPLICATION_SIGNALS_ENABLED: when App Signals is on, skip emitting
      // EndpointSummary LogRecords (App Signals carries equivalent data). The collector
      // still runs so latency histograms feed IncidentSnapshot triggers.
      EndpointCollector endpointCollector =
          new EndpointCollector(
              config.getEndpointFlushInterval(),
              config.getEnvironment(),
              config.getServiceName(),
              config.getDeploymentId(),
              config.getDeploymentTimestamp(),
              config.getDeploymentUrl(),
              config.getGitCommitSha(),
              config.getGitRepoUrl(),
              otlpEmitter,
              config.isApplicationSignalsEnabled());
      collectors.add(endpointCollector);
      endpointCollector.start();
      logger()
          .info(
              "Started EndpointMetricCollector (interval: "
                  + config.getEndpointFlushInterval()
                  + "ms)");

      // Install the per-endpoint latency threshold resolver if the env var is set. Malformed
      // entries are logged at SEVERE and skipped inside the resolver constructor, so startup
      // continues regardless. The bridge stays null if zero good entries survived — the
      // global threshold then applies to every request.
      if (!config.getLatencyThresholds().isEmpty()) {
        software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils
                .LatencyThresholdResolver
            thresholdResolver =
                new software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils
                    .LatencyThresholdResolver(config.getLatencyThresholds());
        if (thresholdResolver.size() > 0) {
          software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore
              .setLatencyThresholdBridge(thresholdResolver);
          logger()
              .info(
                  "Installed LatencyThresholdResolver with "
                      + thresholdResolver.size()
                      + " per-endpoint threshold pattern(s)");
        } else {
          logger()
              .warning(
                  "OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS produced zero valid entries; "
                      + "falling back to the global threshold for all endpoints");
        }
      }

      // Build the shared IncidentSnapshotRecordBuilder. Used by:
      //   - bytecode mode: synchronous IncidentSnapshotEmitter
      //   - lite mode (no bytecode): asynchronous LiteIncidentDrainer
      software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils
              .IncidentSnapshotRecordBuilder
          incidentRecordBuilder =
              new software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils
                  .IncidentSnapshotRecordBuilder(
                  config.getServiceName(),
                  config.getEnvironment(),
                  config.getDeploymentId(),
                  config.getDeploymentTimestamp(),
                  config.getDeploymentUrl(),
                  config.getGitCommitSha(),
                  config.getGitRepoUrl(),
                  ProcessHandle.current().pid());

      // Apply incident-snapshot rate-limit startup defaults from env. Applied for any mode
      // that emits IncidentSnapshots (bytecode + lite). The rate-limit window is fixed at 1 minute.
      software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore
          .setIncidentSnapshotMaxPerMinute(config.getIncidentSnapshotMaxPerMinute());
      software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore
          .setIncidentSnapshotMaxSameError(config.getIncidentSnapshotMaxSameError());
      logger()
          .info(
              "Incident rate-limit startup defaults: maxPerMinute="
                  + config.getIncidentSnapshotMaxPerMinute()
                  + ", maxSameError="
                  + config.getIncidentSnapshotMaxSameError());

      // Always install the direct-emit IncidentSnapshot bridge so incidents emit synchronously.
      // Call path is only captured when bytecode instrumentation is enabled.
      software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter
              .IncidentSnapshotEmitter
          incidentEmitter =
              new software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter
                  .IncidentSnapshotEmitter(incidentRecordBuilder, otlpEmitter);
      software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore
          .setIncidentSnapshotEmitterBridge(incidentEmitter);
      logger().info("Installed IncidentSnapshotEmitter: incidents emit synchronously");

      if (!config.isBytecodeEnabled()) {
        // Lite mode: no bytecode advice. Install the in-memory drainer as the
        // MetadataWriterBridge so DataStore.recordPotentialIncident dispatches to its
        // queue, and ticks records out via the existing OTLP path on the configured
        // flush interval. Snapshots carry exception_info.stack_trace but no call_path
        // (matches Python lite-mode shape).
        software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors
                .LiteIncidentDrainer
            liteDrainer =
                new software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors
                    .LiteIncidentDrainer(10_000, incidentRecordBuilder, otlpEmitter);
        software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore.setMetadataWriterBridge(
            liteDrainer);
        collectors.add(liteDrainer);
        liteDrainer.start();
        logger()
            .info(
                "Installed LiteIncidentDrainer (lite mode: no bytecode). "
                    + "Incidents queue in-memory and emit every "
                    + 10_000
                    + "ms.");
      }

      initialized = true;
      logger()
          .info(
              "ServiceEvents instrumentation initialized successfully (service="
                  + config.getServiceName()
                  + ")");

    } catch (Exception e) {
      logger().log(Level.SEVERE, "Failed to initialize ServiceEvents instrumentation", e);
      // Don't crash application - graceful degradation
      initialized = false;
    }
  }

  /**
   * Stop all collectors and cleanup resources.
   *
   * <p>This should be called during application shutdown to ensure proper cleanup.
   */
  public synchronized void shutdown() {
    if (!initialized) {
      return;
    }

    try {
      logger().info("Shutting down ServiceEvents instrumentation");

      // Stop all collectors
      for (BaseCollector collector : collectors) {
        try {
          collector.stop();
          logger().fine("Stopped collector: " + collector.getClass().getSimpleName());
        } catch (Exception e) {
          logger().log(Level.WARNING, "Error stopping collector", e);
        }
      }
      collectors.clear();

      // Shutdown dedicated OTLP providers (owned by the emitter via lazy init)
      if (otlpEmitter != null) {
        SdkLoggerProvider logProvider = otlpEmitter.getLoggerProvider();
        if (logProvider != null) {
          try {
            logProvider.forceFlush().join(10, TimeUnit.SECONDS);
            logProvider.shutdown().join(10, TimeUnit.SECONDS);
            logger().fine("Shut down ServiceEvents LoggerProvider");
          } catch (Exception e) {
            logger().log(Level.WARNING, "Error shutting down ServiceEvents LoggerProvider", e);
          }
        }
        SdkMeterProvider meterProvider = otlpEmitter.getMeterProvider();
        if (meterProvider != null) {
          try {
            meterProvider.forceFlush().join(10, TimeUnit.SECONDS);
            meterProvider.shutdown().join(10, TimeUnit.SECONDS);
            logger().fine("Shut down ServiceEvents MeterProvider");
          } catch (Exception e) {
            logger().log(Level.WARNING, "Error shutting down ServiceEvents MeterProvider", e);
          }
        }
        otlpEmitter = null;
      }

      initialized = false;
      logger().info("ServiceEvents instrumentation shut down successfully");

    } catch (Exception e) {
      logger().log(Level.SEVERE, "Error during ServiceEvents shutdown", e);
    }
  }

  /** Check if initialized. */
  public boolean isInitialized() {
    return initialized;
  }

  /** Get configuration. */
  public ServiceEventsConfig getConfig() {
    return config;
  }

  /**
   * Create dedicated OTLP providers and the ServiceEventsOtlpEmitter.
   *
   * <p>Creates a dedicated SdkLoggerProvider (for 4 log signals) and SdkMeterProvider (for
   * EndpointErrorMetrics counter), fully isolated from OTel application signals. Applies SigV4
   * wrapping when the logs endpoint matches the CloudWatch OTLP URL pattern.
   *
   * @return ServiceEventsOtlpEmitter, or null if provider creation fails
   */
  private ServiceEventsOtlpEmitter createOtlpEmitter() {
    try {
      // Resource supplier: defers reading ResourceHolder until first emit, when
      // OTel autoconfiguration has populated it with the full resource. Uses the
      // complete Resource (same as Application Signals log exporter) — resource
      // attributes are sent once per OTLP batch, not per record, so the overhead
      // is negligible while providing valuable debugging context.
      // deployment.environment is omitted when environment is unset (no sentinel).
      io.opentelemetry.api.common.AttributesBuilder fallbackAttrs =
          Attributes.builder().put(AttributeKey.stringKey("service.name"), config.getServiceName());
      String resolvedEnvironment = config.getEnvironment();
      if (resolvedEnvironment != null && !resolvedEnvironment.isEmpty()) {
        fallbackAttrs.put(AttributeKey.stringKey("deployment.environment"), resolvedEnvironment);
      }
      Resource fallbackResource = Resource.create(fallbackAttrs.build());

      java.util.function.Supplier<Resource> resourceSupplier =
          () -> {
            try {
              Class<?> holderClass =
                  Class.forName("io.opentelemetry.contrib.awsxray.ResourceHolder");
              java.lang.reflect.Method getResource = holderClass.getMethod("getResource");
              Resource holderResource = (Resource) getResource.invoke(null);
              if (holderResource != null && !holderResource.equals(Resource.getDefault())) {
                return holderResource;
              }
            } catch (Throwable ignored) {
              // ResourceHolder not available or not yet populated — use fallback
            }
            return fallbackResource;
          };

      // Log the endpoint mode now (before lazy init)
      String logsEndpoint = config.getLogsEndpoint();
      if (logsEndpoint.matches(AWS_OTLP_LOGS_ENDPOINT_PATTERN)) {
        logger().info("ServiceEvents OTLP logs: SigV4 direct-to-CloudWatch (" + logsEndpoint + ")");
      } else {
        logger().info("ServiceEvents OTLP logs: collector-proxied (" + logsEndpoint + ")");
      }
      String metricsEndpoint = config.getMetricsEndpoint();
      logger().info("ServiceEvents OTLP metrics: " + metricsEndpoint);

      // File-export mode: when OUTPUT_FILE is set, all three providers use
      // CloudWatch-faithful file exporters in place of the OTLP HTTP exporters.
      String outputFile = config.getOutputFile();
      boolean useFileExport = outputFile != null && !outputFile.isEmpty();

      // LoggerProvider factory: creates the dedicated provider on first use
      java.util.function.Supplier<SdkLoggerProvider> loggerProviderFactory =
          () -> {
            io.opentelemetry.sdk.logs.export.LogRecordExporter logExporter;
            if (useFileExport) {
              logExporter = new ServiceEventsCloudWatchLogFileExporter(outputFile);
            } else {
              OtlpHttpLogRecordExporter plainLogExporter =
                  OtlpHttpLogRecordExporter.builder()
                      .setEndpoint(logsEndpoint)
                      .addHeader("x-aws-log-group", config.getLogGroup())
                      .addHeader("x-aws-log-stream", config.getLogStream())
                      .build();

              if (logsEndpoint.matches(AWS_OTLP_LOGS_ENDPOINT_PATTERN)) {
                logExporter =
                    OtlpAwsLogRecordExporterBuilder.create(plainLogExporter, logsEndpoint).build();
              } else {
                logExporter = plainLogExporter;
              }
            }

            return SdkLoggerProvider.builder()
                .setResource(resourceSupplier.get())
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build())
                .build();
          };

      // MeterProvider factory: creates the dedicated provider on first use.
      // File-export mode writes EMF envelopes to the same file as logs.
      java.util.function.Supplier<SdkMeterProvider> meterProviderFactory =
          () -> {
            io.opentelemetry.sdk.metrics.export.MetricExporter metricExporter;
            if (useFileExport) {
              metricExporter = new ServiceEventsCloudWatchMetricFileExporter(outputFile);
            } else {
              metricExporter =
                  OtlpHttpMetricExporter.builder()
                      .setEndpoint(metricsEndpoint)
                      .setAggregationTemporalitySelector(
                          instrumentType -> AggregationTemporality.DELTA)
                      .build();
            }

            // Build the metric Resource by layering the 4 process-constant
            // ServiceEvents attributes on top of the shared Resource. These ride at
            // the resource level so they're sent once per OTLP batch instead of
            // duplicated on every data point. service.name and environment
            // (deployment.environment) already live on the shared resource via
            // OTel autoconfiguration.
            Resource sharedResource = dropArrayResourceAttributes(resourceSupplier.get());
            io.opentelemetry.api.common.AttributesBuilder metricResAttrs =
                io.opentelemetry.api.common.Attributes.builder();
            metricResAttrs.put("aws.service_events.version", "1");
            if (config.getDeploymentId() != null && !config.getDeploymentId().isEmpty()) {
              metricResAttrs.put("aws.service_events.deployment.id", config.getDeploymentId());
            }
            if (config.getGitCommitSha() != null && !config.getGitCommitSha().isEmpty()) {
              metricResAttrs.put("vcs.ref.head.revision", config.getGitCommitSha());
            }
            if (config.getGitRepoUrl() != null && !config.getGitRepoUrl().isEmpty()) {
              metricResAttrs.put("vcs.repository.url.full", config.getGitRepoUrl());
            }
            Resource metricResource = sharedResource.merge(Resource.create(metricResAttrs.build()));

            SdkMeterProviderBuilder builder =
                SdkMeterProvider.builder()
                    .setResource(metricResource)
                    .registerMetricReader(
                        PeriodicMetricReader.builder(metricExporter)
                            .setInterval(Duration.ofSeconds(60))
                            .build());

            // Register a base-2 exponential histogram view for the
            // service.function.duration metric so the exporter emits an
            // ExponentialHistogram (matching the Python ExponentialBucketHistogramAggregation).
            // Registered in BOTH modes: the file metric exporter now serializes histograms as
            // OTLP JSON, so this View must apply in file mode too — otherwise the histogram
            // would fall back to the SDK default explicit-bucket shape locally.
            builder.registerView(
                InstrumentSelector.builder().setName("service.function.duration").build(),
                View.builder()
                    .setAggregation(Aggregation.base2ExponentialBucketHistogram())
                    .build());

            return builder.build();
          };

      ServiceEventsOtlpEmitter emitter =
          new ServiceEventsOtlpEmitter(
              resourceSupplier,
              loggerProviderFactory,
              meterProviderFactory,
              config.getDeploymentId(),
              config.getGitCommitSha(),
              config.getGitRepoUrl(),
              config.getServiceCodeNamespace());

      logger().info("ServiceEvents OTLP emitter initialized (providers deferred until first emit)");
      return emitter;

    } catch (Throwable e) {
      logger()
          .log(
              Level.WARNING,
              "Failed to create OTLP emitter, falling back to file/console export",
              e);
      return null;
    }
  }

  /**
   * Drops array-type resource attributes. The CW OTLP metrics endpoint only accepts primitive
   * attribute types; array attributes like process.command_args cause a 400 rejection.
   */
  @SuppressWarnings("unchecked")
  private static Resource dropArrayResourceAttributes(Resource resource) {
    io.opentelemetry.api.common.AttributesBuilder builder = Attributes.builder();
    resource
        .getAttributes()
        .forEach(
            (key, value) -> {
              if (!(value instanceof java.util.List)) {
                builder.put((AttributeKey<Object>) key, value);
              }
            });
    return Resource.create(builder.build(), resource.getSchemaUrl());
  }
}
