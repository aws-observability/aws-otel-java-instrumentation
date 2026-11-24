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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.config;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ServiceAttributes;
import java.lang.instrument.Instrumentation;
import java.util.logging.Logger;

/**
 * Configuration for the Dynamic Instrumentation feature. Holds all settings needed for
 * initialization and operation.
 */
public final class DynamicInstrumentationConfig {
  private static final Logger logger =
      Logger.getLogger(DynamicInstrumentationConfig.class.getName());
  private static final int DEFAULT_PROBE_POLL_INTERVAL = 600; // 10 minutes
  private static final int DEFAULT_BREAKPOINT_POLL_INTERVAL = 60; // 1 minute
  private static final int DEFAULT_HTTP_TIMEOUT = 30; // seconds
  private static final int DEFAULT_MAX_RETRIES = 3;
  private static final String DEFAULT_LOGS_ENDPOINT = "http://localhost:4316/v1/logs";

  private final boolean enabled;
  private final String apiUrl;
  private final int probePollIntervalSeconds;
  private final int breakpointPollIntervalSeconds;
  private final Resource resource;
  private final int httpTimeoutSeconds;
  private final int maxRetries;
  private final Instrumentation instrumentation;
  private final String logsEndpoint;
  private volatile String cachedDeploymentEnvironment = null;

  private DynamicInstrumentationConfig(Builder builder) {
    this.enabled = builder.enabled;
    this.apiUrl = builder.apiUrl;
    this.probePollIntervalSeconds = builder.probePollIntervalSeconds;
    this.breakpointPollIntervalSeconds = builder.breakpointPollIntervalSeconds;
    this.resource = builder.resource;
    this.httpTimeoutSeconds = builder.httpTimeoutSeconds;
    this.maxRetries = builder.maxRetries;
    this.instrumentation = builder.instrumentation;
    String trimmedEndpoint = builder.logsEndpoint != null ? builder.logsEndpoint.trim() : null;
    this.logsEndpoint =
        (trimmedEndpoint != null && !trimmedEndpoint.isEmpty())
            ? trimmedEndpoint
            : DEFAULT_LOGS_ENDPOINT;
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getApiUrl() {
    return apiUrl;
  }

  public int getProbePollIntervalSeconds() {
    return probePollIntervalSeconds;
  }

  public int getBreakpointPollIntervalSeconds() {
    return breakpointPollIntervalSeconds;
  }

  public Resource getResource() {
    return resource;
  }

  public int getHttpTimeoutSeconds() {
    return httpTimeoutSeconds;
  }

  public int getMaxRetries() {
    return maxRetries;
  }

  public Instrumentation getInstrumentation() {
    return instrumentation;
  }

  public String getLogsEndpoint() {
    return logsEndpoint;
  }

  /** Extract service name from resource. */
  public String getServiceName() {
    if (resource == null) {
      return "UnknownService";
    }
    String serviceName = resource.getAttribute(ServiceAttributes.SERVICE_NAME);
    if (serviceName != null && !serviceName.startsWith("unknown_service")) {
      return serviceName;
    }
    return "UnknownService";
  }

  /**
   * Extract deployment environment from resource with lazy-loading and caching. Only caches
   * successful environment resolution to handle timing issues with Resource population.
   */
  public String getDeploymentEnvironment() {
    // Return cached value if we successfully found it before
    if (cachedDeploymentEnvironment != null) {
      return cachedDeploymentEnvironment;
    }

    // Try to fetch from Resource
    if (resource == null) {
      logger.fine("AWS DI: Resource is null, deployment environment will be UnknownEnvironment");
      return "UnknownEnvironment"; // Don't cache - Resource might appear later
    }

    String env = resource.getAttribute(AttributeKey.stringKey("deployment.environment.name"));
    if (env != null && !env.isEmpty()) {
      // SUCCESS! Cache it so we never query again
      cachedDeploymentEnvironment = env;
      logger.fine("AWS DI: Deployment environment resolved and cached: " + env);
      return env;
    }

    // Attribute not available yet - don't cache, try again next time
    logger.fine(
        "AWS DI: deployment.environment.name attribute not yet available, will retry on next call");
    return "UnknownEnvironment";
  }

  public static final class Builder {
    private boolean enabled = false;
    private String apiUrl;
    private int probePollIntervalSeconds = DEFAULT_PROBE_POLL_INTERVAL;
    private int breakpointPollIntervalSeconds = DEFAULT_BREAKPOINT_POLL_INTERVAL;
    private Resource resource;
    private int httpTimeoutSeconds = DEFAULT_HTTP_TIMEOUT;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private Instrumentation instrumentation;
    private String logsEndpoint;

    private Builder() {}

    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    public Builder apiUrl(String apiUrl) {
      this.apiUrl = apiUrl;
      return this;
    }

    public Builder probePollIntervalSeconds(int seconds) {
      if (seconds <= 0) {
        throw new IllegalArgumentException("Probe poll interval must be positive");
      }
      this.probePollIntervalSeconds = seconds;
      return this;
    }

    public Builder breakpointPollIntervalSeconds(int seconds) {
      if (seconds <= 0) {
        throw new IllegalArgumentException("Breakpoint poll interval must be positive");
      }
      this.breakpointPollIntervalSeconds = seconds;
      return this;
    }

    public Builder resource(Resource resource) {
      this.resource = resource;
      return this;
    }

    public Builder httpTimeoutSeconds(int seconds) {
      if (seconds <= 0) {
        throw new IllegalArgumentException("HTTP timeout must be positive");
      }
      this.httpTimeoutSeconds = seconds;
      return this;
    }

    public Builder maxRetries(int retries) {
      if (retries < 0) {
        throw new IllegalArgumentException("Max retries must be non-negative");
      }
      this.maxRetries = retries;
      return this;
    }

    public Builder instrumentation(Instrumentation instrumentation) {
      this.instrumentation = instrumentation;
      return this;
    }

    public Builder logsEndpoint(String logsEndpoint) {
      this.logsEndpoint = logsEndpoint;
      return this;
    }

    public DynamicInstrumentationConfig build() {
      if (apiUrl == null || apiUrl.isEmpty()) {
        throw new IllegalStateException("API URL must be specified");
      }
      if (resource == null) {
        throw new IllegalStateException("Resource must be provided");
      }
      return new DynamicInstrumentationConfig(this);
    }
  }
}
