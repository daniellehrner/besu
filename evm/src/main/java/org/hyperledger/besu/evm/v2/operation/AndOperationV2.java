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

/** EVM v2 AND operation (0x16). */
public class AndOperationV2 extends AbstractFixedCostOperationV2 {

  /**
   * Instantiates a new And operation.
   *
   * @param gasCalculator the gas calculator
   */
  public AndOperationV2(final GasCalculator gasCalculator) {
    super(0x16, "AND", 2, 1, gasCalculator, gasCalculator.getVeryLowTierGasCost());
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame, frame.stackDataV2());
  }

  /**
   * Performs AND operation.
   *
   * @param frame the frame
   * @param stack the v2 operand stack ({@code long[]} in big-endian limb order)
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame, final long[] stack) {
    final int sp = frame.stackTopV2();
    return result(frame, and(stack, sp, top(sp), ANY_GAS), 2, 1);
  }

  @InlineInEvmLoop(opcodes = 0x16)
  static long and(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      s[a - 4] &= s[a];
      s[a - 3] &= s[a + 1];
      s[a - 2] &= s[a + 2];
      s[a - 1] &= s[a + 3];
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }
}
