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
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.LONG_BE;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.VERY_LOW_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.done;

import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.v2.StackArithmetic;

/** The MSTORE operation for EVM v2. */
public class MstoreOperationV2 extends AbstractOperationV2 {

  /**
   * Instantiates a new MSTORE operation.
   *
   * @param gasCalculator the gas calculator
   */
  public MstoreOperationV2(final GasCalculator gasCalculator) {
    super(0x52, "MSTORE", 2, 0, gasCalculator);
  }

  @Override
  public OperationResult execute(final MessageFrame frame, final EVM evm) {
    return staticOperation(frame, frame.stackDataV2(), gasCalculator());
  }

  /**
   * Execute the MSTORE opcode on the v2 long[] stack.
   *
   * @param frame the message frame
   * @param stack the stack operands as a long[] array
   * @param gasCalculator the gas calculator
   * @return the operation result
   */
  public static Operation.OperationResult staticOperation(
      final MessageFrame frame, final long[] stack, final GasCalculator gasCalculator) {
    if (!frame.stackHasItemsV2(2)) {
      return new OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
    }
    final int top = frame.stackTopV2();
    final long location = StackArithmetic.clampedToLong(stack, top, 0);

    final long cost = gasCalculator.mStoreOperationGasCost(frame, location);
    if (frame.getRemainingGas() < cost) {
      return new OperationResult(cost, ExceptionalHaltReason.INSUFFICIENT_GAS);
    }

    frame.writeMemoryWord(location, stack, top, 1);
    frame.setTopV2(top - 2);
    return new OperationResult(cost, null);
  }

  /** MSTORE within the memory already expanded. */
  @InlineInEvmLoop(opcodes = 0x52)
  static long mstore(
      final long[] s, final int sp, final int top, final MessageFrame frame, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final long location = s[a + 3];
      if ((s[a] | s[a + 1] | s[a + 2]) == 0
          && location >= 0
          && location <= frame.memoryByteSize() - 32) {
        final byte[] memory = frame.memoryArrayV2();
        final int i = (int) location;
        LONG_BE.set(memory, i, s[a - 4]);
        LONG_BE.set(memory, i + 8, s[a - 3]);
        LONG_BE.set(memory, i + 16, s[a - 2]);
        LONG_BE.set(memory, i + 24, s[a - 1]);
        return done(VERY_LOW_TIER_GAS, 1, -2);
      }
    }
    return FALLBACK;
  }
}
