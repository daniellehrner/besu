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

import static org.hyperledger.besu.evm.V2LoopArms.FALLBACK;
import static org.hyperledger.besu.evm.v2.operation.ArmCall.next;
import static org.hyperledger.besu.evm.v2.operation.ArmCall.ran;

import org.hyperledger.besu.evm.V2LoopArms;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;

/**
 * EVM v2 GAS operation — pushes the amount of available gas remaining (after this instruction's
 * cost) onto the stack.
 */
public class GasOperationV2 extends AbstractFixedCostOperationV2 {

  /**
   * Instantiates a new Gas operation.
   *
   * @param gasCalculator the gas calculator
   */
  public GasOperationV2(final GasCalculator gasCalculator) {
    super(0x5A, "GAS", 0, 1, gasCalculator, gasCalculator.getBaseTierGasCost());
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    final int sp = frame.stackTopV2();
    final long outcome =
        V2LoopArms.gasLeft(frame.stackDataV2(), sp, next(sp), frame.getRemainingGas());
    if (outcome != FALLBACK) {
      return ran(frame, outcome);
    }
    return frame.stackHasSpaceV2(1) ? outOfGasResponse : OVERFLOW_RESPONSE;
  }
}
