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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.config.ServiceEventsConfig;

/**
 * OpenTelemetry instrumentation module for ServiceEvents.
 *
 * <p>This module provides bytecode instrumentation for deep function-level observability in Java
 * applications. All data recording goes through {@code ServiceEventsDataStore} which is loaded in
 * the bootstrap classloader for cross-classloader visibility.
 */
public class ServiceEventsInstrumentationModule extends InstrumentationModule {

  // Lazy logger: this class is loaded during agent init, before the OTel agent's
  // InternalLogger is initialized. A static final Logger would capture a NoopLogger
  // permanently. Deferring getLogger() to first use ensures we get a real logger.
  private static volatile Logger logger;

  private static Logger logger() {
    Logger local = logger;
    if (local == null) {
      local = Logger.getLogger(ServiceEventsInstrumentationModule.class.getName());
      logger = local;
    }
    return local;
  }

  // Flag to track if initialization has completed
  private static volatile boolean initialized = false;

  static {
    // Initialize ServiceEventsInstrumentation once at startup.
    try {
      ServiceEventsInstrumentation.getInstance().initialize();
      initialized = true;
      logger().info("[SERVICE_EVENTS] ServiceEventsInstrumentation initialized at startup");
    } catch (Exception e) {
      logger()
          .log(
              Level.WARNING,
              "[SERVICE_EVENTS] Failed to initialize ServiceEventsInstrumentation: "
                  + e.getMessage(),
              e);
    }
  }

  /** Check if ServiceEvents has been initialized. */
  public static boolean isInitialized() {
    return initialized;
  }

  public ServiceEventsInstrumentationModule() {
    super("serviceevents", "serviceevents-servlet");
    logger().info("[SERVICE_EVENTS] ServiceEventsInstrumentationModule constructor called");
  }

  @Override
  public int order() {
    return 1000;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    logger().info("[SERVICE_EVENTS] typeInstrumentations() called");
    List<TypeInstrumentation> instrumentations = new java.util.ArrayList<>();
    instrumentations.add(new ServletTypeInstrumentation());
    instrumentations.add(new MethodTypeInstrumentation());

    return instrumentations;
  }

  @Override
  public boolean isHelperClass(String className) {
    return className.startsWith(
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.");
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    // Helper classes injected into the application classloader.
    // NOTE: ServiceEventsDataStore is NOT listed here — it's in the bootstrap classloader.
    // These helpers are only needed for classes that the advice code references
    // that are NOT in bootstrap.
    return Arrays.asList(
        // Advice classes
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.ServiceEventsInstrumentationModule$ServletAdvice",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.ServiceEventsInstrumentationModule$MethodAdvice",
        // Main instrumentation class
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.ServiceEventsInstrumentation",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.config.ServiceEventsConfig",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.config.ServiceEventsConfig$Builder",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsCloudWatchLogFileExporter",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsCloudWatchMetricFileExporter",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.ServiceEventsFileWriter",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter.FunctionMetricsBridgeImpl",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.BaseCollector",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.FunctionCallCollector",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.EndpointCollector",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.EndpointCollector$EndpointAggregation",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.EndpointCollector$ErrorInfo",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.instrumentation.ServletInstrumentation",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.instrumentation.ServletInstrumentation$RequestContext",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.CloudWatchMetadata",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.CloudWatchMetadata$CloudWatchMetricDefinition",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.CloudWatchMetadata$CloudWatchMetricSet",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.DurationMetrics",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.EndpointMetricEvent",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.EndpointMetricEvent$Builder",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.FunctionCallMetrics",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models.FunctionCallMetrics$Builder",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.EndpointIdGenerator",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.SehHistogram",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.SehHistogram$Statistics",
        "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.SehHistogram$HistogramData");
  }

  /** Servlet instrumentation that hooks into HttpServlet.service(). */
  public static class ServletTypeInstrumentation implements TypeInstrumentation {

    private static final ServiceEventsConfig CONFIG = ServiceEventsConfig.fromEnv();

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      // Master kill switch: when OTEL_AWS_SERVICE_EVENTS_ENABLED is false, disable all servlet
      // instrumentation. This ensures zero ServiceEvents overhead on the request path.
      if (!CONFIG.isEnabled()) {
        return ElementMatchers.none();
      }

      // Use named() instead of hasSuperType() to instrument ONLY the base servlet class.
      // This prevents duplicate advice execution when multiple classes in the servlet
      // hierarchy (HttpServlet -> FrameworkServlet -> DispatcherServlet) all match.
      return named("javax.servlet.http.HttpServlet")
          .or(named("jakarta.servlet.http.HttpServlet"))
          .or(named("com.amazon.coral.bobcat.CoralHttpServlet"))
          .or(named("com.amazon.coral.bobcat.CoralAsyncHttpServlet"));
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdviceToMethod(
          isMethod()
              .and(isPublic().or(isProtected()))
              .and(named("service"))
              .and(takesArgument(0, hasSuperType(named("javax.servlet.http.HttpServletRequest"))))
              .and(takesArgument(1, hasSuperType(named("javax.servlet.http.HttpServletResponse")))),
          ServletAdvice.class.getName());

      transformer.applyAdviceToMethod(
          isMethod()
              .and(isPublic().or(isProtected()))
              .and(named("service"))
              .and(takesArgument(0, hasSuperType(named("jakarta.servlet.http.HttpServletRequest"))))
              .and(
                  takesArgument(
                      1, hasSuperType(named("jakarta.servlet.http.HttpServletResponse")))),
          ServletAdvice.class.getName());
    }
  }

  /** ByteBuddy advice for servlet service method. Calls ServiceEventsDataStore directly. */
  @SuppressWarnings("unused")
  public static class ServletAdvice {

    private static volatile Logger adviceLogger;

    public static Logger adviceLogger() {
      if (adviceLogger == null) {
        adviceLogger = Logger.getLogger(ServletAdvice.class.getName());
      }
      return adviceLogger;
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object onEnter(
        @Advice.Argument(0) Object request, @Advice.Argument(1) Object response) {
      try {
        String method;
        String requestUri;
        String servletPath;

        // Use reflection to support both javax.servlet and jakarta.servlet.
        // Call setAccessible(true) to avoid IllegalAccessException when the request
        // object is a Tomcat internal wrapper class (e.g. ApplicationHttpRequest).
        java.lang.reflect.Method getMethodM = request.getClass().getMethod("getMethod");
        getMethodM.setAccessible(true);
        method = (String) getMethodM.invoke(request);
        java.lang.reflect.Method getRequestUriM = request.getClass().getMethod("getRequestURI");
        getRequestUriM.setAccessible(true);
        requestUri = (String) getRequestUriM.invoke(request);
        java.lang.reflect.Method getServletPathM = request.getClass().getMethod("getServletPath");
        getServletPathM.setAccessible(true);
        servletPath = (String) getServletPathM.invoke(request);

        String route = servletPath != null && !servletPath.isEmpty() ? servletPath : requestUri;

        // Set context in bootstrap data store (visible to all classloaders)
        software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore.setCurrentOperation(
            method + " " + route);
        // Idempotent: returns false on nested servlet dispatch (e.g., Spring's /error forward
        // on the same thread). Cleanup happens in ServiceEventsSpanProcessor.onEnd after the
        // incident
        // has been emitted.
        software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore.beginInvestigation();

        // Capture both clocks for nanosecond-precision epoch timestamps:
        // - epochStartTimeNs: anchors the request to wall-clock time (ms precision)
        // - monoStartNs: monotonic nanoTime for precise duration/end-time calculation
        long epochStartTimeNs = System.currentTimeMillis() * 1_000_000L;
        long monoStartNs = System.nanoTime();

        return new software.amazon.opentelemetry.javaagent.instrumentation.serviceevents
            .instrumentation.ServletInstrumentation.RequestContext(
            method, route, monoStartNs, epochStartTimeNs);
      } catch (Throwable e) {
        adviceLogger().log(Level.WARNING, "Exception in onEnter: " + e.getMessage(), e);
        return null;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) Object request,
        @Advice.Argument(1) Object response,
        @Advice.Enter Object context,
        @Advice.Thrown Throwable throwable) {
      if (context == null) {
        return;
      }

      // Endpoint + incident recording lives in ServiceEventsSpanProcessor.onEnd(). This advice
      // only manages request-scoped bootstrap state (thread-local cleanup).
      try {
        software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore.clearCurrentOperation();
        software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore.clearCallStack();

        // NOTE: investigationData is NOT cleared here. For Spring, ServletAdvice.onExit
        // runs BEFORE ServiceEventsSpanProcessor.onEnd, and onEnd still needs to peek the call
        // path to build the incident snapshot. Cleanup happens inside ServiceEventsSpanProcessor
        // after recordPotentialIncident has consumed the data.
      } catch (Throwable e) {
        // Never propagate telemetry failures into the request path.
      }
    }
  }

  /** Method instrumentation for function-level metrics. */
  public static class MethodTypeInstrumentation implements TypeInstrumentation {

    private static volatile Logger methodLogger;

    private static Logger methodLogger() {
      if (methodLogger == null) {
        methodLogger = Logger.getLogger(MethodTypeInstrumentation.class.getName());
      }
      return methodLogger;
    }

    private static final ServiceEventsConfig CONFIG = ServiceEventsConfig.fromEnv();

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      // Requires both master flag and bytecode flag to be enabled.
      // OTEL_AWS_SERVICE_EVENTS_ENABLED is the master kill switch for all ServiceEvents features.
      // OTEL_AWS_SERVICE_EVENTS_FUNCTION_INSTRUMENT_ENABLED controls method-level instrumentation
      // specifically.
      if (!CONFIG.isEnabled() || !CONFIG.isBytecodeEnabled()) {
        return ElementMatchers.none();
      }

      List<String> packagesInclude = CONFIG.getPackagesInclude();
      List<String> packagesExclude = CONFIG.getPackagesExclude();

      ElementMatcher.Junction<TypeDescription> matcher =
          buildScopeMatcher(packagesInclude, packagesExclude);
      methodLogger()
          .info(
              "ServiceEvents bytecode scope: PACKAGES_INCLUDE="
                  + packagesInclude
                  + " PACKAGES_EXCLUDE="
                  + packagesExclude);
      return matcher;
    }

    /**
     * SDK self-exclusion matcher — the non-configurable safety boundary, always subtracted from the
     * include scope (even when the user's {@code PACKAGES_INCLUDE} explicitly names one of these
     * prefixes). Built from {@link ServiceEventsConfig#SDK_SELF_EXCLUDE}, which enumerates the OTel
     * runtime and the real ADOT module roots. Instrumenting any of these would cause classloader
     * cycles or infinite recursion in the tracing pipeline.
     */
    private static final ElementMatcher.Junction<TypeDescription> SDK_SELF_EXCLUDE_MATCHER =
        buildPrefixMatcher(ServiceEventsConfig.SDK_SELF_EXCLUDE);

    /**
     * Build the scope matcher.
     *
     * <p>Rule (evaluated per class, highest-priority first):
     *
     * <ol>
     *   <li>Class matches {@link ServiceEventsConfig#SDK_SELF_EXCLUDE} → drop (non-configurable)
     *   <li>{@code PACKAGES_INCLUDE} empty → drop (no implicit default scope)
     *   <li>Class matches any {@code PACKAGES_EXCLUDE} entry → drop
     *   <li>Class matches any {@code PACKAGES_INCLUDE} entry → include
     *   <li>Otherwise → drop
     * </ol>
     *
     * <p>The empty-include early-return below lands BEFORE the include branch, so {@code
     * userWhitelist} is never null when {@code userWhitelist.and(not(selfExclude))} runs (which
     * would NPE). {@code SDK_SELF_EXCLUDE} is always subtracted, even when the include explicitly
     * names those prefixes.
     */
    // package-private static for unit testing (no instance state used). Evaluated against
    // synthetic TypeDescriptions in ServiceEventsInstrumentationModuleTest.
    static ElementMatcher.Junction<TypeDescription> buildScopeMatcher(
        List<String> packagesInclude, List<String> packagesExclude) {

      ElementMatcher.Junction<TypeDescription> userWhitelist = buildPrefixMatcher(packagesInclude);

      // Rules 1/2: empty PACKAGES_INCLUDE (or all entries normalized away) → instrument nothing.
      // Must return before the include branch so userWhitelist is guaranteed non-null below.
      if (userWhitelist == null) {
        return ElementMatchers.none();
      }

      // Rule 4 (with rules 0 + 2 subtracted): include scope, minus SDK self-exclude, minus
      // user exclude. SDK_SELF_EXCLUDE is non-configurable and always wins.
      ElementMatcher.Junction<TypeDescription> matcher = userWhitelist;
      if (SDK_SELF_EXCLUDE_MATCHER != null) {
        matcher = matcher.and(not(SDK_SELF_EXCLUDE_MATCHER));
      }
      ElementMatcher.Junction<TypeDescription> userExclude = buildPrefixMatcher(packagesExclude);
      if (userExclude != null) {
        matcher = matcher.and(not(userExclude));
      }
      return matcher;
    }

    /**
     * Turn a list of user-facing patterns into an OR-matcher over {@code nameStartsWith(prefix)}.
     * Returns {@code null} when the list is empty OR every entry normalizes away (e.g. {@code
     * ["*"]}), so callers can treat "no usable whitelist" uniformly.
     *
     * <p>Prefix normalization: {@code foo.bar.*} and {@code foo.bar} both become {@code
     * nameStartsWith("foo.bar.")} — the trailing {@code .} is preserved so {@code foo.bar} does NOT
     * match {@code foo.barxyz.*}.
     */
    private static ElementMatcher.Junction<TypeDescription> buildPrefixMatcher(
        List<String> patterns) {
      if (patterns == null || patterns.isEmpty()) {
        return null;
      }
      ElementMatcher.Junction<TypeDescription> matcher = null;
      for (String pattern : patterns) {
        String prefix = normalizePrefix(pattern);
        if (prefix == null) {
          continue;
        }
        ElementMatcher.Junction<TypeDescription> entry = nameStartsWith(prefix);
        matcher = matcher == null ? entry : matcher.or(entry);
      }
      return matcher;
    }

    /**
     * Normalize a user pattern to a dotted prefix suitable for {@code nameStartsWith}. Returns
     * {@code null} when the input is empty or a bare {@code *} sentinel (rejected as ambiguous by
     * {@code ServiceEventsConfig.normalizePatterns}, but callers may also arrive via Builder).
     *
     * <p>Strips trailing {@code *} and {@code .} characters in a loop so multi-glob inputs like
     * {@code com.**} or {@code com.myapp.*.*} collapse to a sane prefix rather than producing an
     * invalid {@code nameStartsWith} target.
     */
    private static String normalizePrefix(String pattern) {
      if (pattern == null) {
        return null;
      }
      String trimmed = pattern.trim();
      if (trimmed.isEmpty() || trimmed.equals("*")) {
        return null;
      }
      // Strip trailing '*' and '.' chars so "com.**" / "com.myapp.*.*" / "com.myapp." all
      // normalize to a clean dotted prefix.
      int end = trimmed.length();
      while (end > 0) {
        char c = trimmed.charAt(end - 1);
        if (c == '*' || c == '.') {
          end--;
        } else {
          break;
        }
      }
      if (end == 0) {
        return null;
      }
      String prefix = trimmed.substring(0, end);
      if (!prefix.endsWith(".")) {
        prefix = prefix + ".";
      }
      return prefix;
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdviceToMethod(
          isMethod()
              .and(isPublic())
              .and(not(named("toString")))
              .and(not(named("hashCode")))
              .and(not(named("equals")))
              .and(not(named("getClass"))),
          MethodAdvice.class.getName());
    }
  }

  /**
   * ByteBuddy advice for method-level instrumentation. Calls ServiceEventsDataStore directly.
   *
   * <p>Optimized (C4): returns context Object directly (null when not sampled) instead of wrapping
   * in Object[]. Uses @Advice.Origin in both enter and exit — no need to pass functionId through
   * the array. Eliminates one Object[] allocation per method call.
   */
  @SuppressWarnings("unused")
  public static class MethodAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object onEnter(@Advice.Origin("#t.#m") String functionId) {
      // Returns long[] context or null — no wrapper Object[] allocation
      return software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore.methodEnter(
          functionId);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Origin("#t.#m") String functionId,
        @Advice.Enter Object context,
        @Advice.Thrown Throwable throwable) {

      String exceptionType = null;
      if (throwable != null) {
        exceptionType = throwable.getClass().getSimpleName();
      }

      // context is null when not sampled — methodExit handles null check
      software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore.methodExit(
          functionId, context, exceptionType);

      // Record exception for investigation data
      if (throwable != null) {
        java.io.StringWriter sw = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(sw));
        software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore.recordException(
            exceptionType, throwable.getMessage(), sw.toString());
      }
    }
  }
}
