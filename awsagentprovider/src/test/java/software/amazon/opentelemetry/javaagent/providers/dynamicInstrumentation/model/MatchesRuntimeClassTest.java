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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InstrumentationConfiguration#matchesRuntimeClass}, which lets a breakpoint on a
 * nested class addressed by its SIMPLE name bind to the runtime binary name (Outer$Inner). Without
 * it, such a target silently never fires.
 */
class MatchesRuntimeClassTest {

  private static InstrumentationConfiguration config(String codeUnit, String className) {
    Map<String, Object> codeLocation = new HashMap<>();
    codeLocation.put("Language", "java");
    codeLocation.put("CodeUnit", codeUnit);
    codeLocation.put("ClassName", className);
    codeLocation.put("MethodName", "speak");
    Map<String, Object> location = new HashMap<>();
    location.put("CodeLocation", codeLocation);
    Map<String, Object> item = new HashMap<>();
    item.put("InstrumentationType", "BREAKPOINT");
    item.put("Location", location);
    item.put("LocationHash", "h");
    Map<String, Object> capture = new HashMap<>();
    Map<String, Object> codeCapture = new HashMap<>();
    codeCapture.put("CaptureReturn", true);
    capture.put("CodeCapture", codeCapture);
    item.put("CaptureConfiguration", capture);
    return InstrumentationConfiguration.fromApiConfig(item);
  }

  @Test
  void exactFqnMatches() {
    InstrumentationConfiguration c = config("com.pkg", "TopLevel");
    assertThat(c.matchesRuntimeClass("com.pkg.TopLevel")).isTrue();
  }

  @Test
  void nestedClassBySimpleNameMatchesBinaryName() {
    // User can only supply ClassName="Dog"; runtime sees the binary name with '$'.
    InstrumentationConfiguration c = config("com.pkg.dispatch", "Dog");
    assertThat(c.matchesRuntimeClass("com.pkg.dispatch.InheritanceTests$Dog")).isTrue();
  }

  @Test
  void deeplyNestedSimpleNameMatches() {
    InstrumentationConfiguration c = config("com.pkg", "Inner");
    assertThat(c.matchesRuntimeClass("com.pkg.Outer$Mid$Inner")).isTrue();
  }

  @Test
  void wrongPackageDoesNotMatch() {
    InstrumentationConfiguration c = config("com.pkg", "Dog");
    assertThat(c.matchesRuntimeClass("com.other.Outer$Dog")).isFalse();
  }

  @Test
  void differentSimpleNameDoesNotMatch() {
    InstrumentationConfiguration c = config("com.pkg", "Cat");
    assertThat(c.matchesRuntimeClass("com.pkg.Outer$Dog")).isFalse();
  }

  @Test
  void deeperSubpackageDoesNotMatch() {
    // codeUnit is an exact package, not a prefix: "com.pkg"/"Dog" must NOT match a nested Dog in a
    // DEEPER package, or a breakpoint could bind to an unintended class.
    InstrumentationConfiguration c = config("com.pkg", "Dog");
    assertThat(c.matchesRuntimeClass("com.pkg.sub.Outer$Dog")).isFalse();
  }

  @Test
  void nonNestedDifferentNameDoesNotMatch() {
    // No '$' in the runtime name and not an exact FQN -> no match (avoids loose suffix matching).
    InstrumentationConfiguration c = config("com.pkg", "Dog");
    assertThat(c.matchesRuntimeClass("com.pkg.Cat")).isFalse();
  }

  @Test
  void nullRuntimeNameIsFalse() {
    assertThat(config("com.pkg", "Dog").matchesRuntimeClass(null)).isFalse();
  }

  // ---- matchesRuntimeInstrumentationKey: capture-key reconciliation ----

  @Test
  void runtimeMethodKeyWithBinaryClassMatches() {
    // The collector looks up by the advice's methodKey (no ":line" for method-level), which
    // carries the binary class name for a nested target. It must resolve to the simple-name config.
    InstrumentationConfiguration c = config("com.pkg.dispatch", "Dog");
    assertThat(c.matchesRuntimeInstrumentationKey("com.pkg.dispatch.InheritanceTests$Dog.speak"))
        .isTrue();
  }

  @Test
  void runtimeKeyWithLineSuffixMatchesWhenLineMatches() {
    // config() builds a method-level config (no LineNumber) -> lineNumber 0; ":0" suffix matches.
    InstrumentationConfiguration c = config("com.pkg", "Inner");
    assertThat(c.matchesRuntimeInstrumentationKey("com.pkg.Outer$Inner.speak:0")).isTrue();
  }

  @Test
  void runtimeKeyWrongMethodDoesNotMatch() {
    InstrumentationConfiguration c = config("com.pkg", "Dog");
    assertThat(c.matchesRuntimeInstrumentationKey("com.pkg.Outer$Dog.bark")).isFalse();
  }

  @Test
  void runtimeKeyExactMethodKeyMatches() {
    InstrumentationConfiguration c = config("com.pkg", "TopLevel");
    assertThat(c.matchesRuntimeInstrumentationKey("com.pkg.TopLevel.speak")).isTrue();
  }

  @Test
  void runtimeKeyNullIsFalse() {
    assertThat(config("com.pkg", "Dog").matchesRuntimeInstrumentationKey(null)).isFalse();
  }
}
