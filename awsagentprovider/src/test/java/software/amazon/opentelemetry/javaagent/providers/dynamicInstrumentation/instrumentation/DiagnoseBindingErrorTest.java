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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.ErrorCause;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;

/**
 * Tests for {@link ByteBuddyInstrumentationEngine#diagnoseBindingError}, which lets a non-bindable
 * target (ghost method / unmodifiable class) be reported as ERROR instead of a misleading READY.
 * The diagnosis is inheritance-aware: a method inherited from a superclass or interface is a
 * legitimate target and must NOT be flagged.
 */
class DiagnoseBindingErrorTest {

  // ---- Fixture type hierarchy (uses binary FQNs of these nested classes) ----
  interface Speaker {
    String speak();
  }

  static class Base {
    String inheritedFromBase() {
      return "base";
    }
  }

  static class Derived extends Base implements Speaker {
    @Override
    public String speak() {
      return "hi";
    }

    String ownMethod() {
      return "own";
    }
  }

  abstract static class AbstractHolder {
    abstract String onlyAbstract();

    String concrete() {
      return "c";
    }
  }

  private static final String PKG = DiagnoseBindingErrorTest.class.getName();

  private static InstrumentationConfiguration config(String fqClassName, String methodName) {
    // codeUnit + "." + className must equal the runtime binary name for the exact-match path.
    int lastDot = fqClassName.lastIndexOf('.');
    String codeUnit = fqClassName.substring(0, lastDot);
    String className = fqClassName.substring(lastDot + 1);

    Map<String, Object> location = new HashMap<>();
    location.put("Language", "Java");
    location.put("CodeUnit", codeUnit);
    location.put("ClassName", className);
    location.put("MethodName", methodName);
    location.put("LineNumber", 0);

    Map<String, Object> locationWrapper = new HashMap<>();
    locationWrapper.put("CodeLocation", location);

    Map<String, Object> codeCapture = new HashMap<>();
    codeCapture.put("CaptureReturn", true);

    Map<String, Object> captureWrapper = new HashMap<>();
    captureWrapper.put("CodeCapture", codeCapture);

    Map<String, Object> apiConfig = new HashMap<>();
    apiConfig.put("Location", locationWrapper);
    apiConfig.put("LocationHash", "h" + methodName);
    apiConfig.put("InstrumentationType", "BREAKPOINT");
    apiConfig.put("CaptureConfiguration", captureWrapper);

    return InstrumentationConfiguration.fromApiConfig(apiConfig);
  }

  private static ByteBuddyInstrumentationEngine engineWithLoaded(
      Class<?>[] loaded, boolean modifiable) {
    Instrumentation instr = mock(Instrumentation.class);
    when(instr.getAllLoadedClasses()).thenReturn(loaded);
    lenient()
        .when(instr.isModifiableClass(org.mockito.ArgumentMatchers.any()))
        .thenReturn(modifiable);
    return new ByteBuddyInstrumentationEngine(instr);
  }

  @Test
  void ownMethodOnLoadedModifiableClassIsBindable() {
    ByteBuddyInstrumentationEngine engine = engineWithLoaded(new Class<?>[] {Derived.class}, true);
    assertThat(engine.diagnoseBindingError(config(PKG + "$Derived", "ownMethod"))).isNull();
  }

  @Test
  void methodInheritedFromSuperclassIsBindable() {
    // The legitimate inherited-method case — must NOT be flagged as METHOD_NOT_FOUND.
    ByteBuddyInstrumentationEngine engine = engineWithLoaded(new Class<?>[] {Derived.class}, true);
    assertThat(engine.diagnoseBindingError(config(PKG + "$Derived", "inheritedFromBase"))).isNull();
  }

  @Test
  void methodInheritedFromInterfaceIsBindable() {
    ByteBuddyInstrumentationEngine engine = engineWithLoaded(new Class<?>[] {Derived.class}, true);
    assertThat(engine.diagnoseBindingError(config(PKG + "$Derived", "speak"))).isNull();
  }

  @Test
  void ghostMethodOnLoadedClassReportsMethodNotFound() {
    ByteBuddyInstrumentationEngine engine = engineWithLoaded(new Class<?>[] {Derived.class}, true);
    assertThat(engine.diagnoseBindingError(config(PKG + "$Derived", "noSuchMethod")))
        .isEqualTo(ErrorCause.METHOD_NOT_FOUND);
  }

  @Test
  void unmodifiableClassReportsRuntimeError() {
    ByteBuddyInstrumentationEngine engine = engineWithLoaded(new Class<?>[] {Derived.class}, false);
    // Even for a real method, an unmodifiable class can't bind.
    assertThat(engine.diagnoseBindingError(config(PKG + "$Derived", "ownMethod")))
        .isEqualTo(ErrorCause.RUNTIME_ERROR);
  }

  @Test
  void abstractOnlyMethodReportsMethodNotFound() {
    // A method that resolves only to an abstract declaration can't be woven -> not bindable.
    ByteBuddyInstrumentationEngine engine =
        engineWithLoaded(new Class<?>[] {AbstractHolder.class}, true);
    assertThat(engine.diagnoseBindingError(config(PKG + "$AbstractHolder", "onlyAbstract")))
        .isEqualTo(ErrorCause.METHOD_NOT_FOUND);
  }

  @Test
  void concreteMethodOnAbstractClassIsBindable() {
    ByteBuddyInstrumentationEngine engine =
        engineWithLoaded(new Class<?>[] {AbstractHolder.class}, true);
    assertThat(engine.diagnoseBindingError(config(PKG + "$AbstractHolder", "concrete"))).isNull();
  }

  @Test
  void classNotLoadedIsInconclusive() {
    // Target class not among loaded classes -> null (it may load and bind later).
    ByteBuddyInstrumentationEngine engine = engineWithLoaded(new Class<?>[] {Base.class}, true);
    assertThat(engine.diagnoseBindingError(config(PKG + "$Derived", "ownMethod"))).isNull();
  }

  @Test
  void emptyLoadedSetIsInconclusive() {
    ByteBuddyInstrumentationEngine engine = engineWithLoaded(new Class<?>[] {}, true);
    assertThat(engine.diagnoseBindingError(config(PKG + "$Derived", "ownMethod"))).isNull();
  }
}
