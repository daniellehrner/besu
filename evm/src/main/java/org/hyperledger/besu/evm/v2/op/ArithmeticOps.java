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

/** The arithmetic, comparison and bitwise operations, which only touch the stack. */
final class ArithmeticOps {

  private ArithmeticOps() {}

  static final class Add extends Op {
    Add(final LoopTables t) {
      super(0x01, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.add(s, top), pc + 1);
    }
  }

  static final class Mul extends Op {
    Mul(final LoopTables t) {
      super(0x02, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.mul(s, top), pc + 1);
    }
  }

  static final class Sub extends Op {
    Sub(final LoopTables t) {
      super(0x03, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.sub(s, top), pc + 1);
    }
  }

  static final class Div extends Op {
    Div(final LoopTables t) {
      super(0x04, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.div(s, top), pc + 1);
    }
  }

  static final class SDiv extends Op {
    SDiv(final LoopTables t) {
      super(0x05, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.signedDiv(s, top), pc + 1);
    }
  }

  static final class Mod extends Op {
    Mod(final LoopTables t) {
      super(0x06, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.mod(s, top), pc + 1);
    }
  }

  static final class SMod extends Op {
    SMod(final LoopTables t) {
      super(0x07, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.signedMod(s, top), pc + 1);
    }
  }

  static final class AddMod extends Op {
    AddMod(final LoopTables t) {
      super(0x08, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.addMod(s, top), pc + 1);
    }
  }

  static final class MulMod extends Op {
    MulMod(final LoopTables t) {
      super(0x09, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.mulMod(s, top), pc + 1);
    }
  }

  static final class SignExtend extends Op {
    SignExtend(final LoopTables t) {
      super(0x0b, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.signExtend(s, top), pc + 1);
    }
  }

  static final class Lt extends Op {
    Lt(final LoopTables t) {
      super(0x10, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.lt(s, top), pc + 1);
    }
  }

  static final class Gt extends Op {
    Gt(final LoopTables t) {
      super(0x11, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.gt(s, top), pc + 1);
    }
  }

  static final class SLt extends Op {
    SLt(final LoopTables t) {
      super(0x12, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.slt(s, top), pc + 1);
    }
  }

  static final class SGt extends Op {
    SGt(final LoopTables t) {
      super(0x13, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.sgt(s, top), pc + 1);
    }
  }

  static final class Eq extends Op {
    Eq(final LoopTables t) {
      super(0x14, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.eq(s, top), pc + 1);
    }
  }

  static final class IsZero extends Op {
    IsZero(final LoopTables t) {
      super(0x15, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.isZero(s, top), pc + 1);
    }
  }

  static final class And extends Op {
    And(final LoopTables t) {
      super(0x16, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.and(s, top), pc + 1);
    }
  }

  static final class Or extends Op {
    Or(final LoopTables t) {
      super(0x17, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.or(s, top), pc + 1);
    }
  }

  static final class Xor extends Op {
    Xor(final LoopTables t) {
      super(0x18, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.xor(s, top), pc + 1);
    }
  }

  static final class Not extends Op {
    Not(final LoopTables t) {
      super(0x19, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.not(s, top), pc + 1);
    }
  }

  static final class ByteOp extends Op {
    ByteOp(final LoopTables t) {
      super(0x1a, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.byte_(s, top), pc + 1);
    }
  }

  static final class Shl extends Op {
    Shl(final LoopTables t) {
      super(0x1b, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.shl(s, top), pc + 1);
    }
  }

  static final class Shr extends Op {
    Shr(final LoopTables t) {
      super(0x1c, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.shr(s, top), pc + 1);
    }
  }

  static final class Sar extends Op {
    Sar(final LoopTables t) {
      super(0x1d, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.sar(s, top), pc + 1);
    }
  }

  static final class Clz extends Op {
    Clz(final LoopTables t) {
      super(0x1e, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.clz(s, top), pc + 1);
    }
  }

  static final class Exp extends Op {
    private final GasCalculator gasCalculator;

    Exp(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x0a, t, false);
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
      final long cost = gasCalculator.expOperationGasCost(StackArithmetic.byteLengthAt(s, top, 1));
      if (gas < cost) {
        return LoopResult.halt(DefaultExceptionalHaltReason.INSUFFICIENT_GAS);
      }
      return LoopResult.ok(StackArithmetic.exp(s, top), pc + 1, cost);
    }
  }
}
