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
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.HIGH_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.JUMPDEST_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.analysedCode;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.done;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.isJumpDestinationAt;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.isZeroWord;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.jumpiResult;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.top;

import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * EVM v2 JUMPI operation using long[] stack representation.
 *
 * <p>Each 256-bit word is stored as four longs: index 0 = most significant 64 bits, index 3 = least
 * significant 64 bits.
 */
public class JumpiOperationV2 extends AbstractFixedCostOperationV2 {

  /**
   * Instantiates a new Jumpi operation.
   *
   * @param gasCalculator the gas calculator
   */
  public JumpiOperationV2(final GasCalculator gasCalculator) {
    super(0x57, "JUMPI", 2, 0, gasCalculator, gasCalculator.getHighTierGasCost());
  }

  @Override
  public OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame);
  }

  /**
   * Performs Jumpi operation.
   *
   * @param frame the frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    final int sp = frame.stackTopV2();
    return jumpiResult(
        frame,
        jumpi(
            frame.stackDataV2(), sp, top(sp), frame.getPC(), analysedCode(frame), false, ANY_GAS));
  }

  /** JUMPI, and when it jumps in the untraced loop also the JUMPDEST it lands on. */
  @InlineInEvmLoop(opcodes = 0x57)
  static long jumpi(
      final long[] s,
      final int sp,
      final int top,
      final int pc,
      final Code codeObject,
      final boolean untraced,
      final long gas) {
    if (sp >= 2 && gas >= HIGH_TIER_GAS) {
      final int d = top;
      // a zero condition does not jump, so the destination is not checked
      if (isZeroWord(s, d - 4)) {
        return done(HIGH_TIER_GAS, 1, -2);
      }
      if (isJumpDestinationAt(s, d, codeObject)) {
        final int destination = (int) s[d + 3];
        if (untraced && gas >= HIGH_TIER_GAS + JUMPDEST_GAS) {
          // no tracer sees the JUMPDEST as an operation of its own
          return done(HIGH_TIER_GAS + JUMPDEST_GAS, destination + 1 - pc, -2);
        }
        return done(HIGH_TIER_GAS, destination - pc, -2);
      }
    }
    return FALLBACK;
  }
}
