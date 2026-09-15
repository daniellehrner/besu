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

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.v2.StackArithmetic;
import org.hyperledger.besu.evm.v2.loop.LoopResult;
import org.hyperledger.besu.evm.v2.loop.LoopTables;

/** The operations that only move stack items. */
final class StackOps {

  private StackOps() {}

  static final class Pop extends Op {
    Pop(final LoopTables t) {
      super(0x50, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(top - 1, pc + 1);
    }
  }

  static final class JumpDest extends Op {
    JumpDest(final LoopTables t) {
      super(0x5b, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(top, pc + 1);
    }
  }

  static final class Push0 extends Op {
    Push0(final LoopTables t) {
      super(0x5f, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.pushZero(s, top), pc + 1);
    }
  }

  static final class Push extends Op {
    private final int size;

    Push(final int opcode, final LoopTables t) {
      super(opcode, t, false);
      this.size = opcode - 0x5f;
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(
          StackArithmetic.pushFromBytes(s, top, code, pc + 1, size), pc + 1 + size);
    }
  }

  static final class Dup extends Op {
    private final int depth;

    Dup(final int opcode, final LoopTables t) {
      super(opcode, t, false);
      this.depth = opcode - 0x7f;
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.dup(s, top, depth), pc + 1);
    }
  }

  static final class Swap extends Op {
    private final int depth;

    Swap(final int opcode, final LoopTables t) {
      super(opcode, t, false);
      this.depth = opcode - 0x8f;
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.swap(s, top, depth), pc + 1);
    }
  }
}
