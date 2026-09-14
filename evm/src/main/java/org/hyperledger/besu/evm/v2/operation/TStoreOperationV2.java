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

import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason.DefaultExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.v2.StackArithmetic;
import org.hyperledger.besu.evm.v2.loop.LoopResult;

import org.apache.tuweni.bytes.Bytes32;

/**
 * EVM v2 TSTORE operation (EIP-1153) using long[] stack representation.
 *
 * <p>Pops a slot key and value from the stack, then writes the value to transient storage. Gas cost
 * is WARM_STORAGE_READ_COST (100) per EIP-1153.
 */
public class TStoreOperationV2 extends AbstractFixedCostOperationV2 {

  private static final long GAS_COST = 100L;
  private static final OperationResult TSTORE_SUCCESS = new OperationResult(GAS_COST, null);
  private static final OperationResult TSTORE_OUT_OF_GAS =
      new OperationResult(GAS_COST, ExceptionalHaltReason.INSUFFICIENT_GAS);

  /**
   * Instantiates a new TStore operation.
   *
   * @param gasCalculator the gas calculator
   */
  public TStoreOperationV2(final GasCalculator gasCalculator) {
    super(0x5D, "TSTORE", 2, 0, gasCalculator, gasCalculator.getTransientStoreOperationGasCost());
  }

  @Override
  public OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame, frame.stackDataV2());
  }

  /**
   * Execute the TSTORE opcode on the v2 long[] stack.
   *
   * @param frame the message frame
   * @param s the stack as a long[] array
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame, final long[] s) {
    if (!frame.stackHasItemsV2(2)) {
      return UNDERFLOW_RESPONSE;
    }

    final int top = frame.stackTopV2();

    final byte[] keyBytes = new byte[32];
    final byte[] valueBytes = new byte[32];
    StackArithmetic.toBytesAt(s, top, 0, keyBytes);
    StackArithmetic.toBytesAt(s, top, 1, valueBytes);

    frame.setTopV2(top - 2);

    final long remainingGas = frame.getRemainingGas();
    if (frame.isStatic()) {
      return new OperationResult(remainingGas, ExceptionalHaltReason.ILLEGAL_STATE_CHANGE);
    }

    if (remainingGas < GAS_COST) {
      return TSTORE_OUT_OF_GAS;
    }

    final Bytes32 slot = Bytes32.wrap(keyBytes);
    final Bytes32 value = Bytes32.wrap(valueBytes);

    frame.setTransientStorageValue(frame.getRecipientAddress(), slot, value);

    return TSTORE_SUCCESS;
  }

  /**
   * Executes the operation on the table loop's contract: the loop has checked the stack and owns
   * the program counter, the gas and the stack top. The gas is charged here, after the static
   * check, so that a static frame halts for the state change and not for gas.
   *
   * @param frame the frame
   * @param s the stack data
   * @param top the stack top
   * @param pc the program counter of this operation
   * @param gas the gas remaining before this operation
   * @return the packed result
   */
  public static long exec(
      final MessageFrame frame, final long[] s, final int top, final int pc, final long gas) {
    if (frame.isStatic()) {
      return LoopResult.halt(DefaultExceptionalHaltReason.ILLEGAL_STATE_CHANGE);
    }
    if (gas < GAS_COST) {
      return LoopResult.halt(DefaultExceptionalHaltReason.INSUFFICIENT_GAS);
    }
    final byte[] keyBytes = new byte[32];
    final byte[] valueBytes = new byte[32];
    StackArithmetic.toBytesAt(s, top, 0, keyBytes);
    StackArithmetic.toBytesAt(s, top, 1, valueBytes);
    frame.setTransientStorageValue(
        frame.getRecipientAddress(), Bytes32.wrap(keyBytes), Bytes32.wrap(valueBytes));
    return LoopResult.ok(top - 2, pc + 1, GAS_COST);
  }
}
