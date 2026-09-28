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

import org.hyperledger.besu.evm.V2LoopArms;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;

/**
 * Runs an operation through its arm of the untraced loop in {@link V2LoopArms}, against the state
 * held in the frame. An operation whose arm runs every case in which it succeeds calls the arm, so
 * that the loop and the operation share one implementation and the operation adds only its halts.
 */
final class ArmCall {

  /**
   * The gas to give an arm: whoever calls an operation charges its gas, so the arm must not stop at
   * its own gas check.
   */
  static final long ANY_GAS = Long.MAX_VALUE;

  /** Results by gas cost, which is at most 14 for an arm. */
  private static final OperationResult[] SUCCESS = new OperationResult[16];

  static {
    for (int cost = 0; cost < SUCCESS.length; cost++) {
      SUCCESS[cost] = new OperationResult(cost, null);
    }
  }

  private ArmCall() {}

  /**
   * The index of the top item's first limb, which an arm takes as {@code top}.
   *
   * @param sp the number of items on the stack
   * @return the index
   */
  static int top(final int sp) {
    return (sp - 1) << 2;
  }

  /**
   * The index of the first limb of the slot above the top item, which an arm takes as {@code next}.
   *
   * @param sp the number of items on the stack
   * @return the index
   */
  static int next(final int sp) {
    return sp << 2;
  }

  /**
   * The result of an operation that called its arm. It is a method of its own so that the
   * operations' own methods stay within C2's MaxInlineSize of 35 bytes: the general path's switch
   * calls them, and C2 counts no case of a switch that large as hot.
   *
   * @param frame the frame
   * @param outcome what the arm returned
   * @param consumed the items the operation takes from the stack
   * @param produced the items it leaves there
   * @return the operation's result
   */
  static OperationResult result(
      final MessageFrame frame, final long outcome, final int consumed, final int produced) {
    return outcome != V2LoopArms.FALLBACK ? ran(frame, outcome) : halt(frame, consumed, produced);
  }

  /** Moves the stack and the program counter as an arm that ran its operation says. */
  private static OperationResult ran(final MessageFrame frame, final long outcome) {
    frame.setTopV2(frame.stackTopV2() + V2LoopArms.delta(outcome));
    final int step = V2LoopArms.step(outcome);
    if (step != 1) {
      // the result moves the program counter by one
      frame.setPC(frame.getPC() + step - 1);
    }
    return SUCCESS[(int) V2LoopArms.cost(outcome)];
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
