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

/** The Shl (Shift Left) operation. */
public class ShlOperationV2 extends AbstractFixedCostOperationV2 {

  /**
   * Instantiates a new Shl operation.
   *
   * @param gasCalculator the gas calculator
   */
  public ShlOperationV2(final GasCalculator gasCalculator) {
    super(0x1b, "SHL", 2, 1, gasCalculator, gasCalculator.getVeryLowTierGasCost());
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame);
  }

  /**
   * Performs Shift Left operation.
   *
   * @param frame the frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    final int sp = frame.stackTopV2();
    return result(
        frame,
        shift(frame.stackDataV2(), sp, top(sp), 0x1b, /* constantinople= */ true, ANY_GAS),
        2,
        1);
  }

  /** SHL, SHR and SAR, Constantinople onwards. */
  @Arm(rank = 18, first = 0x1b, last = 0x1d)
  static long shift(
      final long[] s,
      final int sp,
      final int top,
      final int opcode,
      final boolean constantinople,
      final long gas) {
    if (constantinople && sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final int v = a - 4;
      long w3 = s[v];
      long w2 = s[v + 1];
      long w1 = s[v + 2];
      long w0 = s[v + 3];
      final long fill = opcode == 0x1d ? w3 >> 63 : 0L;
      final long shift = s[a + 3];
      if ((s[a] | s[a + 1] | s[a + 2]) != 0 || shift < 0 || shift >= 256) {
        w3 = fill;
        w2 = fill;
        w1 = fill;
        w0 = fill;
      } else {
        final int bits = (int) shift & 63;
        final int limbs = (int) shift >>> 6;
        if (opcode == 0x1b) {
          // whole limbs first, towards the most significant
          w3 = limbs == 0 ? w3 : limbs == 1 ? w2 : limbs == 2 ? w1 : w0;
          w2 = limbs == 0 ? w2 : limbs == 1 ? w1 : limbs == 2 ? w0 : 0;
          w1 = limbs == 0 ? w1 : limbs == 1 ? w0 : 0;
          w0 = limbs == 0 ? w0 : 0;
          if (bits != 0) {
            w3 = w3 << bits | w2 >>> -bits;
            w2 = w2 << bits | w1 >>> -bits;
            w1 = w1 << bits | w0 >>> -bits;
            w0 <<= bits;
          }
        } else {
          // whole limbs first, towards the least significant
          w0 = limbs == 0 ? w0 : limbs == 1 ? w1 : limbs == 2 ? w2 : w3;
          w1 = limbs == 0 ? w1 : limbs == 1 ? w2 : limbs == 2 ? w3 : fill;
          w2 = limbs == 0 ? w2 : limbs == 1 ? w3 : fill;
          w3 = limbs == 0 ? w3 : fill;
          if (bits != 0) {
            w0 = w0 >>> bits | w1 << -bits;
            w1 = w1 >>> bits | w2 << -bits;
            w2 = w2 >>> bits | w3 << -bits;
            w3 = opcode == 0x1c ? w3 >>> bits : w3 >> bits;
          }
        }
      }
      s[v] = w3;
      s[v + 1] = w2;
      s[v + 2] = w1;
      s[v + 3] = w0;
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }
}
