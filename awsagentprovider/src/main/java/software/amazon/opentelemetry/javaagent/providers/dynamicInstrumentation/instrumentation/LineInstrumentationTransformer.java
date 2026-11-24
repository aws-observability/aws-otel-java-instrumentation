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

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.InstrumentationConfiguration;

/**
 * ClassFileTransformer for line-level instrumentation using ASM Tree API.
 *
 * <p>This transformer uses standard ASM (org.objectweb.asm) which will be shaded at build time to
 * avoid conflicts with application ASM or ByteBuddy's internal ASM.
 *
 * <p>Uses ASM Tree API (not Visitor API) because we need:
 *
 * <ul>
 *   <li>Random access to search for specific line numbers in bytecode
 *   <li>Variable scope checking (requires traversing label ranges)
 *   <li>Arbitrary insertion point (middle of method, not just entry/exit)
 * </ul>
 *
 * <p>Performance consideration: Tree API loads entire class into memory (~5ms overhead), but this
 * is negligible compared to retransformation cost (~50-100ms). The flexibility is worth it.
 */
public class LineInstrumentationTransformer implements ClassFileTransformer {

  private static final Logger logger =
      Logger.getLogger(LineInstrumentationTransformer.class.getName());

  private final String targetClassName; // Internal name (e.g., "com/example/PaymentProcessor")
  private final List<InstrumentationConfiguration> lineConfigs;

  /**
   * Creates a transformer for line-level instrumentations in a specific class.
   *
   * @param targetClassName Fully qualified class name (dot notation)
   * @param lineConfigs List of line-level instrumentation configurations for this class
   */
  public LineInstrumentationTransformer(
      String targetClassName, List<InstrumentationConfiguration> lineConfigs) {
    this.targetClassName = targetClassName.replace('.', '/'); // Convert to internal name
    this.lineConfigs = lineConfigs;
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {

    if (!className.equals(targetClassName)) {
      return null; // Not our target class
    }

    logger.log(
        Level.FINE,
        "LineInstrumentationTransformer: Transforming class {0} with {1} line-level instrumentations",
        new Object[] {className, lineConfigs.size()});

    try {
      ClassReader reader = new ClassReader(classfileBuffer);

      // Use custom ClassWriter that uses target classloader for type resolution
      // This fixes inner class loading issues (e.g., DemoController$TestData)
      final ClassLoader targetClassLoader = loader;
      ClassWriter writer =
          new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
              return targetClassLoader; // Use application classloader, not agent classloader
            }
          };

      // Use Tree API to get random access to bytecode structure
      ClassNode classNode = new ClassNode();
      reader.accept(classNode, ClassReader.EXPAND_FRAMES);

      // Transform each method that has line-level instrumentations
      for (MethodNode methodNode : classNode.methods) {
        List<InstrumentationConfiguration> methodConfigs = getConfigsForMethod(methodNode.name);
        if (!methodConfigs.isEmpty()) {
          transformMethod(methodNode, methodConfigs);
        }
      }

      classNode.accept(writer);
      byte[] transformedBytes = writer.toByteArray();

      logger.log(
          Level.FINE,
          "LineInstrumentationTransformer: Successfully transformed class {0}",
          className);
      return transformedBytes;

    } catch (Throwable e) {
      // Catch Throwable: an Error here (e.g., ClassFormatError, StackOverflowError from
      // COMPUTE_FRAMES) would prevent the class from loading entirely.
      logger.log(
          Level.SEVERE,
          "LineInstrumentationTransformer: Failed to transform class " + className,
          e);
      return null; // Return null on error = no transformation
    }
  }

  /**
   * Transforms a method by injecting instrumentation at specific line numbers.
   *
   * <p>For each line-level instrumentation:
   *
   * <ol>
   *   <li>Find the LineNumberNode matching the target line
   *   <li>Determine which local variables are in scope at that line
   *   <li>Generate bytecode to capture those variables in a HashMap
   *   <li>Inject call to LineCaptureAdvice.onLineBreakpointHit()
   * </ol>
   *
   * @param methodNode The ASM MethodNode to transform
   * @param configs List of instrumentation configurations for this method
   */
  private void transformMethod(MethodNode methodNode, List<InstrumentationConfiguration> configs) {
    logger.log(
        Level.FINE,
        "Transforming method {0} with {1} line instrumentations",
        new Object[] {methodNode.name, configs.size()});

    for (InstrumentationConfiguration config : configs) {
      int targetLine = config.getLineNumber();
      String instrumentationKey = config.getInstrumentationKey();

      // Find the label for the target line number
      AbstractInsnNode current = methodNode.instructions.getFirst();
      LabelNode targetLabel = null;

      while (current != null) {
        if (current instanceof LineNumberNode) {
          LineNumberNode lineNode = (LineNumberNode) current;
          if (lineNode.line == targetLine) {
            targetLabel = lineNode.start;
            break;
          }
        }
        current = current.getNext();
      }

      if (targetLabel == null) {
        logger.log(
            Level.WARNING,
            "Could not find line {0} in method {1}",
            new Object[] {targetLine, methodNode.name});
        continue;
      }

      // Find local variables that are in scope at the target line
      List<LocalVariableNode> inScopeVars = new ArrayList<>();
      if (methodNode.localVariables != null) {
        for (LocalVariableNode varNode : methodNode.localVariables) {
          // Skip "this" reference (index 0 in non-static methods)
          if ("this".equals(varNode.name)) {
            continue;
          }
          if (isInScope(methodNode, varNode, targetLabel)) {
            inScopeVars.add(varNode);
          }
        }
      }

      // Generate bytecode to call LineCaptureAdvice.onLineBreakpointHit()
      InsnList injectedCode = generateInstrumentationCode(instrumentationKey, inScopeVars);

      // Inject after the LineNumberNode (right at the start of that line's code)
      methodNode.instructions.insert(targetLabel, injectedCode);

      logger.log(
          Level.FINE,
          "Injected instrumentation at line {0} with {1} local variables",
          new Object[] {targetLine, inScopeVars.size()});
    }
  }

  /**
   * Generates bytecode instructions to capture local variables and call the advice method.
   *
   * <p>Equivalent to: LineCaptureAdvice.onLineBreakpointHit(instrumentationKey, localVariablesMap)
   *
   * <p>Where localVariablesMap is a HashMap<String, Object> containing all in-scope local
   * variables.
   *
   * @param instrumentationKey Unique key for this instrumentation
   * @param inScopeVars List of local variables in scope at the instrumentation point
   * @return InsnList containing the generated bytecode
   */
  private InsnList generateInstrumentationCode(
      String instrumentationKey, List<LocalVariableNode> inScopeVars) {

    InsnList insnList = new InsnList();

    // Load instrumentation key onto stack
    insnList.add(new LdcInsnNode(instrumentationKey));

    // Create HashMap: new HashMap()
    insnList.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
    insnList.add(new InsnNode(Opcodes.DUP));
    insnList.add(
        new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));

    // Add each local variable to the HashMap
    for (LocalVariableNode varNode : inScopeVars) {
      Type varType = Type.getType(varNode.desc);

      // Duplicate HashMap reference for put() call
      insnList.add(new InsnNode(Opcodes.DUP));

      // Load variable name as key
      insnList.add(new LdcInsnNode(varNode.name));

      // Load variable value and box if primitive
      insnList.add(new VarInsnNode(varType.getOpcode(Opcodes.ILOAD), varNode.index));
      MethodInsnNode boxingInsn = createBoxingInstruction(varType);
      if (boxingInsn != null) {
        insnList.add(boxingInsn);
      }

      // Call HashMap.put(key, value)
      insnList.add(
          new MethodInsnNode(
              Opcodes.INVOKEINTERFACE,
              "java/util/Map",
              "put",
              "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
              true));

      // Pop return value (we don't need it)
      insnList.add(new InsnNode(Opcodes.POP));
    }

    // Call static advice method: LineCaptureAdvice.onLineBreakpointHit(key, map)
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "software/amazon/opentelemetry/javaagent/providers/dynamicInstrumentation/instrumentation/advice/LineCaptureAdvice",
            "onLineBreakpointHit",
            "(Ljava/lang/String;Ljava/util/Map;)V",
            false));

    return insnList;
  }

  /**
   * Creates a boxing instruction for primitive types.
   *
   * <p>Converts primitive values to their corresponding wrapper objects so they can be stored in
   * the HashMap<String, Object>.
   *
   * @param varType The type of the variable
   * @return MethodInsnNode for boxing, or null if already an object
   */
  private MethodInsnNode createBoxingInstruction(Type varType) {
    switch (varType.getSort()) {
      case Type.BOOLEAN:
        return new MethodInsnNode(
            Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
      case Type.BYTE:
        return new MethodInsnNode(
            Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
      case Type.CHAR:
        return new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/Character",
            "valueOf",
            "(C)Ljava/lang/Character;",
            false);
      case Type.SHORT:
        return new MethodInsnNode(
            Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
      case Type.INT:
        return new MethodInsnNode(
            Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
      case Type.LONG:
        return new MethodInsnNode(
            Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
      case Type.FLOAT:
        return new MethodInsnNode(
            Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
      case Type.DOUBLE:
        return new MethodInsnNode(
            Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
      default:
        return null; // Already an object reference
    }
  }

  /**
   * Checks if a local variable is in scope at a specific location in the bytecode.
   *
   * <p>A variable is in scope if the location falls between its start and end labels in the
   * method's instruction list.
   *
   * @param methodNode The method containing the variable
   * @param variableNode The local variable to check
   * @param location The instruction location to check
   * @return true if variable is in scope at the location
   */
  private boolean isInScope(
      MethodNode methodNode, LocalVariableNode variableNode, AbstractInsnNode location) {
    AbstractInsnNode startScope =
        variableNode.start != null ? variableNode.start : methodNode.instructions.getFirst();
    AbstractInsnNode endScope =
        variableNode.end != null ? variableNode.end : methodNode.instructions.getLast();

    AbstractInsnNode insn = startScope;
    while (insn != null && insn != endScope) {
      if (insn == location) {
        return true;
      }
      insn = insn.getNext();
    }
    return false;
  }

  /**
   * Gets configurations that apply to a specific method.
   *
   * @param methodName The method name
   * @return List of configurations for that method
   */
  private List<InstrumentationConfiguration> getConfigsForMethod(String methodName) {
    List<InstrumentationConfiguration> methodConfigs = new ArrayList<>();
    for (InstrumentationConfiguration config : lineConfigs) {
      if (config.getMethodName().equals(methodName)) {
        methodConfigs.add(config);
      }
    }
    return methodConfigs;
  }
}
