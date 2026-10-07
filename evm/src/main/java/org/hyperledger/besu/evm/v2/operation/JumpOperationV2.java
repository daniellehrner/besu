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
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.JUMPDEST_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.MID_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.done;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.isJumpDestinationV2;

import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;

/** EVM v2 JUMP operation using long[] stack representation. */
public class JumpOperationV2 extends AbstractFixedCostOperationV2 {

  private static final OperationResult INVALID_JUMP_RESPONSE =
      new OperationResult(8L, ExceptionalHaltReason.INVALID_JUMP_DESTINATION);
  private static final OperationResult JUMP_RESPONSE = new OperationResult(8L, null, 0);

  /**
   * Instantiates a new Jump operation.
   *
   * @param gasCalculator the gas calculator
   */
  public JumpOperationV2(final GasCalculator gasCalculator) {
    super(0x56, "JUMP", 1, 0, gasCalculator, gasCalculator.getMidTierGasCost());
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame, frame.stackDataV2());
  }

  /**
   * Performs Jump operation.
   *
   * @param frame the frame
   * @param s the stack data array
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame, final long[] s) {
    if (!frame.stackHasItemsV2(1)) return UNDERFLOW_RESPONSE;
    final int top = frame.stackTopV2();
    final int destOff = (top - 1) << 2;
    frame.setTopV2(top - 1);
    return performJump(frame, s, destOff, JUMP_RESPONSE, INVALID_JUMP_RESPONSE);
  }

  static OperationResult performJump(
      final MessageFrame frame,
      final long[] s,
      final int off,
      final OperationResult validResponse,
      final OperationResult invalidResponse) {
    // Destination must fit in a non-negative int (code size is bounded by int).
    if (s[off] != 0
        || s[off + 1] != 0
        || s[off + 2] != 0
        || s[off + 3] < 0
        || s[off + 3] > Integer.MAX_VALUE) {
      return invalidResponse;
    }
    final int jumpDestination = (int) s[off + 3];
    if (frame.getCode().isJumpDestInvalid(jumpDestination)) {
      return invalidResponse;
    }
    frame.setPC(jumpDestination);
    return validResponse;
  }

  /** JUMP, and the JUMPDEST it lands on. */
  @InlineInEvmLoop(opcodes = 0x56)
  static long jump(
      final long[] s,
      final int sp,
      final int top,
      final int pc,
      final byte[] code,
      final Code codeObject,
      final long gas) {
    if (sp >= 1 && gas >= MID_TIER_GAS + JUMPDEST_GAS) {
      final int d = top;
      final long destination = s[d + 3];
      if ((s[d] | s[d + 1] | s[d + 2]) == 0
          && isJumpDestinationV2(codeObject.getJumpDestBitMask(), destination, code.length)) {
        return done(MID_TIER_GAS + JUMPDEST_GAS, (int) destination + 1 - pc, -1);
      }
    }
    return FALLBACK;
  }
}
