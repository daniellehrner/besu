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

import static org.hyperledger.besu.evm.v2.operation.Arms.FALLBACK;
import static org.hyperledger.besu.evm.v2.operation.Arms.LONG_BE;
import static org.hyperledger.besu.evm.v2.operation.Arms.VERY_LOW_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.Arms.done;

import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.v2.StackArithmetic;

/** The MLOAD operation for EVM v2. */
public class MloadOperationV2 extends AbstractOperationV2 {

  /**
   * Instantiates a new MLOAD operation.
   *
   * @param gasCalculator the gas calculator
   */
  public MloadOperationV2(final GasCalculator gasCalculator) {
    super(0x51, "MLOAD", 1, 1, gasCalculator);
  }

  @Override
  public OperationResult execute(final MessageFrame frame, final EVM evm) {
    return staticOperation(frame, frame.stackDataV2(), gasCalculator());
  }

  /**
   * Execute the MLOAD opcode on the v2 long[] stack.
   *
   * @param frame the message frame
   * @param stack the stack operands as a long[] array
   * @param gasCalculator the gas calculator
   * @return the operation result
   */
  public static Operation.OperationResult staticOperation(
      final MessageFrame frame, final long[] stack, final GasCalculator gasCalculator) {
    if (!frame.stackHasItemsV2(1)) {
      return new OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
    }
    final int top = frame.stackTopV2();
    final long location = StackArithmetic.clampedToLong(stack, top, 0);

    final long cost = gasCalculator.mLoadOperationGasCost(frame, location);
    if (frame.getRemainingGas() < cost) {
      return new OperationResult(cost, ExceptionalHaltReason.INSUFFICIENT_GAS);
    }

    frame.readMemoryWord(location, stack, top, 0);
    return new OperationResult(cost, null);
  }

  /**
   * MLOAD and CALLDATALOAD, which both read a word from an array into the top item: MLOAD from
   * memory already expanded, CALLDATALOAD from the input data unless the word straddles its end.
   * They share an arm because each read inlines a chain of VarHandle methods into the loop.
   */
  @Arm(
      rank = 14,
      opcodes = {0x51, 0x35})
  static long loadWord(
      final long[] s,
      final int sp,
      final int top,
      final int opcode,
      final MessageFrame frame,
      final long gas) {
    if (sp >= 1 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final long location = s[a + 3];
      final boolean small = (s[a] | s[a + 1] | s[a + 2]) == 0 && location >= 0;
      final byte[] source;
      final boolean readable;
      if (opcode == 0x51) {
        source = frame.memoryArrayV2();
        readable = small && location <= frame.memoryByteSize() - 32;
      } else {
        source = frame.inputDataArrayIfPresent();
        if (source != null && (!small || location >= source.length)) {
          // past the end of the input
          s[a] = 0;
          s[a + 1] = 0;
          s[a + 2] = 0;
          s[a + 3] = 0;
          return done(VERY_LOW_TIER_GAS, 1, 0);
        }
        readable = source != null && source.length - location >= 32;
      }
      if (readable) {
        final int i = (int) location;
        s[a] = (long) LONG_BE.get(source, i);
        s[a + 1] = (long) LONG_BE.get(source, i + 8);
        s[a + 2] = (long) LONG_BE.get(source, i + 16);
        s[a + 3] = (long) LONG_BE.get(source, i + 24);
        return done(VERY_LOW_TIER_GAS, 1, 0);
      }
    }
    return FALLBACK;
  }
}
