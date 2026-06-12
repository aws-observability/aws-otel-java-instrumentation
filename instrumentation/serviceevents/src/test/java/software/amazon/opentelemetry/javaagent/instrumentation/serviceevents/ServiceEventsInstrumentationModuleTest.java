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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.ServiceEventsInstrumentationModule.MethodTypeInstrumentation;

/**
 * Unit tests for {@link MethodTypeInstrumentation#buildScopeMatcher} — the bytecode scope rule.
 *
 * <p>Evaluates the real ByteBuddy {@link ElementMatcher} against synthetic {@link TypeDescription}s
 * (built by FQCN via {@link TypeDescription.Latent}) so the matcher's {@code nameStartsWith}
 * behavior is exercised end-to-end. The rule (highest priority first):
 *
 * <ol>
 *   <li>Matches SDK_SELF_EXCLUDE → drop (non-configurable)
 *   <li>PACKAGES_INCLUDE empty → drop (no implicit default scope)
 *   <li>Matches PACKAGES_EXCLUDE → drop
 *   <li>Matches PACKAGES_INCLUDE → instrument
 *   <li>Otherwise → drop
 * </ol>
 */
class ServiceEventsInstrumentationModuleTest {

  /** Build a TypeDescription with an arbitrary fully-qualified class name. */
  private static TypeDescription type(String fqcn) {
    return new TypeDescription.Latent(fqcn, 0, TypeDescription.Generic.OBJECT, List.of());
  }

  private static boolean matches(List<String> include, List<String> exclude, String fqcn) {
    ElementMatcher.Junction<TypeDescription> m =
        MethodTypeInstrumentation.buildScopeMatcher(include, exclude);
    return m.matches(type(fqcn));
  }

  // --- Baseline rules 0–4 ---

  @Test
  void emptyInclude_dropsAll() {
    assertFalse(matches(List.of(), List.of(), "com.myapp.Handler"));
  }

  @Test
  void includeMatch_instruments() {
    assertTrue(matches(List.of("com.myapp"), List.of(), "com.myapp.Handler"));
  }

  @Test
  void includeNoMatch_drops() {
    assertFalse(matches(List.of("com.myapp"), List.of(), "com.other.Bar"));
  }

  @Test
  void excludeBeatsInclude() {
    assertFalse(
        matches(List.of("com.myapp"), List.of("com.myapp.internal"), "com.myapp.internal.Foo"));
  }

  @Test
  void selfExcludeHoldsUnderWildcardInclude() {
    // "software.amazon.opentelemetry.*" survives the validator (not a bare "*").
    List<String> wildcard = List.of("software.amazon.opentelemetry.*");
    assertFalse(matches(wildcard, List.of(), "io.opentelemetry.sdk.trace.SdkTracer"));
    assertFalse(
        matches(wildcard, List.of(), "software.amazon.opentelemetry.javaagent.tooling.Foo"));
  }

  // --- Java-only regression guards from the plan ---

  @Test
  void softwareAmazonUserApp_stillInstruments() {
    // Generic customer code under software.amazon.* is NOT under the narrowed self-exclude roots.
    assertTrue(matches(List.of("software.amazon.myapp"), List.of(), "software.amazon.myapp.Foo"));
  }

  @Test
  void appsignalsTestsApp_stillInstruments() {
    // The contract-test app shares the software.amazon.opentelemetry. org prefix but is NOT under
    // the narrowed self-exclude roots (.javaagent./.serviceevents./etc), so it must instrument.
    assertTrue(
        matches(
            List.of("software.amazon.opentelemetry.appsignals.tests"),
            List.of(),
            "software.amazon.opentelemetry.appsignals.tests.images.serviceevents.springmvc.BusinessLogic"));
  }

  @Test
  void javaagent_selfExcludedUnderUmbrellaInclude() {
    // Even when the include names the umbrella, the self-exclude root catches the agent.
    assertFalse(
        matches(
            List.of("software.amazon.opentelemetry.*"),
            List.of(),
            "software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.MethodAdvice"));
  }

  // --- INCLUDE / EXCLUDE coverage gaps ---

  @Test
  void includeMultiPatternUnion() {
    assertTrue(matches(List.of("com.myapp", "com.otherapp"), List.of(), "com.otherapp.Foo"));
  }

  @Test
  void includeWithUnrelatedExclude_stillInstruments() {
    assertTrue(matches(List.of("com.myapp"), List.of("com.otherapp"), "com.myapp.Foo"));
  }

  @Test
  void emptyIncludeWithNonemptyExclude_stillDrops() {
    // Rule 1 fires before rule 2 — EXCLUDE alone never opens the gate.
    assertFalse(matches(List.of(), List.of("com.myapp"), "com.other.Bar"));
  }

  @Test
  void excludeMultiPatternUnion() {
    assertFalse(
        matches(
            List.of("com.myapp"),
            List.of("com.myapp.internal", "com.myapp.legacy"),
            "com.myapp.legacy.Bar"));
  }

  @Test
  void excludeMultiPatternUnmatched_stillIncludes() {
    assertTrue(
        matches(
            List.of("com.myapp"),
            List.of("com.myapp.internal", "com.myapp.legacy"),
            "com.myapp.public_.Baz"));
  }

  @Test
  void excludeWinsWhenCollidesWithInclude() {
    assertFalse(matches(List.of("com.myapp"), List.of("com.myapp"), "com.myapp.Foo"));
  }

  @Test
  void selfExcludeWinsOverRedundantExclude() {
    assertFalse(
        matches(List.of("com.myapp"), List.of("io.opentelemetry"), "io.opentelemetry.sdk.trace.X"));
  }

  @Test
  void includeGlobDepthPinning_prefixMatchesAllDepths() {
    // Java prefix matching: "com.myapp" matches all depths below it.
    assertTrue(matches(List.of("com.myapp"), List.of(), "com.myapp.sub.deep.Leaf"));
  }
}
