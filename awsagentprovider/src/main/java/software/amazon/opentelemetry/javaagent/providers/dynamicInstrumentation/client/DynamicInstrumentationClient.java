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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.internal.InstrumentationUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.config.DynamicInstrumentationConfig;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationType;

/**
 * SDK client for fetching instrumentation configurations from backend API. Handles HTTP
 * communication, pagination, and response parsing.
 */
public final class DynamicInstrumentationClient {
  private static final Logger logger =
      Logger.getLogger(DynamicInstrumentationClient.class.getName());
  private static final int MAX_PAGES = 3; // API returns max 50 results per page

  private static final MediaType JSON = MediaType.get("application/json");

  private final DynamicInstrumentationConfig config;
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;
  private ConfigurationPoller poller;

  public DynamicInstrumentationClient(DynamicInstrumentationConfig config) {
    this.config = config;
    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(config.getHttpTimeoutSeconds(), TimeUnit.SECONDS)
            .readTimeout(config.getHttpTimeoutSeconds(), TimeUnit.SECONDS)
            .writeTimeout(config.getHttpTimeoutSeconds(), TimeUnit.SECONDS)
            .build();
    this.objectMapper = new ObjectMapper();
  }

  /** Get service name from configuration. */
  public String getServiceName() {
    return config.getServiceName();
  }

  /** Get deployment environment from configuration. */
  public String getEnvironment() {
    return config.getDeploymentEnvironment();
  }

  /** Get the configuration object. */
  public DynamicInstrumentationConfig getConfig() {
    return config;
  }

  /**
   * Fetch instrumentation configurations for a specific type.
   *
   * @param instrumentationType PROBE or BREAKPOINT
   * @param lastSyncTime Last sync timestamp for incremental updates (can be null)
   * @return ApiResponse containing configurations, or null on failure
   */
  public ApiResponse fetchConfigurationByType(
      InstrumentationType instrumentationType, Long lastSyncTime) {

    List<Map<String, Object>> allConfigs = new ArrayList<>();
    String nextToken = null;
    Long responseSyncedAt = null;
    Integer nextSyncInterval = null;
    Boolean changed = false;

    try {
      // Paginate through results (up to MAX_PAGES)
      for (int page = 0; page < MAX_PAGES; page++) {
        // Build request payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("Service", getServiceName());
        payload.put("Environment", getEnvironment());
        payload.put("InstrumentationType", instrumentationType.name());

        if (nextToken != null && responseSyncedAt != null) {
          payload.put("NextToken", nextToken);
          payload.put("SyncedAt", responseSyncedAt);
        } else if (lastSyncTime != null) {
          payload.put("SyncedAt", lastSyncTime);
        }

        // Make HTTP request
        String url = config.getApiUrl() + "/list-instrumentation-configurations";
        logger.log(
            Level.FINE,
            "Fetching {0} configurations from: {1}",
            new Object[] {instrumentationType, url});

        String jsonPayload = objectMapper.writeValueAsString(payload);

        Request request =
            new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("User-Agent", "DynamicInstrumentationClient/1.0")
                .post(RequestBody.create(jsonPayload, JSON))
                .build();

        try (Response response = executeSuppressed(request)) {
          if (response == null) {
            logger.log(
                Level.WARNING,
                "HTTP request failed for {0} (page {1})",
                new Object[] {instrumentationType, page});
            return null;
          }
          String responseBody = response.body() != null ? response.body().string() : "";

          // Handle response status
          if (response.code() == 200) {
            Map<String, Object> rawResponse =
                objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});

            // Store metadata from first response
            if (page == 0) {
              changed = (Boolean) rawResponse.getOrDefault("Changed", true);
              responseSyncedAt = getLongValue(rawResponse.get("SyncedAt"));
              nextSyncInterval = getIntegerValue(rawResponse.get("SyncInterval"));
            }

            // Process LatestConfigurations and deserialize ConfigurationData
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> latestConfigs =
                (List<Map<String, Object>>) rawResponse.get("LatestConfigurations");

            if (latestConfigs != null) {
              for (Map<String, Object> item : latestConfigs) {
                Map<String, Object> configItem = new HashMap<>(item);

                // Deserialize ConfigurationData if it's a JSON string
                Object configData = item.get("ConfigurationData");
                if (configData instanceof String) {
                  try {
                    Map<String, Object> deserializedData =
                        objectMapper.readValue(
                            (String) configData, new TypeReference<Map<String, Object>>() {});
                    configItem.put("ConfigurationData", deserializedData);
                  } catch (Exception e) {
                    logger.log(
                        Level.WARNING, "Failed to parse ConfigurationData: {0}", e.getMessage());
                  }
                }

                // Ensure AttributeFilters is a list
                if (configItem.get("AttributeFilters") == null) {
                  configItem.put("AttributeFilters", new ArrayList<>());
                }

                allConfigs.add(configItem);
              }
            }

            // Check for next page
            nextToken = (String) rawResponse.get("NextToken");
            if (nextToken == null || nextToken.isEmpty()) {
              break; // No more pages
            }

          } else if (response.code() == 404) {
            logger.fine("No configuration found for service");
            return new ApiResponse(false, null, null, new ArrayList<>());
          } else {
            logger.log(
                Level.WARNING, "HTTP {0}: {1}", new Object[] {response.code(), responseBody});
            return null;
          }
        } // End of try-with-resources for Response
      }

      // Build final response
      return new ApiResponse(changed, responseSyncedAt, nextSyncInterval, allConfigs);

    } catch (IOException e) {
      logger.log(
          Level.SEVERE,
          "Network error fetching {0} configuration: {1}",
          new Object[] {instrumentationType, e.getMessage()});
      return null;
    } catch (Throwable e) {
      // Catch Throwable: OutOfMemoryError from large responses or NoClassDefFoundError
      // from Jackson dependencies should not kill the polling thread.
      logger.log(
          Level.SEVERE,
          "Error fetching {0} configuration: {1}",
          new Object[] {instrumentationType, e.getMessage()});
      return null;
    }
  }

  /**
   * Parse API response configurations into InstrumentationConfiguration objects. Filters by
   * attribute filters before parsing.
   */
  public List<InstrumentationConfiguration> parseConfigurations(ApiResponse response) {
    if (response == null || response.getLatestConfigurations() == null) {
      return new ArrayList<>();
    }

    List<InstrumentationConfiguration> configs = new ArrayList<>();

    for (Map<String, Object> item : response.getLatestConfigurations()) {
      // Check attribute filters first (early filtering)
      @SuppressWarnings("unchecked")
      List<Map<String, String>> filters = (List<Map<String, String>>) item.get("AttributeFilters");

      if (!matchesAttributeFilters(filters)) {
        logger.log(
            Level.FINE,
            "Skipping config due to attribute filter mismatch: {0}",
            item.get("Location"));
        continue;
      }

      // Parse into InstrumentationConfiguration
      try {
        InstrumentationConfiguration config = InstrumentationConfiguration.fromApiConfig(item);
        if (config != null) {
          configs.add(config);
        }
      } catch (Exception e) {
        logger.log(Level.WARNING, "Error parsing config item: {0}", e.getMessage());
      }
    }

    logger.log(
        Level.FINE,
        "Parsed {0} configurations from {1} API items",
        new Object[] {configs.size(), response.getLatestConfigurations().size()});

    return configs;
  }

  /** Check if resource attributes match at least one filter object. */
  private boolean matchesAttributeFilters(List<Map<String, String>> attributeFilters) {
    if (attributeFilters == null || attributeFilters.isEmpty()) {
      return true;
    }

    try {
      for (Map<String, String> filterObj : attributeFilters) {
        if (filterObj == null || filterObj.isEmpty()) {
          continue;
        }

        // Check if all key-value pairs in this filter match
        boolean allMatch = true;
        for (Map.Entry<String, String> entry : filterObj.entrySet()) {
          if (entry.getKey() == null || entry.getKey().isEmpty()) {
            continue;
          }

          Object resourceValue =
              config
                  .getResource()
                  .getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey(entry.getKey()));

          if (!entry.getValue().equals(resourceValue)) {
            allMatch = false;
            break;
          }
        }

        if (allMatch) {
          return true; // At least one filter matched
        }
      }

      return false; // No filters matched

    } catch (Exception e) {
      logger.log(Level.WARNING, "Error checking attribute filters: {0}", e.getMessage());
      return true; // Default to allowing instrumentation on error
    }
  }

  /** Start configuration polling. */
  public void startPolling() {
    if (poller != null) {
      logger.warning("Polling already started");
      return;
    }

    poller = new ConfigurationPoller(this);
    poller.start();
    logger.log(
        Level.FINE,
        "Started configuration polling - PROBE: {0}s, BREAKPOINT: {1}s",
        new Object[] {
          config.getProbePollIntervalSeconds(), config.getBreakpointPollIntervalSeconds()
        });
  }

  /** Stop configuration polling. */
  public void stopPolling() {
    if (poller == null) {
      logger.warning("Polling not started");
      return;
    }

    poller.stop();
    poller = null;
    logger.fine("Stopped configuration polling");
  }

  /** Check if polling is active. */
  public boolean isPolling() {
    return poller != null && poller.isRunning();
  }

  /** Get the configuration poller (may be null if polling not started). */
  public ConfigurationPoller getPoller() {
    return poller;
  }

  /**
   * Report configuration status to backend.
   *
   * @param statusEntries List of status entries to report
   */
  public void reportConfigurationStatus(
      List<
              software
                  .amazon
                  .opentelemetry
                  .javaagent
                  .providers
                  .dynamicInstrumentation
                  .model
                  .StatusEntry>
          statusEntries) {
    if (statusEntries == null || statusEntries.isEmpty()) {
      return;
    }

    try {
      // Build payload
      Map<String, Object> payload = new HashMap<>();
      payload.put("Service", getServiceName());
      payload.put("Environment", getEnvironment());

      List<Map<String, Object>> configurations = new ArrayList<>();
      for (software
              .amazon
              .opentelemetry
              .javaagent
              .providers
              .dynamicInstrumentation
              .model
              .StatusEntry
          entry : statusEntries) {
        configurations.add(entry.toMap());
      }
      payload.put("Configurations", configurations);

      // Send request
      String url = config.getApiUrl() + "/report-instrumentation-configuration-status";
      String jsonPayload = objectMapper.writeValueAsString(payload);

      Request request =
          new Request.Builder()
              .url(url)
              .header("Content-Type", "application/json")
              .header("User-Agent", "DynamicInstrumentationClient/1.0")
              .post(RequestBody.create(jsonPayload, JSON))
              .build();

      try (Response response = executeSuppressed(request)) {
        if (response == null) {
          logger.log(Level.WARNING, "Status report HTTP request failed");
        } else if (response.isSuccessful()) {
          logger.log(
              Level.FINE, "Successfully reported {0} configuration statuses", statusEntries.size());
        } else {
          logger.log(Level.FINE, "Status report failed with HTTP {0}", response.code());
        }
      }

    } catch (Exception e) {
      logger.log(Level.FINE, "Error sending status report", e);
    }
  }

  // Uses io.opentelemetry.api.internal.InstrumentationUtil — the only mechanism to suppress
  // auto-instrumentation on HTTP calls. The ContextKey is private to InstrumentationUtil and
  // uses identity matching, so the public Context API cannot replicate this. This is the same
  // approach used by OTel's own exporters (OkHttpHttpSender, ZipkinSpanExporter).
  private Response executeSuppressed(Request request) {
    Response[] responseHolder = new Response[1];
    InstrumentationUtil.suppressInstrumentation(
        () -> {
          try {
            responseHolder[0] = httpClient.newCall(request).execute();
          } catch (IOException e) {
            logger.log(Level.WARNING, "HTTP call failed during suppressed execution", e);
          } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Unexpected error during suppressed HTTP call", e);
          }
        });
    return responseHolder[0];
  }

  private static Long getLongValue(Object value) {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    return null;
  }

  private static Integer getIntegerValue(Object value) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    return null;
  }
}
