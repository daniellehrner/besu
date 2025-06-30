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

/** The Byte operation. */
public class ByteOperation extends AbstractFixedCostOperation {

  /** The Byte operation success result. */
  static final OperationResult byteSuccess = new OperationResult(3, null);

  /**
   * Instantiates a new Byte operation.
   *
   * @param gasCalculator the gas calculator
   */
  public ByteOperation(final GasCalculator gasCalculator) {
    super(0x1A, "BYTE", 2, 1, gasCalculator, gasCalculator.getVeryLowTierGasCost());
  }

  @Override
  public OperationResult executeFixedCostOperation(final MessageFrame frame, final EVM evm) {
    return staticOperation(frame);
  }

  /**
   * Static Byte operation.
   *
   * <p>Pops index and value from the stack, and pushes the byte at the index in the value.
   *
   * <p>If the index is ≥ 32, pushes zero.
   *
   * @param frame the message frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    final Word256 index = frame.popStackItem();
    final Word256 value = frame.popStackItem();

    final byte resultByte;
    if (index.fitsLong()) {
      final long i = index.toLong();
      if (i >= 32) {
        resultByte = 0;
      } else {
        resultByte = value.get((int) i);
      }
    } else {
      resultByte = 0;
    }

    frame.pushStackItem(Word256.fromByte(resultByte));
    return byteSuccess;
  }
}
