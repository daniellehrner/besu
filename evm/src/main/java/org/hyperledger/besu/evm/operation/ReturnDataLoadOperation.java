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

import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.word256.Word256;

/** The Return data load operation. */
public class ReturnDataLoadOperation extends AbstractOperation {

  /**
   * Instantiates a new Return data load operation.
   *
   * @param gasCalculator the gas calculator
   */
  public ReturnDataLoadOperation(final GasCalculator gasCalculator) {
    super(0xf7, "RETURNDATALOAD", 3, 0, gasCalculator);
  }

  @Override
  public OperationResult execute(final MessageFrame frame, final EVM evm) {
    final Code code = frame.getCode();
    if (code.getEofVersion() == 0) {
      return InvalidOperation.INVALID_RESULT;
    }

    final Word256 offset = frame.popStackItem();
    final int offsetInt = offset.clampedToInt();
    final byte[] returnData = frame.getReturnData().toArrayUnsafe();

    final Word256 result;
    if (offsetInt >= returnData.length) {
      result = Word256.ZERO;
    } else {
      final int copyLen = Math.min(32, returnData.length - offsetInt);
      final byte[] padded = new byte[32];
      System.arraycopy(returnData, offsetInt, padded, 0, copyLen);
      result = Word256.fromBytes(padded);
    }

    frame.pushStackItem(result);
    return new OperationResult(3L, null);
  }
}
