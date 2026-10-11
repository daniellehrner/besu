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
package org.hyperledger.besu.evm;

import static org.hyperledger.besu.evm.operation.PushOperation.PUSH_BASE;
import static org.hyperledger.besu.evm.operation.SwapOperation.SWAP_BASE;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.BASE_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.HIGH_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.JUMPDEST_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.LONG_BE;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.LOW_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.MID_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.VERY_LOW_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.chargesTheLoopsCosts;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.isJumpDestinationV2;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.jumpDestinations;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.internal.JumpDestOnlyCodeCache;
import org.hyperledger.besu.evm.internal.OverflowException;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.operation.AddModOperation;
import org.hyperledger.besu.evm.operation.AddModOperationOptimized;
import org.hyperledger.besu.evm.operation.AddOperation;
import org.hyperledger.besu.evm.operation.AddOperationOptimized;
import org.hyperledger.besu.evm.operation.AndOperation;
import org.hyperledger.besu.evm.operation.AndOperationOptimized;
import org.hyperledger.besu.evm.operation.ByteOperation;
import org.hyperledger.besu.evm.operation.ChainIdOperation;
import org.hyperledger.besu.evm.operation.CountLeadingZerosOperation;
import org.hyperledger.besu.evm.operation.DivOperation;
import org.hyperledger.besu.evm.operation.DivOperationOptimized;
import org.hyperledger.besu.evm.operation.DupNOperation;
import org.hyperledger.besu.evm.operation.DupOperation;
import org.hyperledger.besu.evm.operation.ExchangeOperation;
import org.hyperledger.besu.evm.operation.ExpOperation;
import org.hyperledger.besu.evm.operation.GtOperation;
import org.hyperledger.besu.evm.operation.InvalidOperation;
import org.hyperledger.besu.evm.operation.IsZeroOperation;
import org.hyperledger.besu.evm.operation.JumpDestOperation;
import org.hyperledger.besu.evm.operation.JumpOperation;
import org.hyperledger.besu.evm.operation.JumpiOperation;
import org.hyperledger.besu.evm.operation.LtOperation;
import org.hyperledger.besu.evm.operation.ModOperation;
import org.hyperledger.besu.evm.operation.ModOperationOptimized;
import org.hyperledger.besu.evm.operation.MulModOperation;
import org.hyperledger.besu.evm.operation.MulModOperationOptimized;
import org.hyperledger.besu.evm.operation.MulOperation;
import org.hyperledger.besu.evm.operation.MulOperationOptimized;
import org.hyperledger.besu.evm.operation.NotOperation;
import org.hyperledger.besu.evm.operation.NotOperationOptimized;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.operation.OrOperation;
import org.hyperledger.besu.evm.operation.OrOperationOptimized;
import org.hyperledger.besu.evm.operation.PopOperation;
import org.hyperledger.besu.evm.operation.Push0Operation;
import org.hyperledger.besu.evm.operation.PushOperation;
import org.hyperledger.besu.evm.operation.SDivOperation;
import org.hyperledger.besu.evm.operation.SDivOperationOptimized;
import org.hyperledger.besu.evm.operation.SGtOperation;
import org.hyperledger.besu.evm.operation.SLtOperation;
import org.hyperledger.besu.evm.operation.SModOperation;
import org.hyperledger.besu.evm.operation.SModOperationOptimized;
import org.hyperledger.besu.evm.operation.SarOperation;
import org.hyperledger.besu.evm.operation.SarOperationOptimized;
import org.hyperledger.besu.evm.operation.ShlOperation;
import org.hyperledger.besu.evm.operation.ShlOperationOptimized;
import org.hyperledger.besu.evm.operation.ShrOperation;
import org.hyperledger.besu.evm.operation.ShrOperationOptimized;
import org.hyperledger.besu.evm.operation.SignExtendOperation;
import org.hyperledger.besu.evm.operation.StopOperation;
import org.hyperledger.besu.evm.operation.SubOperation;
import org.hyperledger.besu.evm.operation.SubOperationOptimized;
import org.hyperledger.besu.evm.operation.SwapNOperation;
import org.hyperledger.besu.evm.operation.SwapOperation;
import org.hyperledger.besu.evm.operation.VirtualOperation;
import org.hyperledger.besu.evm.operation.XorOperation;
import org.hyperledger.besu.evm.operation.XorOperationOptimized;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.v2.operation.AddModOperationV2;
import org.hyperledger.besu.evm.v2.operation.AddOperationV2;
import org.hyperledger.besu.evm.v2.operation.AndOperationV2;
import org.hyperledger.besu.evm.v2.operation.ByteOperationV2;
import org.hyperledger.besu.evm.v2.operation.CountLeadingZerosOperationV2;
import org.hyperledger.besu.evm.v2.operation.DivOperationV2;
import org.hyperledger.besu.evm.v2.operation.DupNOperationV2;
import org.hyperledger.besu.evm.v2.operation.DupOperationV2;
import org.hyperledger.besu.evm.v2.operation.EqOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExchangeOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExpOperationV2;
import org.hyperledger.besu.evm.v2.operation.GtOperationV2;
import org.hyperledger.besu.evm.v2.operation.HaltedStackV2;
import org.hyperledger.besu.evm.v2.operation.IsZeroOperationV2;
import org.hyperledger.besu.evm.v2.operation.JumpDestOperationV2;
import org.hyperledger.besu.evm.v2.operation.JumpOperationV2;
import org.hyperledger.besu.evm.v2.operation.JumpiOperationV2;
import org.hyperledger.besu.evm.v2.operation.LtOperationV2;
import org.hyperledger.besu.evm.v2.operation.ModOperationV2;
import org.hyperledger.besu.evm.v2.operation.MulModOperationV2;
import org.hyperledger.besu.evm.v2.operation.MulOperationV2;
import org.hyperledger.besu.evm.v2.operation.NotOperationV2;
import org.hyperledger.besu.evm.v2.operation.OperationsV2;
import org.hyperledger.besu.evm.v2.operation.OrOperationV2;
import org.hyperledger.besu.evm.v2.operation.PopOperationV2;
import org.hyperledger.besu.evm.v2.operation.PushOperationV2;
import org.hyperledger.besu.evm.v2.operation.SDivOperationV2;
import org.hyperledger.besu.evm.v2.operation.SGtOperationV2;
import org.hyperledger.besu.evm.v2.operation.SLtOperationV2;
import org.hyperledger.besu.evm.v2.operation.SModOperationV2;
import org.hyperledger.besu.evm.v2.operation.SarOperationV2;
import org.hyperledger.besu.evm.v2.operation.ShlOperationV2;
import org.hyperledger.besu.evm.v2.operation.ShrOperationV2;
import org.hyperledger.besu.evm.v2.operation.SignExtendOperationV2;
import org.hyperledger.besu.evm.v2.operation.SubOperationV2;
import org.hyperledger.besu.evm.v2.operation.SwapNOperationV2;
import org.hyperledger.besu.evm.v2.operation.SwapOperationV2;
import org.hyperledger.besu.evm.v2.operation.XorOperationV2;

import java.util.Optional;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The Evm. */
public class EVM {
  private static final Logger LOG = LoggerFactory.getLogger(EVM.class);

  /** The constant OVERFLOW_RESPONSE. */
  protected static final OperationResult OVERFLOW_RESPONSE =
      new OperationResult(0L, ExceptionalHaltReason.TOO_MANY_STACK_ITEMS);

  /** The constant UNDERFLOW_RESPONSE. */
  protected static final OperationResult UNDERFLOW_RESPONSE =
      new OperationResult(0L, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);

  private final OperationRegistry operations;
  private final GasCalculator gasCalculator;
  private final Operation endOfScriptStop;
  private final EvmConfiguration evmConfiguration;
  private final EvmSpecVersion evmSpecVersion;

  // Optimized operation flags
  private final boolean enableConstantinople;
  private final boolean enableShanghai;
  private final boolean enableAmsterdam;
  private final boolean enableOsaka;

  private final JumpDestOnlyCodeCache jumpDestOnlyCodeCache;

  private final Operation[] operationsV2;

  // the untraced v2 loop charges its inline operations' costs as constants
  private final boolean v2ChargesTheLoopsCosts;

  /**
   * Instantiates a new Evm.
   *
   * @param operations the operations
   * @param gasCalculator the gas calculator
   * @param evmConfiguration the evm configuration
   * @param evmSpecVersion the evm spec version
   */
  public EVM(
      final OperationRegistry operations,
      final GasCalculator gasCalculator,
      final EvmConfiguration evmConfiguration,
      final EvmSpecVersion evmSpecVersion) {
    this.operations = operations;
    this.gasCalculator = gasCalculator;
    this.endOfScriptStop = new VirtualOperation(new StopOperation(gasCalculator));
    this.evmConfiguration = evmConfiguration;
    this.evmSpecVersion = evmSpecVersion;
    this.jumpDestOnlyCodeCache = new JumpDestOnlyCodeCache(evmConfiguration);

    enableConstantinople = EvmSpecVersion.CONSTANTINOPLE.ordinal() <= evmSpecVersion.ordinal();
    enableShanghai = EvmSpecVersion.SHANGHAI.ordinal() <= evmSpecVersion.ordinal();
    enableAmsterdam = EvmSpecVersion.AMSTERDAM.ordinal() <= evmSpecVersion.ordinal();
    enableOsaka = EvmSpecVersion.OSAKA.ordinal() <= evmSpecVersion.ordinal();

    operationsV2 =
        evmConfiguration.enableEvmV2()
            ? OperationsV2.of(operations.getOperations(), gasCalculator)
            : null;
    v2ChargesTheLoopsCosts = chargesTheLoopsCosts(gasCalculator);
  }

  /**
   * Gets gas calculator.
   *
   * @return the gas calculator
   */
  public GasCalculator getGasCalculator() {
    return gasCalculator;
  }

  /**
   * Gets the max code size, taking configuration and version into account
   *
   * @return The max code size override, if not set the max code size for the EVM version.
   */
  public int getMaxCodeSize() {
    return evmConfiguration.maxCodeSizeOverride().orElse(evmSpecVersion.maxCodeSize);
  }

  /**
   * Gets the max initcode Size, taking configuration and version into account
   *
   * @return The max initcode size override, if not set the max initcode size for the EVM version.
   */
  public int getMaxInitcodeSize() {
    return evmConfiguration.maxInitcodeSizeOverride().orElse(evmSpecVersion.maxInitcodeSize);
  }

  /**
   * Returns the non-fork related configuration parameters of the EVM.
   *
   * @return the EVM configuration.
   */
  public EvmConfiguration getEvmConfiguration() {
    return evmConfiguration;
  }

  /**
   * Returns the configured EVM spec version for this EVM
   *
   * @return the evm spec version
   */
  public EvmSpecVersion getEvmVersion() {
    return evmSpecVersion;
  }

  /**
   * Return the ChainId this Executor is using, or empty if the EVM version does not expose chain
   * ID.
   *
   * @return the ChainId, or empty if not exposed.
   */
  public Optional<Bytes> getChainId() {
    Operation op = operations.get(ChainIdOperation.OPCODE);
    if (op instanceof ChainIdOperation chainIdOperation) {
      return Optional.of(chainIdOperation.getChainId());
    } else {
      return Optional.empty();
    }
  }

  /**
   * Run to halt.
   *
   * @param frame the frame
   * @param operationTracer the tracing
   */
  // Note to maintainers: lots of Java idioms and OO principals are being set aside in the
  // name of performance. This is one of the hottest sections of code.
  //
  // Please benchmark before refactoring.
  public void runToHalt(final MessageFrame frame, @NonNull final OperationTracer operationTracer) {
    // do not remove assert! A single, monomorphic tracer, is allowed in the EVM execution if
    // tracing is disabled for
    // optimization purposes
    assert operationTracer.isEnabled() || operationTracer == OperationTracer.NO_TRACING;

    if (evmConfiguration.enableEvmV2()) {
      runToHaltV2(frame, operationTracer);
      return;
    }
    evmSpecVersion.maybeWarnVersion();

    byte[] code = frame.getCode().getBytes().toArrayUnsafe();
    Operation[] operationArray = operations.getOperations();
    // A node that builds blocks also runs this loop with the interruptible selection tracer, so the
    // tracer call sites are not monomorphic; skip them entirely when tracing is disabled.
    final boolean tracingEnabled = operationTracer.isEnabled();
    while (frame.getState() == MessageFrame.State.CODE_EXECUTING) {
      Operation currentOperation;
      int opcode;
      int pc = frame.getPC();
      if (pc < code.length) {
        opcode = code[pc] & 0xff;
        currentOperation = operationArray[opcode];
      } else {
        opcode = 0;
        currentOperation = endOfScriptStop;
      }
      frame.setCurrentOperation(currentOperation);
      if (tracingEnabled) {
        operationTracer.tracePreExecution(frame);
      }

      OperationResult result;
      try {
        result =
            switch (opcode) {
              case 0x00 -> StopOperation.staticOperation(frame);
              case 0x01 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? AddOperationOptimized.staticOperation(frame)
                      : AddOperation.staticOperation(frame);
              case 0x02 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? MulOperationOptimized.staticOperation(frame)
                      : MulOperation.staticOperation(frame);
              case 0x03 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? SubOperationOptimized.staticOperation(frame)
                      : SubOperation.staticOperation(frame);
              case 0x04 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? DivOperationOptimized.staticOperation(frame)
                      : DivOperation.staticOperation(frame);
              case 0x05 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? SDivOperationOptimized.staticOperation(frame)
                      : SDivOperation.staticOperation(frame);
              case 0x06 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? ModOperationOptimized.staticOperation(frame)
                      : ModOperation.staticOperation(frame);
              case 0x07 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? SModOperationOptimized.staticOperation(frame)
                      : SModOperation.staticOperation(frame);
              case 0x08 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? AddModOperationOptimized.staticOperation(frame)
                      : AddModOperation.staticOperation(frame);
              case 0x09 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? MulModOperationOptimized.staticOperation(frame)
                      : MulModOperation.staticOperation(frame);
              case 0x0a -> ExpOperation.staticOperation(frame, gasCalculator);
              case 0x0b -> SignExtendOperation.staticOperation(frame);
              case 0x0c, 0x0d, 0x0e, 0x0f -> InvalidOperation.invalidOperationResult(opcode);
              case 0x10 -> LtOperation.staticOperation(frame);
              case 0x11 -> GtOperation.staticOperation(frame);
              case 0x12 -> SLtOperation.staticOperation(frame);
              case 0x13 -> SGtOperation.staticOperation(frame);
              case 0x15 -> IsZeroOperation.staticOperation(frame);
              case 0x16 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? AndOperationOptimized.staticOperation(frame)
                      : AndOperation.staticOperation(frame);
              case 0x17 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? OrOperationOptimized.staticOperation(frame)
                      : OrOperation.staticOperation(frame);
              case 0x18 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? XorOperationOptimized.staticOperation(frame)
                      : XorOperation.staticOperation(frame);
              case 0x19 ->
                  evmConfiguration.enableOptimizedOpcodes()
                      ? NotOperationOptimized.staticOperation(frame)
                      : NotOperation.staticOperation(frame);
              case 0x1a -> ByteOperation.staticOperation(frame);
              case 0x1b ->
                  enableConstantinople
                      ? shiftOperation(
                          frame,
                          ShlOperation::staticOperation,
                          ShlOperationOptimized::staticOperation)
                      : InvalidOperation.invalidOperationResult(opcode);
              case 0x1c ->
                  enableConstantinople
                      ? shiftOperation(
                          frame,
                          ShrOperation::staticOperation,
                          ShrOperationOptimized::staticOperation)
                      : InvalidOperation.invalidOperationResult(opcode);
              case 0x1d ->
                  enableConstantinople
                      ? shiftOperation(
                          frame,
                          SarOperation::staticOperation,
                          SarOperationOptimized::staticOperation)
                      : InvalidOperation.invalidOperationResult(opcode);
              case 0x1e ->
                  enableOsaka
                      ? CountLeadingZerosOperation.staticOperation(frame)
                      : InvalidOperation.invalidOperationResult(opcode);
              case 0x50 -> PopOperation.staticOperation(frame);
              case 0x56 -> JumpOperation.staticOperation(frame);
              case 0x57 -> JumpiOperation.staticOperation(frame);
              case 0x5b -> JumpDestOperation.JUMPDEST_SUCCESS;
              case 0x5f ->
                  enableShanghai
                      ? Push0Operation.staticOperation(frame)
                      : InvalidOperation.invalidOperationResult(opcode);
              case 0x60, // PUSH1-32
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
                  0x7f ->
                  PushOperation.staticOperation(frame, code, pc, opcode - PUSH_BASE);
              case 0x80, // DUP1-16
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
                  DupOperation.staticOperation(frame, opcode - DupOperation.DUP_BASE);
              case 0x90, // SWAP1-16
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
                  SwapOperation.staticOperation(frame, opcode - SWAP_BASE);
              case 0xe6 -> // DUPN (EIP-8024)
                  enableAmsterdam
                      ? DupNOperation.staticOperation(frame, code, pc)
                      : InvalidOperation.invalidOperationResult(opcode);
              case 0xe7 -> // SWAPN (EIP-8024)
                  enableAmsterdam
                      ? SwapNOperation.staticOperation(frame, code, pc)
                      : InvalidOperation.invalidOperationResult(opcode);
              case 0xe8 -> // EXCHANGE (EIP-8024)
                  enableAmsterdam
                      ? ExchangeOperation.staticOperation(frame, code, pc)
                      : InvalidOperation.invalidOperationResult(opcode);
              default -> { // unoptimized operations
                frame.setCurrentOperation(currentOperation);
                yield currentOperation.execute(frame, this);
              }
            };
      } catch (final OverflowException oe) {
        result = OVERFLOW_RESPONSE;
      } catch (final UnderflowException ue) {
        result = UNDERFLOW_RESPONSE;
      }
      final ExceptionalHaltReason haltReason = result.getHaltReason();
      if (haltReason != null) {
        LOG.trace("MessageFrame evaluation halted because of {}", haltReason);
        frame.setExceptionalHaltReason(Optional.of(haltReason));
        frame.setState(State.EXCEPTIONAL_HALT);
      } else if (frame.decrementRemainingGas(result.getGasCost()) < 0) {
        frame.setExceptionalHaltReason(Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS));
        frame.setState(State.EXCEPTIONAL_HALT);
      }
      if (frame.getState() == State.CODE_EXECUTING) {
        final int currentPC = frame.getPC();
        final int opSize = result.getPcIncrement();
        frame.setPC(currentPC + opSize);
      }
      if (tracingEnabled) {
        operationTracer.tracePostExecution(frame, result);
      }
    }
  }

  /**
   * Runs the frame on the EVM v2 interpreter, through the untraced loop unless the tracer traces
   * operations. A tracer that only wants the transaction-level hooks gets the untraced loop.
   *
   * @param frame the frame
   * @param operationTracer the tracer
   */
  private void runToHaltV2(final MessageFrame frame, final OperationTracer operationTracer) {
    evmSpecVersion.maybeWarnVersion();
    frame.ensureStackV2();
    // analysed before either loop runs a jump, so that no jump takes the branch that analyses it
    jumpDestinations(frame.getCode());
    if (!v2ChargesTheLoopsCosts || operationTracer.tracesOperations()) {
      runToHaltV2Traced(frame, operationTracer);
    } else {
      runToHaltV2Untraced(frame);
    }
  }

  /**
   * The v2 loop for tracers that trace operations, which runs every operation through its class and
   * keeps all state in the frame, where the tracer reads it.
   */
  private void runToHaltV2Traced(final MessageFrame frame, final OperationTracer operationTracer) {
    final byte[] code = frame.getCode().getBytes().toArrayUnsafe();
    final Operation[] operationArray = operations.getOperations();
    while (frame.getState() == MessageFrame.State.CODE_EXECUTING) {
      final int pc = frame.getPC();
      final int opcode = pc < code.length ? code[pc] & 0xff : 0;
      frame.setCurrentOperation(pc < code.length ? operationArray[opcode] : endOfScriptStop);
      operationTracer.tracePreExecution(frame);
      final OperationResult result = executeOperationV2(frame, code, pc, opcode);
      if (result.getHaltReason() != null) {
        // tracers are the only readers of a halted frame's stack
        HaltedStackV2.popAsV1(frame, opcode, result.getHaltReason());
      }
      completeOperationV2(frame, result);
      operationTracer.tracePostExecution(frame, result);
    }
  }

  // The cases of the untraced v2 loop. It switches on an opcode's case rather than on the opcode,
  // so that the switch's table has an entry per case instead of one per opcode up to the highest
  // one handled inline: that table is bytecode of the loop and counts against its inlining budget.
  // Opcodes without a case map to 0 and take the general path.
  // BEGIN GENERATED case table for the copies of the @InlineInEvmLoop methods of the v2
  // operations below; do not edit, run ./gradlew :evm:generateEvmV2Loop
  private static final int INLINE_SIGN_EXTEND = 1;
  private static final int INLINE_COMPARE = 2;
  private static final int INLINE_EQ = 3;
  private static final int INLINE_IS_ZERO = 4;
  private static final int INLINE_POP = 5;
  private static final int INLINE_JUMP = 6;
  private static final int INLINE_JUMPI = 7;
  private static final int INLINE_GAS_LEFT = 8;
  private static final int INLINE_JUMP_DEST = 9;
  private static final int INLINE_PUSH0 = 10;
  private static final int INLINE_PUSH1 = 11;
  private static final int INLINE_PUSH_SINGLE_LIMB = 12;
  private static final int INLINE_DUP = 13;
  private static final int INLINE_SWAP = 14;

  // Per opcode: its case in the low byte, and above it a bias of minus the case times 16,
  // which the case's stack and code indices cancel again; see runToHaltV2Untraced.
  private static final int[] DISPATCH = new int[256];

  static {
    dispatch(INLINE_SIGN_EXTEND, 0x0b, 0x0b);
    dispatch(INLINE_COMPARE, 0x10, 0x13);
    dispatch(INLINE_EQ, 0x14, 0x14);
    dispatch(INLINE_IS_ZERO, 0x15, 0x15);
    dispatch(INLINE_POP, 0x50, 0x50);
    dispatch(INLINE_JUMP, 0x56, 0x56);
    dispatch(INLINE_JUMPI, 0x57, 0x57);
    dispatch(INLINE_GAS_LEFT, 0x5a, 0x5a);
    dispatch(INLINE_JUMP_DEST, 0x5b, 0x5b);
    dispatch(INLINE_PUSH0, 0x5f, 0x5f);
    dispatch(INLINE_PUSH1, 0x60, 0x60);
    dispatch(INLINE_PUSH_SINGLE_LIMB, 0x61, 0x67);
    dispatch(INLINE_DUP, 0x80, 0x8f);
    dispatch(INLINE_SWAP, 0x90, 0x9f);
  }

  private static void dispatch(final int inlineCase, final int first, final int last) {
    for (int op = first; op <= last; op++) {
      DISPATCH[op] = (-(inlineCase << 4) << 8) | inlineCase;
    }
  }

  // END GENERATED case table

  /**
   * The v2 loop for execution without operation hooks, which is every block import. The program
   * counter, the remaining gas and the stack live in locals, and the cheap operations that need no
   * more than the stack and the code run inline, as copies of the methods of their classes marked
   * {@code @InlineInEvmLoop}. An inline case handles only the outcome where its operation succeeds;
   * anything else, and every other operation, goes through {@link #executeOperationV2} with the
   * state handed back to the frame, so halting behaviour is defined in one place for both loops.
   *
   * <p>The cases between the generated markers are copies of those methods, whose annotation
   * documents the rules they follow; the build rejects a method that breaks one, and EVM.java that
   * does not hold its copies. Edit a method in its operation's class and run {@code ./gradlew
   * :evm:generateEvmV2Loop}. The loop around them keeps one rule of its own: no local is read after
   * the general path's call before it is reloaded, as a local live across a call is kept in memory.
   *
   * <p>C2 also stops inlining into a method once its own bytecode and everything inlined into it
   * reach 8000 bytes, after which even the frame's getters become calls. That budget, not the gas
   * cost, decides which operations are inline.
   */
  private void runToHaltV2Untraced(final MessageFrame frame) {
    if (frame.getState() != MessageFrame.State.CODE_EXECUTING) {
      return;
    }
    final Code codeObject = frame.getCode();
    final byte[] code = codeObject.getBytes().toArrayUnsafe();
    final boolean shanghai = enableShanghai;
    // The general path may grow the stack into a new array, which the loop then reads again.
    long[] s = frame.stackDataV2();
    int sp = frame.stackTopV2();
    int pc = frame.getPC();
    long gas = frame.getRemainingGas();
    while (true) {
      final int opcode = pc < code.length ? code[pc] & 0xff : 0;
      // What an inline case that succeeds charges, how far it moves the program counter and how
      // many items it adds. The cases leave the updates to the common tail below: the same update
      // written out in every case is one computation, which C2 places before the dispatch.
      long cost = -1;
      int step = 0;
      int delta = 0;
      // The dispatch entry holds the case and a bias, which the case cancels again. Stack and code
      // indices built on a biased pointer differ from case to case as far as C2 can tell, so each
      // case computes its own. Built on sp and pc directly, the cases share them, and C2 computes
      // every case's indices ahead of the dispatch, where they take the registers the loop's state
      // needs. C2 inlines in bytecode order and stops at its size budget; EvmV2LoopCompilationTest
      // reports a call it left out.
      final int entry = DISPATCH[opcode];
      final int bias = entry >> 8;
      final int base = (sp << 2) + bias;
      final int pcBase = pc + bias;
      switch (entry & 0xff) {
        // BEGIN GENERATED cases: copies of the @InlineInEvmLoop methods of the v2 operations;
        // edit those, then run ./gradlew :evm:generateEvmV2Loop
        // BEGIN automatically copied from SignExtendOperationV2.signExtend; edit it there
        case INLINE_SIGN_EXTEND -> {
          if (sp >= 2 && gas >= LOW_TIER_GAS) {
            final int index = base + (INLINE_SIGN_EXTEND << 4) - 4;
            final int value = index - 4;
            // From byte 31 on the sign bit is the top bit of the word, so the value stays as it is.
            if ((s[index] | s[index + 1] | s[index + 2]) == 0
                && Long.compareUnsigned(s[index + 3], 31) < 0) {
              final int signBit = ((int) s[index + 3] << 3) + 7;
              // 0 for the least significant limb u0, 3 for the most significant limb u3
              final int signLimbIndex = signBit >>> 6;
              final int signLimb = value + 3 - signLimbIndex;
              final int bitInLimb = signBit & 63;
              final long fill = ((s[signLimb] >>> bitInLimb) & 1L) == 0 ? 0L : -1L;
              if (bitInLimb < 63) {
                final long kept = -1L >>> (63 - bitInLimb);
                s[signLimb] = (s[signLimb] & kept) | (fill & ~kept);
              }
              if (signLimbIndex < 3) {
                s[value] = fill;
              }
              if (signLimbIndex < 2) {
                s[value + 1] = fill;
              }
              if (signLimbIndex < 1) {
                s[value + 2] = fill;
              }
            }
            cost = LOW_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        // END automatically copied from SignExtendOperationV2.signExtend
        // BEGIN automatically copied from LtOperationV2.compare; edit it there
        case INLINE_COMPARE -> {
          if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (INLINE_COMPARE << 4) - 4;
            final int b = a - 4;
            // the most significant limb in which the two differ, or the least significant one
            final int i =
                s[a] != s[b] ? 0 : s[a + 1] != s[b + 1] ? 1 : s[a + 2] != s[b + 2] ? 2 : 3;
            long x = s[a + i];
            long y = s[b + i];
            if (i == 0 && opcode >= 0x12) {
              // signed: flipping the sign bits makes the unsigned comparison a signed one
              x ^= Long.MIN_VALUE;
              y ^= Long.MIN_VALUE;
            }
            final int comparison = Long.compareUnsigned(x, y);
            // LT and SLT are the even opcodes, GT and SGT the odd ones
            {
              // automatically copied from EvmLoopInlining.storeLong; edit it there
              final int storeLongAt = b;
              final long storeLongValue =
                  ((opcode & 1) == 0 ? comparison < 0 : comparison > 0) ? 1L : 0L;
              s[storeLongAt] = 0L;
              s[storeLongAt + 1] = 0L;
              s[storeLongAt + 2] = 0L;
              s[storeLongAt + 3] = storeLongValue;
            }
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        // END automatically copied from LtOperationV2.compare
        // BEGIN automatically copied from EqOperationV2.eq; edit it there
        case INLINE_EQ -> {
          if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (INLINE_EQ << 4) - 4;
            final int b = a - 4;
            final long equal =
                ((s[a] ^ s[b])
                            | (s[a + 1] ^ s[b + 1])
                            | (s[a + 2] ^ s[b + 2])
                            | (s[a + 3] ^ s[b + 3]))
                        == 0
                    ? 1L
                    : 0L;
            {
              // automatically copied from EvmLoopInlining.storeLong; edit it there
              final int storeLongAt = b;
              final long storeLongValue = equal;
              s[storeLongAt] = 0L;
              s[storeLongAt + 1] = 0L;
              s[storeLongAt + 2] = 0L;
              s[storeLongAt + 3] = storeLongValue;
            }
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        // END automatically copied from EqOperationV2.eq
        // BEGIN automatically copied from IsZeroOperationV2.isZero; edit it there
        case INLINE_IS_ZERO -> {
          if (sp >= 1 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (INLINE_IS_ZERO << 4) - 4;
            {
              // automatically copied from EvmLoopInlining.storeLong; edit it there
              final int storeLongAt = a;
              final long storeLongValue =
                  ((s[(a)] | s[(a) + 1] | s[(a) + 2] | s[(a) + 3]) == 0) ? 1L : 0L;
              s[storeLongAt] = 0L;
              s[storeLongAt + 1] = 0L;
              s[storeLongAt + 2] = 0L;
              s[storeLongAt + 3] = storeLongValue;
            }
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = 0;
          }
        }
        // END automatically copied from IsZeroOperationV2.isZero
        // BEGIN automatically copied from PopOperationV2.pop; edit it there
        case INLINE_POP -> {
          if (sp >= 1 && gas >= BASE_TIER_GAS) {
            cost = BASE_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        // END automatically copied from PopOperationV2.pop
        // BEGIN automatically copied from JumpOperationV2.jump; edit it there
        case INLINE_JUMP -> {
          if (sp >= 1 && gas >= MID_TIER_GAS) {
            final int d = base + (INLINE_JUMP << 4) - 4;
            if (((s[(d)] | s[(d) + 1] | s[(d) + 2]) == 0
                && isJumpDestinationV2(
                    codeObject.getJumpDestBitMask(), s[(d) + 3], codeObject.getSize()))) {
              final int destination = (int) s[d + 3];
              if (true && gas >= MID_TIER_GAS + JUMPDEST_GAS) {
                // no tracer sees the JUMPDEST as an operation of its own
                cost = MID_TIER_GAS + JUMPDEST_GAS;
                step = destination + 1 - pc;
                delta = -1;
                break;
              }
              cost = MID_TIER_GAS;
              step = destination - pc;
              delta = -1;
            }
          }
        }
        // END automatically copied from JumpOperationV2.jump
        // BEGIN automatically copied from JumpiOperationV2.jumpi; edit it there
        case INLINE_JUMPI -> {
          if (sp >= 2 && gas >= HIGH_TIER_GAS) {
            final int d = base + (INLINE_JUMPI << 4) - 4;
            // a zero condition does not jump, so the destination is not checked
            if (((s[(d - 4)] | s[(d - 4) + 1] | s[(d - 4) + 2] | s[(d - 4) + 3]) == 0)) {
              cost = HIGH_TIER_GAS;
              step = 1;
              delta = -2;
              break;
            }
            if (((s[(d)] | s[(d) + 1] | s[(d) + 2]) == 0
                && isJumpDestinationV2(
                    codeObject.getJumpDestBitMask(), s[(d) + 3], codeObject.getSize()))) {
              final int destination = (int) s[d + 3];
              if (true && gas >= HIGH_TIER_GAS + JUMPDEST_GAS) {
                // no tracer sees the JUMPDEST as an operation of its own
                cost = HIGH_TIER_GAS + JUMPDEST_GAS;
                step = destination + 1 - pc;
                delta = -2;
                break;
              }
              cost = HIGH_TIER_GAS;
              step = destination - pc;
              delta = -2;
            }
          }
        }
        // END automatically copied from JumpiOperationV2.jumpi
        // BEGIN automatically copied from GasOperationV2.gasLeft; edit it there
        case INLINE_GAS_LEFT -> {
          if ((base + (INLINE_GAS_LEFT << 4)) < s.length && gas >= BASE_TIER_GAS) {
            {
              // automatically copied from EvmLoopInlining.storeLong; edit it there
              final int storeLongAt = (base + (INLINE_GAS_LEFT << 4));
              final long storeLongValue = gas - BASE_TIER_GAS;
              s[storeLongAt] = 0L;
              s[storeLongAt + 1] = 0L;
              s[storeLongAt + 2] = 0L;
              s[storeLongAt + 3] = storeLongValue;
            }
            cost = BASE_TIER_GAS;
            step = 1;
            delta = 1;
          }
        }
        // END automatically copied from GasOperationV2.gasLeft
        // BEGIN automatically copied from JumpDestOperationV2.jumpDest; edit it there
        case INLINE_JUMP_DEST -> {
          if (gas >= JUMPDEST_GAS) {
            cost = JUMPDEST_GAS;
            step = 1;
            delta = 0;
          }
        }
        // END automatically copied from JumpDestOperationV2.jumpDest
        // BEGIN automatically copied from PushOperationV2.push0; edit it there
        case INLINE_PUSH0 -> {
          if (shanghai && (base + (INLINE_PUSH0 << 4)) < s.length && gas >= BASE_TIER_GAS) {
            {
              // automatically copied from EvmLoopInlining.storeLong; edit it there
              final int storeLongAt = (base + (INLINE_PUSH0 << 4));
              final long storeLongValue = 0L;
              s[storeLongAt] = 0L;
              s[storeLongAt + 1] = 0L;
              s[storeLongAt + 2] = 0L;
              s[storeLongAt + 3] = storeLongValue;
            }
            cost = BASE_TIER_GAS;
            step = 1;
            delta = 1;
          }
        }
        // END automatically copied from PushOperationV2.push0
        // BEGIN automatically copied from PushOperationV2.push1; edit it there
        case INLINE_PUSH1 -> {
          if ((base + (INLINE_PUSH1 << 4)) < s.length && gas >= VERY_LOW_TIER_GAS) {
            // past the end of the code, the immediate is zero
            {
              // automatically copied from EvmLoopInlining.storeLong; edit it there
              final int storeLongAt = (base + (INLINE_PUSH1 << 4));
              final long storeLongValue =
                  (pcBase + (INLINE_PUSH1 << 4)) + 1 < code.length
                      ? code[(pcBase + (INLINE_PUSH1 << 4)) + 1] & 0xffL
                      : 0L;
              s[storeLongAt] = 0L;
              s[storeLongAt + 1] = 0L;
              s[storeLongAt + 2] = 0L;
              s[storeLongAt + 3] = storeLongValue;
            }
            cost = VERY_LOW_TIER_GAS;
            step = 2;
            delta = 1;
          }
        }
        // END automatically copied from PushOperationV2.push1
        // BEGIN automatically copied from PushOperationV2.pushSingleLimb; edit it there
        case INLINE_PUSH_SINGLE_LIMB -> {
          if ((base + (INLINE_PUSH_SINGLE_LIMB << 4)) < s.length
              && (pcBase + (INLINE_PUSH_SINGLE_LIMB << 4)) + 9 <= code.length
              && gas >= VERY_LOW_TIER_GAS) {
            final int size = opcode - 0x5f;
            // the immediate is the first size bytes of the eight
            {
              // automatically copied from EvmLoopInlining.storeLong; edit it there
              final int storeLongAt = (base + (INLINE_PUSH_SINGLE_LIMB << 4));
              final long storeLongValue =
                  (long) LONG_BE.get(code, (pcBase + (INLINE_PUSH_SINGLE_LIMB << 4)) + 1)
                      >>> (64 - (size << 3));
              s[storeLongAt] = 0L;
              s[storeLongAt + 1] = 0L;
              s[storeLongAt + 2] = 0L;
              s[storeLongAt + 3] = storeLongValue;
            }
            cost = VERY_LOW_TIER_GAS;
            step = size + 1;
            delta = 1;
          }
        }
        // END automatically copied from PushOperationV2.pushSingleLimb
        // BEGIN automatically copied from DupOperationV2.dup; edit it there
        case INLINE_DUP -> {
          final int depth = opcode - 0x7f;
          if (sp >= depth && (base + (INLINE_DUP << 4)) < s.length && gas >= VERY_LOW_TIER_GAS) {
            {
              // automatically copied from EvmLoopInlining.copyWord; edit it there
              final int copyWordFrom = (base + (INLINE_DUP << 4)) - (depth << 2);
              final int copyWordTo = (base + (INLINE_DUP << 4));
              s[copyWordTo] = s[copyWordFrom];
              s[copyWordTo + 1] = s[copyWordFrom + 1];
              s[copyWordTo + 2] = s[copyWordFrom + 2];
              s[copyWordTo + 3] = s[copyWordFrom + 3];
            }
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = 1;
          }
        }
        // END automatically copied from DupOperationV2.dup
        // BEGIN automatically copied from SwapOperationV2.swap; edit it there
        case INLINE_SWAP -> {
          final int depth = opcode - 0x8f;
          if (sp > depth && gas >= VERY_LOW_TIER_GAS) {
            {
              // automatically copied from EvmLoopInlining.swapWords; edit it there
              final int swapWordsA = (base + (INLINE_SWAP << 4) - 4);
              final int swapWordsB = (base + (INLINE_SWAP << 4) - 4) - (depth << 2);
              long swapWordsT = s[swapWordsA];
              s[swapWordsA] = s[swapWordsB];
              s[swapWordsB] = swapWordsT;
              swapWordsT = s[swapWordsA + 1];
              s[swapWordsA + 1] = s[swapWordsB + 1];
              s[swapWordsB + 1] = swapWordsT;
              swapWordsT = s[swapWordsA + 2];
              s[swapWordsA + 2] = s[swapWordsB + 2];
              s[swapWordsB + 2] = swapWordsT;
              swapWordsT = s[swapWordsA + 3];
              s[swapWordsA + 3] = s[swapWordsB + 3];
              s[swapWordsB + 3] = swapWordsT;
            }
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = 0;
          }
        }
        // END automatically copied from SwapOperationV2.swap
        // END GENERATED cases
        default -> {
          // everything else goes through executeOperationV2 below
        }
      }
      if (cost >= 0) {
        gas -= cost;
        pc += step;
        sp += delta;
        continue;
      }

      frame.setTopV2(sp);
      frame.setPC(pc);
      frame.setGasRemaining(gas);
      completeOperationV2(frame, executeOperationV2(frame, code, pc, opcode));
      if (frame.getState() != MessageFrame.State.CODE_EXECUTING) {
        return;
      }
      s = frame.stackDataV2();
      sp = frame.stackTopV2();
      pc = frame.getPC();
      gas = frame.getRemainingGas();
    }
  }

  /**
   * Charges the operation's gas, or halts the frame, and steps past the operation.
   *
   * @param frame the frame
   * @param result the result of the operation
   */
  private static void completeOperationV2(final MessageFrame frame, final OperationResult result) {
    final ExceptionalHaltReason haltReason = result.getHaltReason();
    if (haltReason != null) {
      LOG.trace("MessageFrame evaluation halted because of {}", haltReason);
      frame.setExceptionalHaltReason(Optional.of(haltReason));
      frame.setState(MessageFrame.State.EXCEPTIONAL_HALT);
    } else if (frame.decrementRemainingGas(result.getGasCost()) < 0) {
      frame.setExceptionalHaltReason(Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS));
      frame.setState(MessageFrame.State.EXCEPTIONAL_HALT);
    }
    if (frame.getState() == MessageFrame.State.CODE_EXECUTING) {
      frame.setPC(frame.getPC() + result.getPcIncrement());
    }
  }

  /**
   * Executes one operation against the state held in the frame. The opcodes listed in the switch
   * are executed directly; all others by the v2 versions of the fork's registered operations.
   *
   * @param frame the frame
   * @param code the code being executed
   * @param pc the program counter
   * @param opcode the operation code, 0 past the end of the code
   * @return the result of the operation
   */
  private OperationResult executeOperationV2(
      final MessageFrame frame, final byte[] code, final int pc, final int opcode) {
    try {
      return switch (opcode) {
        case 0x00 -> StopOperation.staticOperation(frame);
        case 0x01 -> AddOperationV2.staticOperation(frame);
        case 0x02 -> MulOperationV2.staticOperation(frame);
        case 0x03 -> SubOperationV2.staticOperation(frame);
        case 0x04 -> DivOperationV2.staticOperation(frame);
        case 0x05 -> SDivOperationV2.staticOperation(frame);
        case 0x06 -> ModOperationV2.staticOperation(frame);
        case 0x07 -> SModOperationV2.staticOperation(frame);
        case 0x08 -> AddModOperationV2.staticOperation(frame);
        case 0x09 -> MulModOperationV2.staticOperation(frame);
        case 0x0a -> ExpOperationV2.staticOperation(frame, gasCalculator);
        case 0x0b -> SignExtendOperationV2.staticOperation(frame);
        case 0x0c, 0x0d, 0x0e, 0x0f -> InvalidOperation.invalidOperationResult(opcode);
        case 0x10 -> LtOperationV2.staticOperation(frame);
        case 0x11 -> GtOperationV2.staticOperation(frame);
        case 0x12 -> SLtOperationV2.staticOperation(frame);
        case 0x13 -> SGtOperationV2.staticOperation(frame);
        case 0x14 -> EqOperationV2.staticOperation(frame);
        case 0x15 -> IsZeroOperationV2.staticOperation(frame);
        case 0x16 -> AndOperationV2.staticOperation(frame);
        case 0x17 -> OrOperationV2.staticOperation(frame);
        case 0x18 -> XorOperationV2.staticOperation(frame);
        case 0x19 -> NotOperationV2.staticOperation(frame);
        case 0x1a -> ByteOperationV2.staticOperation(frame);
        case 0x1b ->
            enableConstantinople
                ? ShlOperationV2.staticOperation(frame)
                : InvalidOperation.invalidOperationResult(opcode);
        case 0x1c ->
            enableConstantinople
                ? ShrOperationV2.staticOperation(frame)
                : InvalidOperation.invalidOperationResult(opcode);
        case 0x1d ->
            enableConstantinople
                ? SarOperationV2.staticOperation(frame)
                : InvalidOperation.invalidOperationResult(opcode);
        case 0x1e ->
            enableOsaka
                ? CountLeadingZerosOperationV2.staticOperation(frame)
                : InvalidOperation.invalidOperationResult(opcode);
        case 0x50 -> PopOperationV2.staticOperation(frame);
        case 0x56 -> JumpOperationV2.staticOperation(frame);
        case 0x57 -> JumpiOperationV2.staticOperation(frame);
        case 0x5b -> JumpDestOperationV2.staticOperation(frame);
        case 0x5F -> // PUSH0
            enableShanghai
                ? PushOperationV2.SingleByte.staticOperation(frame, code, pc, 0)
                : InvalidOperation.invalidOperationResult(opcode);
        case 0x60 -> // PUSH1
            PushOperationV2.SingleByte.staticOperation(frame, code, pc, 1);
        case 0x61, // PUSH2-8
            0x62,
            0x63,
            0x64,
            0x65,
            0x66,
            0x67 ->
            PushOperationV2.SingleLimb.staticOperation(frame, code, pc, opcode - PUSH_BASE);
        case 0x68, // PUSH9-32
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
            0x7f ->
            PushOperationV2.MultiLimb.staticOperation(frame, code, pc, opcode - PUSH_BASE);
        case 0x80, // DUP1-16
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
            DupOperationV2.staticOperation(frame, opcode - DupOperationV2.DUP_BASE);
        case 0x90, // SWAP1-16
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
            SwapOperationV2.staticOperation(frame, opcode - SwapOperationV2.SWAP_BASE);
        case 0xe6 -> // DUPN (EIP-8024)
            enableAmsterdam
                ? DupNOperationV2.staticOperation(frame, code, pc)
                : InvalidOperation.invalidOperationResult(opcode);
        case 0xe7 -> // SWAPN (EIP-8024)
            enableAmsterdam
                ? SwapNOperationV2.staticOperation(frame, code, pc)
                : InvalidOperation.invalidOperationResult(opcode);
        case 0xe8 -> // EXCHANGE (EIP-8024)
            enableAmsterdam
                ? ExchangeOperationV2.staticOperation(frame, code, pc)
                : InvalidOperation.invalidOperationResult(opcode);
        default -> operationsV2[opcode].execute(frame, this);
      };
    } catch (final OverflowException oe) {
      return OVERFLOW_RESPONSE;
    } catch (final UnderflowException ue) {
      return UNDERFLOW_RESPONSE;
    }
  }

  /**
   * Get Operations (unsafe)
   *
   * @return Operations array
   */
  public Operation[] getOperationsUnsafe() {
    return operations.getOperations();
  }

  private OperationResult shiftOperation(
      final MessageFrame frame,
      final Function<MessageFrame, OperationResult> standard,
      final Function<MessageFrame, OperationResult> optimized) {
    return evmConfiguration.enableOptimizedOpcodes()
        ? optimized.apply(frame)
        : standard.apply(frame);
  }

  /**
   * Gets or creates code instance with a cached jump destination.
   *
   * @param codeHash the code hash
   * @param codeBytes the code bytes
   * @return the code instance with the cached jump destination
   */
  public Code getOrCreateCachedJumpDest(final Hash codeHash, final Bytes codeBytes) {
    if (Hash.EMPTY.getBytes().equals(codeHash.getBytes())) {
      return Code.EMPTY_CODE;
    }

    Code result = jumpDestOnlyCodeCache.getIfPresent(codeHash);
    if (result == null) {
      result = new Code(codeBytes);
      jumpDestOnlyCodeCache.put(codeHash, result);
    }

    return result;
  }
}
