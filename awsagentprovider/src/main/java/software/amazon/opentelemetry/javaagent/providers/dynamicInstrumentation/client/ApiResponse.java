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

import java.util.List;
import java.util.Map;

/**
 * Response from the backend API /list-instrumentation-configurations endpoint. Contains
 * configuration items and metadata for incremental sync.
 */
public final class ApiResponse {
  private final boolean changed;
  private final Long syncedAt;
  private final Integer syncInterval;
  private final List<Map<String, Object>> latestConfigurations;

  public ApiResponse(
      boolean changed,
      Long syncedAt,
      Integer syncInterval,
      List<Map<String, Object>> latestConfigurations) {
    this.changed = changed;
    this.syncedAt = syncedAt;
    this.syncInterval = syncInterval;
    this.latestConfigurations = latestConfigurations;
  }

  public boolean isChanged() {
    return changed;
  }

  public Long getSyncedAt() {
    return syncedAt;
  }

  public Integer getSyncInterval() {
    return syncInterval;
  }

  public List<Map<String, Object>> getLatestConfigurations() {
    return latestConfigurations;
  }
}
