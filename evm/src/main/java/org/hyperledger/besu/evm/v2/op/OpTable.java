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
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.log.EIP7708TransferLogEmitter;
import org.hyperledger.besu.evm.log.TransferLogEmitter;
import org.hyperledger.besu.evm.operation.ChainIdOperation;
import org.hyperledger.besu.evm.operation.InvalidOperation;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.v2.loop.LoopTables;

/** The 256 operation objects of one fork, indexed by opcode. */
public final class OpTable {

  private final Op[] ops = new Op[256];

  /**
   * Builds the table for one EVM.
   *
   * @param evm the EVM whose fork, gas calculator and registry the operations use
   * @param operations the operations registered for the fork
   */
  public OpTable(final EVM evm, final OperationRegistry operations) {
    final GasCalculator gc = evm.getGasCalculator();
    final LoopTables t = new LoopTables(operations, gc);
    final int fork = evm.getEvmVersion().ordinal();
    final boolean paris = EvmSpecVersion.PARIS.ordinal() <= fork;
    final boolean cancun = EvmSpecVersion.CANCUN.ordinal() <= fork;
    final boolean amsterdam = EvmSpecVersion.AMSTERDAM.ordinal() <= fork;

    set(new ControlOps.Stop(t));
    set(new ArithmeticOps.Add(t));
    set(new ArithmeticOps.Mul(t));
    set(new ArithmeticOps.Sub(t));
    set(new ArithmeticOps.Div(t));
    set(new ArithmeticOps.SDiv(t));
    set(new ArithmeticOps.Mod(t));
    set(new ArithmeticOps.SMod(t));
    set(new ArithmeticOps.AddMod(t));
    set(new ArithmeticOps.MulMod(t));
    set(new ArithmeticOps.Exp(t, gc));
    set(new ArithmeticOps.SignExtend(t));
    set(new ArithmeticOps.Lt(t));
    set(new ArithmeticOps.Gt(t));
    set(new ArithmeticOps.SLt(t));
    set(new ArithmeticOps.SGt(t));
    set(new ArithmeticOps.Eq(t));
    set(new ArithmeticOps.IsZero(t));
    set(new ArithmeticOps.And(t));
    set(new ArithmeticOps.Or(t));
    set(new ArithmeticOps.Xor(t));
    set(new ArithmeticOps.Not(t));
    set(new ArithmeticOps.ByteOp(t));
    set(new ArithmeticOps.Shl(t));
    set(new ArithmeticOps.Shr(t));
    set(new ArithmeticOps.Sar(t));
    set(new ArithmeticOps.Clz(t));
    set(new MemoryOps.Keccak256(t, gc));
    set(new EnvOps.Address(t));
    set(new StateOps.Balance(t, gc));
    set(new EnvOps.Origin(t));
    set(new EnvOps.Caller(t));
    set(new EnvOps.CallValue(t));
    set(new MemoryOps.CallDataLoad(t));
    set(new EnvOps.CallDataSize(t));
    set(new MemoryOps.CallDataCopy(t, gc));
    set(new EnvOps.CodeSize(t));
    set(new MemoryOps.CodeCopy(t, gc));
    set(new EnvOps.GasPrice(t));
    set(new StateOps.ExtCodeSize(t, gc));
    set(new MemoryOps.ExtCodeCopy(t, gc));
    set(new EnvOps.ReturnDataSize(t));
    set(new MemoryOps.ReturnDataCopy(t, gc));
    set(new StateOps.ExtCodeHash(t, gc));
    set(new StateOps.BlockHash(t));
    set(new EnvOps.Coinbase(t));
    set(new EnvOps.Timestamp(t));
    set(new EnvOps.BlockNumber(t));
    set(new EnvOps.Difficulty(t, paris));
    set(new EnvOps.GasLimit(t));
    final Operation chainIdOp = operations.get(ChainIdOperation.OPCODE);
    if (chainIdOp instanceof ChainIdOperation cid) {
      set(new EnvOps.ChainId(t, cid.getChainId().toArrayUnsafe()));
    }
    set(new StateOps.SelfBalance(t));
    set(new EnvOps.BaseFee(t));
    set(new StateOps.BlobHash(t));
    set(new EnvOps.BlobBaseFee(t));
    set(new EnvOps.SlotNum(t));
    set(new StackOps.Pop(t));
    set(new MemoryOps.MLoad(t, gc));
    set(new MemoryOps.MStore(t, gc));
    set(new MemoryOps.MStore8(t, gc));
    set(new StateOps.SLoad(t, gc));
    set(new LegacyOps.SStore(t, gc));
    set(new ControlOps.Jump(t));
    set(new ControlOps.JumpI(t));
    set(new EnvOps.Pc(t));
    set(new EnvOps.MSize(t));
    set(new EnvOps.Gas(t));
    set(new StackOps.JumpDest(t));
    set(new StateOps.TLoad(t));
    set(new StateOps.TStore(t));
    set(new MemoryOps.MCopy(t, gc));
    set(new StackOps.Push0(t));
    for (int opcode = 0x60; opcode <= 0x7f; opcode++) {
      set(new StackOps.Push(opcode, t));
    }
    for (int opcode = 0x80; opcode <= 0x8f; opcode++) {
      set(new StackOps.Dup(opcode, t));
    }
    for (int opcode = 0x90; opcode <= 0x9f; opcode++) {
      set(new StackOps.Swap(opcode, t));
    }
    for (int opcode = 0xa0; opcode <= 0xa4; opcode++) {
      set(new StateOps.Log(opcode, t, gc));
    }
    for (int opcode = 0xe6; opcode <= 0xe8; opcode++) {
      set(new LegacyOps.Immediate(opcode, t));
    }
    for (final int opcode : new int[] {0xf0, 0xf1, 0xf2, 0xf4, 0xf5, 0xfa}) {
      set(new LegacyOps.Call(opcode, t, gc, evm));
    }
    set(new ControlOps.Return(t, gc));
    set(new LegacyOps.Pay(t, gc));
    set(new ControlOps.Revert(t, gc));
    set(new ControlOps.Invalid(0xfe, t));
    set(
        new LegacyOps.SelfDestruct(
            t,
            gc,
            cancun,
            amsterdam ? EIP7708TransferLogEmitter.INSTANCE : TransferLogEmitter.NOOP));

    // the registry decides which opcodes the fork has: anything it lacks is invalid whatever the
    // stack holds, and anything else it registered runs through its registry object
    final Operation[] registered = operations.getOperations();
    for (int opcode = 0; opcode < 256; opcode++) {
      final Operation operation = registered[opcode];
      if (operation == null || operation instanceof InvalidOperation) {
        ops[opcode] = new ControlOps.Invalid(opcode, t);
      } else if (ops[opcode] == null) {
        ops[opcode] = new LegacyOps.Registry(opcode, t, operation, evm);
      }
    }
  }

  private void set(final Op op) {
    ops[op.opcode] = op;
  }

  /**
   * The operation for an opcode.
   *
   * @param opcode the opcode
   * @return the operation, never null
   */
  public Op get(final int opcode) {
    return ops[opcode];
  }

  /**
   * The table itself, for the loop.
   *
   * @return the 256 operations
   */
  public Op[] ops() {
    return ops;
  }
}
