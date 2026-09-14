/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.evm.v2.loop;

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason.DefaultExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.OverflowException;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.log.EIP7708TransferLogEmitter;
import org.hyperledger.besu.evm.log.TransferLogEmitter;
import org.hyperledger.besu.evm.operation.ChainIdOperation;
import org.hyperledger.besu.evm.operation.InvalidOperation;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.operation.StopOperation;
import org.hyperledger.besu.evm.operation.VirtualOperation;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.v2.StackArithmetic;
import org.hyperledger.besu.evm.v2.operation.BalanceOperationV2;
import org.hyperledger.besu.evm.v2.operation.BlobHashOperationV2;
import org.hyperledger.besu.evm.v2.operation.BlockHashOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallCodeOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallDataCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallDataLoadOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallOperationV2;
import org.hyperledger.besu.evm.v2.operation.CodeCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.Create2OperationV2;
import org.hyperledger.besu.evm.v2.operation.CreateOperationV2;
import org.hyperledger.besu.evm.v2.operation.DelegateCallOperationV2;
import org.hyperledger.besu.evm.v2.operation.DupNOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExchangeOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExtCodeCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExtCodeHashOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExtCodeSizeOperationV2;
import org.hyperledger.besu.evm.v2.operation.InvalidOperationV2;
import org.hyperledger.besu.evm.v2.operation.Keccak256OperationV2;
import org.hyperledger.besu.evm.v2.operation.LogOperationV2;
import org.hyperledger.besu.evm.v2.operation.MCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.MloadOperationV2;
import org.hyperledger.besu.evm.v2.operation.Mstore8OperationV2;
import org.hyperledger.besu.evm.v2.operation.MstoreOperationV2;
import org.hyperledger.besu.evm.v2.operation.PayOperationV2;
import org.hyperledger.besu.evm.v2.operation.PushOperationV2;
import org.hyperledger.besu.evm.v2.operation.ReturnDataCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.ReturnOperationV2;
import org.hyperledger.besu.evm.v2.operation.RevertOperationV2;
import org.hyperledger.besu.evm.v2.operation.SLoadOperationV2;
import org.hyperledger.besu.evm.v2.operation.SStoreOperationV2;
import org.hyperledger.besu.evm.v2.operation.SelfBalanceOperationV2;
import org.hyperledger.besu.evm.v2.operation.SelfDestructOperationV2;
import org.hyperledger.besu.evm.v2.operation.StaticCallOperationV2;
import org.hyperledger.besu.evm.v2.operation.StopOperationV2;
import org.hyperledger.besu.evm.v2.operation.SwapNOperationV2;
import org.hyperledger.besu.evm.v2.operation.TLoadOperationV2;
import org.hyperledger.besu.evm.v2.operation.TStoreOperationV2;

import java.util.Optional;

/**
 * The EVM v2 interpreter loop that checks stack depth and fixed gas from per-opcode tables ahead of
 * the switch and keeps the program counter, the remaining gas and the stack top in locals.
 *
 * <p>Operations move to the loop's contract one family at a time. A case on the new contract
 * returns a packed {@link LoopResult}; a case still on the old contract returns an {@link
 * OperationResult} which is translated after the switch. The switch itself holds one call per case
 * and nothing else, because the JIT inlines a call site only while the whole compiled loop stays
 * under its size budget.
 */
public final class TableLoop {

  private static final OperationResult OVERFLOW_RESPONSE =
      new OperationResult(0L, DefaultExceptionalHaltReason.TOO_MANY_STACK_ITEMS);
  private static final OperationResult UNDERFLOW_RESPONSE =
      new OperationResult(0L, DefaultExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
  private static final long INVALID =
      LoopResult.halt(DefaultExceptionalHaltReason.INVALID_OPERATION);
  private static final int DUP_BASE = 0x7f;
  private static final int SWAP_BASE = 0x8f;

  private final EVM evm;
  private final GasCalculator gasCalculator;
  private final Operation[] operationArray;
  private final Operation endOfScriptStop;
  private final LoopTables tables;
  private final byte[] chainId;
  private final boolean enableByzantium;
  private final boolean enableConstantinople;
  private final boolean enableIstanbul;
  private final boolean enableLondon;
  private final boolean enableParis;
  private final boolean enableShanghai;
  private final boolean enableCancun;
  private final boolean enableAmsterdam;
  private final boolean enableOsaka;

  /**
   * Creates the loop for one EVM.
   *
   * @param evm the EVM whose operations, gas calculator and fork the loop runs
   * @param operations the operations registered for the fork
   */
  public TableLoop(final EVM evm, final OperationRegistry operations) {
    this.evm = evm;
    this.gasCalculator = evm.getGasCalculator();
    this.operationArray = operations.getOperations();
    this.endOfScriptStop = new VirtualOperation(new StopOperation(gasCalculator));
    this.tables = new LoopTables(operations, gasCalculator);
    final Operation chainIdOp = operations.get(ChainIdOperation.OPCODE);
    this.chainId =
        chainIdOp instanceof ChainIdOperation cid ? cid.getChainId().toArrayUnsafe() : null;
    final int fork = evm.getEvmVersion().ordinal();
    enableByzantium = EvmSpecVersion.BYZANTIUM.ordinal() <= fork;
    enableConstantinople = EvmSpecVersion.CONSTANTINOPLE.ordinal() <= fork;
    enableIstanbul = EvmSpecVersion.ISTANBUL.ordinal() <= fork;
    enableLondon = EvmSpecVersion.LONDON.ordinal() <= fork;
    enableParis = EvmSpecVersion.PARIS.ordinal() <= fork;
    enableShanghai = EvmSpecVersion.SHANGHAI.ordinal() <= fork;
    enableCancun = EvmSpecVersion.CANCUN.ordinal() <= fork;
    enableAmsterdam = EvmSpecVersion.AMSTERDAM.ordinal() <= fork;
    enableOsaka = EvmSpecVersion.OSAKA.ordinal() <= fork;
  }

  /**
   * Runs the frame until it halts, suspends for a child frame, or completes.
   *
   * @param frame the frame, which must hold a v2 stack
   * @param operationTracer the tracer, or {@link OperationTracer#NO_TRACING}
   */
  public void run(final MessageFrame frame, final OperationTracer operationTracer) {
    final byte[] code = frame.getCode().getBytes().toArrayUnsafe();
    final long[] s = frame.stackDataV2();
    final byte[] stackIn = tables.stackIn;
    final byte[] stackOut = tables.stackOut;
    final long[] fixedGas = tables.fixedGas;
    final byte[] flags = tables.flags;
    final int maxStack = frame.stackMaxSizeV2();
    final boolean tracing = operationTracer != OperationTracer.NO_TRACING;
    frame.setRecordUpdatesForTracer(tracing);
    if (frame.getState() != MessageFrame.State.CODE_EXECUTING) {
      return;
    }
    int pc = frame.getPC();
    long gas = frame.getRemainingGas();
    int top = frame.stackTopV2();
    while (true) {
      final int opcode = pc < code.length ? code[pc] & 0xff : 0;
      final boolean nativeOp = (flags[opcode] & LoopTables.NATIVE) != 0;
      long r;
      OperationResult result = null;
      if (tracing) {
        frame.setPC(pc);
        frame.setGasRemaining(gas);
        frame.setTopV2(top);
        frame.setCurrentOperation(pc < code.length ? operationArray[opcode] : endOfScriptStop);
        operationTracer.tracePreExecution(frame);
      }
      if (top < stackIn[opcode]) {
        r = LoopResult.halt(DefaultExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
      } else if (top - stackIn[opcode] + stackOut[opcode] > maxStack) {
        r = LoopResult.halt(DefaultExceptionalHaltReason.TOO_MANY_STACK_ITEMS);
      } else if (nativeOp && (gas -= fixedGas[opcode]) < 0) {
        // the frame keeps the overdrawn balance, as it does after any other out-of-gas halt
        r = LoopResult.halt(DefaultExceptionalHaltReason.INSUFFICIENT_GAS);
      } else {
        if (!nativeOp && !tracing) {
          frame.setPC(pc);
          frame.setGasRemaining(gas);
          frame.setTopV2(top);
        }
        r = 0L;
        try {
          switch (opcode) {
            // ---- operations on the loop's contract
            case 0x50 -> r = LoopResult.ok(top - 1, pc + 1);
            case 0x5b -> r = LoopResult.ok(top, pc + 1);
            case 0x5f ->
                r =
                    enableShanghai
                        ? LoopResult.ok(StackArithmetic.pushZero(s, top), pc + 1)
                        : INVALID;
            case 0x80,
                0x81,
                0x82,
                0x83,
                0x84,
                0x85,
                0x86,
                0x87,
                0x88,
                0x89,
                0x8a,
                0x8b,
                0x8c,
                0x8d,
                0x8e,
                0x8f ->
                r = LoopResult.ok(StackArithmetic.dup(s, top, opcode - DUP_BASE), pc + 1);
            case 0x90,
                0x91,
                0x92,
                0x93,
                0x94,
                0x95,
                0x96,
                0x97,
                0x98,
                0x99,
                0x9a,
                0x9b,
                0x9c,
                0x9d,
                0x9e,
                0x9f ->
                r = LoopResult.ok(StackArithmetic.swap(s, top, opcode - SWAP_BASE), pc + 1);
            // ---- operations still on the old contract
            case 0x01 -> r = LoopResult.ok(StackArithmetic.add(s, top), pc + 1);
            case 0x02 -> r = LoopResult.ok(StackArithmetic.mul(s, top), pc + 1);
            case 0x03 -> r = LoopResult.ok(StackArithmetic.sub(s, top), pc + 1);
            case 0x04 -> r = LoopResult.ok(StackArithmetic.div(s, top), pc + 1);
            case 0x05 -> r = LoopResult.ok(StackArithmetic.signedDiv(s, top), pc + 1);
            case 0x06 -> r = LoopResult.ok(StackArithmetic.mod(s, top), pc + 1);
            case 0x07 -> r = LoopResult.ok(StackArithmetic.signedMod(s, top), pc + 1);
            case 0x08 -> r = LoopResult.ok(StackArithmetic.addMod(s, top), pc + 1);
            case 0x09 -> r = LoopResult.ok(StackArithmetic.mulMod(s, top), pc + 1);
            case 0x0a -> r = exp(s, top, pc, gas);
            case 0x0b -> r = LoopResult.ok(StackArithmetic.signExtend(s, top), pc + 1);
            case 0x10 -> r = LoopResult.ok(StackArithmetic.lt(s, top), pc + 1);
            case 0x11 -> r = LoopResult.ok(StackArithmetic.gt(s, top), pc + 1);
            case 0x12 -> r = LoopResult.ok(StackArithmetic.slt(s, top), pc + 1);
            case 0x13 -> r = LoopResult.ok(StackArithmetic.sgt(s, top), pc + 1);
            case 0x14 -> r = LoopResult.ok(StackArithmetic.eq(s, top), pc + 1);
            case 0x15 -> r = LoopResult.ok(StackArithmetic.isZero(s, top), pc + 1);
            case 0x16 -> r = LoopResult.ok(StackArithmetic.and(s, top), pc + 1);
            case 0x17 -> r = LoopResult.ok(StackArithmetic.or(s, top), pc + 1);
            case 0x18 -> r = LoopResult.ok(StackArithmetic.xor(s, top), pc + 1);
            case 0x19 -> r = LoopResult.ok(StackArithmetic.not(s, top), pc + 1);
            case 0x1a -> r = LoopResult.ok(StackArithmetic.byte_(s, top), pc + 1);
            case 0x1b ->
                r =
                    enableConstantinople
                        ? LoopResult.ok(StackArithmetic.shl(s, top), pc + 1)
                        : INVALID;
            case 0x1c ->
                r =
                    enableConstantinople
                        ? LoopResult.ok(StackArithmetic.shr(s, top), pc + 1)
                        : INVALID;
            case 0x1d ->
                r =
                    enableConstantinople
                        ? LoopResult.ok(StackArithmetic.sar(s, top), pc + 1)
                        : INVALID;
            case 0x1e ->
                r = enableOsaka ? LoopResult.ok(StackArithmetic.clz(s, top), pc + 1) : INVALID;
            case 0x60,
                0x61,
                0x62,
                0x63,
                0x64,
                0x65,
                0x66,
                0x67,
                0x68,
                0x69,
                0x6a,
                0x6b,
                0x6c,
                0x6d,
                0x6e,
                0x6f,
                0x70,
                0x71,
                0x72,
                0x73,
                0x74,
                0x75,
                0x76,
                0x77,
                0x78,
                0x79,
                0x7a,
                0x7b,
                0x7c,
                0x7d,
                0x7e,
                0x7f -> {
              final int size = opcode - PushOperationV2.PUSH_BASE;
              r =
                  LoopResult.ok(
                      StackArithmetic.pushFromBytes(s, top, code, pc + 1, size), pc + 1 + size);
            }
            case 0xe6 -> // DUPN (EIP-8024)
                result =
                    enableAmsterdam
                        ? DupNOperationV2.staticOperation(frame, s, code, pc)
                        : InvalidOperation.invalidOperationResult(opcode);
            case 0xe7 -> // SWAPN (EIP-8024)
                result =
                    enableAmsterdam
                        ? SwapNOperationV2.staticOperation(frame, s, code, pc)
                        : InvalidOperation.invalidOperationResult(opcode);
            case 0xe8 -> // EXCHANGE (EIP-8024)
                result =
                    enableAmsterdam
                        ? ExchangeOperationV2.staticOperation(frame, s, code, pc)
                        : InvalidOperation.invalidOperationResult(opcode);
            case 0x51 -> r = MloadOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
            case 0x52 -> r = MstoreOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
            case 0x53 -> r = Mstore8OperationV2.exec(frame, s, top, pc, gas, gasCalculator);
            case 0x5e ->
                r =
                    enableCancun
                        ? MCopyOperationV2.exec(frame, s, top, pc, gas, gasCalculator)
                        : INVALID;
            case 0xf0 -> result = CreateOperationV2.staticOperation(frame, s, gasCalculator, evm);
            case 0xf1 -> result = CallOperationV2.staticOperation(frame, s, gasCalculator, evm);
            case 0xf2 -> result = CallCodeOperationV2.staticOperation(frame, s, gasCalculator, evm);
            case 0xf4 ->
                result = DelegateCallOperationV2.staticOperation(frame, s, gasCalculator, evm);
            case 0xf5 -> result = Create2OperationV2.staticOperation(frame, s, gasCalculator, evm);
            case 0xfa ->
                result = StaticCallOperationV2.staticOperation(frame, s, gasCalculator, evm);
            case 0x54 -> result = SLoadOperationV2.staticOperation(frame, s, gasCalculator);
            case 0x55 ->
                result =
                    SStoreOperationV2.staticOperation(
                        frame, s, gasCalculator, SStoreOperationV2.EIP_1706_MINIMUM);
            case 0x5c -> r = enableCancun ? TLoadOperationV2.exec(frame, s, top, pc) : INVALID;
            case 0x5d ->
                r = enableCancun ? TStoreOperationV2.exec(frame, s, top, pc, gas) : INVALID;
            case 0x20 -> r = Keccak256OperationV2.exec(frame, s, top, pc, gas, gasCalculator);
            case 0x31 -> result = BalanceOperationV2.staticOperation(frame, s, gasCalculator);
            case 0x35 -> r = CallDataLoadOperationV2.exec(frame, s, top, pc);
            case 0x37 -> r = CallDataCopyOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
            case 0x39 -> r = CodeCopyOperationV2.exec(frame, s, top, pc, gas, gasCalculator);
            case 0x3b -> result = ExtCodeSizeOperationV2.staticOperation(frame, s, gasCalculator);
            case 0x3c -> result = ExtCodeCopyOperationV2.staticOperation(frame, s, gasCalculator);
            case 0x3e ->
                r =
                    enableByzantium
                        ? ReturnDataCopyOperationV2.exec(frame, s, top, pc, gas, gasCalculator)
                        : INVALID;
            case 0x3f ->
                result =
                    enableConstantinople
                        ? ExtCodeHashOperationV2.staticOperation(frame, s, gasCalculator)
                        : InvalidOperation.invalidOperationResult(opcode);
            case 0x40 -> result = BlockHashOperationV2.staticOperation(frame, s);
            case 0x47 ->
                r = enableIstanbul ? SelfBalanceOperationV2.exec(frame, s, top, pc) : INVALID;
            case 0x49 -> r = enableCancun ? BlobHashOperationV2.exec(frame, s, top, pc) : INVALID;
            case 0x30 ->
                r =
                    LoopResult.ok(
                        StackArithmetic.pushAddress(s, top, frame.getRecipientAddress()), pc + 1);
            case 0x32 ->
                r =
                    LoopResult.ok(
                        StackArithmetic.pushAddress(s, top, frame.getOriginatorAddress()), pc + 1);
            case 0x33 ->
                r =
                    LoopResult.ok(
                        StackArithmetic.pushAddress(s, top, frame.getSenderAddress()), pc + 1);
            case 0x34 ->
                r =
                    LoopResult.ok(
                        StackArithmetic.pushWei(s, top, frame.getApparentValue()), pc + 1);
            case 0x36 ->
                r =
                    LoopResult.ok(
                        StackArithmetic.pushLong(s, top, frame.getInputData().size()), pc + 1);
            case 0x38 ->
                r =
                    LoopResult.ok(
                        StackArithmetic.pushLong(s, top, frame.getCode().getSize()), pc + 1);
            case 0x3a ->
                r = LoopResult.ok(StackArithmetic.pushWei(s, top, frame.getGasPrice()), pc + 1);
            case 0x3d ->
                r =
                    enableByzantium
                        ? LoopResult.ok(
                            StackArithmetic.pushLong(s, top, frame.getReturnData().size()), pc + 1)
                        : INVALID;
            case 0x41 ->
                r =
                    LoopResult.ok(
                        StackArithmetic.pushAddress(s, top, frame.getMiningBeneficiary()), pc + 1);
            case 0x42 ->
                r =
                    LoopResult.ok(
                        StackArithmetic.pushLong(s, top, frame.getBlockValues().getTimestamp()),
                        pc + 1);
            case 0x43 ->
                r =
                    LoopResult.ok(
                        StackArithmetic.pushLong(s, top, frame.getBlockValues().getNumber()),
                        pc + 1);
            case 0x44 -> r = LoopResult.ok(pushDifficulty(frame, s, top), pc + 1);
            case 0x45 ->
                r =
                    LoopResult.ok(
                        StackArithmetic.pushLong(s, top, frame.getBlockValues().getGasLimit()),
                        pc + 1);
            case 0x46 ->
                r =
                    chainId != null
                        ? LoopResult.ok(
                            StackArithmetic.pushFromBytes(s, top, chainId, 0, chainId.length),
                            pc + 1)
                        : INVALID;
            case 0x48 -> r = enableLondon ? pushBaseFee(frame, s, top, pc) : INVALID;
            case 0x4a ->
                r =
                    enableCancun
                        ? LoopResult.ok(
                            StackArithmetic.pushWei(s, top, frame.getBlobGasPrice()), pc + 1)
                        : INVALID;
            case 0x4b ->
                r =
                    enableAmsterdam
                        ? LoopResult.ok(
                            StackArithmetic.pushLong(
                                s, top, frame.getBlockValues().getSlotNumber()),
                            pc + 1)
                        : INVALID;
            case 0x58 -> r = LoopResult.ok(StackArithmetic.pushLong(s, top, pc), pc + 1);
            case 0x59 ->
                r = LoopResult.ok(StackArithmetic.pushLong(s, top, frame.memoryByteSize()), pc + 1);
            case 0x5a -> r = LoopResult.ok(StackArithmetic.pushLong(s, top, gas), pc + 1);
            case 0x00 -> result = StopOperationV2.staticOperation(frame);
            case 0x56 -> r = jump(frame, s, top - 1, top - 1);
            case 0x57 ->
                r =
                    StackArithmetic.isZeroAt(s, top, 1)
                        ? LoopResult.ok(top - 2, pc + 1)
                        : jump(frame, s, top - 1, top - 2);
            case 0xf3 -> result = ReturnOperationV2.staticOperation(frame, s, gasCalculator);
            case 0xfd -> // REVERT (Byzantium+)
                result =
                    enableByzantium
                        ? RevertOperationV2.staticOperation(frame, s, gasCalculator)
                        : InvalidOperation.invalidOperationResult(opcode);
            case 0xfe -> result = InvalidOperationV2.INVALID_RESULT;
            case 0xa0, 0xa1, 0xa2, 0xa3, 0xa4 -> {
              int topicCount = opcode - 0xa0;
              result = LogOperationV2.staticOperation(frame, s, topicCount, gasCalculator);
            }
            case 0xff ->
                result =
                    SelfDestructOperationV2.staticOperation(
                        frame,
                        s,
                        gasCalculator,
                        enableCancun,
                        enableAmsterdam
                            ? EIP7708TransferLogEmitter.INSTANCE
                            : TransferLogEmitter.NOOP);
            case 0xfc -> // PAY (EIP-7708, Amsterdam+)
                result =
                    enableAmsterdam
                        ? PayOperationV2.staticOperation(frame, s, gasCalculator)
                        : InvalidOperation.invalidOperationResult(opcode);
            default -> {
              final Operation currentOperation =
                  pc < code.length ? operationArray[opcode] : endOfScriptStop;
              frame.setCurrentOperation(currentOperation);
              result = currentOperation.execute(frame, evm);
            }
          }
        } catch (final OverflowException oe) {
          result = OVERFLOW_RESPONSE;
        } catch (final UnderflowException ue) {
          result = UNDERFLOW_RESPONSE;
        }
        if (result != null) {
          r = legacy(frame, result);
        }
      }

      final int haltCode = LoopResult.haltCode(r);
      if (haltCode != 0) {
        if (!LoopResult.isLegacy(r) && result == null) {
          // the frame never saw this operation; give it the state the loop holds
          frame.setPC(pc);
          frame.setGasRemaining(gas);
          frame.setTopV2(top);
        }
        frame.setExceptionalHaltReason(Optional.of(LoopResult.haltReason(frame, haltCode)));
        frame.setState(MessageFrame.State.EXCEPTIONAL_HALT);
        if (tracing) {
          operationTracer.tracePostExecution(frame, result != null ? result : haltResult(r));
        }
        return;
      }
      top = LoopResult.top(r);
      pc = LoopResult.pc(r);
      if (result != null) {
        gas = frame.getRemainingGas();
        if (frame.getState() != MessageFrame.State.CODE_EXECUTING) {
          if (tracing) {
            operationTracer.tracePostExecution(frame, result);
          }
          return;
        }
        frame.setPC(pc);
        if (tracing) {
          operationTracer.tracePostExecution(frame, result);
        }
      } else {
        gas -= LoopResult.gas(r);
        if (gas < 0) {
          frame.setPC(pc);
          frame.setGasRemaining(gas);
          frame.setTopV2(top);
          frame.setExceptionalHaltReason(
              Optional.of(DefaultExceptionalHaltReason.INSUFFICIENT_GAS));
          frame.setState(MessageFrame.State.EXCEPTIONAL_HALT);
          if (tracing) {
            operationTracer.tracePostExecution(frame, haltResult(r));
          }
          return;
        }
        if (tracing) {
          frame.setPC(pc);
          frame.setGasRemaining(gas);
          frame.setTopV2(top);
          operationTracer.tracePostExecution(
              frame, new OperationResult(fixedGas[opcode] + LoopResult.gas(r), null));
        }
      }
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

  private int pushDifficulty(final MessageFrame frame, final long[] s, final int top) {
    final byte[] bytes =
        enableParis
            ? frame.getBlockValues().getMixHashOrPrevRandao().toArrayUnsafe()
            : frame.getBlockValues().getDifficultyBytes().toArrayUnsafe();
    return StackArithmetic.pushFromBytes(s, top, bytes, 0, bytes.length);
  }

  private static long pushBaseFee(
      final MessageFrame frame, final long[] s, final int top, final int pc) {
    final Optional<Wei> baseFee = frame.getBlockValues().getBaseFee();
    return baseFee.isPresent()
        ? LoopResult.ok(StackArithmetic.pushWei(s, top, baseFee.get()), pc + 1)
        : INVALID;
  }

  private long exp(final long[] s, final int top, final int pc, final long gas) {
    final long cost = gasCalculator.expOperationGasCost(StackArithmetic.byteLengthAt(s, top, 1));
    if (gas < cost) {
      return LoopResult.halt(DefaultExceptionalHaltReason.INSUFFICIENT_GAS);
    }
    return LoopResult.ok(StackArithmetic.exp(s, top), pc + 1, cost);
  }

  /** Translates the result of an operation on the old contract, after it ran. */
  private static long legacy(final MessageFrame frame, final OperationResult result) {
    final ExceptionalHaltReason haltReason = result.getHaltReason();
    if (haltReason != null) {
      return LoopResult.halt(frame, haltReason);
    }
    if (frame.decrementRemainingGas(result.getGasCost()) < 0) {
      return LoopResult.halt(DefaultExceptionalHaltReason.INSUFFICIENT_GAS);
    }
    return LoopResult.legacy(frame.stackTopV2(), frame.getPC() + result.getPcIncrement());
  }

  private static OperationResult haltResult(final long r) {
    return new OperationResult(0L, LoopResult.haltReason(null, LoopResult.haltCode(r)));
  }
}
