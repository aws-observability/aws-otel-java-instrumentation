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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Status entry for reporting instrumentation configuration status to backend.
 *
 * <p>Serialized to JSON and sent in batch to /report-instrumentation-configuration-status endpoint.
 */
public class StatusEntry {
  private static final String SIGNAL_TYPE = "SNAPSHOT";

  private final String instrumentationType;
  private final String locationHash;
  private final ConfigurationStatus status;
  private final ErrorCause errorCause; // nullable
  private final long timestamp;

  public StatusEntry(
      String instrumentationType,
      String locationHash,
      ConfigurationStatus status,
      ErrorCause errorCause,
      Instant timestamp) {
    this.instrumentationType = instrumentationType;
    this.locationHash = locationHash;
    this.status = status;
    this.errorCause = errorCause;
    this.timestamp = timestamp.getEpochSecond();
  }

  public StatusEntry(
      String instrumentationType,
      String locationHash,
      ConfigurationStatus status,
      Instant timestamp) {
    this(instrumentationType, locationHash, status, null, timestamp);
  }

  /**
   * Convert to Map for JSON serialization.
   *
   * <p>Format:
   *
   * <pre>
   * {
   *   "InstrumentationType": "BREAKPOINT",
   *   "SignalType": "SNAPSHOT",
   *   "LocationHash": "abc123",
   *   "Status": "ACTIVE",
   *   "Time": 1738545234,
   *   "ErrorCause": "METHOD_NOT_FOUND" (optional)
   * }
   * </pre>
   */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new HashMap<>();
    map.put("InstrumentationType", instrumentationType);
    map.put("SignalType", SIGNAL_TYPE);
    map.put("LocationHash", locationHash);
    map.put("Status", status.name());
    map.put("Time", timestamp);

    if (errorCause != null) {
      map.put("ErrorCause", errorCause.name());
    }

    return map;
  }

  // Getters
  public String getLocationHash() {
    return locationHash;
  }

  public ConfigurationStatus getStatus() {
    return status;
  }

  public ErrorCause getErrorCause() {
    return errorCause;
  }

  public long getTimestamp() {
    return timestamp;
  }
}
