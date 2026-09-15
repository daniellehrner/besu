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
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason.DefaultExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.operation.StopOperation;
import org.hyperledger.besu.evm.operation.VirtualOperation;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.v2.loop.LoopResult;

import java.util.Optional;

/**
 * The EVM v2 interpreter loop that dispatches through operation objects. It reads an operation's
 * stack and gas needs from its fields, checks them, and makes one virtual call. The loop stays
 * small so the JIT keeps the program counter, the gas and the stack top in registers and inlines
 * everything the loop itself does; each operation is compiled on its own.
 */
public final class VtableLoop {

  private static final long UNDERFLOW =
      LoopResult.halt(DefaultExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
  private static final long OVERFLOW =
      LoopResult.halt(DefaultExceptionalHaltReason.TOO_MANY_STACK_ITEMS);
  private static final long OUT_OF_GAS =
      LoopResult.halt(DefaultExceptionalHaltReason.INSUFFICIENT_GAS);

  private final Op[] ops;
  private final Op stop;
  private final Operation[] operationArray;
  private final Operation endOfScriptStop;

  /**
   * Creates the loop for one EVM.
   *
   * @param evm the EVM whose operations, gas calculator and fork the loop runs
   * @param operations the operations registered for the fork
   */
  public VtableLoop(final EVM evm, final OperationRegistry operations) {
    this.ops = new OpTable(evm, operations).ops();
    this.stop = ops[0x00];
    this.operationArray = operations.getOperations();
    this.endOfScriptStop = new VirtualOperation(new StopOperation(evm.getGasCalculator()));
  }

  /**
   * Runs the frame until it halts, suspends for a child frame, or completes.
   *
   * @param frame the frame, which must hold a v2 stack
   * @param operationTracer the tracer, or {@link OperationTracer#NO_TRACING}
   */
  public void run(final MessageFrame frame, final OperationTracer operationTracer) {
    final boolean tracing = operationTracer != OperationTracer.NO_TRACING;
    frame.setRecordUpdatesForTracer(tracing);
    if (frame.getState() != MessageFrame.State.CODE_EXECUTING) {
      return;
    }
    if (tracing) {
      runTraced(frame, operationTracer);
      return;
    }
    final byte[] code = frame.getCode().getBytes().toArrayUnsafe();
    final long[] s = frame.stackDataV2();
    final Op[] ops = this.ops;
    final int maxStack = frame.stackMaxSizeV2();
    int pc = frame.getPC();
    long gas = frame.getRemainingGas();
    int top = frame.stackTopV2();
    while (true) {
      final Op op = pc < code.length ? ops[code[pc] & 0xff] : stop;
      long r;
      if (top < op.stackIn) {
        r = UNDERFLOW;
      } else if (top - op.stackIn + op.stackOut > maxStack) {
        r = OVERFLOW;
      } else if ((gas -= op.fixedGas) < 0) {
        // the frame keeps the overdrawn balance, as it does after any other out-of-gas halt
        r = OUT_OF_GAS;
      } else {
        r = op.exec(frame, code, s, top, pc, gas);
      }
      if (LoopResult.haltCode(r) != 0) {
        halt(frame, r, op.legacy, pc, gas, top);
        return;
      }
      if (op.legacy) {
        // the operation moved the frame itself; take its gas and check whether it suspended
        gas = frame.getRemainingGas();
        top = LoopResult.top(r);
        pc = LoopResult.pc(r);
        if (frame.getState() != MessageFrame.State.CODE_EXECUTING) {
          return;
        }
        continue;
      }
      gas -= LoopResult.gas(r);
      top = LoopResult.top(r);
      if (gas < 0) {
        halt(frame, OUT_OF_GAS, false, pc, gas, top);
        return;
      }
      if (op.checksState && frame.getState() != MessageFrame.State.CODE_EXECUTING) {
        // the frame is done; it keeps the pc of this operation, as the other loops leave it
        frame.setPC(pc);
        frame.setGasRemaining(gas);
        frame.setTopV2(top);
        return;
      }
      pc = LoopResult.pc(r);
    }
  }

  private static void halt(
      final MessageFrame frame,
      final long r,
      final boolean legacy,
      final int pc,
      final long gas,
      final int top) {
    if (!legacy) {
      // the frame never saw this operation; give it the state the loop holds
      frame.setPC(pc);
      frame.setGasRemaining(gas);
      frame.setTopV2(top);
    }
    frame.setExceptionalHaltReason(
        Optional.of(LoopResult.haltReason(frame, LoopResult.haltCode(r))));
    frame.setState(MessageFrame.State.EXCEPTIONAL_HALT);
  }

  /** The same loop with the frame kept current for the tracer around every operation. */
  private void runTraced(final MessageFrame frame, final OperationTracer operationTracer) {
    final byte[] code = frame.getCode().getBytes().toArrayUnsafe();
    final long[] s = frame.stackDataV2();
    final Op[] ops = this.ops;
    final int maxStack = frame.stackMaxSizeV2();
    int pc = frame.getPC();
    long gas = frame.getRemainingGas();
    int top = frame.stackTopV2();
    while (true) {
      final boolean inCode = pc < code.length;
      final Op op = inCode ? ops[code[pc] & 0xff] : stop;
      frame.setPC(pc);
      frame.setGasRemaining(gas);
      frame.setTopV2(top);
      frame.setCurrentOperation(inCode ? operationArray[op.opcode] : endOfScriptStop);
      operationTracer.tracePreExecution(frame);
      long r;
      OperationResult result = null;
      if (top < op.stackIn) {
        r = UNDERFLOW;
      } else if (top - op.stackIn + op.stackOut > maxStack) {
        r = OVERFLOW;
      } else if ((gas -= op.fixedGas) < 0) {
        r = OUT_OF_GAS;
      } else if (op.legacy) {
        result = op.execLegacy(frame, code, s, pc);
        r = LegacyOp.translate(frame, result);
      } else {
        r = op.exec(frame, code, s, top, pc, gas);
      }
      if (LoopResult.haltCode(r) != 0) {
        halt(frame, r, op.legacy, pc, gas, top);
        operationTracer.tracePostExecution(frame, result != null ? result : haltResult(r));
        return;
      }
      if (op.legacy) {
        gas = frame.getRemainingGas();
        top = LoopResult.top(r);
        pc = LoopResult.pc(r);
        if (frame.getState() != MessageFrame.State.CODE_EXECUTING) {
          operationTracer.tracePostExecution(frame, result);
          return;
        }
        frame.setPC(pc);
        operationTracer.tracePostExecution(frame, result);
        continue;
      }
      gas -= LoopResult.gas(r);
      top = LoopResult.top(r);
      if (gas < 0) {
        halt(frame, OUT_OF_GAS, false, pc, gas, top);
        operationTracer.tracePostExecution(frame, haltResult(OUT_OF_GAS));
        return;
      }
      result = new OperationResult(op.fixedGas + LoopResult.gas(r), null);
      if (op.checksState && frame.getState() != MessageFrame.State.CODE_EXECUTING) {
        frame.setGasRemaining(gas);
        frame.setTopV2(top);
        operationTracer.tracePostExecution(frame, result);
        return;
      }
      pc = LoopResult.pc(r);
      frame.setPC(pc);
      frame.setGasRemaining(gas);
      frame.setTopV2(top);
      operationTracer.tracePostExecution(frame, result);
    }
  }

  private static OperationResult haltResult(final long r) {
    return new OperationResult(0L, LoopResult.haltReason(null, LoopResult.haltCode(r)));
  }
}
