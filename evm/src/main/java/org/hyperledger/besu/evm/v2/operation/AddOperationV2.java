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

import static org.hyperledger.besu.evm.v2.operation.Arms.ANY_GAS;
import static org.hyperledger.besu.evm.v2.operation.Arms.FALLBACK;
import static org.hyperledger.besu.evm.v2.operation.Arms.VERY_LOW_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.Arms.done;
import static org.hyperledger.besu.evm.v2.operation.Arms.result;
import static org.hyperledger.besu.evm.v2.operation.Arms.top;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;

/**
 * EVM v2 ADD operation using long[] stack representation.
 *
 * <p>Each 256-bit word is stored as four longs: index 0 = most significant 64 bits, index 3 = least
 * significant 64 bits. Addition is performed with carry propagation from least to most significant
 * word, with overflow silently truncated (mod 2^256).
 */
public class AddOperationV2 extends AbstractFixedCostOperationV2 {

  /**
   * Instantiates a new Add operation.
   *
   * @param gasCalculator the gas calculator
   */
  public AddOperationV2(final GasCalculator gasCalculator) {
    super(0x01, "ADD", 2, 1, gasCalculator, gasCalculator.getVeryLowTierGasCost());
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame);
  }

  /**
   * Execute the ADD opcode on the v2 long[] stack.
   *
   * <p>ADD: stack[top-2] = stack[top-1] + stack[top-2], return top-1.
   *
   * @param frame the message frame
   * @return the operation result
   */
  public static Operation.OperationResult staticOperation(final MessageFrame frame) {
    final int sp = frame.stackTopV2();
    return result(frame, add(frame.stackDataV2(), sp, top(sp), ANY_GAS), 2, 1);
  }

  @Arm(rank = 10, opcodes = 0x01)
  static long add(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final int b = a - 4;
      final long x0 = s[a + 3];
      final long y0 = s[b + 3];
      final long r0 = x0 + y0;
      final long c0 = ((x0 & y0) | ((x0 | y0) & ~r0)) >>> 63;
      final long x1 = s[a + 2];
      final long y1 = s[b + 2];
      final long r1 = x1 + y1 + c0;
      final long c1 = ((x1 & y1) | ((x1 | y1) & ~r1)) >>> 63;
      final long x2 = s[a + 1];
      final long y2 = s[b + 1];
      final long r2 = x2 + y2 + c1;
      final long c2 = ((x2 & y2) | ((x2 | y2) & ~r2)) >>> 63;
      s[b] = s[a] + s[b] + c2;
      s[b + 1] = r2;
      s[b + 2] = r1;
      s[b + 3] = r0;
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }
}
