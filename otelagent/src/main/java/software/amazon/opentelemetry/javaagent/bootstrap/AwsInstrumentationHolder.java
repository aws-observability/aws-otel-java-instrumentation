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

package software.amazon.opentelemetry.javaagent.bootstrap;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the Instrumentation reference for global access by AWS Dynamic Instrumentation.
 *
 * <p>This class lives in the bootstrap package to ensure it's loaded by the bootstrap classloader,
 * making it visible to all application code and injected bytecode.
 *
 * <p>The Instrumentation instance is set once during agent bootstrap (premain/agentmain) and can be
 * retrieved later by components that need to perform bytecode transformations.
 *
 * <p>Named AwsInstrumentationHolder to avoid collision with upstream OpenTelemetry's
 * InstrumentationHolder.
 */
public final class AwsInstrumentationHolder {

  private static final AtomicReference<Instrumentation> instrumentation = new AtomicReference<>();

  private AwsInstrumentationHolder() {
    // Utility class - no instantiation
  }

  /**
   * Set the Instrumentation instance. Intended to be called once during agent bootstrap.
   *
   * <p>This method is idempotent: the first non-null instance wins and any subsequent call is a
   * no-op. It deliberately never throws — this holder sits on the universal agent entry path
   * (premain), so a duplicate set (for example, the same agent JAR specified twice via {@code
   * -javaagent}) must not be able to abort JVM startup.
   *
   * @param inst The Instrumentation instance from premain/agentmain
   */
  public static void setInstrumentation(Instrumentation inst) {
    instrumentation.compareAndSet(null, inst);
  }

  /**
   * Get the Instrumentation instance.
   *
   * @return The Instrumentation instance, or null if not yet set
   */
  public static Instrumentation getInstrumentation() {
    return instrumentation.get();
  }
}
