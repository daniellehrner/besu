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
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.LOW_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.done;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.result;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.top;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * EVM v2 SIGNEXTEND operation using long[] stack representation.
 *
 * <p>Each 256-bit word is stored as four longs: index 0 = most significant 64 bits, index 3 = least
 * significant 64 bits.
 */
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
  public OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame);
  }

  /**
   * Execute the SIGNEXTEND opcode on the v2 long[] stack.
   *
   * <p>SIGNEXTEND: extends the sign of the byte at index stack[top-1], counted from the least
   * significant byte, of stack[top-2] into the more significant bytes, return top-1.
   *
   * @param frame the message frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    final int sp = frame.stackTopV2();
    return result(frame, signExtend(frame.stackDataV2(), sp, top(sp), ANY_GAS), UNDERFLOW_RESPONSE);
  }

  /**
   * SIGNEXTEND: extends the sign of the byte the top item indexes, from the least significant, of
   * the item below it, which the result replaces.
   */
  @InlineInEvmLoop(opcodes = 0x0b)
  static long signExtend(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= LOW_TIER_GAS) {
      final int index = top;
      final int value = index - 4;
      // From byte 31 on the sign bit is the top bit of the word, so the value stays as it is.
      if ((s[index] | s[index + 1] | s[index + 2]) == 0
          && Long.compareUnsigned(s[index + 3], 31) < 0) {
        final int signBit = ((int) s[index + 3] << 3) + 7;
        // 0 for the least significant limb u0, 3 for the most significant limb u3
        final int signLimbIndex = signBit >>> 6;
        final int signLimb = value + 3 - signLimbIndex;
        final int bitInLimb = signBit & 63;
        final long fill = ((s[signLimb] >>> bitInLimb) & 1L) == 0 ? 0L : -1L;
        if (bitInLimb < 63) {
          final long kept = -1L >>> (63 - bitInLimb);
          s[signLimb] = (s[signLimb] & kept) | (fill & ~kept);
        }
        if (signLimbIndex < 3) {
          s[value] = fill;
        }
        if (signLimbIndex < 2) {
          s[value + 1] = fill;
        }
        if (signLimbIndex < 1) {
          s[value + 2] = fill;
        }
      }
      return done(LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }
}
