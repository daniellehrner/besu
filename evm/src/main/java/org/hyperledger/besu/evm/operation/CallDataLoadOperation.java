/*
 * Copyright contributors to Hyperledger Besu.
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

/** The Call data load operation. */
public class CallDataLoadOperation extends AbstractFixedCostOperation {

  /**
   * Instantiates a new Call data load operation.
   *
   * @param gasCalculator the gas calculator
   */
  public CallDataLoadOperation(final GasCalculator gasCalculator) {
    super(0x35, "CALLDATALOAD", 1, 1, gasCalculator, gasCalculator.getVeryLowTierGasCost());
  }

  @Override
  public OperationResult executeFixedCostOperation(final MessageFrame frame, final EVM evm) {
    final Word256 offsetWord = frame.popStackItem();

    // If offset doesn't fit into int, return zero
    if (offsetWord.bitLength() > 31) {
      frame.pushStackItem(Word256.ZERO);
      return successResponse;
    }

    final int offset = offsetWord.toInt();
    if (offset < 0) {
      frame.pushStackItem(Word256.ZERO);
      return successResponse;
    }

    final byte[] input = frame.getInputData().toArrayUnsafe();
    final byte[] result = new byte[32];

    // Copy as much as is available from input, rest remains zero
    final int available = Math.max(0, Math.min(32, input.length - offset));
    if (available > 0) {
      System.arraycopy(input, offset, result, 0, available);
    }

    frame.pushStackItem(Word256.fromBytes(result));
    return successResponse;
  }
}
