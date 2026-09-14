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

import static org.hyperledger.besu.evm.internal.Words.clampedAdd;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason.DefaultExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.v2.StackArithmetic;
import org.hyperledger.besu.evm.v2.loop.LoopResult;

import org.apache.tuweni.bytes.Bytes;

/**
 * EVM v2 EXTCODECOPY operation (0x3C).
 *
 * <p>Pops address, destOffset, sourceOffset, and size from the stack, applies warm/cold account
 * access cost, and copies the external contract's code into memory.
 */
public class ExtCodeCopyOperationV2 extends AbstractOperationV2 {

  /**
   * Instantiates a new ExtCodeCopy operation.
   *
   * @param gasCalculator the gas calculator
   */
  public ExtCodeCopyOperationV2(final GasCalculator gasCalculator) {
    super(0x3C, "EXTCODECOPY", 4, 0, gasCalculator);
  }

  /**
   * Cost of EXTCODECOPY including warm/cold access.
   *
   * @param frame the frame
   * @param memOffset the memory destination offset
   * @param length the number of bytes to copy
   * @param accountIsWarm whether the address was already warm
   * @return the total gas cost
   */
  protected long cost(
      final MessageFrame frame,
      final long memOffset,
      final long length,
      final boolean accountIsWarm) {
    return clampedAdd(
        gasCalculator().extCodeCopyOperationGasCost(frame, memOffset, length),
        accountIsWarm
            ? gasCalculator().getWarmStorageReadCost()
            : gasCalculator().getColdAccountAccessCost());
  }

  @Override
  public OperationResult execute(final MessageFrame frame, final EVM evm) {
    return staticOperation(frame, frame.stackDataV2(), gasCalculator());
  }

  /**
   * Execute EXTCODECOPY on the v2 long[] stack.
   *
   * @param frame the message frame
   * @param s the stack data
   * @param gasCalculator the gas calculator
   * @return the operation result
   */
  public static OperationResult staticOperation(
      final MessageFrame frame, final long[] s, final GasCalculator gasCalculator) {
    if (!frame.stackHasItemsV2(4)) {
      return new OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
    }
    final int top = frame.stackTopV2();
    final Address address = StackArithmetic.toAddressAt(s, top, 0);
    final long memOffset = StackArithmetic.clampedToLong(s, top, 1);
    final long sourceOffset = StackArithmetic.clampedToLong(s, top, 2);
    final long numBytes = StackArithmetic.clampedToLong(s, top, 3);
    frame.setTopV2(top - 4);

    final boolean accountIsWarm =
        frame.warmUpAddress(address) || gasCalculator.isPrecompile(address);
    final long cost =
        clampedAdd(
            gasCalculator.extCodeCopyOperationGasCost(frame, memOffset, numBytes),
            accountIsWarm
                ? gasCalculator.getWarmStorageReadCost()
                : gasCalculator.getColdAccountAccessCost());

    if (frame.getRemainingGas() < cost) {
      return new OperationResult(cost, ExceptionalHaltReason.INSUFFICIENT_GAS);
    }

    final Account account = getAccount(address, frame);
    final Bytes code = account != null ? account.getCode() : Bytes.EMPTY;

    frame.writeMemory(memOffset, sourceOffset, numBytes, code);

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
    final Address address = StackArithmetic.toAddressAt(s, top, 0);
    final long memOffset = StackArithmetic.clampedToLong(s, top, 1);
    final long sourceOffset = StackArithmetic.clampedToLong(s, top, 2);
    final long numBytes = StackArithmetic.clampedToLong(s, top, 3);
    final boolean accountIsWarm =
        frame.warmUpAddress(address) || gasCalculator.isPrecompile(address);
    final long cost =
        clampedAdd(
            gasCalculator.extCodeCopyOperationGasCost(frame, memOffset, numBytes),
            accountIsWarm
                ? gasCalculator.getWarmStorageReadCost()
                : gasCalculator.getColdAccountAccessCost());
    if (gas < cost) {
      return LoopResult.halt(DefaultExceptionalHaltReason.INSUFFICIENT_GAS);
    }
    final Account account = getAccount(address, frame);
    frame.writeMemory(
        memOffset, sourceOffset, numBytes, account != null ? account.getCode() : Bytes.EMPTY);
    return LoopResult.ok(top - 4, pc + 1, cost);
  }
}
