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

import static org.hyperledger.besu.evm.v2.operation.ArmCall.ANY_GAS;
import static org.hyperledger.besu.evm.v2.operation.ArmCall.result;
import static org.hyperledger.besu.evm.v2.operation.ArmCall.top;

import org.hyperledger.besu.evm.V2LoopArms;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * EVM v2 SUB operation using long[] stack representation.
 *
 * <p>Each 256-bit word is stored as four longs: index 0 = most significant 64 bits, index 3 = least
 * significant 64 bits.
 */
public class SubOperationV2 extends AbstractFixedCostOperationV2 {

  /**
   * Instantiates a new Sub operation.
   *
   * @param gasCalculator the gas calculator
   */
  public SubOperationV2(final GasCalculator gasCalculator) {
    super(0x03, "SUB", 2, 1, gasCalculator, gasCalculator.getVeryLowTierGasCost());
  }

  @Override
  public OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame);
  }

  /**
   * Execute the SUB opcode on the v2 long[] stack.
   *
   * <p>SUB: stack[top-2] = stack[top-1] - stack[top-2], return top-1.
   *
   * @param frame the message frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    final int sp = frame.stackTopV2();
    return result(frame, V2LoopArms.sub(frame.stackDataV2(), sp, top(sp), ANY_GAS), 2, 1);
  }
}
