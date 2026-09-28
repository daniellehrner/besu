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
import static org.hyperledger.besu.evm.v2.operation.ArmCall.ANY_GAS;
import static org.hyperledger.besu.evm.v2.operation.ArmCall.halt;
import static org.hyperledger.besu.evm.v2.operation.ArmCall.ran;

import org.hyperledger.besu.evm.V2LoopArms;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;

/** EVM v2 JUMPDEST operation. A marker opcode that is a no-op at runtime. */
public class JumpDestOperationV2 extends AbstractFixedCostOperationV2 {

  /** The constant OPCODE. */
  public static final int OPCODE = 0x5B;

  /**
   * Instantiates a new Jump dest operation.
   *
   * @param gasCalculator the gas calculator
   */
  public JumpDestOperationV2(final GasCalculator gasCalculator) {
    super(OPCODE, "JUMPDEST", 0, 0, gasCalculator, gasCalculator.getJumpDestOperationGasCost());
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame);
  }

  /**
   * Performs JUMPDEST operation (no-op marker).
   *
   * @param frame the frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    final long outcome = V2LoopArms.jumpdest(ANY_GAS);
    return outcome != FALLBACK ? ran(frame, outcome) : halt(frame, 0, 0);
  }
}
