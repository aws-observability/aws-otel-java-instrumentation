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
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.StatusEntry;

/**
 * Sink for delivering instrumentation configuration status reports to the backend.
 *
 * <p>Decouples {@link StatusReporter} from the concrete {@link DynamicInstrumentationClient} (which
 * is {@code final}) so the reporting logic can be exercised with a capturing implementation in
 * tests. The production implementation is {@link DynamicInstrumentationClient}.
 */
public interface StatusReportSink {

  /**
   * Report a batch of configuration status entries to the backend.
   *
   * @param statusEntries Status entries to report
   */
  void reportConfigurationStatus(List<StatusEntry> statusEntries);
}
