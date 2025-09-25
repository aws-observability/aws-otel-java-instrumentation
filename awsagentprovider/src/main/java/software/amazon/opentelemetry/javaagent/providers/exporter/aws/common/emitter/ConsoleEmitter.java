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

import java.io.PrintStream;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A log event emitter that prints Log Events to Standard Out. */
public class ConsoleEmitter implements LogEventEmitter<PrintStream> {
  private static final Logger logger = Logger.getLogger(ConsoleEmitter.class.getName());
  private final PrintStream emitter;

  public ConsoleEmitter() {
    this.emitter = System.out;
  }

  public ConsoleEmitter(PrintStream emitter) {
    this.emitter = emitter;
  }

  @Override
  public PrintStream getEmitter() {
    return this.emitter;
  }

  @Override
  public void emit(Map<String, Object> logEvent) {
    try {
      Object messageObj = logEvent.get("message");
      String message = messageObj != null ? messageObj.toString() : "";
      if (message.isEmpty()) {
        logger.log(Level.WARNING, String.format("Empty message in log event: %s", logEvent));
        return;
      }
      this.emitter.println(message);
      this.emitter.flush();
    } catch (Exception error) {
      logger.log(
          Level.SEVERE,
          String.format(
              "Failed to write EMF log to console. Log event: %s. Error: %s",
              logEvent, error.getMessage()));
    }
  }

  @Override
  public void flushEvents() {
    this.emitter.flush();
  }
}
