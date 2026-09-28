/*
 * Copyright contributors to Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.evm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * HotSpot refuses to compile a method whose own bytecode exceeds {@code HugeMethodLimit} = 8000,
 * and the same gate covers on-stack replacement, so an oversized interpreter loop would never leave
 * the template interpreter at any tier. The limit is a develop constant and cannot be raised in a
 * product build.
 *
 * <p>The budget is tighter than the number suggests: C2 initialises its inline-bytecode counter
 * with the root method's own size rather than zero, so every helper inlined into the loop is
 * charged against what is left of the 8000.
 */
class EvmLoopMethodSizeTest {

  /** HotSpot's HugeMethodLimit. A method above this is never compiled and never OSR-compiled. */
  private static final int HUGE_METHOD_LIMIT = 8000;

  /**
   * The loop is useless without room to inline into it, so it is held well under the hard limit.
   * Raising this is a deliberate decision about inlining headroom, not a formality.
   */
  private static final int LOOP_BUDGET = 6000;

  @Test
  void noMethodExceedsTheHugeMethodLimit() {
    final Map<String, Integer> sizes = methodSizes();
    assertThat(sizes).isNotEmpty();
    assertThat(sizes)
        .as(
            "no method may exceed HugeMethodLimit=%d, or HotSpot stops compiling it",
            HUGE_METHOD_LIMIT)
        .allSatisfy((name, size) -> assertThat(size).isLessThan(HUGE_METHOD_LIMIT));
  }

  @Test
  void interpreterLoopsLeaveRoomToInlineInto() {
    final Map<String, Integer> sizes = methodSizes();
    sizes.forEach(
        (name, size) -> {
          if (name.startsWith("runToHalt")) {
            assertThat(size)
                .as(
                    "%s is %d bytes; every byte here is charged against the 8000 that also has to "
                        + "cover everything inlined into the loop",
                    name, size)
                .isLessThan(LOOP_BUDGET);
          }
        });
  }

  /** HotSpot's MaxInlineSize: C2 inlines a callee up to this size at any call site. */
  private static final int MAX_INLINE_SIZE = 35;

  /** How deep the calls of a call are followed; C2 inlines well beyond it. */
  private static final int INLINE_DEPTH = 3;

  /**
   * The calls the untraced v2 loop makes on purpose without their being inlined: the general path,
   * which runs an operation through its implementation, and what the loop does once before it
   * starts. Everything else it calls must be inlined, or C2 keeps the loop's locals in memory.
   */
  static final Set<String> NOT_INLINED =
      Set.of(
          "EVM.executeOperationV2",
          "EVM.completeOperationV2",
          "Code.pushBits",
          "Code.jumpDestinations",
          "MessageFrame.getCode",
          "Bytes.toArrayUnsafe",
          "Code.getBytes",
          "MessageFrame.stackDataV2");

  /** Classes whose methods C2 replaces with intrinsics. */
  private static final Set<String> INTRINSICS =
      Set.of("java/lang/Long", "java/lang/Math", "java/lang/invoke/VarHandle");

  @Test
  void everyCallInTheUntracedV2LoopIsInlined() {
    final MethodModel loop = method(EVM.class, "runToHaltV2Untraced", null).orElseThrow();
    final List<String> problems = new ArrayList<>();
    checkCalls(loop.code().orElseThrow(), "runToHaltV2Untraced", 0, problems);
    assertThat(problems)
        .as(
            "the untraced v2 loop may only call what C2 inlines at any call site; a call it does "
                + "not inline makes it keep the loop's locals in memory, see V2LoopArms")
        .isEmpty();
  }

  private void checkCalls(
      final CodeModel code, final String path, final int depth, final List<String> problems) {
    for (final CodeElement element : code) {
      if (!(element instanceof InvokeInstruction invoke)) {
        continue;
      }
      final String owner = invoke.owner().asInternalName();
      final String name = invoke.name().stringValue();
      final String simpleName = owner.substring(owner.lastIndexOf('/') + 1) + "." + name;
      if (INTRINSICS.contains(owner) || (depth == 0 && NOT_INLINED.contains(simpleName))) {
        continue;
      }
      final String call = path + " -> " + simpleName;
      final Optional<MethodModel> callee = method(owner, name, invoke.type().stringValue());
      if (callee.isEmpty() || callee.get().code().isEmpty()) {
        problems.add(call + ": no code to inline (an interface or abstract call)");
        continue;
      }
      final CodeModel calleeCode = callee.get().code().get();
      final int size = callee.get().findAttribute(Attributes.code()).orElseThrow().codeLength();
      if (size > MAX_INLINE_SIZE) {
        problems.add(call + ": " + size + " bytes, above MaxInlineSize=" + MAX_INLINE_SIZE);
      } else if (depth + 1 < INLINE_DEPTH) {
        checkCalls(calleeCode, call, depth + 1, problems);
      }
    }
  }

  private static Optional<MethodModel> method(
      final Class<?> type, final String name, final String descriptor) {
    return method(type.getName().replace('.', '/'), name, descriptor);
  }

  private static Optional<MethodModel> method(
      final String internalName, final String name, final String descriptor) {
    String owner = internalName;
    while (owner != null) {
      final ClassModel model = classModel(owner);
      if (model == null) {
        return Optional.empty();
      }
      for (final MethodModel method : model.methods()) {
        if (method.methodName().equalsString(name)
            && (descriptor == null || method.methodType().equalsString(descriptor))) {
          return Optional.of(method);
        }
      }
      owner = model.superclass().map(c -> c.asInternalName()).orElse(null);
    }
    return Optional.empty();
  }

  private static ClassModel classModel(final String internalName) {
    try (InputStream in =
        EvmLoopMethodSizeTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
      return in == null ? null : ClassFile.of().parse(in.readAllBytes());
    } catch (final Exception e) {
      throw new AssertionError("could not read " + internalName, e);
    }
  }

  private Map<String, Integer> methodSizes() {
    final ClassModel model;
    try (InputStream in = EVM.class.getResourceAsStream("EVM.class")) {
      assertThat(in).as("EVM.class must be readable from the classpath").isNotNull();
      model = ClassFile.of().parse(in.readAllBytes());
    } catch (final Exception e) {
      throw new AssertionError("could not read EVM.class", e);
    }
    final Map<String, Integer> sizes = new LinkedHashMap<>();
    for (final MethodModel method : model.methods()) {
      method
          .findAttribute(Attributes.code())
          .ifPresent(
              code -> sizes.merge(method.methodName().stringValue(), code.codeLength(), Math::max));
    }
    return sizes;
  }
}
