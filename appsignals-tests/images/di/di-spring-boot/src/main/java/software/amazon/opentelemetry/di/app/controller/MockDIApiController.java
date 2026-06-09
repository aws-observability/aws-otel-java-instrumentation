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

package software.amazon.opentelemetry.di.app.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock DI API server running on the main application port (8080). Serves PROBE and BREAKPOINT
 * configurations to the DI agent via the /list-instrumentation-configurations endpoint.
 *
 * <p>The real CloudWatch Agent API uses POST with a JSON body containing: {"Service": "...",
 * "Environment": "...", "InstrumentationType": "PROBE|BREAKPOINT"}
 *
 * <p>And returns: {"Changed": true, "SyncedAt": ..., "LatestConfigurations": [...]}
 */
@RestController
public class MockDIApiController {

  @Autowired private ObjectMapper objectMapper;

  private Map<String, List<Map<String, Object>>> configCache;

  // Records every status entry the SDK reports, so contract tests can assert the
  // READY -> ACTIVE -> DISABLED reporting lifecycle end-to-end. Thread-safe: status reports arrive
  // on the SDK's background reporter thread while tests read via the HTTP endpoint below.
  private final ConcurrentLinkedQueue<Map<String, Object>> reportedStatuses =
      new ConcurrentLinkedQueue<>();

  @PostMapping("/list-instrumentation-configurations")
  @SuppressWarnings("unchecked")
  public ResponseEntity<Map<String, Object>> listConfigurations(
      @RequestBody Map<String, Object> request) throws IOException {

    if (configCache == null) {
      configCache = loadAllConfigs();
    }

    String instrumentationType = (String) request.getOrDefault("InstrumentationType", "BREAKPOINT");

    List<Map<String, Object>> configs =
        configCache.getOrDefault(instrumentationType.toUpperCase(), List.of());

    Map<String, Object> response = new HashMap<>();
    response.put("Changed", true);
    response.put("SyncedAt", System.currentTimeMillis());
    response.put("LatestConfigurations", configs);

    return ResponseEntity.ok(response);
  }

  @PostMapping("/report-instrumentation-configuration-status")
  @SuppressWarnings("unchecked")
  public ResponseEntity<Map<String, Object>> reportStatus(
      @RequestBody Map<String, Object> request) {
    // Record each reported status entry for contract-test assertions.
    Object configurations = request.get("Configurations");
    if (configurations instanceof List) {
      for (Object entry : (List<Object>) configurations) {
        if (entry instanceof Map) {
          reportedStatuses.add((Map<String, Object>) entry);
        }
      }
    }
    return ResponseEntity.ok(Map.of("status", "ok"));
  }

  /**
   * Test-only endpoint: returns every instrumentation status entry reported so far, in arrival
   * order. Used by DI contract tests to verify the READY/ACTIVE/DISABLED reporting lifecycle.
   */
  @GetMapping("/reported-instrumentation-statuses")
  public ResponseEntity<List<Map<String, Object>>> getReportedStatuses() {
    return ResponseEntity.ok(new ArrayList<>(reportedStatuses));
  }

  @SuppressWarnings("unchecked")
  private Map<String, List<Map<String, Object>>> loadAllConfigs() throws IOException {
    Map<String, List<Map<String, Object>>> cache = new HashMap<>();

    // Load probe configs
    ClassPathResource probeResource = new ClassPathResource("di-configs/probes.json");
    Map<String, Object> probeData =
        objectMapper.readValue(
            probeResource.getInputStream(), new TypeReference<Map<String, Object>>() {});
    cache.put("PROBE", (List<Map<String, Object>>) probeData.get("LatestConfigurations"));

    // Load breakpoint configs
    ClassPathResource bpResource = new ClassPathResource("di-configs/breakpoints.json");
    Map<String, Object> bpData =
        objectMapper.readValue(
            bpResource.getInputStream(), new TypeReference<Map<String, Object>>() {});
    cache.put("BREAKPOINT", (List<Map<String, Object>>) bpData.get("LatestConfigurations"));

    return cache;
  }
}
