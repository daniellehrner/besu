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

import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason.DefaultExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.v2.StackArithmetic;
import org.hyperledger.besu.evm.v2.loop.LoopResult;

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

  /**
   * Executes the operation on the table loop's contract: the loop has checked the stack and owns
   * the program counter, the gas and the stack top.
   *
   * @param frame the frame
   * @param s the stack data
   * @param top the stack top
   * @param pc the program counter of this operation
   * @param gas the gas remaining before this operation
   * @param gasCalculator the gas calculator
   * @return the packed result
   */
  public static long exec(
      final MessageFrame frame,
      final long[] s,
      final int top,
      final int pc,
      final long gas,
      final GasCalculator gasCalculator) {
    final long location = StackArithmetic.clampedToLong(s, top, 0);
    final long cost = gasCalculator.mStoreOperationGasCost(frame, location);
    if (gas < cost) {
      return LoopResult.halt(DefaultExceptionalHaltReason.INSUFFICIENT_GAS);
    }
    frame.writeMemoryWord(location, s, top, 1);
    return LoopResult.ok(top - 2, pc + 1, cost);
  }
}
