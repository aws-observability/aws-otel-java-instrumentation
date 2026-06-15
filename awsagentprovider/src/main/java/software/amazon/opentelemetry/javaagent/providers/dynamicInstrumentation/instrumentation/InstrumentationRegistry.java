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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationState;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationType;

/**
 * Static registry for instrumentation configurations.
 *
 * <p>This registry is accessible from ByteBuddy Advice classes (injected bytecode), which may run
 * in different classloaders. The static nature ensures configurations are available regardless of
 * classloader context.
 *
 * <p>Supports querying configurations by class name to enable the single transformer pattern, where
 * one transformer applies all instrumentations for a class in a single transformation pass. This
 * minimizes JVM retransformation overhead.
 *
 * <p>Thread-safe using ConcurrentHashMap.
 */
public final class InstrumentationRegistry {

  private static final Logger logger = Logger.getLogger(InstrumentationRegistry.class.getName());

  private static final Map<String, InstrumentationConfiguration> configurations =
      new ConcurrentHashMap<>();

  // Runtime state tracking (hit counts, disable status).
  // ConcurrentHashMap type retained (not Map) so that compute() holds the per-key lock,
  // guaranteeing atomic check-and-replace when detecting recreated configs.
  private static final ConcurrentHashMap<String, InstrumentationState> states =
      new ConcurrentHashMap<>();

  // Parameter name mappings: methodKey -> String[] (index i = name for parameter i).
  // Empty string at an index means "unnamed, use argN fallback".
  // Key absent means no debug info was available — advice falls back to argN for all.
  private static final Map<String, String[]> parameterNames = new ConcurrentHashMap<>();

  private InstrumentationRegistry() {
    // Utility class - no instantiation
  }

  /**
   * Register an instrumentation configuration.
   *
   * @param methodKey Unique method key (codeUnit.className.methodName)
   * @param config The instrumentation configuration
   */
  public static void register(String methodKey, InstrumentationConfiguration config) {
    configurations.put(methodKey, config);

    // Atomic check-and-replace under ConcurrentHashMap's per-key lock. InstrumentationState holds
    // only static metadata (runtime hit state lives in DIDataStore.HitState); replacing it on a
    // config change just refreshes that metadata.
    states.compute(
        methodKey,
        (key, existing) -> {
          if (existing == null || hasConfigChanged(existing, config)) {
            // New or recreated config — refresh the metadata snapshot.
            return new InstrumentationState(
                config.getLocationHash(),
                config.getMaxHits(),
                config.getExpiresAt(),
                config.getInstrumentationType(),
                config.getCreatedAt());
          }
          // Same config — preserve existing state.
          return existing;
        });
  }

  /**
   * Check if configuration has changed based on locationHash and createdAt.
   *
   * @param existingState Current state for the method key
   * @param newConfig New configuration being registered
   * @return true if configuration has changed (location or recreation), false otherwise
   */
  private static boolean hasConfigChanged(
      InstrumentationState existingState, InstrumentationConfiguration newConfig) {
    // Check locationHash
    if (!Objects.equals(existingState.getLocationHash(), newConfig.getLocationHash())) {
      return true;
    }
    // Check createdAt — if both are non-null and differ, config was recreated
    Instant existingCreatedAt = existingState.getCreatedAt();
    Instant newCreatedAt = newConfig.getCreatedAt();
    if (existingCreatedAt != null
        && newCreatedAt != null
        && !existingCreatedAt.equals(newCreatedAt)) {
      return true;
    }
    // If new config has createdAt but existing doesn't (upgrade scenario), treat as changed
    if (existingCreatedAt == null && newCreatedAt != null) {
      return true;
    }
    return false;
  }

  /**
   * Get an instrumentation configuration.
   *
   * @param methodKey Unique method key
   * @return The configuration, or null if not found
   */
  public static InstrumentationConfiguration get(String methodKey) {
    return configurations.get(methodKey);
  }

  /**
   * Remove an instrumentation configuration.
   *
   * @param methodKey Unique method key
   * @return The removed configuration, or null if not found
   */
  public static InstrumentationConfiguration remove(String methodKey) {
    states.remove(methodKey);
    parameterNames.remove(methodKey);
    return configurations.remove(methodKey);
  }

  /** Clear all configurations and states. */
  public static void clearAll() {
    configurations.clear();
    states.clear();
    parameterNames.clear();
  }

  /** Get the number of active configurations. */
  public static int size() {
    return configurations.size();
  }

  /** Check if a configuration exists. */
  public static boolean contains(String methodKey) {
    return configurations.containsKey(methodKey);
  }

  /**
   * Get all instrumentation configurations for a specific class. Used by single transformer to
   * apply all instrumentations in one pass.
   *
   * @param className Fully qualified class name
   * @return List of configurations for the class (empty if none)
   */
  public static List<InstrumentationConfiguration> getConfigsForClass(String className) {
    return configurations.values().stream()
        .filter(config -> config.getFullyQualifiedClassName().equals(className))
        .collect(Collectors.toList());
  }

  /**
   * Get all classes that have active instrumentations. Used to determine which classes need
   * retransformation.
   *
   * @return Set of class names with active instrumentations
   */
  public static Set<String> getAllInstrumentedClasses() {
    return configurations.values().stream()
        .map(InstrumentationConfiguration::getFullyQualifiedClassName)
        .collect(Collectors.toSet());
  }

  /**
   * Get all active configurations. Used for rebuilding single transformer with all
   * instrumentations.
   *
   * @return List of all configurations
   */
  public static List<InstrumentationConfiguration> getAllConfigurations() {
    return new ArrayList<>(configurations.values());
  }

  /**
   * Get function-grouped configurations for a specific class. Used by single transformer to
   * determine method wrapper needs and line-level instrumentation.
   *
   * @param className Fully qualified class name
   * @return Map of functionKey to FunctionInstrumentationSet
   */
  public static Map<String, FunctionInstrumentationSet> getFunctionSets(String className) {
    List<InstrumentationConfiguration> classConfigs = getConfigsForClass(className);
    return InstrumentationGrouper.groupByFunctionForClass(classConfigs, className);
  }

  /**
   * Register configuration using primitive parameters to work across classloader boundaries. Called
   * via reflection from agent classloader to populate the InstrumentationRegistry copy in the
   * application classloader with full CaptureConfiguration details.
   *
   * @param instrumentationKey Unique key
   * @param locationHash 16-character hex LocationHash
   * @param codeUnit Package name
   * @param className Simple class name
   * @param methodName Method name
   * @param lineNumber Line number
   * @param filePath Source file
   * @param instrumentationType "PROBE" or "BREAKPOINT"
   * @param captureReturn Capture return value
   * @param captureStackTrace Capture stack trace
   * @param captureArguments Array of argument names
   * @param captureLocals Array of local variable names
   * @param maxStringLength Max string length
   * @param maxCollectionWidth Max collection width
   * @param maxCollectionDepth Max collection depth
   * @param maxStackFrames Max stack frames
   * @param maxStackTraceSize Max stack trace size
   * @param maxObjectDepth Max object depth
   * @param maxFieldsPerObject Max fields per object
   * @param maxHits Maximum hits
   */
  public static void registerFromPrimitives(
      String instrumentationKey,
      String locationHash,
      String codeUnit,
      String className,
      String methodName,
      int lineNumber,
      String filePath,
      String instrumentationType,
      boolean captureReturn,
      boolean captureStackTrace,
      String[] captureArguments,
      String[] captureLocals,
      int maxStringLength,
      int maxCollectionWidth,
      int maxCollectionDepth,
      int maxStackFrames,
      int maxStackTraceSize,
      int maxObjectDepth,
      int maxFieldsPerObject,
      int maxHits) {

    try {
      // Build CaptureConfiguration
      CaptureConfiguration captureConfig =
          CaptureConfiguration.builder()
              .captureReturn(captureReturn)
              .captureStackTrace(captureStackTrace)
              .captureArguments(
                  captureArguments != null ? java.util.Arrays.asList(captureArguments) : null)
              .captureLocals(captureLocals != null ? java.util.Arrays.asList(captureLocals) : null)
              .maxStringLength(maxStringLength)
              .maxCollectionWidth(maxCollectionWidth)
              .maxCollectionDepth(maxCollectionDepth)
              .maxStackFrames(maxStackFrames)
              .maxStackTraceSize(maxStackTraceSize)
              .maxObjectDepth(maxObjectDepth)
              .maxFieldsPerObject(maxFieldsPerObject)
              .build();

      // Use reflection to access private constructor
      java.lang.reflect.Constructor<InstrumentationConfiguration> constructor =
          InstrumentationConfiguration.class.getDeclaredConstructor(
              String.class,
              String.class,
              String.class,
              int.class,
              String.class,
              CaptureConfiguration.class,
              String.class,
              InstrumentationType.class,
              java.time.Instant.class,
              int.class,
              List.class,
              String.class,
              java.time.Instant.class,
              String.class);
      constructor.setAccessible(true);

      InstrumentationConfiguration config =
          constructor.newInstance(
              codeUnit,
              className,
              methodName,
              lineNumber,
              filePath,
              captureConfig,
              locationHash, // Use actual locationHash parameter
              InstrumentationType.valueOf(instrumentationType),
              null, // expiresAt
              maxHits,
              new ArrayList<>(), // attributeFilters
              null, // arn
              null, // createdAt
              "SNAPSHOT"); // signalType

      // Use correct key format: methodKey for method-level, instrumentationKey for line-level
      String registryKey =
          (lineNumber == 0)
              ? codeUnit + "." + className + "." + methodName // methodKey for method-level
              : instrumentationKey; // instrumentationKey for line-level

      configurations.put(registryKey, config);

      // Atomic check-and-replace under per-key lock.
      // Note: createdAt is always null on the primitives path — change detection for
      // recreated configs happens in the agent-classloader register() call, not here.
      // This path is a secondary mirror into the application classloader for Advice code.
      InstrumentationType instType = InstrumentationType.valueOf(instrumentationType);
      states.compute(
          registryKey,
          (key, existing) -> {
            if (existing == null || hasConfigChanged(existing, config)) {
              return new InstrumentationState(locationHash, maxHits, null, instType, null);
            }
            return existing;
          });

    } catch (Exception e) {
      // Log but don't throw - called from injected code
      logger.fine("Failed to register via primitives: " + e.getMessage());
    }
  }

  /**
   * Get capture arguments filter for a key. Used by MethodCaptureAdvice to filter arguments.
   *
   * @param key Method key
   * @return null = do not capture, empty = capture all, non-empty = capture only those names
   */
  public static String[] getCaptureArguments(String key) {
    InstrumentationConfiguration config = configurations.get(key);
    if (config == null || config.getCaptureConfig() == null) return null;
    List<String> args = config.getCaptureConfig().getCaptureArguments();
    return args != null ? args.toArray(new String[0]) : null;
  }

  /**
   * Get capture locals filter for a key. Used by LineCaptureAdvice to filter locals.
   *
   * @param key Instrumentation key (methodKey:lineNumber)
   * @return null = do not capture, empty = capture all, non-empty = capture only those names
   */
  public static String[] getCaptureLocals(String key) {
    InstrumentationConfiguration config = configurations.get(key);
    if (config == null || config.getCaptureConfig() == null) return null;
    List<String> locals = config.getCaptureConfig().getCaptureLocals();
    return locals != null ? locals.toArray(new String[0]) : null;
  }

  /**
   * Get whether to capture return value for a key. Used by MethodCaptureAdvice.
   *
   * @param key Method key
   * @return true if return value should be captured
   */
  public static boolean shouldCaptureReturn(String key) {
    InstrumentationConfiguration config = configurations.get(key);
    if (config == null || config.getCaptureConfig() == null) return false;
    return config.getCaptureConfig().isCaptureReturn();
  }

  /**
   * Get capture limits for an instrumentation key as an int array. Returns defaults if key not
   * found. Used by advice classes to pass limits to DIDataStore.
   *
   * @param key Instrumentation key (methodKey or instrumentationKey)
   * @return int[] {maxDepth, maxFields, maxCollWidth, maxStrLen, maxCollDepth}
   */
  public static int[] getLimits(String key) {
    InstrumentationConfiguration config = configurations.get(key);
    if (config == null) {
      return new int[] {3, 20, 20, 255, 3}; // defaults: depth, fields, collWidth, strLen, collDepth
    }
    CaptureConfiguration cc = config.getCaptureConfig();
    return new int[] {
      cc.getMaxObjectDepth(),
      cc.getMaxFieldsPerObject(),
      cc.getMaxCollectionWidth(),
      cc.getMaxStringLength(),
      cc.getMaxCollectionDepth()
    };
  }

  /**
   * Get runtime state for an instrumentation.
   *
   * @param key Instrumentation key
   * @return State, or null if not found
   */
  public static InstrumentationState getState(String key) {
    return states.get(key);
  }

  /**
   * Get all runtime states for status reporting.
   *
   * @return Copy of states map
   */
  public static Map<String, InstrumentationState> getAllStates() {
    return new HashMap<>(states);
  }

  /**
   * Register resolved parameter names for a method key. Called at instrumentation time after
   * resolving names from bytecode via ByteBuddy's MethodDescription.
   *
   * @param methodKey Unique method key (codeUnit.className.methodName)
   * @param names Array of parameter names, index i = name for parameter i. Empty string means
   *     unnamed (use "argN" fallback).
   */
  public static void registerParameterNames(String methodKey, String[] names) {
    parameterNames.put(methodKey, names);
  }

  /**
   * Get resolved parameter names for a method key. Called from MethodCaptureAdvice (inlined
   * bytecode) to use real names instead of "arg0", "arg1".
   *
   * @param methodKey Unique method key
   * @return String[] of parameter names, or null if no debug info was available
   */
  public static String[] getParameterNames(String methodKey) {
    return parameterNames.get(methodKey);
  }

  /**
   * Register parameter names from a serialized comma-delimited string. Used for cross-classloader
   * registration via reflection. Empty segments mean unnamed: "orderId,,quantity" means param 0 =
   * "orderId", param 1 = unnamed, param 2 = "quantity".
   *
   * @param methodKey Unique method key
   * @param serializedNames Comma-delimited parameter names
   */
  public static void registerParameterNamesFromPrimitives(
      String methodKey, String serializedNames) {
    try {
      if (serializedNames == null) {
        return;
      }
      String[] names = serializedNames.split(",", -1);
      parameterNames.put(methodKey, names);
    } catch (Exception e) {
      logger.fine("Failed to register parameter names via primitives: " + e.getMessage());
    }
  }
}
