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

import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.FALLBACK;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.HIGH_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.JUMPDEST_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.done;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.isJumpDestinationV2;

import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;

/** EVM v2 JUMPI (conditional jump) operation using long[] stack representation. */
public class JumpiOperationV2 extends AbstractFixedCostOperationV2 {

  private static final OperationResult INVALID_JUMP_RESPONSE =
      new OperationResult(10L, ExceptionalHaltReason.INVALID_JUMP_DESTINATION);
  private static final OperationResult JUMPI_RESPONSE = new OperationResult(10L, null, 0);
  private static final OperationResult NOJUMP_RESPONSE = new OperationResult(10L, null);

  /**
   * Instantiates a new JUMPI operation.
   *
   * @param gasCalculator the gas calculator
   */
  public JumpiOperationV2(final GasCalculator gasCalculator) {
    super(0x57, "JUMPI", 2, 0, gasCalculator, gasCalculator.getHighTierGasCost());
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame, frame.stackDataV2());
  }

  /**
   * Performs JUMPI operation.
   *
   * @param frame the frame
   * @param s the stack data array
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame, final long[] s) {
    if (!frame.stackHasItemsV2(2)) return UNDERFLOW_RESPONSE;
    final int top = frame.stackTopV2();
    final int destOff = (top - 1) << 2;
    final int condOff = (top - 2) << 2;
    frame.setTopV2(top - 2);

    // If condition is zero (false), no jump will be performed.
    if (s[condOff] == 0 && s[condOff + 1] == 0 && s[condOff + 2] == 0 && s[condOff + 3] == 0) {
      return NOJUMP_RESPONSE;
    }

    return JumpOperationV2.performJump(frame, s, destOff, JUMPI_RESPONSE, INVALID_JUMP_RESPONSE);
  }

  /** JUMPI, and the JUMPDEST it lands on. */
  @InlineInEvmLoop(opcodes = 0x57)
  static long jumpi(
      final long[] s,
      final int sp,
      final int top,
      final int pc,
      final byte[] code,
      final Code codeObject,
      final long gas) {
    if (sp >= 2 && gas >= HIGH_TIER_GAS) {
      final int d = top;
      final int c = d - 4;
      if ((s[c] | s[c + 1] | s[c + 2] | s[c + 3]) == 0) {
        return done(HIGH_TIER_GAS, 1, -2);
      }
      final long destination = s[d + 3];
      if ((s[d] | s[d + 1] | s[d + 2]) == 0
          && gas >= HIGH_TIER_GAS + JUMPDEST_GAS
          && isJumpDestinationV2(codeObject.getJumpDestBitMask(), destination, code.length)) {
        return done(HIGH_TIER_GAS + JUMPDEST_GAS, (int) destination + 1 - pc, -2);
      }
    }
    return FALLBACK;
  }
}
