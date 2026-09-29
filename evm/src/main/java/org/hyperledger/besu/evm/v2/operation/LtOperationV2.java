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

/** The Lt operation. */
public class LtOperationV2 extends AbstractFixedCostOperationV2 {

  /**
   * Instantiates a new Lt operation.
   *
   * @param gasCalculator the gas calculator
   */
  public LtOperationV2(final GasCalculator gasCalculator) {
    super(0x10, "LT", 2, 1, gasCalculator, gasCalculator.getVeryLowTierGasCost());
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame, frame.stackDataV2());
  }

  /**
   * Performs lt operation.
   *
   * @param frame the frame
   * @param stack the v2 operand stack ({@code long[]} in big-endian limb order)
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame, final long[] stack) {
    final int sp = frame.stackTopV2();
    return result(frame, compare(stack, sp, top(sp), 0x10, ANY_GAS), 2, 1);
  }

  /** LT, GT, SLT and SGT. */
  @InlineInEvmLoop(first = 0x10, last = 0x13)
  static long compare(
      final long[] s, final int sp, final int top, final int opcode, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final int b = a - 4;
      // the most significant limb in which the two differ, or the least significant one
      final int i = s[a] != s[b] ? 0 : s[a + 1] != s[b + 1] ? 1 : s[a + 2] != s[b + 2] ? 2 : 3;
      long x = s[a + i];
      long y = s[b + i];
      if (i == 0 && opcode >= 0x12) {
        // signed: flipping the sign bits makes the unsigned comparison a signed one
        x ^= Long.MIN_VALUE;
        y ^= Long.MIN_VALUE;
      }
      final int comparison = Long.compareUnsigned(x, y);
      s[b] = 0;
      s[b + 1] = 0;
      s[b + 2] = 0;
      // LT and SLT are the even opcodes, GT and SGT the odd ones
      s[b + 3] = ((opcode & 1) == 0 ? comparison < 0 : comparison > 0) ? 1L : 0L;
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }
}
