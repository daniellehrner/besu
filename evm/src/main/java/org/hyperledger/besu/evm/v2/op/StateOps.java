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
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.v2.loop.LoopTables;
import org.hyperledger.besu.evm.v2.operation.BalanceOperationV2;
import org.hyperledger.besu.evm.v2.operation.BlobHashOperationV2;
import org.hyperledger.besu.evm.v2.operation.BlockHashOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExtCodeHashOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExtCodeSizeOperationV2;
import org.hyperledger.besu.evm.v2.operation.LogOperationV2;
import org.hyperledger.besu.evm.v2.operation.SLoadOperationV2;
import org.hyperledger.besu.evm.v2.operation.SelfBalanceOperationV2;
import org.hyperledger.besu.evm.v2.operation.TLoadOperationV2;
import org.hyperledger.besu.evm.v2.operation.TStoreOperationV2;

/** The operations that read accounts, storage, block hashes and blob hashes, and the logs. */
final class StateOps {

  private StateOps() {}

  static final class SLoad extends Op {
    private final GasCalculator gasCalculator;

    SLoad(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x54, t, false);
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
      return SLoadOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class Balance extends Op {
    private final GasCalculator gasCalculator;

    Balance(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x31, t, false);
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
      return BalanceOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class ExtCodeSize extends Op {
    private final GasCalculator gasCalculator;

    ExtCodeSize(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x3b, t, false);
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
      return ExtCodeSizeOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class ExtCodeHash extends Op {
    private final GasCalculator gasCalculator;

    ExtCodeHash(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x3f, t, false);
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
      return ExtCodeHashOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class TLoad extends Op {
    TLoad(final LoopTables t) {
      super(0x5c, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return TLoadOperationV2.exec(frame, s, top, pc);
    }
  }

  static final class BlockHash extends Op {
    BlockHash(final LoopTables t) {
      super(0x40, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return BlockHashOperationV2.exec(frame, s, top, pc);
    }
  }

  static final class SelfBalance extends Op {
    SelfBalance(final LoopTables t) {
      super(0x47, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return SelfBalanceOperationV2.exec(frame, s, top, pc);
    }
  }

  static final class BlobHash extends Op {
    BlobHash(final LoopTables t) {
      super(0x49, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return BlobHashOperationV2.exec(frame, s, top, pc);
    }
  }

  static final class TStore extends Op {
    TStore(final LoopTables t) {
      super(0x5d, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return TStoreOperationV2.exec(frame, s, top, pc, gas);
    }
  }

  static final class Log extends Op {
    private final GasCalculator gasCalculator;
    private final int topics;

    Log(final int opcode, final LoopTables t, final GasCalculator gasCalculator) {
      super(opcode, t, false);
      this.gasCalculator = gasCalculator;
      this.topics = opcode - 0xa0;
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return LogOperationV2.exec(frame, s, top, pc, gas, topics, gasCalculator);
    }
  }
}
