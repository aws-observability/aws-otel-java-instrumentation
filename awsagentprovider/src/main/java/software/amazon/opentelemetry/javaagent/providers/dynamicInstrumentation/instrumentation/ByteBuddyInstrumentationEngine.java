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

import static net.bytebuddy.matcher.ElementMatchers.named;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.advice.MethodCaptureAdvice;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;

/**
 * Applies dynamic instrumentation to target methods using ByteBuddy.
 *
 * <p>Uses a single transformer pattern: one ClassFileTransformer handles all active
 * instrumentations. This approach minimizes JVM retransformation overhead by batching multiple
 * configuration changes into a single retransformation pass per class.
 *
 * <p>Rationale for single transformer:
 *
 * <ul>
 *   <li>Java bytecode transformation operates at class level, not method level
 *   <li>Each retransformation invalidates JIT optimizations and requires recompilation
 *   <li>Single transformer: N configs on same class = 1 retransformation
 *   <li>Multiple transformers: N configs = N retransformations (wasteful)
 *   <li>Enables coordination between different instrumentation types (method + line-level)
 * </ul>
 *
 * <p>Currently handles method-level instrumentation via ByteBuddy Advice:
 *
 * <ul>
 *   <li>MethodInstrumentationAdvice: Creates INTERNAL spans around methods
 *   <li>MethodCaptureAdvice: Captures method arguments and return values
 * </ul>
 *
 * <p>Line-level instrumentation (ASM-based) will be added in future phase.
 */
public class ByteBuddyInstrumentationEngine {
  private static final Logger logger =
      Logger.getLogger(ByteBuddyInstrumentationEngine.class.getName());

  private final Instrumentation instrumentation;
  private ClassFileTransformer currentTransformer; // Single ByteBuddy transformer
  private final java.util.Map<String, ClassFileTransformer> lineTransformers =
      new ConcurrentHashMap<>(); // ASM transformers for line-level instrumentation (one per class)
  private final Set<ClassLoader> injectedClassLoaders = ConcurrentHashMap.newKeySet();
  private Set<String> previouslyInstrumentedClasses = new HashSet<>(); // Track for removal

  public ByteBuddyInstrumentationEngine(Instrumentation instrumentation) {
    this.instrumentation = instrumentation;
    logger.fine("ByteBuddyInstrumentationEngine initialized with single transformer pattern");
  }

  /**
   * Rebuilds the single transformer with all current configurations and applies to affected
   * classes.
   *
   * <p>This method should be called whenever configurations change (additions or removals). It:
   *
   * <ol>
   *   <li>Removes the old transformer
   *   <li>Creates a new transformer that knows about ALL active configurations
   *   <li>Identifies which classes are affected
   *   <li>Retransforms all affected classes in one batch
   * </ol>
   *
   * <p>Uses function-level grouping: Configurations are grouped by class.method, and the method
   * wrapper (INTERNAL span) is applied conditionally only when needed.
   *
   * <p>Batching ensures optimal performance: Adding 10 configs to 3 classes triggers only 3
   * retransformations (not 10).
   */
  public void rebuildAndApplyTransformer() {
    logger.fine("Rebuilding single transformer with all active configurations");

    // Step 1: Remove old transformer
    if (currentTransformer != null) {
      instrumentation.removeTransformer(currentTransformer);
      logger.fine("Removed previous transformer");
    }

    // Step 2: Get all active configs and previously instrumented classes
    List<InstrumentationConfiguration> allConfigs = InstrumentationRegistry.getAllConfigurations();
    Set<String> currentInstrumentedClasses = InstrumentationRegistry.getAllInstrumentedClasses();

    // Determine classes that need retransformation (current OR previously instrumented)
    Set<String> classesToCheck = new HashSet<>(currentInstrumentedClasses);
    classesToCheck.addAll(previouslyInstrumentedClasses);

    if (allConfigs.isEmpty()) {
      logger.fine(
          "No active configurations, will remove instrumentation from previously instrumented classes");
      currentTransformer = null;

      // Retransform previously instrumented classes to remove instrumentation
      if (!previouslyInstrumentedClasses.isEmpty()) {
        Set<Class<?>> classesToRetransform = findLoadedClasses(previouslyInstrumentedClasses);
        if (!classesToRetransform.isEmpty()) {
          logger.log(
              Level.FINE,
              "Retransforming {0} classes to remove instrumentation",
              classesToRetransform.size());
          retransformClasses(new ArrayList<>(classesToRetransform));
        }
        previouslyInstrumentedClasses.clear();
      }
      return;
    }

    logger.log(Level.FINE, "Creating transformer for {0} active configurations", allConfigs.size());

    // Step 3: Create ByteBuddy transformer for method-level instrumentations
    currentTransformer = createSingleTransformer();

    // Step 4: Create ASM transformers for line-level instrumentations
    registerLineTransformers();

    // Step 5: Find affected classes (both current and previously instrumented)
    Set<Class<?>> classesToRetransform = findLoadedClasses(classesToCheck);

    // Step 6: Retransform all affected classes in one batch
    if (!classesToRetransform.isEmpty()) {
      logger.log(Level.FINE, "Retransforming {0} affected classes", classesToRetransform.size());
      retransformClasses(new ArrayList<>(classesToRetransform));
    } else {
      logger.fine("No loaded classes to retransform (will apply when classes load)");
    }

    // Update tracking
    previouslyInstrumentedClasses = currentInstrumentedClasses;
  }

  /**
   * Registers ASM-based line-level transformers for classes that have line-level instrumentations.
   *
   * <p>Creates one LineInstrumentationTransformer per class that contains line-level configs. These
   * transformers are registered separately from the ByteBuddy transformer and execute in sequence.
   */
  private void registerLineTransformers() {
    // Remove old line transformers
    for (ClassFileTransformer transformer : lineTransformers.values()) {
      instrumentation.removeTransformer(transformer);
    }
    lineTransformers.clear();

    // Get all classes with line-level instrumentations
    java.util.Map<String, List<InstrumentationConfiguration>> classToLineConfigs =
        new java.util.HashMap<>();

    for (InstrumentationConfiguration config : InstrumentationRegistry.getAllConfigurations()) {
      if (config.isLineLevel()) {
        classToLineConfigs
            .computeIfAbsent(config.getFullyQualifiedClassName(), k -> new ArrayList<>())
            .add(config);
      }
    }

    if (classToLineConfigs.isEmpty()) {
      logger.fine("No line-level instrumentations to register");
      return;
    }

    logger.log(
        Level.FINE, "Registering line transformers for {0} classes", classToLineConfigs.size());

    // Create and register one transformer per class
    for (java.util.Map.Entry<String, List<InstrumentationConfiguration>> entry :
        classToLineConfigs.entrySet()) {
      String className = entry.getKey();
      List<InstrumentationConfiguration> lineConfigs = entry.getValue();

      logger.log(
          Level.FINE,
          "Creating line transformer for {0} with {1} line-level configs",
          new Object[] {className, lineConfigs.size()});

      ClassFileTransformer lineTransformer =
          new LineInstrumentationTransformer(className, lineConfigs);

      // Register with instrumentation (retransform capable)
      instrumentation.addTransformer(lineTransformer, true);
      lineTransformers.put(className, lineTransformer);
    }

    logger.log(Level.FINE, "Registered {0} line-level transformers", lineTransformers.size());
  }

  /**
   * Creates a single transformer that handles all active instrumentations.
   *
   * <p>Uses function-level grouping from InstrumentationRegistry.getFunctionSets():
   *
   * <ul>
   *   <li>Groups instrumentations by class.method (function level)
   *   <li>Handles line-level instrumentations via ASM transformer
   * </ul>
   *
   * <p>The transformer checks each class being loaded/retransformed against the registry to
   * determine which instrumentations to apply.
   */
  private ClassFileTransformer createSingleTransformer() {
    return new AgentBuilder.Default()
        .disableClassFormatChanges()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
        .with(
            new AgentBuilder.Listener.Adapter() {
              @Override
              public void onTransformation(
                  TypeDescription typeDescription,
                  ClassLoader classLoader,
                  JavaModule module,
                  boolean loaded,
                  DynamicType dynamicType) {
                logger.log(Level.FINE, "Transformed class: {0}", typeDescription.getName());
              }

              @Override
              public void onError(
                  String typeName,
                  ClassLoader classLoader,
                  JavaModule module,
                  boolean loaded,
                  Throwable throwable) {
                logger.log(Level.SEVERE, "Error transforming class: " + typeName, throwable);
              }
            })
        .type(new TypeMatcher()) // Only match classes with instrumentations
        .transform(
            new AgentBuilder.Transformer() {
              @Override
              public DynamicType.Builder<?> transform(
                  DynamicType.Builder<?> builder,
                  TypeDescription typeDescription,
                  ClassLoader classLoader,
                  JavaModule module,
                  ProtectionDomain protectionDomain) {

                String className = typeDescription.getName();

                // Get function sets (instrumentations grouped by method)
                Map<String, FunctionInstrumentationSet> functionSetsMap =
                    InstrumentationRegistry.getFunctionSets(className);

                if (functionSetsMap.isEmpty()) {
                  return builder;
                }

                List<FunctionInstrumentationSet> functionSets =
                    new ArrayList<>(functionSetsMap.values());

                logger.log(
                    Level.FINE,
                    "Applying {0} function sets to class: {1}",
                    new Object[] {functionSets.size(), className});

                // Inject Advice classes into target classloader (once per classloader)
                if (!injectAdviceClasses(classLoader)) {
                  logger.log(
                      Level.SEVERE,
                      "Skipping instrumentation for {0}: Advice class injection failed",
                      className);
                  return builder;
                }

                // Populate injected registry with ALL configurations (line-level + method-level)
                // This ensures MethodCaptureAdvice can find method-level configs for return value
                // capture
                List<InstrumentationConfiguration> allConfigs =
                    InstrumentationRegistry.getConfigsForClass(className);
                if (!allConfigs.isEmpty()) {
                  populateInjectedRegistry(classLoader, allConfigs);
                }

                // Apply instrumentation for each function set
                for (FunctionInstrumentationSet functionSet : functionSets) {
                  String methodName = functionSet.getMethodName();

                  // Apply data capture (arguments/return) if any config needs it
                  boolean needsCapture = functionSet.needsDataCapture();
                  logger.log(
                      Level.FINE,
                      "Function {0}.{1} needsDataCapture: {2}",
                      new Object[] {className, methodName, needsCapture});
                  if (needsCapture) {
                    // Resolve real parameter names from bytecode for argument naming
                    InstrumentationConfiguration methodConfig = functionSet.getMethodSpanConfig();
                    if (methodConfig != null) {
                      resolveAndRegisterParameterNames(
                          typeDescription,
                          classLoader,
                          className,
                          methodName,
                          methodConfig.getMethodKey());
                    }
                    logger.log(
                        Level.FINE,
                        "Applying data capture to: {0}.{1}",
                        new Object[] {className, methodName});
                    builder =
                        builder.visit(Advice.to(MethodCaptureAdvice.class).on(named(methodName)));
                  }

                  // Note: Line-level instrumentations are handled by ASM transformer
                  // (see LineInstrumentationTransformer, added separately to instrumentation)
                }

                return builder;
              }
            })
        .installOn(instrumentation);
  }

  /**
   * Finds loaded Class objects for given class names.
   *
   * @param classNames Set of fully qualified class names
   * @return Set of loaded Class objects
   */
  private Set<Class<?>> findLoadedClasses(Set<String> classNames) {
    Set<Class<?>> loadedClasses = new HashSet<>();

    for (String className : classNames) {
      Class<?> loadedClass = findLoadedClass(className);
      if (loadedClass != null) {
        loadedClasses.add(loadedClass);
      }
    }

    return loadedClasses;
  }

  /**
   * Retransforms a list of classes, handling errors gracefully.
   *
   * @param classes List of classes to retransform
   */
  private void retransformClasses(List<Class<?>> classes) {
    for (Class<?> clazz : classes) {
      try {
        if (!instrumentation.isModifiableClass(clazz)) {
          logger.log(Level.WARNING, "Class is not modifiable: {0}", clazz.getName());
          continue;
        }

        logger.log(Level.FINE, "Retransforming class: {0}", clazz.getName());
        instrumentation.retransformClasses(clazz);
        logger.log(Level.FINE, "Successfully retransformed: {0}", clazz.getName());

      } catch (Throwable e) {
        // Catch Throwable: VerifyError/LinkageError during retransformation should not
        // prevent other classes from being retransformed.
        logger.log(Level.SEVERE, "Failed to retransform class: " + clazz.getName(), e);
      }
    }
  }

  /** Get the number of active instrumentations. */
  public int getActiveInstrumentationCount() {
    return InstrumentationRegistry.size();
  }

  /** Get classloaders that have been injected with Advice classes. */
  public Set<ClassLoader> getInjectedClassLoaders() {
    return new HashSet<>(injectedClassLoaders);
  }

  /**
   * Clear all instrumentations and remove transformers.
   *
   * <p>Note: Does not retransform classes to remove instrumentation. Classes will retain
   * instrumentation until they are naturally reloaded or application restarts.
   */
  public void clearAll() {
    logger.fine("Clearing all instrumentations");

    if (currentTransformer != null) {
      instrumentation.removeTransformer(currentTransformer);
      currentTransformer = null;
    }

    // Remove line transformers
    for (ClassFileTransformer transformer : lineTransformers.values()) {
      instrumentation.removeTransformer(transformer);
    }
    lineTransformers.clear();

    InstrumentationRegistry.clearAll();
    injectedClassLoaders.clear();
  }

  /**
   * Find a loaded class by name.
   *
   * @param className Fully qualified class name
   * @return The Class object if loaded, null otherwise
   */
  private Class<?> findLoadedClass(String className) {
    for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
      if (clazz.getName().equals(className)) {
        return clazz;
      }
    }
    return null;
  }

  /**
   * Injects Advice classes and their dependencies into target classloader. This solves the
   * classloader visibility issue where inlined Advice code needs to reference classes that aren't
   * visible to the application classloader.
   *
   * @param targetClassLoader The classloader to inject into
   * @return true if injection succeeded (or was already done), false on failure
   */
  private boolean injectAdviceClasses(ClassLoader targetClassLoader) {
    if (targetClassLoader == null) {
      // Bootstrap classloader - classes already visible via appendToBootstrapClassLoaderSearch
      logger.fine("Target is bootstrap classloader, skipping injection");
      return true;
    }

    // Check if already injected
    if (injectedClassLoaders.contains(targetClassLoader)) {
      logger.log(
          Level.FINE,
          "Advice classes already injected into: {0}",
          targetClassLoader.getClass().getName());
      return true;
    }

    try {
      logger.log(
          Level.FINE,
          "Injecting Advice classes into classloader: {0}",
          targetClassLoader.getClass().getName());

      // Get bytecode locator
      ClassFileLocator locator =
          ClassFileLocator.ForClassLoader.of(MethodCaptureAdvice.class.getClassLoader());

      // Inject Advice classes
      injectClass(
          targetClassLoader,
          MethodCaptureAdvice.class.getName(),
          locator.locate(MethodCaptureAdvice.class.getName()).resolve());
      injectClass(
          targetClassLoader,
          MethodCaptureAdvice.class.getName() + "$EntryData",
          locator.locate(MethodCaptureAdvice.class.getName() + "$EntryData").resolve());
      injectClass(
          targetClassLoader,
          "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.advice.LineCaptureAdvice",
          locator
              .locate(
                  "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.advice.LineCaptureAdvice")
              .resolve());
      // Inject dependencies
      injectClass(
          targetClassLoader,
          InstrumentationRegistry.class.getName(),
          locator.locate(InstrumentationRegistry.class.getName()).resolve());
      injectClass(
          targetClassLoader,
          "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration",
          locator
              .locate(
                  "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration")
              .resolve());
      injectClass(
          targetClassLoader,
          "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureConfiguration",
          locator
              .locate(
                  "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureConfiguration")
              .resolve());
      injectClass(
          targetClassLoader,
          "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureConfiguration$Builder",
          locator
              .locate(
                  "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureConfiguration$Builder")
              .resolve());
      injectClass(
          targetClassLoader,
          "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationType",
          locator
              .locate(
                  "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationType")
              .resolve());
      injectClass(
          targetClassLoader,
          "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationState",
          locator
              .locate(
                  "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationState")
              .resolve());
      injectClass(
          targetClassLoader,
          "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.DisableReason",
          locator
              .locate(
                  "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.DisableReason")
              .resolve());
      injectClass(
          targetClassLoader,
          "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureRateLimiter",
          locator
              .locate(
                  "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureRateLimiter")
              .resolve());

      // Mark as injected
      injectedClassLoaders.add(targetClassLoader);
      logger.log(
          Level.FINE,
          "Successfully injected Advice classes into: {0}",
          targetClassLoader.getClass().getName());
      return true;

    } catch (Throwable e) {
      // Catch Throwable: NoClassDefFoundError/LinkageError during class injection is common
      // in agent scenarios. Return false so caller skips instrumentation for this class.
      logger.log(Level.SEVERE, "Failed to inject Advice classes", e);
      return false;
    }
  }

  /**
   * Resolve parameter names from bytecode and register them in InstrumentationRegistry. Uses
   * ByteBuddy's MethodDescription to read names from the MethodParameters attribute (requires
   * compilation with {@code -parameters} flag) or from debug info.
   *
   * <p>If no parameters are named, nothing is registered and the advice falls back to "argN".
   */
  private void resolveAndRegisterParameterNames(
      net.bytebuddy.description.type.TypeDescription typeDescription,
      ClassLoader classLoader,
      String className,
      String methodName,
      String methodKey) {
    // Prefer reading parameter names from the class file bytes. When ByteBuddy retransforms an
    // already-loaded class it may hand us a TypeDescription.ForLoadedType, whose parameter names
    // are resolved via runtime reflection (Executable.getParameters().isNamePresent()). That
    // reflection does not reliably expose the MethodParameters attribute across JVMs/class loaders,
    // so it intermittently reports parameters as unnamed and the advice falls back to "argN". The
    // class file bytes always carry the MethodParameters attribute (compilation with -parameters),
    // so a byte-based TypePool resolution is deterministic. We fall back to the supplied
    // TypeDescription only if the byte-based lookup is unavailable.
    String[] names = readParameterNamesFromBytes(classLoader, className, methodName);
    if (names == null) {
      names = readParameterNamesFromTypeDescription(typeDescription, methodName);
    }

    if (names != null) {
      InstrumentationRegistry.registerParameterNames(methodKey, names);
      software.amazon.opentelemetry.javaagent.bootstrap.di.DIDataStore.registerParameterNames(
          methodKey, names);
      logger.log(
          Level.FINE,
          "Resolved parameter names for {0}: {1}",
          new Object[] {methodKey, java.util.Arrays.toString(names)});
    } else {
      logger.log(
          Level.FINE,
          "No parameter names available for {0}; advice will fall back to argN",
          methodKey);
    }
  }

  /**
   * Reads parameter names for {@code methodName} from {@code className}'s class file bytes using a
   * ByteBuddy {@link net.bytebuddy.pool.TypePool} in EXTENDED reader mode (which parses the
   * MethodParameters attribute). Returns the names array if at least one parameter is named, or
   * {@code null} if the class/method/names could not be resolved from bytes.
   */
  private String[] readParameterNamesFromBytes(
      ClassLoader classLoader, String className, String methodName) {
    try {
      ClassFileLocator locator =
          classLoader != null
              ? ClassFileLocator.ForClassLoader.of(classLoader)
              : ClassFileLocator.ForClassLoader.ofBootLoader();
      // EXTENDED reader mode parses the MethodParameters attribute from the class file bytes.
      net.bytebuddy.pool.TypePool pool =
          new net.bytebuddy.pool.TypePool.Default(
              net.bytebuddy.pool.TypePool.CacheProvider.NoOp.INSTANCE,
              locator,
              net.bytebuddy.pool.TypePool.Default.ReaderMode.EXTENDED);
      net.bytebuddy.pool.TypePool.Resolution resolution = pool.describe(className);
      if (!resolution.isResolved()) {
        return null;
      }
      return readParameterNamesFromTypeDescription(resolution.resolve(), methodName);
    } catch (Throwable t) {
      logger.log(
          Level.FINE,
          "Could not read parameter names from bytes for {0}.{1}: {2}",
          new Object[] {className, methodName, t.toString()});
      return null;
    }
  }

  /**
   * Extracts parameter names for {@code methodName} from a resolved {@link
   * net.bytebuddy.description.type.TypeDescription}. Returns the names array if at least one
   * parameter is named, otherwise {@code null}.
   */
  private static String[] readParameterNamesFromTypeDescription(
      net.bytebuddy.description.type.TypeDescription typeDescription, String methodName) {
    net.bytebuddy.description.method.MethodList<?> methods =
        typeDescription.getDeclaredMethods().filter(named(methodName));
    if (methods.isEmpty()) {
      return null;
    }

    // Use the first method found. Overloads share the same methodKey — a known limitation.
    net.bytebuddy.description.method.MethodDescription method = methods.get(0);
    net.bytebuddy.description.method.ParameterList<?> params = method.getParameters();
    if (params.isEmpty()) {
      return null;
    }

    String[] names = new String[params.size()];
    boolean anyNamed = false;
    for (int i = 0; i < params.size(); i++) {
      if (params.get(i).isNamed()) {
        names[i] = params.get(i).getName();
        anyNamed = true;
      } else {
        names[i] = "";
      }
    }
    return anyNamed ? names : null;
  }

  /**
   * Custom matcher that only matches classes with active instrumentations. This prevents our
   * transformer from interfering with other classes.
   */
  private static class TypeMatcher
      extends net.bytebuddy.matcher.ElementMatcher.Junction.AbstractBase<TypeDescription> {
    @Override
    public boolean matches(TypeDescription target) {
      String className = target.getName();
      List<InstrumentationConfiguration> configs =
          InstrumentationRegistry.getConfigsForClass(className);
      return !configs.isEmpty();
    }
  }

  /** Injects a single class into target classloader using reflection. */
  private void injectClass(ClassLoader targetClassLoader, String className, byte[] classBytes)
      throws Exception {
    // Check if already loaded
    try {
      targetClassLoader.loadClass(className);
      logger.log(Level.FINE, "Class already loaded: {0}", className);
      return;
    } catch (ClassNotFoundException e) {
      // Not loaded - proceed with injection
    }

    // Use reflection to call protected defineClass() method
    java.lang.reflect.Method defineClassMethod =
        ClassLoader.class.getDeclaredMethod(
            "defineClass", String.class, byte[].class, int.class, int.class);
    defineClassMethod.setAccessible(true);

    // Inject the class
    defineClassMethod.invoke(targetClassLoader, className, classBytes, 0, classBytes.length);
    logger.log(Level.FINE, "Injected class: {0}", className);
  }

  /**
   * Populates the InstrumentationRegistry in the target classloader via reflection. Uses primitive
   * parameters to avoid classloader type incompatibility.
   *
   * @param targetClassLoader The classloader containing the injected InstrumentationRegistry
   * @param configs List of line-level configurations to register
   */
  private void populateInjectedRegistry(
      ClassLoader targetClassLoader, List<InstrumentationConfiguration> configs) {
    if (targetClassLoader == null || configs.isEmpty()) {
      return;
    }

    try {
      // Load InstrumentationRegistry from target classloader
      Class<?> registryClass =
          targetClassLoader.loadClass(
              "software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.InstrumentationRegistry");

      // Get the registerFromPrimitives method
      java.lang.reflect.Method registerMethod =
          registryClass.getMethod(
              "registerFromPrimitives",
              String.class,
              String.class,
              String.class,
              String.class,
              String.class,
              int.class,
              String.class,
              String.class,
              boolean.class,
              boolean.class,
              String[].class,
              String[].class,
              int.class,
              int.class,
              int.class,
              int.class,
              int.class,
              int.class,
              int.class,
              int.class);

      // Get the registerParameterNamesFromPrimitives method for real argument naming
      java.lang.reflect.Method registerParamNamesMethod =
          registryClass.getMethod(
              "registerParameterNamesFromPrimitives", String.class, String.class);

      // Register each configuration
      for (InstrumentationConfiguration config : configs) {
        CaptureConfiguration captureConfig = config.getCaptureConfig();
        if (captureConfig == null) {
          captureConfig = CaptureConfiguration.builder().build(); // Use defaults
        }

        registerMethod.invoke(
            null, // static method
            config.getInstrumentationKey(),
            config.getLocationHash(),
            config.getCodeUnit(),
            config.getClassName(),
            config.getMethodName(),
            config.getLineNumber(),
            config.getFilePath(),
            config.getInstrumentationType().name(),
            captureConfig.isCaptureReturn(),
            captureConfig.isCaptureStackTrace(),
            captureConfig.getCaptureArguments() != null
                ? captureConfig.getCaptureArguments().toArray(new String[0])
                : null,
            captureConfig.getCaptureLocals() != null
                ? captureConfig.getCaptureLocals().toArray(new String[0])
                : null,
            captureConfig.getMaxStringLength(),
            captureConfig.getMaxCollectionWidth(),
            captureConfig.getMaxCollectionDepth(),
            captureConfig.getMaxStackFrames(),
            captureConfig.getMaxStackTraceSize(),
            captureConfig.getMaxObjectDepth(),
            captureConfig.getMaxFieldsPerObject(),
            config.getMaxHits());

        // Register resolved parameter names if available
        String[] paramNames = InstrumentationRegistry.getParameterNames(config.getMethodKey());
        if (paramNames != null) {
          registerParamNamesMethod.invoke(
              null, config.getMethodKey(), String.join(",", paramNames));
        }

        logger.log(
            Level.FINE, "Populated injected registry with: {0}", config.getInstrumentationKey());
      }

      logger.log(
          Level.FINE,
          "Successfully populated {0} configurations in target classloader registry",
          configs.size());

    } catch (Throwable e) {
      // Catch Throwable: NoClassDefFoundError is common when target classloader cannot
      // resolve DI model classes. Log and continue — instrumentation will work without
      // registry population (just won't have config metadata in app CL).
      logger.log(Level.SEVERE, "Failed to populate injected registry in target classloader", e);
    }
  }
}
