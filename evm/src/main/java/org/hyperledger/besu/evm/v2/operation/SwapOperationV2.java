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
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.top;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;

/**
 * EVM v2 SWAP1-16 operation (opcodes 0x90–0x9F).
 *
 * <p>Swaps the top stack item with the item at depth {@code index} (1-based). Gas cost is veryLow
 * tier (3).
 */
public class SwapOperationV2 extends AbstractFixedCostOperationV2 {

  /** The SWAP opcode base (SWAP1 = 0x90, so base = 0x8F). */
  public static final int SWAP_BASE = 0x8F;

  private final int index;

  /**
   * Instantiates a new Swap operation.
   *
   * @param index the 1-based swap depth (1–16)
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
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame, frame.stackDataV2(), index);
  }

  /**
   * Performs SWAP operation.
   *
   * @param frame the frame
   * @param s the stack data array
   * @param index the 1-based swap depth
   * @return the operation result
   */
  public static OperationResult staticOperation(
      final MessageFrame frame, final long[] s, final int index) {
    final int sp = frame.stackTopV2();
    return result(frame, swap(s, sp, top(sp), SWAP_BASE + index, ANY_GAS), index + 1, index + 1);
  }

  @InlineInEvmLoop(first = 0x90, last = 0x9f)
  static long swap(final long[] s, final int sp, final int top, final int opcode, final long gas) {
    final int depth = opcode - 0x8f;
    if (sp > depth && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final int b = top - (depth << 2);
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
      return done(VERY_LOW_TIER_GAS, 1, 0);
    }
    return FALLBACK;
  }
}
