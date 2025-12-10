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

import java.io.PrintStream;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.common.emitter.ConsoleEmitter;
import software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics.common.emitter.LogEventEmitter;

public class ConsoleEmfExporterBuilder {
  private String namespace;
  private LogEventEmitter<PrintStream> emitter;
  private boolean shouldAddApplicationSignalsDimensions = false;

  public ConsoleEmfExporterBuilder setNamespace(String namespace) {
    this.namespace = namespace;
    return this;
  }

  public ConsoleEmfExporterBuilder setEmitter(LogEventEmitter<PrintStream> emitter) {
    this.emitter = emitter;
    return this;
  }

  public ConsoleEmfExporterBuilder setShouldAddApplicationSignalsDimensions(
      boolean shouldAddApplicationSignalsDimensions) {
    this.shouldAddApplicationSignalsDimensions = shouldAddApplicationSignalsDimensions;
    return this;
  }

  public ConsoleEmfExporter build() {
    if (this.namespace == null) {
      this.namespace = "default";
    }
    if (this.emitter == null) {
      this.emitter = new ConsoleEmitter();
    }
    return ConsoleEmfExporter.create(
        this.namespace, this.emitter, this.shouldAddApplicationSignalsDimensions);
  }
}
