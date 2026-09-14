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
import org.hyperledger.besu.evm.v2.StackArithmetic;
import org.hyperledger.besu.evm.v2.loop.LoopResult;

import org.apache.tuweni.bytes.Bytes;

/** EVM v2 REVERT operation using long[] stack representation. */
public class RevertOperationV2 extends AbstractOperationV2 {

  /** REVERT opcode number */
  public static final int OPCODE = 0xFD;

  /**
   * Instantiates a new Revert operation.
   *
   * @param gasCalculator the gas calculator
   */
  public RevertOperationV2(final GasCalculator gasCalculator) {
    super(OPCODE, "REVERT", 2, 0, gasCalculator);
  }

  @Override
  public OperationResult execute(final MessageFrame frame, final EVM evm) {
    return staticOperation(frame, frame.stackDataV2(), gasCalculator());
  }

  /**
   * Performs Revert operation.
   *
   * @param frame the frame
   * @param s the stack data array
   * @param gasCalculator the gas calculator
   * @return the operation result
   */
  public static OperationResult staticOperation(
      final MessageFrame frame, final long[] s, final GasCalculator gasCalculator) {
    if (!frame.stackHasItemsV2(2)) {
      return new OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
    }
    final int top = frame.stackTopV2();
    final long from = StackArithmetic.clampedToLong(s, top, 0);
    final long length = StackArithmetic.clampedToLong(s, top, 1);
    frame.setTopV2(top - 2);

    final long cost = gasCalculator.memoryExpansionGasCost(frame, from, length);
    if (frame.getRemainingGas() < cost) {
      return new OperationResult(cost, ExceptionalHaltReason.INSUFFICIENT_GAS);
    }

    final Bytes reason = frame.readMemory(from, length);
    frame.setOutputData(reason);
    frame.setRevertReason(reason);
    frame.setState(MessageFrame.State.REVERT);
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
    final long from = StackArithmetic.clampedToLong(s, top, 0);
    final long length = StackArithmetic.clampedToLong(s, top, 1);
    final long cost = gasCalculator.memoryExpansionGasCost(frame, from, length);
    if (gas < cost) {
      return LoopResult.halt(DefaultExceptionalHaltReason.INSUFFICIENT_GAS);
    }
    final Bytes reason = frame.readMemory(from, length);
    frame.setOutputData(reason);
    frame.setRevertReason(reason);
    frame.setState(MessageFrame.State.REVERT);
    return LoopResult.ok(top - 2, pc + 1, cost);
  }
}
