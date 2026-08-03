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

package software.amazon.distro.opentelemetry.extension.spanmetrics.internal;

import io.opentelemetry.api.OpenTelemetry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges the host's fully-built OpenTelemetry instance to the span processor. The processor is
 * constructed before the MeterProvider exists, so the instance is supplied later by whichever host
 * hook fires: the javaagent listener, the Spring auto-configuration, or a manual bind() call.
 *
 * <p>This class is internal and not part of the public API.
 */
public final class OpenTelemetryHolder {

  private static final Logger logger = Logger.getLogger(OpenTelemetryHolder.class.getName());

  private static final AtomicReference<OpenTelemetry> instance = new AtomicReference<>();

  /** First bind wins. A later bind of a different instance is ignored but logged. */
  public static void set(OpenTelemetry openTelemetry) {
    if (!instance.compareAndSet(null, openTelemetry)) {
      OpenTelemetry existing = instance.get();
      if (existing != openTelemetry) {
        logger.log(
            Level.WARNING,
            "Span metrics already bound to an OpenTelemetry instance; ignoring a later, different"
                + " bind. Span metrics will use the first instance.");
      }
    }
  }

  public static OpenTelemetry get() {
    return instance.get();
  }

  private OpenTelemetryHolder() {}
}
