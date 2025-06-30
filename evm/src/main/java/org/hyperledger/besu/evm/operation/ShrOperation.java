/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.evm.operation;

import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.word256.Word256;

/** The Shr (Shift Right) operation. */
public class ShrOperation extends AbstractFixedCostOperation {

  /** The Shr operation success result. */
  static final OperationResult shrSuccess = new OperationResult(3, null);

  /**
   * Instantiates a new Shr operation.
   *
   * @param gasCalculator the gas calculator
   */
  public ShrOperation(final GasCalculator gasCalculator) {
    super(0x1c, "SHR", 2, 1, gasCalculator, gasCalculator.getVeryLowTierGasCost());
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(
      final MessageFrame frame, final EVM evm) {
    return staticOperation(frame);
  }

  /**
   * Performs SHR operation.
   *
   * @param frame the frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    final Word256 shiftAmount = frame.popStackItem();
    final Word256 value = frame.popStackItem();

    final int shiftBits = shiftAmount.toInt();
    final Word256 result =
        (shiftBits >= 256 || shiftBits < 0) ? Word256.ZERO : value.shr(shiftBits);

    frame.pushStackItem(result);
    return shrSuccess;
  }
}
