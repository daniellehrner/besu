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

import static org.hyperledger.besu.evm.v2.operation.Arms.ANY_GAS;
import static org.hyperledger.besu.evm.v2.operation.Arms.FALLBACK;
import static org.hyperledger.besu.evm.v2.operation.Arms.LOW_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.Arms.done;
import static org.hyperledger.besu.evm.v2.operation.Arms.result;
import static org.hyperledger.besu.evm.v2.operation.Arms.top;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;

/** EVM v2 SIGNEXTEND operation (0x0B). */
public class SignExtendOperationV2 extends AbstractFixedCostOperationV2 {

  /**
   * Instantiates a new SignExtend operation.
   *
   * @param gasCalculator the gas calculator
   */
  public SignExtendOperationV2(final GasCalculator gasCalculator) {
    super(0x0B, "SIGNEXTEND", 2, 1, gasCalculator, gasCalculator.getLowTierGasCost());
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame, frame.stackDataV2());
  }

  /**
   * Performs SIGNEXTEND operation.
   *
   * @param frame the frame
   * @param stack the v2 operand stack ({@code long[]} in big-endian limb order)
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame, final long[] stack) {
    final int sp = frame.stackTopV2();
    return result(frame, signextend(stack, sp, top(sp), ANY_GAS), 2, 1);
  }

  @Arm(rank = 26, opcodes = 0x0b)
  static long signextend(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= LOW_TIER_GAS) {
      final int a = top;
      final int v = a - 4;
      final long index = s[a + 3];
      if ((s[a] | s[a + 1] | s[a + 2]) == 0 && index >= 0 && index < 31) {
        // the sign bit and the limb holding it, limbs counted from the least significant
        final int signBit = (int) index * 8 + 7;
        final int limb = v + 3 - (signBit >>> 6);
        final long below = -1L >>> (63 - (signBit & 63));
        final long fill = (s[limb] >>> (signBit & 63) & 1L) == 0 ? 0L : -1L;
        s[limb] = (s[limb] & below) | (fill & ~below);
        // and every limb above it
        if (limb > v) {
          s[v] = fill;
        }
        if (limb > v + 1) {
          s[v + 1] = fill;
        }
        if (limb > v + 2) {
          s[v + 2] = fill;
        }
      }
      return done(LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }
}
