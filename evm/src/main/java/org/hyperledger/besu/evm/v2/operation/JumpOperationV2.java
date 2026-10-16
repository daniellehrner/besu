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
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.JUMPDEST_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.MID_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.analysedCode;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.isJumpDestinationAt;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.jumpResult;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.jumped;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.top;

import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * EVM v2 JUMP operation using long[] stack representation.
 *
 * <p>Each 256-bit word is stored as four longs: index 0 = most significant 64 bits, index 3 = least
 * significant 64 bits.
 */
public class JumpOperationV2 extends AbstractFixedCostOperationV2 {

  /**
   * Instantiates a new Jump operation.
   *
   * @param gasCalculator the gas calculator
   */
  public JumpOperationV2(final GasCalculator gasCalculator) {
    super(0x56, "JUMP", 1, 0, gasCalculator, gasCalculator.getMidTierGasCost());
  }

  @Override
  public OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame);
  }

  /**
   * Execute the JUMP opcode on the v2 long[] stack.
   *
   * <p>JUMP: pc = stack[top-1] if it is a valid jump destination, return top-1.
   *
   * @param frame the message frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    final int sp = frame.stackTopV2();
    return jumpResult(
        frame,
        jump(frame.stackDataV2(), sp, top(sp), frame.getPC(), analysedCode(frame), false, ANY_GAS));
  }

  /** JUMP, and in the untraced loop also the JUMPDEST it lands on. */
  @InlineInEvmLoop(opcodes = 0x56)
  static long jump(
      final long[] s,
      final int sp,
      final int top,
      final int pc,
      final Code codeObject,
      final boolean untraced,
      final long gas) {
    if (sp >= 1 && gas >= MID_TIER_GAS) {
      final int d = top;
      if (isJumpDestinationAt(s, d, codeObject)) {
        final int destination = (int) s[d + 3];
        if (untraced && gas >= MID_TIER_GAS + JUMPDEST_GAS) {
          // no tracer sees the JUMPDEST as an operation of its own
          return jumped(MID_TIER_GAS + JUMPDEST_GAS, destination + 1 - pc, -1);
        }
        return jumped(MID_TIER_GAS, destination - pc, -1);
      }
    }
    return FALLBACK;
  }
}
