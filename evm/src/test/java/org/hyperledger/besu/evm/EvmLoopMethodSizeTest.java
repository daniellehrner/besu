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
import java.lang.classfile.MethodModel;
import java.util.LinkedHashMap;
import java.util.Map;

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
