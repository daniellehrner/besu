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

import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.log.TransferLogEmitter;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.v2.loop.LoopTables;
import org.hyperledger.besu.evm.v2.operation.CallCodeOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallOperationV2;
import org.hyperledger.besu.evm.v2.operation.Create2OperationV2;
import org.hyperledger.besu.evm.v2.operation.CreateOperationV2;
import org.hyperledger.besu.evm.v2.operation.DelegateCallOperationV2;
import org.hyperledger.besu.evm.v2.operation.DupNOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExchangeOperationV2;
import org.hyperledger.besu.evm.v2.operation.PayOperationV2;
import org.hyperledger.besu.evm.v2.operation.SStoreOperationV2;
import org.hyperledger.besu.evm.v2.operation.SelfDestructOperationV2;
import org.hyperledger.besu.evm.v2.operation.StaticCallOperationV2;
import org.hyperledger.besu.evm.v2.operation.SwapNOperationV2;

/** The operations still on the old contract: calls, creates, storage writes and the rest. */
final class LegacyOps {

  private LegacyOps() {}

  /** A call or create, which needs the EVM to run the child frame. */
  static final class Call extends LegacyOp {
    private final GasCalculator gasCalculator;
    private final EVM evm;

    Call(final int opcode, final LoopTables t, final GasCalculator gasCalculator, final EVM evm) {
      super(opcode, t);
      this.gasCalculator = gasCalculator;
      this.evm = evm;
    }

    @Override
    OperationResult run(final MessageFrame frame, final byte[] code, final long[] s, final int pc) {
      return switch (opcode) {
        case 0xf0 -> CreateOperationV2.staticOperation(frame, s, gasCalculator, evm);
        case 0xf1 -> CallOperationV2.staticOperation(frame, s, gasCalculator, evm);
        case 0xf2 -> CallCodeOperationV2.staticOperation(frame, s, gasCalculator, evm);
        case 0xf4 -> DelegateCallOperationV2.staticOperation(frame, s, gasCalculator, evm);
        case 0xf5 -> Create2OperationV2.staticOperation(frame, s, gasCalculator, evm);
        default -> StaticCallOperationV2.staticOperation(frame, s, gasCalculator, evm);
      };
    }
  }

  static final class SStore extends LegacyOp {
    private final GasCalculator gasCalculator;

    SStore(final LoopTables t, final GasCalculator gasCalculator) {
      super(0x55, t);
      this.gasCalculator = gasCalculator;
    }

    @Override
    OperationResult run(final MessageFrame frame, final byte[] code, final long[] s, final int pc) {
      return SStoreOperationV2.staticOperation(
          frame, s, gasCalculator, SStoreOperationV2.EIP_1706_MINIMUM);
    }
  }

  static final class SelfDestruct extends LegacyOp {
    private final GasCalculator gasCalculator;
    private final boolean eip6780;
    private final TransferLogEmitter emitter;

    SelfDestruct(
        final LoopTables t,
        final GasCalculator gasCalculator,
        final boolean eip6780,
        final TransferLogEmitter emitter) {
      super(0xff, t);
      this.gasCalculator = gasCalculator;
      this.eip6780 = eip6780;
      this.emitter = emitter;
    }

    @Override
    OperationResult run(final MessageFrame frame, final byte[] code, final long[] s, final int pc) {
      return SelfDestructOperationV2.staticOperation(frame, s, gasCalculator, eip6780, emitter);
    }
  }

  static final class Pay extends LegacyOp {
    private final GasCalculator gasCalculator;

    Pay(final LoopTables t, final GasCalculator gasCalculator) {
      super(0xfc, t);
      this.gasCalculator = gasCalculator;
    }

    @Override
    OperationResult run(final MessageFrame frame, final byte[] code, final long[] s, final int pc) {
      return PayOperationV2.staticOperation(frame, s, gasCalculator);
    }
  }

  /** DUPN, SWAPN and EXCHANGE, which read their depth from an immediate. */
  static final class Immediate extends LegacyOp {
    Immediate(final int opcode, final LoopTables t) {
      super(opcode, t);
    }

    @Override
    OperationResult run(final MessageFrame frame, final byte[] code, final long[] s, final int pc) {
      return switch (opcode) {
        case 0xe6 -> DupNOperationV2.staticOperation(frame, s, code, pc);
        case 0xe7 -> SwapNOperationV2.staticOperation(frame, s, code, pc);
        default -> ExchangeOperationV2.staticOperation(frame, s, code, pc);
      };
    }
  }

  /** Any other operation the fork registered, run through its registry object. */
  static final class Registry extends LegacyOp {
    private final Operation operation;
    private final EVM evm;

    Registry(final int opcode, final LoopTables t, final Operation operation, final EVM evm) {
      super(opcode, t);
      this.operation = operation;
      this.evm = evm;
    }

    @Override
    OperationResult run(final MessageFrame frame, final byte[] code, final long[] s, final int pc) {
      frame.setCurrentOperation(operation);
      return operation.execute(frame, evm);
    }
  }
}
