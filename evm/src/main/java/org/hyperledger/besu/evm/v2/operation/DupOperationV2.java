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
import static org.hyperledger.besu.evm.v2.operation.Arms.next;
import static org.hyperledger.besu.evm.v2.operation.Arms.result;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;

/**
 * EVM v2 DUP1-16 operation (opcodes 0x80–0x8F).
 *
 * <p>Duplicates the item at depth {@code index} (1-based) to the top of the stack. Gas cost is
 * veryLow tier (3).
 */
public class DupOperationV2 extends AbstractFixedCostOperationV2 {

  /** The DUP opcode base (DUP1 = 0x80, so base = 0x7F). */
  public static final int DUP_BASE = 0x7F;

  private final int index;

  /**
   * Instantiates a new Dup operation.
   *
   * @param index the 1-based depth to duplicate (1–16)
   * @param gasCalculator the gas calculator
   */
  public DupOperationV2(final int index, final GasCalculator gasCalculator) {
    super(
        DUP_BASE + index,
        "DUP" + index,
        index,
        index + 1,
        gasCalculator,
        gasCalculator.getVeryLowTierGasCost());
    this.index = index;
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame, frame.stackDataV2(), index);
  }

  /**
   * Performs DUP operation.
   *
   * @param frame the frame
   * @param s the stack data array
   * @param index the 1-based depth to duplicate
   * @return the operation result
   */
  public static OperationResult staticOperation(
      final MessageFrame frame, final long[] s, final int index) {
    final int sp = frame.stackTopV2();
    return result(frame, dup(s, sp, next(sp), DUP_BASE + index, ANY_GAS), index, index + 1);
  }

  @Arm(rank = 3, first = 0x80, last = 0x8f)
  static long dup(final long[] s, final int sp, final int next, final int opcode, final long gas) {
    final int depth = opcode - 0x7f;
    if (sp >= depth && (sp << 2) < s.length && gas >= VERY_LOW_TIER_GAS) {
      final int from = next - (depth << 2);
      final int to = next;
      s[to] = s[from];
      s[to + 1] = s[from + 1];
      s[to + 2] = s[from + 2];
      s[to + 3] = s[from + 3];
      return done(VERY_LOW_TIER_GAS, 1, 1);
    }
    return FALLBACK;
  }
}
