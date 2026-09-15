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
package org.hyperledger.besu.evm.v2.op;

import org.hyperledger.besu.evm.frame.ExceptionalHaltReason.DefaultExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.v2.StackArithmetic;
import org.hyperledger.besu.evm.v2.loop.LoopResult;
import org.hyperledger.besu.evm.v2.loop.LoopTables;
import org.hyperledger.besu.evm.v2.operation.ReturnOperationV2;
import org.hyperledger.besu.evm.v2.operation.RevertOperationV2;

import org.apache.tuweni.bytes.Bytes;

/** The operations that end the frame or move the program counter, and the invalid opcode. */
final class ControlOps {

  private ControlOps() {}

  static final class Stop extends Op {
    Stop(final LoopTables t) {
      super(0x00, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      frame.setState(MessageFrame.State.CODE_SUCCESS);
      frame.setOutputData(Bytes.EMPTY);
      return LoopResult.ok(top, pc + 1);
    }
  }

  static final class Jump extends Op {
    Jump(final LoopTables t) {
      super(0x56, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return jump(frame, s, top - 1, top - 1);
    }
  }

  static final class JumpI extends Op {
    JumpI(final LoopTables t) {
      super(0x57, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return StackArithmetic.isZeroAt(s, top, 1)
          ? LoopResult.ok(top - 2, pc + 1)
          : jump(frame, s, top - 1, top - 2);
    }
  }

  static final class Return extends Op {
    private final GasCalculator gasCalculator;

    Return(final LoopTables t, final GasCalculator gasCalculator) {
      super(0xf3, t, false);
      this.gasCalculator = gasCalculator;
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return ReturnOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class Revert extends Op {
    private final GasCalculator gasCalculator;

    Revert(final LoopTables t, final GasCalculator gasCalculator) {
      super(0xfd, t, false);
      this.gasCalculator = gasCalculator;
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return RevertOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  /** The designated invalid opcode, and every opcode the fork does not have. */
  static final class Invalid extends Op {
    private static final long INVALID =
        LoopResult.halt(DefaultExceptionalHaltReason.INVALID_OPERATION);

    Invalid(final int opcode, final LoopTables t) {
      super(opcode, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return INVALID;
    }
  }

  /**
   * Jumps to the destination in a stack slot. The destination slot is read before the stack top is
   * lowered, so JUMPI passes the slot of its destination and the top it leaves behind.
   */
  private static long jump(
      final MessageFrame frame, final long[] s, final int slot, final int newTop) {
    final int off = slot << 2;
    if (s[off] != 0
        || s[off + 1] != 0
        || s[off + 2] != 0
        || s[off + 3] < 0
        || s[off + 3] > Integer.MAX_VALUE) {
      return LoopResult.halt(DefaultExceptionalHaltReason.INVALID_JUMP_DESTINATION);
    }
    final int destination = (int) s[off + 3];
    if (frame.getCode().isJumpDestInvalid(destination)) {
      return LoopResult.halt(DefaultExceptionalHaltReason.INVALID_JUMP_DESTINATION);
    }
    return LoopResult.ok(newTop, destination);
  }
}
