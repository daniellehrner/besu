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

import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.BASE_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.FALLBACK;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.done;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.next;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.stackWithSpace;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.storeLong;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/** The Gas operation. */
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
  public OperationResult executeFixedCostOperation(final MessageFrame frame) {
    final int sp = frame.stackTopV2();
    // gasLeft subtracts the base tier's cost, and this calculator may charge another
    final long gasPlusBaseTier = frame.getRemainingGas() - gasCost + BASE_TIER_GAS;
    if (gasLeft(stackWithSpace(frame, 1), next(sp), gasPlusBaseTier) == FALLBACK) {
      return overflowResponse;
    }
    frame.setTopV2(sp + 1);
    return successResponse;
  }

  /** GAS: what is left once GAS itself is paid. */
  @InlineInEvmLoop(opcodes = 0x5a)
  static long gasLeft(final long[] s, final int next, final long gas) {
    if (next < s.length && gas >= BASE_TIER_GAS) {
      storeLong(s, next, gas - BASE_TIER_GAS);
      return done(BASE_TIER_GAS, 1, 1);
    }
    return FALLBACK;
  }
}
