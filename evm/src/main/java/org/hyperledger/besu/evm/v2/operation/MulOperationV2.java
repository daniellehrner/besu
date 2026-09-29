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

import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.FALLBACK;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.LOW_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.done;

import org.hyperledger.besu.evm.UInt256;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * EVM v2 MUL operation using long[] stack representation.
 *
 * <p>Each 256-bit word is stored as four longs: index 0 = most significant 64 bits, index 3 = least
 * significant 64 bits.
 */
public class MulOperationV2 extends AbstractFixedCostOperationV2 {

  private static final OperationResult MUL_SUCCESS = new OperationResult(5, null);

  /**
   * Instantiates a new Mul operation.
   *
   * @param gasCalculator the gas calculator
   */
  public MulOperationV2(final GasCalculator gasCalculator) {
    super(0x02, "MUL", 2, 1, gasCalculator, gasCalculator.getLowTierGasCost());
  }

  @Override
  public OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame);
  }

  /**
   * Execute the MUL opcode on the v2 long[] stack. MUL: stack[top-2] = stack[top-1] * stack[top-2],
   * return top-1
   *
   * @param frame the message frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    if (!frame.stackHasItemsV2(2)) return UNDERFLOW_RESPONSE;
    long[] stack = frame.stackDataV2();
    int top = frame.stackTopV2();
    final int aOffset = (top - 1) << 2;
    final int bOffset = (top - 2) << 2;

    UInt256 valueA =
        new UInt256(stack[aOffset], stack[aOffset + 1], stack[aOffset + 2], stack[aOffset + 3]);
    UInt256 valueB =
        new UInt256(stack[bOffset], stack[bOffset + 1], stack[bOffset + 2], stack[bOffset + 3]);

    UInt256 r = valueA.mul(valueB);

    stack[bOffset] = r.u3();
    stack[bOffset + 1] = r.u2();
    stack[bOffset + 2] = r.u1();
    stack[bOffset + 3] = r.u0();

    frame.setTopV2(top - 1);
    return MUL_SUCCESS;
  }

  /** MUL when either factor fits in 64 bits. */
  @InlineInEvmLoop(opcodes = 0x02)
  static long mul(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= LOW_TIER_GAS) {
      final int a = top;
      final int b = a - 4;
      final int wide;
      if ((s[a] | s[a + 1] | s[a + 2]) == 0) {
        wide = b;
      } else if ((s[b] | s[b + 1] | s[b + 2]) == 0) {
        wide = a;
      } else {
        wide = -1;
      }
      if (wide >= 0) {
        final long m = s[(wide == a ? b : a) + 3];
        final long x3 = s[wide];
        final long x2 = s[wide + 1];
        final long x1 = s[wide + 2];
        final long x0 = s[wide + 3];
        final long h0 = Math.unsignedMultiplyHigh(x0, m);
        final long h1 = Math.unsignedMultiplyHigh(x1, m);
        final long h2 = Math.unsignedMultiplyHigh(x2, m);
        final long l1 = x1 * m;
        final long r1 = l1 + h0;
        // a high half is at most 2^64 - 2, so adding a carry to one cannot overflow
        final long l2 = x2 * m;
        final long r2 = l2 + h1 + (Long.compareUnsigned(r1, l1) < 0 ? 1L : 0L);
        s[b] = x3 * m + h2 + (Long.compareUnsigned(r2, l2) < 0 ? 1L : 0L);
        s[b + 1] = r2;
        s[b + 2] = r1;
        s[b + 3] = x0 * m;
        return done(LOW_TIER_GAS, 1, -1);
      }
    }
    return FALLBACK;
  }
}
