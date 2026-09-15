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

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason.DefaultExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.v2.StackArithmetic;
import org.hyperledger.besu.evm.v2.loop.LoopResult;
import org.hyperledger.besu.evm.v2.loop.LoopTables;

import java.util.Optional;

/** The operations that push a value of the frame, the block or the loop itself. */
final class EnvOps {

  private static final long INVALID =
      LoopResult.halt(DefaultExceptionalHaltReason.INVALID_OPERATION);

  private EnvOps() {}

  static final class Address extends Op {
    Address(final LoopTables t) {
      super(0x30, t, false);
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
          StackArithmetic.pushAddress(s, top, frame.getRecipientAddress()), pc + 1);
    }
  }

  static final class Origin extends Op {
    Origin(final LoopTables t) {
      super(0x32, t, false);
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
          StackArithmetic.pushAddress(s, top, frame.getOriginatorAddress()), pc + 1);
    }
  }

  static final class Caller extends Op {
    Caller(final LoopTables t) {
      super(0x33, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.pushAddress(s, top, frame.getSenderAddress()), pc + 1);
    }
  }

  static final class CallValue extends Op {
    CallValue(final LoopTables t) {
      super(0x34, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.pushWei(s, top, frame.getApparentValue()), pc + 1);
    }
  }

  static final class CallDataSize extends Op {
    CallDataSize(final LoopTables t) {
      super(0x36, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.pushLong(s, top, frame.getInputData().size()), pc + 1);
    }
  }

  static final class CodeSize extends Op {
    CodeSize(final LoopTables t) {
      super(0x38, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.pushLong(s, top, frame.getCode().getSize()), pc + 1);
    }
  }

  static final class GasPrice extends Op {
    GasPrice(final LoopTables t) {
      super(0x3a, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.pushWei(s, top, frame.getGasPrice()), pc + 1);
    }
  }

  static final class ReturnDataSize extends Op {
    ReturnDataSize(final LoopTables t) {
      super(0x3d, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.pushLong(s, top, frame.getReturnData().size()), pc + 1);
    }
  }

  static final class Coinbase extends Op {
    Coinbase(final LoopTables t) {
      super(0x41, t, false);
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
          StackArithmetic.pushAddress(s, top, frame.getMiningBeneficiary()), pc + 1);
    }
  }

  static final class Timestamp extends Op {
    Timestamp(final LoopTables t) {
      super(0x42, t, false);
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
          StackArithmetic.pushLong(s, top, frame.getBlockValues().getTimestamp()), pc + 1);
    }
  }

  static final class BlockNumber extends Op {
    BlockNumber(final LoopTables t) {
      super(0x43, t, false);
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
          StackArithmetic.pushLong(s, top, frame.getBlockValues().getNumber()), pc + 1);
    }
  }

  static final class GasLimit extends Op {
    GasLimit(final LoopTables t) {
      super(0x45, t, false);
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
          StackArithmetic.pushLong(s, top, frame.getBlockValues().getGasLimit()), pc + 1);
    }
  }

  static final class BlobBaseFee extends Op {
    BlobBaseFee(final LoopTables t) {
      super(0x4a, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.pushWei(s, top, frame.getBlobGasPrice()), pc + 1);
    }
  }

  static final class SlotNum extends Op {
    SlotNum(final LoopTables t) {
      super(0x4b, t, false);
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
          StackArithmetic.pushLong(s, top, frame.getBlockValues().getSlotNumber()), pc + 1);
    }
  }

  static final class Pc extends Op {
    Pc(final LoopTables t) {
      super(0x58, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.pushLong(s, top, pc), pc + 1);
    }
  }

  static final class MSize extends Op {
    MSize(final LoopTables t) {
      super(0x59, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.pushLong(s, top, frame.memoryByteSize()), pc + 1);
    }
  }

  static final class Gas extends Op {
    Gas(final LoopTables t) {
      super(0x5a, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LoopResult.ok(StackArithmetic.pushLong(s, top, gas), pc + 1);
    }
  }

  static final class Difficulty extends Op {
    private final boolean prevRandao;

    Difficulty(final LoopTables t, final boolean prevRandao) {
      super(0x44, t, false);
      this.prevRandao = prevRandao;
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      final byte[] bytes =
          prevRandao
              ? frame.getBlockValues().getMixHashOrPrevRandao().toArrayUnsafe()
              : frame.getBlockValues().getDifficultyBytes().toArrayUnsafe();
      return LoopResult.ok(StackArithmetic.pushFromBytes(s, top, bytes, 0, bytes.length), pc + 1);
    }
  }

  static final class ChainId extends Op {
    private final byte[] chainId;

    ChainId(final LoopTables t, final byte[] chainId) {
      super(0x46, t, false);
      this.chainId = chainId;
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
          StackArithmetic.pushFromBytes(s, top, chainId, 0, chainId.length), pc + 1);
    }
  }

  static final class BaseFee extends Op {
    BaseFee(final LoopTables t) {
      super(0x48, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      final Optional<Wei> baseFee = frame.getBlockValues().getBaseFee();
      return baseFee.isPresent()
          ? LoopResult.ok(StackArithmetic.pushWei(s, top, baseFee.get()), pc + 1)
          : INVALID;
    }
  }
}
