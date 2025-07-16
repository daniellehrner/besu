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

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.word256.Word256;

import org.apache.tuweni.bytes.Bytes32;

/** The Div operation. */
public class DivOperation extends AbstractFixedCostOperation {

  /** The Div success. */
  static final OperationResult divSuccess = new OperationResult(5, null);

  /**
   * Instantiates a new Div operation.
   *
   * @param gasCalculator the gas calculator
   */
  public DivOperation(final GasCalculator gasCalculator) {
    super(0x04, "DIV", 2, 1, gasCalculator, gasCalculator.getLowTierGasCost());
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(
      final MessageFrame frame, final EVM evm) {
    return staticOperation(frame);
  }

  /**
   * Performs Div operation.
   *
   * @param frame the frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    final Word256 a = Word256.fromBytes(frame.popStackItem().toArrayUnsafe());
    final Word256 b = Word256.fromBytes(frame.popStackItem().toArrayUnsafe());

    if (b.isZero()) {
      frame.pushStackItem(Bytes32.EMPTY);

    } else {
      final BigInteger aBi = new BigInteger(1, a.toBytesArray());
      final BigInteger bBi = new BigInteger(1, b.toBytesArray());
      final BigInteger result = aBi.divide(bBi);

      // because it's unsigned there is a change a 33 byte result will occur
      // there is no toByteArrayUnsigned so we have to check and trim
      byte[] resultArray = result.toByteArray();
      int length = resultArray.length;
      if (length > 32) {
        frame.pushStackItem(Bytes32.wrap(Word256.fromBytes(resultArray, length - 32).toBytesArray()));
      } else {
        frame.pushStackItem(Bytes.wrap(Word256.fromBytes(resultArray).toBytesArray()));
      }
    }

    return divSuccess;
  }
}
