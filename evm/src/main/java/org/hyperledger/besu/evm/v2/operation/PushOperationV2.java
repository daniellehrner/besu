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
import static org.hyperledger.besu.evm.v2.operation.Arms.HIGH_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.Arms.JUMPDEST_GAS;
import static org.hyperledger.besu.evm.v2.operation.Arms.MID_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.Arms.VERY_LOW_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.Arms.done;
import static org.hyperledger.besu.evm.v2.operation.Arms.isJumpDestinationV2;
import static org.hyperledger.besu.evm.v2.operation.Arms.next;
import static org.hyperledger.besu.evm.v2.operation.Arms.result;

import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;

/**
 * EVM v2 PUSH1-32 operation (opcodes 0x60–0x7F).
 *
 * <p>Pushes the {@code length} immediate bytes after the opcode, which the analysis of the code
 * decoded once for the whole contract. Gas cost is veryLow tier (3). PC increment is {@code 1 +
 * length}.
 */
public class PushOperationV2 extends AbstractFixedCostOperationV2 {

  /** The PUSH opcode base (PUSH0 = 0x5F, so PUSH1 = 0x60). */
  public static final int PUSH_BASE = 0x5F;

  private final int length;

  /**
   * Instantiates a new Push operation for PUSH{length}.
   *
   * @param length the number of bytes to push (1–32)
   * @param gasCalculator the gas calculator
   */
  public PushOperationV2(final int length, final GasCalculator gasCalculator) {
    super(
        PUSH_BASE + length,
        "PUSH" + length,
        0,
        1,
        gasCalculator,
        gasCalculator.getVeryLowTierGasCost());
    this.length = length;
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(final MessageFrame frame) {
    return staticOperation(frame, frame.stackDataV2(), frame.getPC(), length);
  }

  /**
   * Performs PUSH operation.
   *
   * @param frame the frame
   * @param s the stack data array
   * @param pc the current program counter
   * @param pushSize the number of bytes to push
   * @return the operation result
   */
  public static OperationResult staticOperation(
      final MessageFrame frame, final long[] s, final int pc, final int pushSize) {
    return result(frame, runArm(frame, s, pc, PUSH_BASE + pushSize), 0, 1);
  }

  private static long runArm(
      final MessageFrame frame, final long[] s, final int pc, final int opcode) {
    final Code codeObject = frame.getCode();
    codeObject.analyse(); // builds the tables the arm reads
    final int sp = frame.stackTopV2();
    return push(s, sp, next(sp), pc, opcode, codeObject, ANY_GAS);
  }

  @Arm(rank = 1, opcodes = 0x60)
  static long push1(
      final long[] s,
      final int sp,
      final int next,
      final int at,
      final byte[] code,
      final long gas) {
    if ((sp << 2) < s.length && gas >= VERY_LOW_TIER_GAS) {
      final int dst = next;
      final int i = at;
      s[dst] = 0;
      s[dst + 1] = 0;
      s[dst + 2] = 0;
      s[dst + 3] = i + 1 < code.length ? code[i + 1] & 0xff : 0;
      return done(VERY_LOW_TIER_GAS, 2, 1);
    }
    return FALLBACK;
  }

  /**
   * PUSH1-32, although the loop runs PUSH1 and PUSH2 through arms of their own; the immediate was
   * decoded when the code was analysed.
   */
  @Arm(rank = 2, first = 0x62, last = 0x7f)
  static long push(
      final long[] s,
      final int sp,
      final int next,
      final int pc,
      final int opcode,
      final Code codeObject,
      final long gas) {
    if ((sp << 2) < s.length && gas >= VERY_LOW_TIER_GAS) {
      final int dst = next;
      final int block = pc >>> 6;
      final long value =
          codeObject
              .pushValues()[
              codeObject.pushBase()[block]
                  + Long.bitCount(codeObject.pushBits()[block] & ((1L << (pc & 63)) - 1))];
      if (opcode <= 0x67) { // PUSH1-8, the value itself
        s[dst] = 0;
        s[dst + 1] = 0;
        s[dst + 2] = 0;
        s[dst + 3] = value;
      } else { // PUSH9-32, where the wide values start
        final long[] wide = codeObject.pushWide();
        final int o = (int) value;
        s[dst] = wide[o];
        s[dst + 1] = wide[o + 1];
        s[dst + 2] = wide[o + 2];
        s[dst + 3] = wide[o + 3];
      }
      return done(VERY_LOW_TIER_GAS, opcode - 0x5e, 1);
    }
    return FALLBACK;
  }

  /** PUSH2, and the PUSH2 JUMP and PUSH2 JUMPI that follow it. */
  @Arm(rank = 7, opcodes = 0x61)
  static long push2(
      final long[] s,
      final int sp,
      final int top,
      final int next,
      final int pc,
      final int at,
      final byte[] code,
      final Code codeObject,
      final long gas) {
    final int i = at;
    if ((sp << 2) < s.length && gas >= VERY_LOW_TIER_GAS && i + 2 < code.length) {
      final int immediate = (code[i + 1] & 0xff) << 8 | (code[i + 2] & 0xff);
      final int following = i + 3 < code.length ? code[i + 3] & 0xff : 0;
      if (following == 0x56) {
        if (gas >= VERY_LOW_TIER_GAS + MID_TIER_GAS + JUMPDEST_GAS
            && isJumpDestinationV2(codeObject.getJumpDestBitMask(), immediate, code.length)) {
          // PUSH2 JUMP JUMPDEST, without the destination going through the stack
          return done(VERY_LOW_TIER_GAS + MID_TIER_GAS + JUMPDEST_GAS, immediate + 1 - pc, 0);
        }
      } else if (following == 0x57 && sp >= 1 && gas >= VERY_LOW_TIER_GAS + HIGH_TIER_GAS) {
        final int c = top;
        if ((s[c] | s[c + 1] | s[c + 2] | s[c + 3]) == 0) {
          // PUSH2 JUMPI, not taken
          return done(VERY_LOW_TIER_GAS + HIGH_TIER_GAS, 4, -1);
        }
        if (gas >= VERY_LOW_TIER_GAS + HIGH_TIER_GAS + JUMPDEST_GAS
            && isJumpDestinationV2(codeObject.getJumpDestBitMask(), immediate, code.length)) {
          // PUSH2 JUMPI JUMPDEST, taken
          return done(VERY_LOW_TIER_GAS + HIGH_TIER_GAS + JUMPDEST_GAS, immediate + 1 - pc, -1);
        }
      }
      final int dst = next;
      s[dst] = 0;
      s[dst + 1] = 0;
      s[dst + 2] = 0;
      s[dst + 3] = immediate;
      return done(VERY_LOW_TIER_GAS, 3, 1);
    }
    return FALLBACK;
  }
}
