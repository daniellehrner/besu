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

import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.ANY_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.FALLBACK;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.VERY_LOW_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.done;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.result;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.swapWords;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.top;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * EVM v2 SWAP1-16 operation using long[] stack representation.
 *
 * <p>Each 256-bit word is stored as four longs: index 0 = most significant 64 bits, index 3 = least
 * significant 64 bits.
 */
public class SwapOperationV2 extends AbstractFixedCostOperationV2 {

  /** The constant SWAP_BASE: SWAPn has opcode SWAP_BASE + n. */
  public static final int SWAP_BASE = 0x8F;

  private final int index;

  /**
   * Instantiates a new Swap operation.
   *
   * @param index the n of SWAPn, 1-16; the top is exchanged with the item at depth n + 1
   * @param gasCalculator the gas calculator
   */
  public SwapOperationV2(final int index, final GasCalculator gasCalculator) {
    super(
        SWAP_BASE + index,
        "SWAP" + index,
        index + 1,
        index + 1,
        gasCalculator,
        gasCalculator.getVeryLowTierGasCost());
    this.index = index;
  }

  @Override
  public OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame, index);
  }

  /**
   * Execute the SWAPn opcode on the v2 long[] stack.
   *
   * <p>SWAPn: exchange stack[top-1] and stack[top-1-n], top unchanged.
   *
   * @param frame the message frame
   * @param index the n of SWAPn; the top is exchanged with the item at depth n + 1
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame, final int index) {
    final int sp = frame.stackTopV2();
    return result(
        frame,
        swap(frame.stackDataV2(), sp, top(sp), SWAP_BASE + index, ANY_GAS),
        UNDERFLOW_RESPONSE);
  }

  @InlineInEvmLoop(first = 0x90, last = 0x9f)
  static long swap(final long[] s, final int sp, final int top, final int opcode, final long gas) {
    final int depth = opcode - 0x8f;
    if (sp > depth && gas >= VERY_LOW_TIER_GAS) {
      swapWords(s, top, top - (depth << 2));
      return done(VERY_LOW_TIER_GAS, 1, 0);
    }
    return FALLBACK;
  }
}
