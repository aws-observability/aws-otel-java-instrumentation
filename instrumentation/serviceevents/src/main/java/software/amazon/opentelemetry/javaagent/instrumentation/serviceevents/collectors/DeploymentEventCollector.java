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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsOtlpEmitter;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.DeploymentEvent;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.ProcessUtils;

/**
 * Collector for deployment event telemetry.
 *
 * <p>Emits a {@link DeploymentEvent} once at startup and then every 24 hours. Captures deployment
 * metadata (git commit, CI/CD info, SDK version) for the instrumented service.
 */
public class DeploymentEventCollector extends BaseCollector {

  private static final Logger logger = Logger.getLogger(DeploymentEventCollector.class.getName());
  private static final DateTimeFormatter ISO_FORMATTER =
      DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

  /** 24 hours in milliseconds. */
  public static final int FLUSH_INTERVAL_MS = 86_400_000;

  private final AtomicBoolean firstCollect = new AtomicBoolean(true);

  private final String environment;
  private final String serviceName;
  private final String deploymentId;
  private final String deploymentTimestamp;
  private final String deploymentUrl;
  private final String gitCommitSha;
  private final String gitRepoUrl;
  private final long pid;
  private final String sdkVersion;
  private final ObjectMapper objectMapper;

  /**
   * Initialize the deployment event collector.
   *
   * @param flushIntervalMs How often to emit deployment events (default 24h)
   * @param environment Deployment environment
   * @param serviceName Service name
   * @param deploymentId Deployment identifier
   * @param deploymentTimestamp Deployment timestamp
   * @param deploymentUrl Deployment URL
   * @param gitCommitSha Git commit SHA
   * @param gitRepoUrl Git repository URL
   * @param otlpEmitter Optional OTLP emitter for sending OTLP signals
   */
  public DeploymentEventCollector(
      int flushIntervalMs,
      String environment,
      String serviceName,
      String deploymentId,
      String deploymentTimestamp,
      String deploymentUrl,
      String gitCommitSha,
      String gitRepoUrl,
      ServiceEventsOtlpEmitter otlpEmitter) {
    super(flushIntervalMs, "DeploymentEventCollector", otlpEmitter);
    // No sentinel: environment stays null/empty when unset so emit paths omit it.
    this.environment =
        environment != null ? environment : System.getenv().getOrDefault("ENVIRONMENT", null);
    this.serviceName = serviceName != null ? serviceName : "UnknownService";
    this.deploymentId = deploymentId != null ? deploymentId : "unknown-deployment-id";
    this.deploymentTimestamp = deploymentTimestamp != null ? deploymentTimestamp : "";
    this.deploymentUrl = deploymentUrl != null ? deploymentUrl : "";
    this.gitCommitSha = gitCommitSha != null ? gitCommitSha : "";
    this.gitRepoUrl = gitRepoUrl != null ? gitRepoUrl : "";
    this.pid = ProcessUtils.currentPid();
    this.sdkVersion = loadSdkVersion();
    this.objectMapper = new ObjectMapper();
  }

  @Override
  protected void collect() {
    String trigger;
    if (firstCollect.compareAndSet(true, false)) {
      trigger = "startup";
    } else if (!isRunning()) {
      trigger = "shutdown";
    } else {
      trigger = "periodic";
    }

    String timestamp = ISO_FORMATTER.format(Instant.now());

    DeploymentEvent.DeploymentContext deploymentContext =
        new DeploymentEvent.DeploymentContext(
            gitRepoUrl, gitCommitSha, deploymentUrl, deploymentTimestamp, deploymentId);

    DeploymentEvent event =
        DeploymentEvent.builder()
            .timestamp(timestamp)
            .serviceName(serviceName)
            .environment(environment)
            .sdkVersion(sdkVersion)
            .pid(pid)
            .deploymentContext(deploymentContext)
            .build();

    if (otlpEmitter != null) {
      otlpEmitter.emitDeploymentEvent(event, trigger);
    } else {
      exportToConsole(event);
    }
    logger.info("Exported DeploymentEvent telemetry (trigger=" + trigger + ")");
  }

  private void exportToConsole(DeploymentEvent event) {
    System.out.println("\n" + ProcessUtils.repeat("=", 80));
    System.out.println("SERVICE_EVENTS DEPLOYMENT EVENT TELEMETRY");
    System.out.println(ProcessUtils.repeat("=", 80));

    try {
      String json = objectMapper.writeValueAsString(event.toMap());
      System.out.println(json);
    } catch (JsonProcessingException e) {
      logger.log(Level.WARNING, "Failed to serialize deployment event", e);
    }

    System.out.println("\n" + ProcessUtils.repeat("=", 80) + "\n");
  }

  private static String loadSdkVersion() {
    try (InputStream is =
        DeploymentEventCollector.class
            .getClassLoader()
            .getResourceAsStream("serviceevents-version.properties")) {
      if (is != null) {
        Properties props = new Properties();
        props.load(is);
        return props.getProperty("sdk_version", "unknown");
      }
    } catch (Exception e) {
      logger.log(Level.FINE, "Failed to load SDK version from properties", e);
    }
    return "unknown";
  }
}
