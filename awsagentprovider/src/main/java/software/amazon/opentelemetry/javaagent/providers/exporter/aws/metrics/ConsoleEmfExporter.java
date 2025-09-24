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

package software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics;

import io.opentelemetry.sdk.common.CompletableResultCode;
import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.BaseEmfExporter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.emitter.ConsoleEmitter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.emitter.LogEventEmitter;

public class ConsoleEmfExporter extends BaseEmfExporter<PrintStream> {
  private static final Logger logger = Logger.getLogger(ConsoleEmfExporter.class.getName());

  /**
   * Initialize the Console EMF exporter.
   *
   * @param namespace CloudWatch namespace for metrics (defaults to "default")
   */
  public ConsoleEmfExporter(String namespace) {
    super(namespace, new ConsoleEmitter());
  }

  /**
   * Initialize the Console EMF exporter with custom emitter for testing.
   *
   * @param namespace CloudWatch namespace for metrics
   * @param emitter Custom emitter
   */
  public ConsoleEmfExporter(String namespace, LogEventEmitter<PrintStream> emitter) {
    super(namespace, emitter);
  }

  @Override
  public CompletableResultCode flush() {
    this.emitter.flushEvents();
    logger.log(
        Level.FINE,
        "ConsoleEmfExporter force_flush called - no buffering to flush for console output");
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    logger.log(Level.FINE, "ConsoleEmfExporter shutdown called");
    return CompletableResultCode.ofSuccess();
  }
}
