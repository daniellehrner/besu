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

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;

/** The Shr (Shift Right) operation. */
public class ShrOperationV2 extends AbstractFixedCostOperationV2 {

  /** The Shr operation success result. */
  static final OperationResult shrSuccess = new OperationResult(3, null);

  /**
   * Instantiates a new Shr operation.
   *
   * @param gasCalculator the gas calculator
   */
  public ShrOperationV2(final GasCalculator gasCalculator) {
    super(0x1c, "SHR", 2, 1, gasCalculator, gasCalculator.getVeryLowTierGasCost());
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame);
  }

  /**
   * Performs SHR operation.
   *
   * @param frame the frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    if (!frame.stackHasItemsV2(2)) return UNDERFLOW_RESPONSE;
    long[] stack = frame.stackDataV2();
    int top = frame.stackTopV2();
    final int shiftOffset = (--top) << 2;
    final int shift = StackArithmetic.shiftAmount(stack, shiftOffset);
    final int valueOffset = (--top) << 2;
    final long v3 = stack[valueOffset];
    StackArithmetic.sar(
        stack,
        valueOffset,
        v3,
        stack[valueOffset + 1],
        stack[valueOffset + 2],
        stack[valueOffset + 3],
        shift,
        0);
    frame.setTopV2(++top);
    return shrSuccess;
  }
}
