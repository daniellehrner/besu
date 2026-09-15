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
import org.hyperledger.besu.evm.v2.operation.CallDataCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallDataLoadOperationV2;
import org.hyperledger.besu.evm.v2.operation.CodeCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExtCodeCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.Keccak256OperationV2;
import org.hyperledger.besu.evm.v2.operation.MCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.MloadOperationV2;
import org.hyperledger.besu.evm.v2.operation.Mstore8OperationV2;
import org.hyperledger.besu.evm.v2.operation.MstoreOperationV2;
import org.hyperledger.besu.evm.v2.operation.ReturnDataCopyOperationV2;

/** The operations that read or write memory, call data, code or return data. */
final class MemoryOps {

  private MemoryOps() {}

  static final class MLoad extends Op {
    private final GasCalculator gasCalculator;

    MLoad(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x51, t, false);
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
      return MloadOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class MStore extends Op {
    private final GasCalculator gasCalculator;

    MStore(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x52, t, false);
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
      return MstoreOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class MStore8 extends Op {
    private final GasCalculator gasCalculator;

    MStore8(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x53, t, false);
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
      return Mstore8OperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class MCopy extends Op {
    private final GasCalculator gasCalculator;

    MCopy(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x5e, t, false);
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
      return MCopyOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class Keccak256 extends Op {
    private final GasCalculator gasCalculator;

    Keccak256(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x20, t, false);
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
      return Keccak256OperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class CallDataCopy extends Op {
    private final GasCalculator gasCalculator;

    CallDataCopy(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x37, t, false);
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
      return CallDataCopyOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class CodeCopy extends Op {
    private final GasCalculator gasCalculator;

    CodeCopy(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x39, t, false);
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
      return CodeCopyOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class ReturnDataCopy extends Op {
    private final GasCalculator gasCalculator;

    ReturnDataCopy(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x3e, t, false);
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
      return ReturnDataCopyOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class ExtCodeCopy extends Op {
    private final GasCalculator gasCalculator;

    ExtCodeCopy(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x3c, t, false);
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
      return ExtCodeCopyOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
    }
  }

  static final class CallDataLoad extends Op {
    CallDataLoad(final LoopTables t) {
      super(0x35, t, false);
    }

    @Override
    public long exec(
        final MessageFrame frame,
        final byte[] code,
        final long[] s,
        final int top,
        final int pc,
        final long gas) {
      return CallDataLoadOperationV2.exec(frame, s, top, pc);
    }
  }
}
