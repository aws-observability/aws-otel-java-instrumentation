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

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.advice.MethodCaptureAdvice;

/**
 * Verifies the overloaded-method parameter-name fix: the per-overload signature key built at
 * registration time (engine side) must exactly equal the key derived at runtime from
 * {@code @Advice.Origin} (advice side), so each overload captures its OWN parameter names instead
 * of the first-declared overload's.
 */
class OverloadParameterNamesTest {

  // Overloaded target: distinct parameter names per overload.
  @SuppressWarnings("unused")
  static class Overloaded {
    public String process(int count) {
      return "int:" + count;
    }

    public String process(String label) {
      return "str:" + label;
    }

    public String process(int count, String label) {
      return count + label;
    }

    // Array-parameter overload: guards the signature-suffix array rendering. ByteBuddy's
    // getName() yields the descriptor form "[Ljava.lang.String;" / "[I" while @Advice.Origin
    // (MethodDescription.toString()) yields the source form "java.lang.String[]" / "int[]" —
    // the suffix must use the source form so the keys match.
    public String process(String[] tags, int[] counts) {
      return tags.length + ":" + counts.length;
    }
  }

  private static TypeDescription describe() {
    // EXTENDED reader mode parses the MethodParameters attribute (compiled with -parameters).
    TypePool pool =
        new TypePool.Default(
            TypePool.CacheProvider.NoOp.INSTANCE,
            net.bytebuddy.dynamic.ClassFileLocator.ForClassLoader.of(
                OverloadParameterNamesTest.class.getClassLoader()),
            TypePool.Default.ReaderMode.EXTENDED);
    return pool.describe(Overloaded.class.getName()).resolve();
  }

  @Test
  void registrationSignatureKeyMatchesRuntimeOriginKey() {
    String methodKey = Overloaded.class.getName() + ".process";
    TypeDescription type = describe();

    int checked = 0;
    for (MethodDescription method :
        type.getDeclaredMethods().filter(net.bytebuddy.matcher.ElementMatchers.named("process"))) {
      // Engine side: methodKey + signatureSuffix(method).
      String registrationKey = methodKey + ByteBuddyInstrumentationEngine.signatureSuffix(method);
      // Advice side: derived from @Advice.Origin, which renders MethodDescription.toString().
      String runtimeKey = MethodCaptureAdvice.signatureKey(method.toString());
      assertThat(runtimeKey)
          .as("registration key must match runtime @Advice.Origin key for overload %s", method)
          .isEqualTo(registrationKey);
      checked++;
    }
    assertThat(checked).isEqualTo(4); // all four overloads
  }

  @Test
  void overloadsProduceDistinctSignatureKeys() {
    String methodKey = Overloaded.class.getName() + ".process";
    TypeDescription type = describe();

    java.util.Set<String> keys = new java.util.HashSet<>();
    for (MethodDescription method :
        type.getDeclaredMethods().filter(net.bytebuddy.matcher.ElementMatchers.named("process"))) {
      keys.add(methodKey + ByteBuddyInstrumentationEngine.signatureSuffix(method));
    }
    // Four overloads -> four distinct keys (the whole point: they no longer collapse to one).
    assertThat(keys).hasSize(4);
  }

  @Test
  void signatureKeyExtractsArgListFromOrigin() {
    // Sanity on the runtime parser against a representative Origin string.
    String origin = "public java.lang.String com.example.Overloaded.process(java.lang.String)";
    assertThat(MethodCaptureAdvice.signatureKey(origin))
        .isEqualTo("com.example.Overloaded.process(java.lang.String)");
  }
}
