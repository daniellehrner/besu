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

import static org.hyperledger.besu.evm.operation.PushOperation.PUSH_BASE;
import static org.hyperledger.besu.evm.v2.operation.AbstractOperationV2.OVERFLOW_RESPONSE;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.ANY_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.BASE_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.FALLBACK;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.LONG_BE;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.PUSH1;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.VERY_LOW_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.done;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.next;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.ran;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.result;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.stackWithSpace;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.storeLong;
import static org.hyperledger.besu.evm.v2.operation.StackUtil.fromBytesBE;
import static org.hyperledger.besu.evm.v2.operation.StackUtil.getLongBE;

import org.hyperledger.besu.evm.UInt256;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.OverflowException;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;

/**
 * The PUSH0-32 operations. The interpreter runs them inline, so they exist only as static methods
 * and the fork's operation table has no instance of them.
 */
public final class PushOperationV2 {
  /** The Push operation success result. */
  private static final OperationResult pushSuccess = new OperationResult(3, null);

  private PushOperationV2() {}

  /** PUSH0: pushes zero. */
  @InlineInEvmLoop(opcodes = 0x5f)
  static long push0(final long[] s, final int next, final boolean shanghai, final long gas) {
    if (shanghai && next < s.length && gas >= BASE_TIER_GAS) {
      storeLong(s, next, 0L);
      return done(BASE_TIER_GAS, 1, 1);
    }
    return FALLBACK;
  }

  /** PUSH1: pushes the byte after the opcode, which is zero past the end of the code. */
  @InlineInEvmLoop(opcodes = 0x60)
  static long push1(
      final long[] s, final int next, final int at, final byte[] code, final long gas) {
    if (next < s.length && gas >= VERY_LOW_TIER_GAS) {
      // past the end of the code, the immediate is zero
      storeLong(s, next, at + 1 < code.length ? code[at + 1] & 0xffL : 0L);
      return done(VERY_LOW_TIER_GAS, 2, 1);
    }
    return FALLBACK;
  }

  /** PUSH2 to PUSH8, when the eight bytes after the opcode are all code. */
  @InlineInEvmLoop(first = 0x61, last = 0x67)
  static long pushSingleLimb(
      final long[] s,
      final int next,
      final int at,
      final byte[] code,
      final int opcode,
      final long gas) {
    if (next < s.length && at + 9 <= code.length && gas >= VERY_LOW_TIER_GAS) {
      final int size = opcode - PUSH1 + 1;
      // the immediate is the first size bytes of the eight
      storeLong(s, next, (long) LONG_BE.get(code, at + 1) >>> (64 - (size << 3)));
      return done(VERY_LOW_TIER_GAS, size + 1, 1);
    }
    return FALLBACK;
  }

  private static void pushUInt256ToStack(final MessageFrame frame, final UInt256 pushValue) {
    if (!frame.ensureStackCapacityV2(1)) {
      throw new OverflowException();
    }
    final long[] stack = frame.stackDataV2();
    final int top = frame.stackTopV2();
    final int offset = top << 2;
    stack[offset] = pushValue.u3();
    stack[offset + 1] = pushValue.u2();
    stack[offset + 2] = pushValue.u1();
    stack[offset + 3] = pushValue.u0();
    frame.setTopV2(top + 1);
  }

  private static void pushLongToStack(final MessageFrame frame, final long u0) {
    if (!frame.ensureStackCapacityV2(1)) {
      throw new OverflowException();
    }
    final long[] stack = frame.stackDataV2();
    final int top = frame.stackTopV2();
    final int offset = top << 2;
    stack[offset] = 0;
    stack[offset + 1] = 0;
    stack[offset + 2] = 0;
    stack[offset + 3] = u0;
    frame.setTopV2(top + 1);
  }

  /** Optimized version of PUSH opcode for PUSH0 and PUSH1 only. */
  public static final class SingleByte {

    private SingleByte() {}

    /**
     * Performs Push operation statically.
     *
     * @param frame the frame
     * @param code the code
     * @param pc the pc
     * @param pushSize the push size
     * @return the operation result
     */
    public static OperationResult staticOperation(
        final MessageFrame frame, final byte[] code, final int pc, final int pushSize) {
      return pushSize == 0 ? pushZero(frame) : pushOneByte(frame, code, pc);
    }

    private static OperationResult pushZero(final MessageFrame frame) {
      final int sp = frame.stackTopV2();
      return result(
          frame, push0(stackWithSpace(frame, 1), next(sp), true, ANY_GAS), OVERFLOW_RESPONSE);
    }

    private static OperationResult pushOneByte(
        final MessageFrame frame, final byte[] code, final int pc) {
      final int sp = frame.stackTopV2();
      return result(
          frame, push1(stackWithSpace(frame, 1), next(sp), pc, code, ANY_GAS), OVERFLOW_RESPONSE);
    }
  }

  /** Optimized version for PUSH opcode for PUSH2 to PUSH8. */
  public static final class SingleLimb {

    private SingleLimb() {}

    /**
     * Performs Push operation statically.
     *
     * @param frame the frame
     * @param code the code
     * @param pc the pc
     * @param pushSize the push size
     * @return the operation result
     */
    public static OperationResult staticOperation(
        final MessageFrame frame, final byte[] code, final int pc, final int pushSize) {
      return pushed(
          frame,
          pushSingleLimb(
              stackWithSpace(frame, 1),
              next(frame.stackTopV2()),
              pc,
              code,
              PUSH_BASE + pushSize,
              ANY_GAS),
          code,
          pc,
          pushSize);
    }

    private static OperationResult pushed(
        final MessageFrame frame,
        final long outcome,
        final byte[] code,
        final int pc,
        final int pushSize) {
      return outcome != FALLBACK ? ran(frame, outcome) : nearCodeEnd(frame, code, pc, pushSize);
    }

    /**
     * A push the inline method leaves alone: one within eight bytes of the end of the code, which a
     * truncated push is too, or one onto a full stack.
     */
    private static OperationResult nearCodeEnd(
        final MessageFrame frame, final byte[] code, final int pc, final int pushSize) {
      final int start = pc + 1;
      final int end = start + pushSize;
      long u0 = getLongBE(code, start, Math.min(end, code.length));

      // Slow-path - when push is truncated and zeros need to be appended
      if (end > code.length) {
        final int remainingSize = code.length - start;
        final int shift = (pushSize - remainingSize) * 8;
        u0 <<= shift;
      }

      pushLongToStack(frame, u0);
      frame.setPC(pc + pushSize);
      return pushSuccess;
    }
  }

  /**
   * Generic multi limb version of PUSH opcode, can execute PUSH0-32, but it should only be used for
   * multiple long limbs because of performance.
   */
  public static final class MultiLimb {

    private MultiLimb() {}

    /**
     * Performs Push operation statically.
     *
     * @param frame the frame
     * @param code the code
     * @param pc the pc
     * @param pushSize the push size
     * @return the operation result
     */
    public static OperationResult staticOperation(
        final MessageFrame frame, final byte[] code, final int pc, final int pushSize) {
      final int start = pc + 1;
      final int end = start + pushSize;
      final int remainingSize = Math.min(end, code.length) - start;
      UInt256 pushValue = fromBytesBE(code, start, remainingSize);

      // Slow-path - when push is truncated and zeros need to be appended
      if (end > code.length) {
        pushValue = pushValue.shiftLeft((pushSize - remainingSize) * 8);
      }

      pushUInt256ToStack(frame, pushValue);
      frame.setPC(pc + pushSize);
      return pushSuccess;
    }
  }
}
