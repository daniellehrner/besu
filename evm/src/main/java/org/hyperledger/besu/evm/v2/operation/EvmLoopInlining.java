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

import static org.hyperledger.besu.evm.v2.operation.AbstractOperationV2.OVERFLOW_RESPONSE;
import static org.hyperledger.besu.evm.v2.operation.AbstractOperationV2.UNDERFLOW_RESPONSE;

import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * What the methods copied into the untraced EVM v2 loop, marked with {@link InlineInEvmLoop}, are
 * written with: the constants and helpers they may name, which EVM.java imports from here too, and
 * the way an operation class runs such a method against the state held in the frame.
 */
public final class EvmLoopInlining {

  // Constants, as fields of the gas calculator would be loads C2 hoists to the loop's dispatch. An
  // EVM whose calculator charges other costs does not run the loop; see chargesTheLoopsCosts.

  /** The base tier's cost. */
  public static final long BASE_TIER_GAS = 2L;

  /** The very low tier's cost. */
  public static final long VERY_LOW_TIER_GAS = 3L;

  /** The low tier's cost. */
  public static final long LOW_TIER_GAS = 5L;

  /** The mid tier's cost. */
  public static final long MID_TIER_GAS = 8L;

  /** The high tier's cost. */
  public static final long HIGH_TIER_GAS = 10L;

  /** JUMPDEST's cost. */
  public static final long JUMPDEST_GAS = 1L;

  /** Reads a big-endian word of a byte array, as code holds the immediates of PUSH. */
  public static final VarHandle LONG_BE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

  /** What an inline method returns when it leaves the operation to its class. */
  static final long FALLBACK = -1L;

  /**
   * The gas to give an inline method from an operation class: whoever calls an operation charges
   * its gas, so the method must not stop at its own gas check.
   */
  static final long ANY_GAS = Long.MAX_VALUE;

  /** JUMP to a destination that is not a JUMPDEST, charging its cost as v1 does. */
  private static final OperationResult INVALID_JUMP =
      new OperationResult(MID_TIER_GAS, ExceptionalHaltReason.INVALID_JUMP_DESTINATION);

  /** JUMPI to a destination that is not a JUMPDEST, charging its cost as v1 does. */
  private static final OperationResult INVALID_JUMPI =
      new OperationResult(HIGH_TIER_GAS, ExceptionalHaltReason.INVALID_JUMP_DESTINATION);

  /** The bit of an outcome that says the program counter moved to a jump destination. */
  private static final long JUMPED = 1L << 40;

  /** The results of the inline operations by gas cost, for the steps the interpreter makes. */
  private static final OperationResult[] SUCCESS = new OperationResult[16];

  /** The same for jumps, which set the program counter themselves. */
  private static final OperationResult[] SUCCESS_JUMPED = new OperationResult[16];

  static {
    for (int cost = 0; cost < SUCCESS.length; cost++) {
      SUCCESS[cost] = new OperationResult(cost, null);
      SUCCESS_JUMPED[cost] = new OperationResult(cost, null, 0);
    }
  }

  private EvmLoopInlining() {}

  /**
   * Whether a destination is a JUMPDEST of the code. The inline methods call it, so it has to stay
   * within C2's MaxInlineSize of 35 bytes of bytecode; one unsigned comparison is the range check
   * {@code 0 <= destination < codeSize}.
   *
   * @param jumpDestBitMask the code's JUMPDEST bits
   * @param destination the destination
   * @param codeSize the size of the code
   * @return whether it is a JUMPDEST
   */
  public static boolean isJumpDestinationV2(
      final long[] jumpDestBitMask, final long destination, final int codeSize) {
    return Long.compareUnsigned(destination, codeSize) < 0
        && (jumpDestBitMask[(int) (destination >>> 6)] & 1L << destination) != 0L;
  }

  /**
   * The code's JUMPDEST bits, analysing the code first if nothing has yet. EVM v2 has the code
   * analysed before it runs any of it, so that the inline methods can read the bits straight from
   * the code; an operation's class asks for the code through {@link #analysedCode}, as it may also
   * run on its own.
   *
   * @param code the code
   * @return its JUMPDEST bits, or null for empty code, which has no destination to look up
   */
  public static long[] jumpDestinations(final Code code) {
    final long[] bits = code.getJumpDestBitMask();
    return bits != null ? bits : analyse(code);
  }

  /** The frame's code, analysed for its JUMPDESTs. */
  static Code analysedCode(final MessageFrame frame) {
    final Code code = frame.getCode();
    jumpDestinations(code);
    return code;
  }

  private static long[] analyse(final Code code) {
    // asked for a destination, the code analyses itself and keeps the bits
    code.isJumpDestInvalid(0);
    return code.getJumpDestBitMask();
  }

  /**
   * Whether the item whose first limb is at {@code at} is a valid jump destination: a JUMPDEST of
   * the code. The loop holds a copy of it in every case that calls it.
   *
   * @param s the stack
   * @param at the index of the item's first limb
   * @param codeObject the code
   * @return whether a jump to it is valid
   */
  @InlineInEvmLoop
  static boolean isJumpDestinationAt(final long[] s, final int at, final Code codeObject) {
    return (s[at] | s[at + 1] | s[at + 2]) == 0
        && isJumpDestinationV2(codeObject.getJumpDestBitMask(), s[at + 3], codeObject.getSize());
  }

  /**
   * Stores a value of at most 64 bits as the item whose first limb is at {@code at}. The loop holds
   * a copy of it in every case that calls it.
   *
   * @param s the stack
   * @param at the index of the item's first limb
   * @param value the value
   */
  @InlineInEvmLoop
  static void storeLong(final long[] s, final int at, final long value) {
    s[at] = 0L;
    s[at + 1] = 0L;
    s[at + 2] = 0L;
    s[at + 3] = value;
  }

  /**
   * Whether the item whose first limb is at {@code at} is zero. The loop holds a copy of it in
   * every case that calls it.
   *
   * @param s the stack
   * @param at the index of the item's first limb
   * @return whether it is zero
   */
  @InlineInEvmLoop
  static boolean isZeroWord(final long[] s, final int at) {
    return (s[at] | s[at + 1] | s[at + 2] | s[at + 3]) == 0;
  }

  /**
   * Copies the item whose first limb is at {@code from} into the slot whose first limb is at {@code
   * to}. The loop holds a copy of it in every case that calls it.
   *
   * @param s the stack
   * @param from the index of the first limb of the item to copy
   * @param to the index of the first limb of the slot to copy it into
   */
  @InlineInEvmLoop
  static void copyWord(final long[] s, final int from, final int to) {
    s[to] = s[from];
    s[to + 1] = s[from + 1];
    s[to + 2] = s[from + 2];
    s[to + 3] = s[from + 3];
  }

  /**
   * Swaps the items whose first limbs are at {@code a} and {@code b}. The loop holds a copy of it
   * in every case that calls it.
   *
   * @param s the stack
   * @param a the index of the first limb of one item
   * @param b the index of the first limb of the other
   */
  @InlineInEvmLoop
  static void swapWords(final long[] s, final int a, final int b) {
    long t = s[a];
    s[a] = s[b];
    s[b] = t;
    t = s[a + 1];
    s[a + 1] = s[b + 1];
    s[b + 1] = t;
    t = s[a + 2];
    s[a + 2] = s[b + 2];
    s[b + 2] = t;
    t = s[a + 3];
    s[a + 3] = s[b + 3];
    s[b + 3] = t;
  }

  /**
   * Whether a gas calculator charges the costs the untraced loop charges as constants. An EVM whose
   * calculator charges others runs every operation through its class.
   *
   * @param gasCalculator the gas calculator
   * @return whether the untraced loop charges what the calculator charges
   */
  public static boolean chargesTheLoopsCosts(final GasCalculator gasCalculator) {
    return gasCalculator.getBaseTierGasCost() == BASE_TIER_GAS
        && gasCalculator.getVeryLowTierGasCost() == VERY_LOW_TIER_GAS
        && gasCalculator.getLowTierGasCost() == LOW_TIER_GAS
        && gasCalculator.getMidTierGasCost() == MID_TIER_GAS
        && gasCalculator.getHighTierGasCost() == HIGH_TIER_GAS
        && gasCalculator.getJumpDestOperationGasCost() == JUMPDEST_GAS;
  }

  /** What an inline method returns when it has run the operation: gas, pc step, stack delta. */
  static long done(final long cost, final int step, final int delta) {
    return cost << 41 | (step & 0xffffffffL) << 8 | (delta & 0xffL);
  }

  /** As {@link #done}, for an operation that jumped, which a tracer sees at its destination. */
  static long jumped(final long cost, final int step, final int delta) {
    return done(cost, step, delta) | JUMPED;
  }

  static long cost(final long outcome) {
    return outcome >>> 41;
  }

  static int step(final long outcome) {
    return (int) (outcome >>> 8);
  }

  static int delta(final long outcome) {
    return (byte) outcome;
  }

  /** The index of the top item's first limb, which an inline method takes as top. */
  static int top(final int sp) {
    return (sp - 1) << 2;
  }

  /**
   * The index of the first limb of the slot above the top item, which an inline method takes as
   * next.
   */
  static int next(final int sp) {
    return sp << 2;
  }

  /**
   * The frame's stack, grown first when it cannot take {@code items} more. An inline method that
   * adds items falls back when the array is full, which in the operation's class then means the
   * stack has reached its limit.
   */
  static long[] stackWithSpace(final MessageFrame frame, final int items) {
    frame.ensureStackCapacityV2(items);
    return frame.stackDataV2();
  }

  /**
   * The result of an operation that ran its inline method: the method's update, or the operation's
   * halt when the method fell back. It is a method of its own so that the operations' own methods
   * stay within C2's MaxInlineSize of 35 bytes: the general path's switch calls them, and C2 counts
   * no case of a switch that large as hot.
   */
  static OperationResult result(
      final MessageFrame frame, final long outcome, final OperationResult halt) {
    return outcome != FALLBACK ? ran(frame, outcome) : halt;
  }

  /**
   * The result of an operation whose inline method can only fall back on the stack: it needs more
   * items than there are, or, given the room the operation's class made, adds more than the stack
   * may hold.
   */
  static OperationResult stackResult(
      final MessageFrame frame, final long outcome, final int consumed) {
    if (outcome != FALLBACK) {
      return ran(frame, outcome);
    }
    return frame.stackHasItemsV2(consumed) ? OVERFLOW_RESPONSE : UNDERFLOW_RESPONSE;
  }

  /** The result of JUMP after its inline method. */
  static OperationResult jumpResult(final MessageFrame frame, final long outcome) {
    return outcome != FALLBACK ? ran(frame, outcome) : invalidJump(frame, 1, INVALID_JUMP);
  }

  /** The result of JUMPI after its inline method. */
  static OperationResult jumpiResult(final MessageFrame frame, final long outcome) {
    return outcome != FALLBACK ? ran(frame, outcome) : invalidJump(frame, 2, INVALID_JUMPI);
  }

  /**
   * A jump whose inline method fell back: an underflow, or an invalid destination, which v1 reports
   * after popping the jump's arguments.
   */
  private static OperationResult invalidJump(
      final MessageFrame frame, final int consumed, final OperationResult invalidDestination) {
    if (!frame.stackHasItemsV2(consumed)) {
      return UNDERFLOW_RESPONSE;
    }
    frame.setTopV2(frame.stackTopV2() - consumed);
    return invalidDestination;
  }

  /**
   * Moves the stack and the program counter as an inline method that ran its operation says. An
   * operation whose method cannot fall back when given all the gas it wants calls this directly.
   */
  static OperationResult ran(final MessageFrame frame, final long outcome) {
    frame.setTopV2(frame.stackTopV2() + delta(outcome));
    final int cost = (int) cost(outcome);
    final int step = step(outcome);
    if ((outcome & JUMPED) != 0) {
      // the program counter is at the destination even if the gas then runs out, as in v1
      frame.setPC(frame.getPC() + step);
      return cost < SUCCESS_JUMPED.length
          ? SUCCESS_JUMPED[cost]
          : new OperationResult(cost, null, 0);
    }
    if (step != 1) {
      // the interpreter adds the result's increment of one
      frame.setPC(frame.getPC() + step - 1);
    }
    return cost < SUCCESS.length ? SUCCESS[cost] : new OperationResult(cost, null);
  }
}
