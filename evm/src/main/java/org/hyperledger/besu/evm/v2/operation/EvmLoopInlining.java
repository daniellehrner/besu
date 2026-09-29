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

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * What the arms of the untraced EVM v2 loop, marked with {@link InlineInEvmLoop}, are written with:
 * the constants and helpers they may name, which EVM.java imports from here too, and the way an
 * operation class runs its arm against the state held in the frame.
 */
public final class EvmLoopInlining {

  // The costs the loops charge for the operations they run inline, the same numbers the operations'
  // results carry. Constants rather than fields of the gas calculator: as fields they are loads C2
  // hoists to the loop's dispatch, where they run for every operation.

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

  /** Reads and writes a big-endian word of a byte array, as memory and input data hold them. */
  public static final VarHandle LONG_BE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

  /** What an arm returns when it leaves the operation to its class. */
  static final long FALLBACK = -1L;

  /**
   * The gas to give an arm from an operation class: whoever calls an operation charges its gas, so
   * the arm must not stop at its own gas check.
   */
  static final long ANY_GAS = Long.MAX_VALUE;

  /** Results by gas cost, which is at most 14 for an arm. */
  private static final OperationResult[] SUCCESS = new OperationResult[16];

  static {
    for (int cost = 0; cost < SUCCESS.length; cost++) {
      SUCCESS[cost] = new OperationResult(cost, null);
    }
  }

  private EvmLoopInlining() {}

  /**
   * Whether a destination is a JUMPDEST of the code. The arms call it, so it has to stay within
   * C2's MaxInlineSize of 35 bytes of bytecode; one unsigned comparison is the range check {@code 0
   * <= destination < codeSize}.
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

  /** What an arm returns when it has run the operation: the gas, the pc's step, the stack delta. */
  static long done(final long cost, final int step, final int delta) {
    return cost << 32 | (step & 0xffffffL) << 8 | (delta & 0xffL);
  }

  static long cost(final long outcome) {
    return outcome >>> 32;
  }

  static int step(final long outcome) {
    return (int) outcome >> 8;
  }

  static int delta(final long outcome) {
    return (byte) outcome;
  }

  /** The index of the top item's first limb, which an arm takes as {@code top}. */
  static int top(final int sp) {
    return (sp - 1) << 2;
  }

  /** The index of the first limb of the slot above the top item, which an arm takes as next. */
  static int next(final int sp) {
    return sp << 2;
  }

  /**
   * The result of an operation that called its arm. It is a method of its own so that the
   * operations' own methods stay within C2's MaxInlineSize of 35 bytes: the general path's switch
   * calls them, and C2 counts no case of a switch that large as hot.
   */
  static OperationResult result(
      final MessageFrame frame, final long outcome, final int consumed, final int produced) {
    return outcome != FALLBACK ? ran(frame, outcome) : halt(frame, consumed, produced);
  }

  /** Moves the stack and the program counter as an arm that ran its operation says. */
  private static OperationResult ran(final MessageFrame frame, final long outcome) {
    frame.setTopV2(frame.stackTopV2() + delta(outcome));
    final int step = step(outcome);
    if (step != 1) {
      // the result moves the program counter by one
      frame.setPC(frame.getPC() + step - 1);
    }
    return SUCCESS[(int) cost(outcome)];
  }

  /**
   * The halt of an operation whose arm did not run it. Given all the gas it wants, an arm stops
   * only for a stack it cannot use.
   */
  private static OperationResult halt(
      final MessageFrame frame, final int consumed, final int produced) {
    if (!frame.stackHasItemsV2(consumed)) {
      return UNDERFLOW_RESPONSE;
    }
    if (!frame.stackHasSpaceV2(produced - consumed)) {
      return OVERFLOW_RESPONSE;
    }
    throw new IllegalStateException("an arm did not run an operation it should have run");
  }
}
