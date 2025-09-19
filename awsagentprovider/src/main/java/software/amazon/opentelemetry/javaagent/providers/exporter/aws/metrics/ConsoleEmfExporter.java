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
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.common.BaseEmfExporter;

public class ConsoleEmfExporter extends BaseEmfExporter {
  private static final Logger logger = Logger.getLogger(ConsoleEmfExporter.class.getName());

  /**
   * Initialize the Console EMF exporter.
   *
   * @param namespace CloudWatch namespace for metrics (defaults to "default")
   */
  public ConsoleEmfExporter(String namespace) {
    super(namespace);
  }

  @Override
  public CompletableResultCode flush() {
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

  /**
   * Send a log event message to stdout for console output.
   *
   * <p>This method writes the EMF log message to stdout, making it easy to capture and redirect the
   * output for processing or debugging purposes.
   *
   * @param logEvent The log event dictionary containing 'message' and 'timestamp' keys, where
   *     'message' is the JSON-serialized EMF log
   */
  @Override
  protected void emit(Map<String, Object> logEvent) {
    try {
      Object messageObj = logEvent.get("message");
      String message = messageObj != null ? messageObj.toString() : "";
      if (message.isEmpty()) {
        logger.log(Level.WARNING, "Empty message in log event: " + logEvent);
        return;
      }
      System.out.println(message);
      System.out.flush();
    } catch (Exception error) {
      logger.log(
          Level.SEVERE,
          "Failed to write EMF log to console. Log event: "
              + logEvent
              + ". Error: "
              + error.getMessage());
    }
  }
}
