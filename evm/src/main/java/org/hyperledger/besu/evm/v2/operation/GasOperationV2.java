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

import static org.hyperledger.besu.evm.v2.operation.Arms.BASE_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.Arms.FALLBACK;
import static org.hyperledger.besu.evm.v2.operation.Arms.done;
import static org.hyperledger.besu.evm.v2.operation.Arms.next;
import static org.hyperledger.besu.evm.v2.operation.Arms.result;

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
    final long gas = frame.getRemainingGas();
    return gas < gasCost ? outOfGas(frame) : result(frame, runArm(frame, gas), 0, 1);
  }

  // the arm pushes the gas left once GAS is paid, so unlike the other arms it needs the real gas
  private static long runArm(final MessageFrame frame, final long gas) {
    final int sp = frame.stackTopV2();
    return gasLeft(frame.stackDataV2(), sp, next(sp), gas);
  }

  private OperationResult outOfGas(final MessageFrame frame) {
    return frame.stackHasSpaceV2(1) ? outOfGasResponse : OVERFLOW_RESPONSE;
  }

  /** GAS: what is left once GAS itself is paid. */
  @Arm(rank = 25, opcodes = 0x5a)
  static long gasLeft(final long[] s, final int sp, final int next, final long gas) {
    if ((sp << 2) < s.length && gas >= BASE_TIER_GAS) {
      final int dst = next;
      s[dst] = 0;
      s[dst + 1] = 0;
      s[dst + 2] = 0;
      s[dst + 3] = gas - BASE_TIER_GAS;
      return done(BASE_TIER_GAS, 1, 1);
    }
    return FALLBACK;
  }
}
