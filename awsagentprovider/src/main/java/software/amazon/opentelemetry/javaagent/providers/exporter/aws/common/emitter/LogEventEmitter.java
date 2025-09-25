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

package software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.emitter;

import java.util.Map;

/**
 * Generic interface for log event emitters.
 *
 * @param <T> The type of the underlying emitter client
 */
public interface LogEventEmitter<T> {

  /**
   * Get the underlying emitter client.
   *
   * @return The emitter client
   */
  T getEmitter();

  /**
   * Emit a log event.
   *
   * @param logEvent The log event to emit
   */
  void emit(Map<String, Object> logEvent);

  /** Flush any pending events. */
  void flushEvents();
}
