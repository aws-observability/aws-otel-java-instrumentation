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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;

/**
 * Groups instrumentation configurations by function (class + method).
 *
 * <p>This utility handles conflict resolution and priority:
 *
 * <ul>
 *   <li>Groups multiple configs for same method into one FunctionInstrumentationSet
 *   <li>Resolves PROBE vs BREAKPOINT priority at line=0
 *   <li>Collects all line-level breakpoints per method
 *   <li>Skips invalid configurations gracefully
 * </ul>
 *
 * <p>Example: Given configs for com.example.OrderService.processOrder():
 *
 * <ul>
 *   <li>PROBE at line=0
 *   <li>BREAKPOINT at line=42
 *   <li>BREAKPOINT at line=55
 * </ul>
 *
 * <p>Result: One FunctionInstrumentationSet with PROBE + 2 line breakpoints.
 */
public final class InstrumentationGrouper {

  private static final Logger logger = Logger.getLogger(InstrumentationGrouper.class.getName());

  private InstrumentationGrouper() {
    // Utility class
  }

  /**
   * Group instrumentation configurations by function.
   *
   * <p>Multiple configs for the same function are merged into one FunctionInstrumentationSet.
   * Invalid configs are skipped with logging.
   *
   * @param configs List of instrumentation configurations
   * @return Map of functionKey (class.method) to FunctionInstrumentationSet
   */
  public static Map<String, FunctionInstrumentationSet> groupByFunction(
      List<InstrumentationConfiguration> configs) {

    Map<String, FunctionInstrumentationSet> grouped = new HashMap<>();
    int skipped = 0;

    for (InstrumentationConfiguration config : configs) {
      try {
        if (config == null) {
          skipped++;
          continue;
        }

        String functionKey = config.getFullyQualifiedClassName() + "." + config.getMethodName();

        // Get or create FunctionInstrumentationSet
        FunctionInstrumentationSet funcSet = grouped.get(functionKey);
        if (funcSet == null) {
          funcSet =
              new FunctionInstrumentationSet(
                  config.getFullyQualifiedClassName(), config.getMethodName());
          grouped.put(functionKey, funcSet);
        }

        // Add config to function set (handles line=0 priority)
        funcSet.addConfiguration(config);

      } catch (Exception e) {
        logger.warning("Failed to group configuration, skipping: " + e.getMessage());
        skipped++;
      }
    }

    if (skipped > 0) {
      logger.warning("Skipped " + skipped + " invalid configurations during grouping");
    }

    logger.fine(
        "Grouped " + configs.size() + " configurations into " + grouped.size() + " functions");

    return grouped;
  }

  /**
   * Group configurations for a specific class. Filters configs by class name first, then groups by
   * function.
   *
   * @param configs All configurations
   * @param className Target class name
   * @return Map of functionKey to FunctionInstrumentationSet for this class
   */
  public static Map<String, FunctionInstrumentationSet> groupByFunctionForClass(
      List<InstrumentationConfiguration> configs, String className) {

    Map<String, FunctionInstrumentationSet> grouped = new HashMap<>();

    for (InstrumentationConfiguration config : configs) {
      try {
        // Match exact FQN or a nested class addressed by simple name (binary `className` here is
        // the runtime name, e.g. com.pkg.Outer$Inner). matchesRuntimeClass handles both.
        if (config != null && config.matchesRuntimeClass(className)) {
          String functionKey = config.getFullyQualifiedClassName() + "." + config.getMethodName();

          FunctionInstrumentationSet funcSet = grouped.get(functionKey);
          if (funcSet == null) {
            funcSet = new FunctionInstrumentationSet(className, config.getMethodName());
            grouped.put(functionKey, funcSet);
          }

          funcSet.addConfiguration(config);
        }
      } catch (Exception e) {
        logger.warning("Error grouping config for class " + className + ": " + e.getMessage());
      }
    }

    return grouped;
  }
}
