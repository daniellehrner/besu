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
package org.hyperledger.besu.evm.v2.operation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method of an operation class as that operation's arm in the untraced EVM v2 loop, which
 * runs the cheap operations inline, one arm of its switch each. {@code ./gradlew
 * :evm:generateEvmV2Loop} copies the body of every such method into the loop in EVM.java, between
 * the generated markers, and every build checks that the loop still holds exactly that. Edit the
 * arm here, in its operation's class, never in EVM.java.
 *
 * <p>The loop is fast only while C2 keeps its state in registers, and a small change to one arm can
 * cost every operation a large share of its time. The generator therefore rejects an arm that
 * breaks one of these rules, each of which was measured:
 *
 * <ul>
 *   <li>No call other than the few that always inline: {@code Long} and {@code Math} intrinsics,
 *       {@code LONG_BE}, the frame's memory and input accessors, the code's analysis tables and
 *       {@code isJumpDestinationV2}. One call C2 does not inline, in any arm, makes it keep all of
 *       the loop's locals in memory for the whole loop; one allocating arm took JUMPDEST from 0.45
 *       to about 2 ns.
 *   <li>No allocation, lambda, string, exception or synchronization, for the same reason.
 *   <li>No loop, not even over the four limbs of a word. Every loop head is an on-stack replacement
 *       entry point, and which compilation a JVM then ends up with depends on where it happened to
 *       enter: the same program ran at 2.7k or 16k us from one JVM to the next.
 *   <li>No field and no local of the loop other than the parameters listed below. A field is a load
 *       C2 hoists to the dispatch, where it runs for every operation. The constants and helpers an
 *       arm names come from {@link Arms}, statically imported, as EVM.java imports them, so that
 *       the name means the same in the loop as in the arm.
 *   <li>Stack indices are built on {@code top} or {@code next}, code indices on {@code at}, never
 *       on {@code sp} or {@code pc}. The generator turns those three into indices carrying the
 *       arm's own bias. Built on {@code sp} directly, the same index appears in many arms, and C2
 *       computes all of them once, ahead of the dispatch, where they take the registers the loop's
 *       state needs.
 *   <li>An arm reports its outcome only through {@code return done(cost, step, delta)} or {@code
 *       return FALLBACK}; the loop's common tail applies the update. Written out in every arm, the
 *       same update is again one computation that C2 moves ahead of the dispatch.
 * </ul>
 *
 * <p>An arm handles only the case in which its operation succeeds. Whatever else it meets, it
 * returns {@code FALLBACK}, charging nothing, and the loop runs the operation through its class,
 * which owns every halt.
 *
 * <p>Where an arm runs every case in which its operation succeeds, the class's own implementation
 * calls the arm through {@link Arms#result}, giving it all the gas it wants since the class's
 * callers charge the gas, so that the loop and the class share one implementation. The other
 * classes keep their own: PUSH2, JUMP and JUMPI also run the operation after them, which a tracer
 * has to see on its own, and MUL, DIV, MOD, MSTORE, MLOAD, CALLDATALOAD and CALLDATASIZE run only
 * their common cases in the arm. Operations that share an arm, such as LT, GT, SLT and SGT, keep it
 * in the class of the first of them, as one arm costs the loop less bytecode than four.
 *
 * <p>An arm is a static method returning long, and takes its parameters by name from the loop,
 * declaring only the ones it uses: {@code s} the stack, {@code sp} the number of items on it,
 * {@code top} the index of the top item's first limb and {@code next} that of the slot above it,
 * {@code pc}, {@code at} the program counter as a code index, {@code code}, {@code codeObject},
 * {@code opcode}, {@code gas} the gas left, {@code frame}, and the fork flags {@code
 * constantinople} and {@code shanghai}.
 *
 * <p>A new arm also belongs in the program of {@code EvmV2LoopCompilationTest}, which checks what
 * C2 inlines only in the arms its program reaches; docs/evm/v2-loop-arms.md has the rest of the
 * workflow.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
@interface Arm {

  /**
   * Where the arm comes in the loop, counting from 1. The arms are ranked by how often mainnet
   * executes their operations: C2 inlines in the loop's order and stops when the loop reaches its
   * size budget.
   *
   * @return the rank
   */
  int rank();

  /**
   * The opcodes the arm runs, besides any range given by {@link #first} and {@link #last}.
   *
   * @return the opcodes
   */
  int[] opcodes() default {};

  /**
   * The first of a range of opcodes the arm runs.
   *
   * @return the opcode, or -1 for none
   */
  int first() default -1;

  /**
   * The last of a range of opcodes the arm runs.
   *
   * @return the opcode, or -1 for none
   */
  int last() default -1;
}
