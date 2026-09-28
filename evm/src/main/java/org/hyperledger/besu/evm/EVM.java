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

import static com.google.common.base.Preconditions.checkNotNull;
import static org.hyperledger.besu.evm.operation.PushOperation.PUSH_BASE;
import static org.hyperledger.besu.evm.operation.SwapOperation.SWAP_BASE;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.internal.JumpDestOnlyCodeCache;
import org.hyperledger.besu.evm.internal.OverflowException;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.log.EIP7708TransferLogEmitter;
import org.hyperledger.besu.evm.log.TransferLogEmitter;
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
import org.hyperledger.besu.evm.v2.operation.AddressOperationV2;
import org.hyperledger.besu.evm.v2.operation.AndOperationV2;
import org.hyperledger.besu.evm.v2.operation.BalanceOperationV2;
import org.hyperledger.besu.evm.v2.operation.BaseFeeOperationV2;
import org.hyperledger.besu.evm.v2.operation.BlobBaseFeeOperationV2;
import org.hyperledger.besu.evm.v2.operation.BlobHashOperationV2;
import org.hyperledger.besu.evm.v2.operation.BlockHashOperationV2;
import org.hyperledger.besu.evm.v2.operation.ByteOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallCodeOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallDataCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallDataLoadOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallDataSizeOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallValueOperationV2;
import org.hyperledger.besu.evm.v2.operation.CallerOperationV2;
import org.hyperledger.besu.evm.v2.operation.ChainIdOperationV2;
import org.hyperledger.besu.evm.v2.operation.ClzOperationV2;
import org.hyperledger.besu.evm.v2.operation.CodeCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.CodeSizeOperationV2;
import org.hyperledger.besu.evm.v2.operation.CoinbaseOperationV2;
import org.hyperledger.besu.evm.v2.operation.Create2OperationV2;
import org.hyperledger.besu.evm.v2.operation.CreateOperationV2;
import org.hyperledger.besu.evm.v2.operation.DelegateCallOperationV2;
import org.hyperledger.besu.evm.v2.operation.DifficultyOperationV2;
import org.hyperledger.besu.evm.v2.operation.DivOperationV2;
import org.hyperledger.besu.evm.v2.operation.DupNOperationV2;
import org.hyperledger.besu.evm.v2.operation.DupOperationV2;
import org.hyperledger.besu.evm.v2.operation.EqOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExchangeOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExpOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExtCodeCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExtCodeHashOperationV2;
import org.hyperledger.besu.evm.v2.operation.ExtCodeSizeOperationV2;
import org.hyperledger.besu.evm.v2.operation.GasLimitOperationV2;
import org.hyperledger.besu.evm.v2.operation.GasOperationV2;
import org.hyperledger.besu.evm.v2.operation.GasPriceOperationV2;
import org.hyperledger.besu.evm.v2.operation.GtOperationV2;
import org.hyperledger.besu.evm.v2.operation.InvalidOperationV2;
import org.hyperledger.besu.evm.v2.operation.IsZeroOperationV2;
import org.hyperledger.besu.evm.v2.operation.JumpDestOperationV2;
import org.hyperledger.besu.evm.v2.operation.JumpOperationV2;
import org.hyperledger.besu.evm.v2.operation.JumpiOperationV2;
import org.hyperledger.besu.evm.v2.operation.Keccak256OperationV2;
import org.hyperledger.besu.evm.v2.operation.LogOperationV2;
import org.hyperledger.besu.evm.v2.operation.LtOperationV2;
import org.hyperledger.besu.evm.v2.operation.MCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.MSizeOperationV2;
import org.hyperledger.besu.evm.v2.operation.MloadOperationV2;
import org.hyperledger.besu.evm.v2.operation.ModOperationV2;
import org.hyperledger.besu.evm.v2.operation.Mstore8OperationV2;
import org.hyperledger.besu.evm.v2.operation.MstoreOperationV2;
import org.hyperledger.besu.evm.v2.operation.MulModOperationV2;
import org.hyperledger.besu.evm.v2.operation.MulOperationV2;
import org.hyperledger.besu.evm.v2.operation.NotOperationV2;
import org.hyperledger.besu.evm.v2.operation.NumberOperationV2;
import org.hyperledger.besu.evm.v2.operation.OrOperationV2;
import org.hyperledger.besu.evm.v2.operation.OriginOperationV2;
import org.hyperledger.besu.evm.v2.operation.PayOperationV2;
import org.hyperledger.besu.evm.v2.operation.PcOperationV2;
import org.hyperledger.besu.evm.v2.operation.PopOperationV2;
import org.hyperledger.besu.evm.v2.operation.PrevRandaoOperationV2;
import org.hyperledger.besu.evm.v2.operation.Push0OperationV2;
import org.hyperledger.besu.evm.v2.operation.PushOperationV2;
import org.hyperledger.besu.evm.v2.operation.ReturnDataCopyOperationV2;
import org.hyperledger.besu.evm.v2.operation.ReturnDataSizeOperationV2;
import org.hyperledger.besu.evm.v2.operation.ReturnOperationV2;
import org.hyperledger.besu.evm.v2.operation.RevertOperationV2;
import org.hyperledger.besu.evm.v2.operation.SDivOperationV2;
import org.hyperledger.besu.evm.v2.operation.SLoadOperationV2;
import org.hyperledger.besu.evm.v2.operation.SModOperationV2;
import org.hyperledger.besu.evm.v2.operation.SStoreOperationV2;
import org.hyperledger.besu.evm.v2.operation.SarOperationV2;
import org.hyperledger.besu.evm.v2.operation.SelfBalanceOperationV2;
import org.hyperledger.besu.evm.v2.operation.SelfDestructOperationV2;
import org.hyperledger.besu.evm.v2.operation.SgtOperationV2;
import org.hyperledger.besu.evm.v2.operation.ShlOperationV2;
import org.hyperledger.besu.evm.v2.operation.ShrOperationV2;
import org.hyperledger.besu.evm.v2.operation.SignExtendOperationV2;
import org.hyperledger.besu.evm.v2.operation.SlotNumOperationV2;
import org.hyperledger.besu.evm.v2.operation.SltOperationV2;
import org.hyperledger.besu.evm.v2.operation.StaticCallOperationV2;
import org.hyperledger.besu.evm.v2.operation.StopOperationV2;
import org.hyperledger.besu.evm.v2.operation.SubOperationV2;
import org.hyperledger.besu.evm.v2.operation.SwapNOperationV2;
import org.hyperledger.besu.evm.v2.operation.SwapOperationV2;
import org.hyperledger.besu.evm.v2.operation.TLoadOperationV2;
import org.hyperledger.besu.evm.v2.operation.TStoreOperationV2;
import org.hyperledger.besu.evm.v2.operation.TimestampOperationV2;
import org.hyperledger.besu.evm.v2.operation.XorOperationV2;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
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

  // The costs the untraced v2 loop charges for the operations it runs inline, the same numbers the
  // v2 operations' results carry. Constants rather than fields of the gas calculator: as fields
  // they are loads C2 hoists to the loop's dispatch, where they run for every operation.
  private static final long BASE_TIER_GAS = 2L;
  private static final long VERY_LOW_TIER_GAS = 3L;
  private static final long LOW_TIER_GAS = 5L;
  private static final long MID_TIER_GAS = 8L;
  private static final long HIGH_TIER_GAS = 10L;
  private static final long JUMPDEST_GAS = 1L;

  // Reads and writes a big-endian word of a byte array, as memory and input data hold them.
  private static final VarHandle LONG_BE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

  private final OperationRegistry operations;
  private final GasCalculator gasCalculator;
  private final Operation endOfScriptStop;
  private final EvmConfiguration evmConfiguration;
  private final EvmSpecVersion evmSpecVersion;

  // Optimized operation flags
  private final boolean enableByzantium;
  private final boolean enableConstantinople;
  private final boolean enableIstanbul;
  private final boolean enableLondon;
  private final boolean enableParis;
  private final boolean enableShanghai;
  private final boolean enableCancun;
  private final boolean enableAmsterdam;
  private final boolean enableOsaka;

  // V2 operation instances that require constructor arguments
  private final ChainIdOperationV2 chainIdOperationV2;
  private final GasOperationV2 gasOperationV2;

  // Call, create and SLOAD instances are held rather than built per execution: the dispatch path
  // allocated a fresh operation object on every call and create, and re-derived SLOAD's warm and
  // cold costs from the gas calculator on every load.
  private final CallOperationV2 callOperationV2;
  private final CallCodeOperationV2 callCodeOperationV2;
  private final DelegateCallOperationV2 delegateCallOperationV2;
  private final StaticCallOperationV2 staticCallOperationV2;
  private final CreateOperationV2 createOperationV2;
  private final Create2OperationV2 create2OperationV2;
  private final SLoadOperationV2 sLoadOperationV2;

  private final JumpDestOnlyCodeCache jumpDestOnlyCodeCache;

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

    enableByzantium = EvmSpecVersion.BYZANTIUM.ordinal() <= evmSpecVersion.ordinal();
    enableConstantinople = EvmSpecVersion.CONSTANTINOPLE.ordinal() <= evmSpecVersion.ordinal();
    enableIstanbul = EvmSpecVersion.ISTANBUL.ordinal() <= evmSpecVersion.ordinal();
    enableLondon = EvmSpecVersion.LONDON.ordinal() <= evmSpecVersion.ordinal();
    enableParis = EvmSpecVersion.PARIS.ordinal() <= evmSpecVersion.ordinal();
    enableShanghai = EvmSpecVersion.SHANGHAI.ordinal() <= evmSpecVersion.ordinal();
    enableCancun = EvmSpecVersion.CANCUN.ordinal() <= evmSpecVersion.ordinal();
    enableAmsterdam = EvmSpecVersion.AMSTERDAM.ordinal() <= evmSpecVersion.ordinal();
    enableOsaka = EvmSpecVersion.OSAKA.ordinal() <= evmSpecVersion.ordinal();

    // Pre-compute V2 operation instances that require constructor arguments.
    // ChainIdOperation is only registered for Istanbul+, so the instanceof check is the gate.
    Operation chainIdOp = operations.get(ChainIdOperation.OPCODE);
    if (chainIdOp instanceof ChainIdOperation cid) {
      chainIdOperationV2 = new ChainIdOperationV2(gasCalculator, cid.getChainId());
    } else {
      chainIdOperationV2 = null;
    }
    gasOperationV2 = new GasOperationV2(gasCalculator);
    callOperationV2 = new CallOperationV2(gasCalculator);
    callCodeOperationV2 = new CallCodeOperationV2(gasCalculator);
    delegateCallOperationV2 = new DelegateCallOperationV2(gasCalculator);
    staticCallOperationV2 = new StaticCallOperationV2(gasCalculator);
    createOperationV2 = new CreateOperationV2(gasCalculator);
    create2OperationV2 = new Create2OperationV2(gasCalculator);
    sLoadOperationV2 = new SLoadOperationV2(gasCalculator);
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
    if (evmConfiguration.enableEvmV2()) {
      frame.ensureV2Stack();
      runToHaltV2(frame, operationTracer);
      return;
    }
    evmSpecVersion.maybeWarnVersion();

    byte[] code = frame.getCode().getBytes().toArrayUnsafe();
    Operation[] operationArray = operations.getOperations();
    frame.setRecordUpdatesForTracer(operationTracer.isEnabled());
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
      operationTracer.tracePreExecution(frame);

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
      operationTracer.tracePostExecution(frame, result);
    }
  }

  /**
   * Runs the frame on the EVM v2 interpreter, through the untraced loop unless the tracer wants the
   * per-operation hooks. A tracer that only wants the transaction-level hooks reports itself
   * disabled and gets the untraced loop.
   *
   * @param frame the frame
   * @param operationTracer the tracer
   */
  private void runToHaltV2(final MessageFrame frame, final OperationTracer operationTracer) {
    evmSpecVersion.maybeWarnVersion();
    final boolean tracing = operationTracer.isEnabled();
    frame.setRecordUpdatesForTracer(tracing);
    if (tracing) {
      runToHaltV2Traced(frame, operationTracer);
    } else {
      runToHaltV2Untraced(frame);
    }
  }

  // The arms of the untraced v2 loop. It switches on an opcode's arm rather than on the opcode, so
  // that the switch's table has an entry per arm instead of one per opcode up to the highest one
  // handled inline: that table is bytecode of the loop and counts against its inlining budget.
  // Opcodes without an arm map to 0 and take the general path.
  private static final int ARM_PUSH = 1;
  private static final int ARM_DUP = 2;
  private static final int ARM_SWAP = 3;
  private static final int ARM_JUMPDEST = 4;
  private static final int ARM_POP = 5;
  private static final int ARM_PUSH2 = 6;
  private static final int ARM_JUMPI = 7;
  private static final int ARM_JUMP = 8;
  private static final int ARM_ADD = 9;
  private static final int ARM_MSTORE = 10;
  private static final int ARM_ISZERO = 11;
  private static final int ARM_AND = 12;
  private static final int ARM_MLOAD = 13;
  private static final int ARM_SUB = 14;
  private static final int ARM_EQ = 15;
  private static final int ARM_COMPARE = 16;
  private static final int ARM_SHIFT = 17;
  private static final int ARM_CALLDATALOAD = 18;
  private static final int ARM_PUSH0 = 19;
  private static final int ARM_MUL = 20;
  private static final int ARM_CALLDATASIZE = 21;
  private static final int ARM_DIV_MOD = 22;
  private static final int ARM_OR = 23;
  private static final int ARM_NOT = 24;
  private static final int ARM_GAS = 25;
  private static final int ARM_SIGNEXTEND = 26;
  private static final int ARM_XOR = 27;
  private static final int ARM_PUSH1 = 28;

  // Per opcode: the arm in the low byte, and above it a bias of minus the arm times 16 for the
  // untraced loop's stack and code indices; see runToHaltV2Untraced.
  private static final int[] DISPATCH = new int[256];

  static {
    final byte[] arms = new byte[256];
    for (int op = 0x60; op <= 0x7f; op++) {
      arms[op] = ARM_PUSH;
    }
    arms[0x60] = ARM_PUSH1;
    arms[0x61] = ARM_PUSH2;
    for (int op = 0x80; op <= 0x8f; op++) {
      arms[op] = ARM_DUP;
    }
    for (int op = 0x90; op <= 0x9f; op++) {
      arms[op] = ARM_SWAP;
    }
    arms[0x5b] = ARM_JUMPDEST;
    arms[0x50] = ARM_POP;
    arms[0x57] = ARM_JUMPI;
    arms[0x56] = ARM_JUMP;
    arms[0x01] = ARM_ADD;
    arms[0x52] = ARM_MSTORE;
    arms[0x15] = ARM_ISZERO;
    arms[0x16] = ARM_AND;
    arms[0x51] = ARM_MLOAD;
    arms[0x03] = ARM_SUB;
    arms[0x14] = ARM_EQ;
    arms[0x10] = ARM_COMPARE;
    arms[0x11] = ARM_COMPARE;
    arms[0x12] = ARM_COMPARE;
    arms[0x13] = ARM_COMPARE;
    arms[0x1b] = ARM_SHIFT;
    arms[0x1c] = ARM_SHIFT;
    arms[0x1d] = ARM_SHIFT;
    arms[0x35] = ARM_CALLDATALOAD;
    arms[0x5f] = ARM_PUSH0;
    arms[0x02] = ARM_MUL;
    arms[0x36] = ARM_CALLDATASIZE;
    arms[0x04] = ARM_DIV_MOD;
    arms[0x06] = ARM_DIV_MOD;
    arms[0x17] = ARM_OR;
    arms[0x19] = ARM_NOT;
    arms[0x5a] = ARM_GAS;
    arms[0x0b] = ARM_SIGNEXTEND;
    arms[0x18] = ARM_XOR;
    for (int op = 0; op < 256; op++) {
      // CALLDATALOAD shares MLOAD's case, which cancels MLOAD's bias
      final int biasArm = arms[op] == ARM_CALLDATALOAD ? ARM_MLOAD : arms[op];
      DISPATCH[op] = (-(biasArm << 4) << 8) | arms[op];
    }
  }

  /**
   * The v2 loop for untraced execution, which is every block import. The program counter, the
   * remaining gas and the stack pointer live in locals, and the cheap operations that neither read
   * world state nor need more than the stack, the code, the input data or already expanded memory
   * run inline, which is nearly all operations executed on mainnet. An inline arm handles only the
   * case where its operation succeeds; anything else, and every other operation, goes through
   * {@link #executeOperationV2} with the state handed back to the frame, so halting behaviour is
   * defined in one place for both loops.
   *
   * <p>No inline arm allocates or calls anything C2 does not inline: one such call is enough for C2
   * to keep all of the loop's locals in memory rather than in registers, which costs more than the
   * dispatch itself. The arms are written out rather than calling the stack helpers, because C2
   * inlines a helper above 35 bytes of bytecode only at call sites it rates hot. For the same
   * reason no local is read after the general path's call before it is reloaded.
   *
   * <p>C2 also stops inlining into a method once its own bytecode and everything inlined into it
   * reach 8000 bytes, after which even the frame's getters become calls. That budget, not the gas
   * cost, decides which operations are inline: the cheap operations that mainnet executes rarely,
   * each well under a tenth of a percent, take the general path.
   */
  private void runToHaltV2Untraced(final MessageFrame frame) {
    if (frame.getState() != MessageFrame.State.CODE_EXECUTING) {
      return;
    }
    final Code codeObject = frame.getCode();
    // builds the tables the PUSH and jump arms read from codeObject
    codeObject.pushBits();
    codeObject.jumpDestinations();
    final byte[] code = codeObject.getBytes().toArrayUnsafe();
    final boolean constantinople = enableConstantinople;
    final boolean shanghai = enableShanghai;
    // The stack array is stable for one call: a frame returns it to the pool only once execution
    // has left the frame for good. It holds exactly the maximum number of items.
    final long[] s = frame.stackDataV2();
    int sp = frame.stackTopV2();
    int pc = frame.getPC();
    long gas = frame.getRemainingGas();
    while (true) {
      final int opcode = pc < code.length ? code[pc] & 0xff : 0;
      // What an inline arm that succeeds charges, how far it moves the program counter and how
      // many items it adds. The arms leave the updates to the common tail below: the same update
      // written out in every arm is one computation, which C2 places before the dispatch.
      long cost = -1;
      int step = 0;
      int delta = 0;
      // The dispatch entry holds the arm and a bias, which the arm cancels again. Stack and code
      // indices built on a biased pointer differ from arm to arm as far as C2 can tell, so each
      // arm computes its own. Built on sp and pc directly, the arms share them, and C2 computes
      // every arm's indices ahead of the dispatch, where they take the registers the loop's state
      // needs. The arms are in the order of how often mainnet executes them, because C2 inlines
      // in bytecode order and stops when the method reaches its size budget.
      final int entry = DISPATCH[opcode];
      final int bias = entry >> 8;
      final int base = (sp << 2) + bias;
      final int pcBase = pc + bias;
      switch (entry & 0xff) {
        case ARM_PUSH1 -> {
          if ((sp << 2) < s.length && gas >= VERY_LOW_TIER_GAS) {
            final int dst = base + (ARM_PUSH1 << 4);
            final int at = pcBase + (ARM_PUSH1 << 4);
            s[dst] = 0;
            s[dst + 1] = 0;
            s[dst + 2] = 0;
            s[dst + 3] = at + 1 < code.length ? code[at + 1] & 0xff : 0;
            cost = VERY_LOW_TIER_GAS;
            step = 2;
            delta = 1;
          }
        }
        case ARM_PUSH -> { // PUSH3-32; the immediate was decoded when the code was analysed
          if ((sp << 2) < s.length && gas >= VERY_LOW_TIER_GAS) {
            final int dst = base + (ARM_PUSH << 4);
            final int block = pc >>> 6;
            final long value =
                codeObject
                    .pushValues[
                    codeObject.pushBase[block]
                        + Long.bitCount(codeObject.pushBits[block] & ((1L << (pc & 63)) - 1))];
            if (opcode <= 0x67) { // PUSH3-8, the value itself
              s[dst] = 0;
              s[dst + 1] = 0;
              s[dst + 2] = 0;
              s[dst + 3] = value;
            } else { // PUSH9-32, where the wide values start
              final long[] wide = codeObject.pushWide;
              final int o = (int) value;
              s[dst] = wide[o];
              s[dst + 1] = wide[o + 1];
              s[dst + 2] = wide[o + 2];
              s[dst + 3] = wide[o + 3];
            }
            cost = VERY_LOW_TIER_GAS;
            step = opcode - 0x5e;
            delta = 1;
          }
        }
        case ARM_DUP -> {
          final int depth = opcode - 0x7f;
          if (sp >= depth && (sp << 2) < s.length && gas >= VERY_LOW_TIER_GAS) {
            final int from = base + (ARM_DUP << 4) - (depth << 2);
            final int to = base + (ARM_DUP << 4);
            s[to] = s[from];
            s[to + 1] = s[from + 1];
            s[to + 2] = s[from + 2];
            s[to + 3] = s[from + 3];
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = 1;
          }
        }
        case ARM_SWAP -> {
          final int depth = opcode - 0x8f;
          if (sp > depth && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_SWAP << 4) - 4;
            final int b = base + (ARM_SWAP << 4) - 4 - (depth << 2);
            long t = s[a];
            s[a] = s[b];
            s[b] = t;
            t = s[a + 1];
            s[a + 1] = s[b + 1];
            s[b + 1] = t;
            t = s[a + 2];
            s[a + 2] = s[b + 2];
            s[b + 2] = t;
            t = s[a + 3];
            s[a + 3] = s[b + 3];
            s[b + 3] = t;
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = 0;
          }
        }
        case ARM_JUMPDEST -> {
          if (gas >= JUMPDEST_GAS) {
            cost = JUMPDEST_GAS;
            step = 1;
            delta = 0;
          }
        }
        case ARM_POP -> {
          if (sp >= 1 && gas >= BASE_TIER_GAS) {
            cost = BASE_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        case ARM_PUSH2 -> { // and the PUSH2 JUMP and PUSH2 JUMPI that follow it
          final int at = pcBase + (ARM_PUSH2 << 4);
          if ((sp << 2) < s.length && gas >= VERY_LOW_TIER_GAS && at + 2 < code.length) {
            final int immediate = (code[at + 1] & 0xff) << 8 | (code[at + 2] & 0xff);
            final int next = at + 3 < code.length ? code[at + 3] & 0xff : 0;
            if (next == 0x56) {
              if (gas >= VERY_LOW_TIER_GAS + MID_TIER_GAS + JUMPDEST_GAS
                  && isJumpDestination(codeObject.jumpDestBitMask, immediate, code.length)) {
                // PUSH2 JUMP JUMPDEST, without the destination going through the stack
                cost = VERY_LOW_TIER_GAS + MID_TIER_GAS + JUMPDEST_GAS;
                step = immediate + 1 - pc;
                delta = 0;
                break;
              }
            } else if (next == 0x57 && sp >= 1 && gas >= VERY_LOW_TIER_GAS + HIGH_TIER_GAS) {
              final int c = base + (ARM_PUSH2 << 4) - 4;
              if ((s[c] | s[c + 1] | s[c + 2] | s[c + 3]) == 0) {
                // PUSH2 JUMPI, not taken
                cost = VERY_LOW_TIER_GAS + HIGH_TIER_GAS;
                step = 4;
                delta = -1;
                break;
              }
              if (gas >= VERY_LOW_TIER_GAS + HIGH_TIER_GAS + JUMPDEST_GAS
                  && isJumpDestination(codeObject.jumpDestBitMask, immediate, code.length)) {
                // PUSH2 JUMPI JUMPDEST, taken
                cost = VERY_LOW_TIER_GAS + HIGH_TIER_GAS + JUMPDEST_GAS;
                step = immediate + 1 - pc;
                delta = -1;
                break;
              }
            }
            final int dst = base + (ARM_PUSH2 << 4);
            s[dst] = 0;
            s[dst + 1] = 0;
            s[dst + 2] = 0;
            s[dst + 3] = immediate;
            cost = VERY_LOW_TIER_GAS;
            step = 3;
            delta = 1;
          }
        }
        case ARM_JUMPI -> { // and the JUMPDEST it lands on
          if (sp >= 2 && gas >= HIGH_TIER_GAS) {
            final int d = base + (ARM_JUMPI << 4) - 4;
            final int c = d - 4;
            if ((s[c] | s[c + 1] | s[c + 2] | s[c + 3]) == 0) {
              cost = HIGH_TIER_GAS;
              step = 1;
              delta = -2;
              break;
            }
            final long destination = s[d + 3];
            if ((s[d] | s[d + 1] | s[d + 2]) == 0
                && gas >= HIGH_TIER_GAS + JUMPDEST_GAS
                && isJumpDestination(codeObject.jumpDestBitMask, destination, code.length)) {
              cost = HIGH_TIER_GAS + JUMPDEST_GAS;
              step = (int) destination + 1 - pc;
              delta = -2;
            }
          }
        }
        case ARM_JUMP -> { // and the JUMPDEST it lands on
          if (sp >= 1 && gas >= MID_TIER_GAS + JUMPDEST_GAS) {
            final int d = base + (ARM_JUMP << 4) - 4;
            final long destination = s[d + 3];
            if ((s[d] | s[d + 1] | s[d + 2]) == 0
                && isJumpDestination(codeObject.jumpDestBitMask, destination, code.length)) {
              cost = MID_TIER_GAS + JUMPDEST_GAS;
              step = (int) destination + 1 - pc;
              delta = -1;
            }
          }
        }
        case ARM_ADD -> {
          if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_ADD << 4) - 4;
            final int b = a - 4;
            final long x0 = s[a + 3];
            final long y0 = s[b + 3];
            final long r0 = x0 + y0;
            final long c0 = ((x0 & y0) | ((x0 | y0) & ~r0)) >>> 63;
            final long x1 = s[a + 2];
            final long y1 = s[b + 2];
            final long r1 = x1 + y1 + c0;
            final long c1 = ((x1 & y1) | ((x1 | y1) & ~r1)) >>> 63;
            final long x2 = s[a + 1];
            final long y2 = s[b + 1];
            final long r2 = x2 + y2 + c1;
            final long c2 = ((x2 & y2) | ((x2 | y2) & ~r2)) >>> 63;
            s[b] = s[a] + s[b] + c2;
            s[b + 1] = r2;
            s[b + 2] = r1;
            s[b + 3] = r0;
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        case ARM_MSTORE -> { // within the memory already expanded
          if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_MSTORE << 4) - 4;
            final long location = s[a + 3];
            if ((s[a] | s[a + 1] | s[a + 2]) == 0
                && location >= 0
                && location <= frame.memoryByteSize() - 32) {
              final byte[] memory = frame.memoryArrayV2();
              final int i = (int) location;
              LONG_BE.set(memory, i, s[a - 4]);
              LONG_BE.set(memory, i + 8, s[a - 3]);
              LONG_BE.set(memory, i + 16, s[a - 2]);
              LONG_BE.set(memory, i + 24, s[a - 1]);
              cost = VERY_LOW_TIER_GAS;
              step = 1;
              delta = -2;
            }
          }
        }
        case ARM_ISZERO -> {
          if (sp >= 1 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_ISZERO << 4) - 4;
            final long zero = (s[a] | s[a + 1] | s[a + 2] | s[a + 3]) == 0 ? 1L : 0L;
            s[a] = 0;
            s[a + 1] = 0;
            s[a + 2] = 0;
            s[a + 3] = zero;
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = 0;
          }
        }
        case ARM_AND -> {
          if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_AND << 4) - 4;
            s[a - 4] &= s[a];
            s[a - 3] &= s[a + 1];
            s[a - 2] &= s[a + 2];
            s[a - 1] &= s[a + 3];
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        case ARM_MLOAD, ARM_CALLDATALOAD -> {
          // Both read a word from an array into the top item: MLOAD from memory already expanded,
          // CALLDATALOAD from the input data unless the word straddles its end. They share the
          // reads because each read inlines a chain of VarHandle methods into the loop.
          if (sp >= 1 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_MLOAD << 4) - 4;
            final long location = s[a + 3];
            final boolean small = (s[a] | s[a + 1] | s[a + 2]) == 0 && location >= 0;
            final byte[] source;
            final boolean readable;
            if (opcode == 0x51) {
              source = frame.memoryArrayV2();
              readable = small && location <= frame.memoryByteSize() - 32;
            } else {
              source = frame.inputDataArrayIfPresent();
              if (source != null && (!small || location >= source.length)) {
                // past the end of the input
                s[a] = 0;
                s[a + 1] = 0;
                s[a + 2] = 0;
                s[a + 3] = 0;
                cost = VERY_LOW_TIER_GAS;
                step = 1;
                delta = 0;
                break;
              }
              readable = source != null && source.length - location >= 32;
            }
            if (readable) {
              final int i = (int) location;
              s[a] = (long) LONG_BE.get(source, i);
              s[a + 1] = (long) LONG_BE.get(source, i + 8);
              s[a + 2] = (long) LONG_BE.get(source, i + 16);
              s[a + 3] = (long) LONG_BE.get(source, i + 24);
              cost = VERY_LOW_TIER_GAS;
              step = 1;
              delta = 0;
            }
          }
        }
        case ARM_SUB -> {
          if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_SUB << 4) - 4;
            final int b = a - 4;
            final long x0 = s[a + 3];
            final long y0 = s[b + 3];
            final long r0 = x0 - y0;
            final long b0 = ((~x0 & y0) | ((~x0 | y0) & r0)) >>> 63;
            final long x1 = s[a + 2];
            final long y1 = s[b + 2];
            final long r1 = x1 - y1 - b0;
            final long b1 = ((~x1 & y1) | ((~x1 | y1) & r1)) >>> 63;
            final long x2 = s[a + 1];
            final long y2 = s[b + 1];
            final long r2 = x2 - y2 - b1;
            final long b2 = ((~x2 & y2) | ((~x2 | y2) & r2)) >>> 63;
            s[b] = s[a] - s[b] - b2;
            s[b + 1] = r2;
            s[b + 2] = r1;
            s[b + 3] = r0;
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        case ARM_EQ -> {
          if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_EQ << 4) - 4;
            final int b = a - 4;
            final long equal =
                ((s[a] ^ s[b])
                            | (s[a + 1] ^ s[b + 1])
                            | (s[a + 2] ^ s[b + 2])
                            | (s[a + 3] ^ s[b + 3]))
                        == 0
                    ? 1L
                    : 0L;
            s[b] = 0;
            s[b + 1] = 0;
            s[b + 2] = 0;
            s[b + 3] = equal;
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        case ARM_COMPARE -> { // LT, GT, SLT, SGT
          if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_COMPARE << 4) - 4;
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
            s[b] = 0;
            s[b + 1] = 0;
            s[b + 2] = 0;
            // LT and SLT are the even opcodes, GT and SGT the odd ones
            s[b + 3] = ((opcode & 1) == 0 ? comparison < 0 : comparison > 0) ? 1L : 0L;
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        case ARM_SHIFT -> { // SHL, SHR, SAR, Constantinople onwards
          if (constantinople && sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_SHIFT << 4) - 4;
            final int v = a - 4;
            long w3 = s[v];
            long w2 = s[v + 1];
            long w1 = s[v + 2];
            long w0 = s[v + 3];
            final long fill = opcode == 0x1d ? w3 >> 63 : 0L;
            final long shift = s[a + 3];
            if ((s[a] | s[a + 1] | s[a + 2]) != 0 || shift < 0 || shift >= 256) {
              w3 = fill;
              w2 = fill;
              w1 = fill;
              w0 = fill;
            } else {
              final int bits = (int) shift & 63;
              final int limbs = (int) shift >>> 6;
              if (opcode == 0x1b) {
                // whole limbs first, towards the most significant
                w3 = limbs == 0 ? w3 : limbs == 1 ? w2 : limbs == 2 ? w1 : w0;
                w2 = limbs == 0 ? w2 : limbs == 1 ? w1 : limbs == 2 ? w0 : 0;
                w1 = limbs == 0 ? w1 : limbs == 1 ? w0 : 0;
                w0 = limbs == 0 ? w0 : 0;
                if (bits != 0) {
                  w3 = w3 << bits | w2 >>> -bits;
                  w2 = w2 << bits | w1 >>> -bits;
                  w1 = w1 << bits | w0 >>> -bits;
                  w0 <<= bits;
                }
              } else {
                // whole limbs first, towards the least significant
                w0 = limbs == 0 ? w0 : limbs == 1 ? w1 : limbs == 2 ? w2 : w3;
                w1 = limbs == 0 ? w1 : limbs == 1 ? w2 : limbs == 2 ? w3 : fill;
                w2 = limbs == 0 ? w2 : limbs == 1 ? w3 : fill;
                w3 = limbs == 0 ? w3 : fill;
                if (bits != 0) {
                  w0 = w0 >>> bits | w1 << -bits;
                  w1 = w1 >>> bits | w2 << -bits;
                  w2 = w2 >>> bits | w3 << -bits;
                  w3 = opcode == 0x1c ? w3 >>> bits : w3 >> bits;
                }
              }
            }
            s[v] = w3;
            s[v + 1] = w2;
            s[v + 2] = w1;
            s[v + 3] = w0;
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        case ARM_PUSH0 -> { // Shanghai onwards
          if (shanghai && (sp << 2) < s.length && gas >= BASE_TIER_GAS) {
            final int dst = base + (ARM_PUSH0 << 4);
            s[dst] = 0;
            s[dst + 1] = 0;
            s[dst + 2] = 0;
            s[dst + 3] = 0;
            cost = BASE_TIER_GAS;
            step = 1;
            delta = 1;
          }
        }
        case ARM_MUL -> { // when either factor fits in 64 bits
          if (sp >= 2 && gas >= LOW_TIER_GAS) {
            final int a = base + (ARM_MUL << 4) - 4;
            final int b = a - 4;
            final int wide;
            if ((s[a] | s[a + 1] | s[a + 2]) == 0) {
              wide = b;
            } else if ((s[b] | s[b + 1] | s[b + 2]) == 0) {
              wide = a;
            } else {
              wide = -1;
            }
            if (wide >= 0) {
              final long m = s[(wide == a ? b : a) + 3];
              final long x3 = s[wide];
              final long x2 = s[wide + 1];
              final long x1 = s[wide + 2];
              final long x0 = s[wide + 3];
              final long h0 = Math.unsignedMultiplyHigh(x0, m);
              final long h1 = Math.unsignedMultiplyHigh(x1, m);
              final long h2 = Math.unsignedMultiplyHigh(x2, m);
              final long l1 = x1 * m;
              final long r1 = l1 + h0;
              // a high half is at most 2^64 - 2, so adding a carry to one cannot overflow
              final long l2 = x2 * m;
              final long r2 = l2 + h1 + (Long.compareUnsigned(r1, l1) < 0 ? 1L : 0L);
              s[b] = x3 * m + h2 + (Long.compareUnsigned(r2, l2) < 0 ? 1L : 0L);
              s[b + 1] = r2;
              s[b + 2] = r1;
              s[b + 3] = x0 * m;
              cost = LOW_TIER_GAS;
              step = 1;
              delta = -1;
            }
          }
        }
        case ARM_CALLDATASIZE -> {
          final byte[] data = frame.inputDataArrayIfPresent();
          if (data != null && (sp << 2) < s.length && gas >= BASE_TIER_GAS) {
            final int dst = base + (ARM_CALLDATASIZE << 4);
            s[dst] = 0;
            s[dst + 1] = 0;
            s[dst + 2] = 0;
            s[dst + 3] = data.length;
            cost = BASE_TIER_GAS;
            step = 1;
            delta = 1;
          }
        }
        case ARM_DIV_MOD -> { // when both operands fit in 64 bits
          if (sp >= 2 && gas >= LOW_TIER_GAS) {
            final int a = base + (ARM_DIV_MOD << 4) - 4;
            final int b = a - 4;
            if ((s[a] | s[a + 1] | s[a + 2] | s[b] | s[b + 1] | s[b + 2]) == 0) {
              final long x = s[a + 3];
              final long y = s[b + 3];
              s[b + 3] =
                  y == 0
                      ? 0L
                      : opcode == 0x04 ? Long.divideUnsigned(x, y) : Long.remainderUnsigned(x, y);
              cost = LOW_TIER_GAS;
              step = 1;
              delta = -1;
            }
          }
        }
        case ARM_OR -> {
          if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_OR << 4) - 4;
            s[a - 4] |= s[a];
            s[a - 3] |= s[a + 1];
            s[a - 2] |= s[a + 2];
            s[a - 1] |= s[a + 3];
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        case ARM_NOT -> {
          if (sp >= 1 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_NOT << 4) - 4;
            s[a] = ~s[a];
            s[a + 1] = ~s[a + 1];
            s[a + 2] = ~s[a + 2];
            s[a + 3] = ~s[a + 3];
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = 0;
          }
        }
        case ARM_GAS -> { // what is left once GAS itself is paid
          if ((sp << 2) < s.length && gas >= BASE_TIER_GAS) {
            final int dst = base + (ARM_GAS << 4);
            s[dst] = 0;
            s[dst + 1] = 0;
            s[dst + 2] = 0;
            s[dst + 3] = gas - BASE_TIER_GAS;
            cost = BASE_TIER_GAS;
            step = 1;
            delta = 1;
          }
        }
        case ARM_SIGNEXTEND -> {
          if (sp >= 2 && gas >= LOW_TIER_GAS) {
            final int a = base + (ARM_SIGNEXTEND << 4) - 4;
            final int v = a - 4;
            final long index = s[a + 3];
            if ((s[a] | s[a + 1] | s[a + 2]) == 0 && index >= 0 && index < 31) {
              // the sign bit and the limb holding it, limbs counted from the least significant
              final int signBit = (int) index * 8 + 7;
              final int limb = v + 3 - (signBit >>> 6);
              final long below = -1L >>> (63 - (signBit & 63));
              final long fill = (s[limb] >>> (signBit & 63) & 1L) == 0 ? 0L : -1L;
              s[limb] = (s[limb] & below) | (fill & ~below);
              // and every limb above it
              if (limb > v) {
                s[v] = fill;
              }
              if (limb > v + 1) {
                s[v + 1] = fill;
              }
              if (limb > v + 2) {
                s[v + 2] = fill;
              }
            }
            cost = LOW_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
        case ARM_XOR -> {
          if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
            final int a = base + (ARM_XOR << 4) - 4;
            s[a - 4] ^= s[a];
            s[a - 3] ^= s[a + 1];
            s[a - 2] ^= s[a + 2];
            s[a - 1] ^= s[a + 3];
            cost = VERY_LOW_TIER_GAS;
            step = 1;
            delta = -1;
          }
        }
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
      sp = frame.stackTopV2();
      pc = frame.getPC();
      gas = frame.getRemainingGas();
    }
  }

  /**
   * The v2 loop for traced execution, which calls the tracer around every operation and therefore
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
      completeOperationV2(frame, result);
      operationTracer.tracePostExecution(frame, result);
    }
  }

  private static boolean isJumpDestination(
      final long[] jumpDestBitMask, final long destination, final int codeSize) {
    return destination >= 0
        && destination < codeSize
        && (jumpDestBitMask[(int) (destination >>> 6)] & 1L << destination) != 0L;
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
   * Executes one operation against the state held in the frame, the most frequent ones in this
   * method and the rest in {@link #coldOperation}.
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
            DupOperationV2.staticOperation(
                frame, frame.stackDataV2(), opcode - DupOperationV2.DUP_BASE);
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
            SwapOperationV2.staticOperation(
                frame, frame.stackDataV2(), opcode - SwapOperationV2.SWAP_BASE);
        case 0x5b -> JumpDestOperationV2.staticOperation(frame);
        case 0x50 -> PopOperationV2.staticOperation(frame);
        case 0x57 -> JumpiOperationV2.staticOperation(frame, frame.stackDataV2());
        case 0x56 -> JumpOperationV2.staticOperation(frame, frame.stackDataV2());
        case 0x01 -> AddOperationV2.staticOperation(frame);
        case 0x52 -> MstoreOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator);
        case 0x15 -> IsZeroOperationV2.staticOperation(frame, frame.stackDataV2());
        case 0x16 -> AndOperationV2.staticOperation(frame, frame.stackDataV2());
        case 0x51 -> MloadOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator);
        case 0x03 -> SubOperationV2.staticOperation(frame);
        case 0x14 -> EqOperationV2.staticOperation(frame, frame.stackDataV2());
        case 0x10 -> LtOperationV2.staticOperation(frame, frame.stackDataV2());
        case 0x1b ->
            enableConstantinople
                ? ShlOperationV2.staticOperation(frame)
                : InvalidOperation.invalidOperationResult(opcode);
        case 0x11 -> GtOperationV2.staticOperation(frame, frame.stackDataV2());
        case 0x5f ->
            enableShanghai
                ? Push0OperationV2.staticOperation(frame, frame.stackDataV2())
                : InvalidOperation.invalidOperationResult(opcode);
        case 0x35 -> CallDataLoadOperationV2.staticOperation(frame, frame.stackDataV2());
        case 0x1c ->
            enableConstantinople
                ? ShrOperationV2.staticOperation(frame)
                : InvalidOperation.invalidOperationResult(opcode);
        case 0x09 -> MulModOperationV2.staticOperation(frame);
        case 0x02 -> MulOperationV2.staticOperation(frame);
        case 0x54 -> sLoadOperationV2.execute(frame, this);
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
            PushOperationV2.staticOperation(
                frame, frame.stackDataV2(), code, pc, opcode - PushOperationV2.PUSH_BASE);
        default -> coldOperation(frame, opcode, code, pc);
      };
    } catch (final OverflowException oe) {
      return OVERFLOW_RESPONSE;
    } catch (final UnderflowException ue) {
      return UNDERFLOW_RESPONSE;
    }
  }

  /**
   * The arms outside the hot set, in their own method so they get their own 8000 byte budget
   * instead of spending the interpreter loop's. Measured over 400 mainnet blocks these account for
   * about 5% of dispatches between them.
   *
   * @param frame the message frame
   * @param opcode the opcode to execute
   * @param code the code being executed
   * @param pc the program counter, for the arms that read their immediate
   * @return the operation result
   */
  private OperationResult coldOperation(
      final MessageFrame frame, final int opcode, final byte[] code, final int pc) {
    final Operation[] operationArray = operations.getOperations();
    return switch (opcode) {
      case 0x04 -> DivOperationV2.staticOperation(frame);
      case 0x05 -> SDivOperationV2.staticOperation(frame);
      case 0x06 -> ModOperationV2.staticOperation(frame);
      case 0x07 -> SModOperationV2.staticOperation(frame);
      case 0x08 -> AddModOperationV2.staticOperation(frame);
      case 0x0a -> ExpOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator);
      case 0x0b -> SignExtendOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x12 -> SltOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x13 -> SgtOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x17 -> OrOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x18 -> XorOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x19 -> NotOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x1a -> ByteOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x1d ->
          enableConstantinople
              ? SarOperationV2.staticOperation(frame)
              : InvalidOperation.invalidOperationResult(opcode);
      case 0x1e ->
          enableOsaka
              ? ClzOperationV2.staticOperation(frame, frame.stackDataV2())
              : InvalidOperation.invalidOperationResult(opcode);
      case 0xe6 -> // DUPN (EIP-8024)
          enableAmsterdam
              ? DupNOperationV2.staticOperation(frame, frame.stackDataV2(), code, pc)
              : InvalidOperation.invalidOperationResult(opcode);
      case 0xe7 -> // SWAPN (EIP-8024)
          enableAmsterdam
              ? SwapNOperationV2.staticOperation(frame, frame.stackDataV2(), code, pc)
              : InvalidOperation.invalidOperationResult(opcode);
      case 0xe8 -> // EXCHANGE (EIP-8024)
          enableAmsterdam
              ? ExchangeOperationV2.staticOperation(frame, frame.stackDataV2(), code, pc)
              : InvalidOperation.invalidOperationResult(opcode);
      case 0x53 -> Mstore8OperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator);
      case 0x5e ->
          enableCancun
              ? MCopyOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator)
              : InvalidOperation.invalidOperationResult(opcode);
      case 0xf0 ->
          CreateOperationV2.staticOperation(frame, frame.stackDataV2(), createOperationV2, this);
      case 0xf1 ->
          CallOperationV2.staticOperation(frame, frame.stackDataV2(), callOperationV2, this);
      case 0xf2 ->
          CallCodeOperationV2.staticOperation(
              frame, frame.stackDataV2(), callCodeOperationV2, this);
      case 0xf4 ->
          DelegateCallOperationV2.staticOperation(
              frame, frame.stackDataV2(), delegateCallOperationV2, this);
      case 0xf5 ->
          Create2OperationV2.staticOperation(frame, frame.stackDataV2(), create2OperationV2, this);
      case 0xfa ->
          StaticCallOperationV2.staticOperation(
              frame, frame.stackDataV2(), staticCallOperationV2, this);
      case 0x55 ->
          SStoreOperationV2.staticOperation(
              frame, frame.stackDataV2(), gasCalculator, SStoreOperationV2.EIP_1706_MINIMUM);
      case 0x5c ->
          enableCancun
              ? TLoadOperationV2.staticOperation(frame, frame.stackDataV2())
              : InvalidOperation.invalidOperationResult(opcode);
      case 0x5d ->
          enableCancun
              ? TStoreOperationV2.staticOperation(frame, frame.stackDataV2())
              : InvalidOperation.invalidOperationResult(opcode);
      // Data copy / hash / account operations
      case 0x20 -> Keccak256OperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator);
      case 0x31 -> BalanceOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator);
      case 0x37 ->
          CallDataCopyOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator);
      case 0x39 -> CodeCopyOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator);
      case 0x3b ->
          ExtCodeSizeOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator);
      case 0x3c ->
          ExtCodeCopyOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator);
      case 0x3e ->
          enableByzantium
              ? ReturnDataCopyOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator)
              : InvalidOperation.invalidOperationResult(opcode);
      case 0x3f ->
          enableConstantinople
              ? ExtCodeHashOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator)
              : InvalidOperation.invalidOperationResult(opcode);
      case 0x40 -> BlockHashOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x47 ->
          enableIstanbul
              ? SelfBalanceOperationV2.staticOperation(frame, frame.stackDataV2())
              : InvalidOperation.invalidOperationResult(opcode);
      case 0x49 ->
          enableCancun
              ? BlobHashOperationV2.staticOperation(frame, frame.stackDataV2())
              : InvalidOperation.invalidOperationResult(opcode);
      // Environment push operations (0 → 1)
      case 0x30 -> AddressOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x32 -> OriginOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x33 -> CallerOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x34 -> CallValueOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x36 -> CallDataSizeOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x38 -> CodeSizeOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x3a -> GasPriceOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x3d ->
          enableByzantium
              ? ReturnDataSizeOperationV2.staticOperation(frame, frame.stackDataV2())
              : InvalidOperation.invalidOperationResult(opcode);
      case 0x41 -> CoinbaseOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x42 -> TimestampOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x43 -> NumberOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x44 ->
          enableParis
              ? PrevRandaoOperationV2.staticOperation(frame, frame.stackDataV2())
              : DifficultyOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x45 -> GasLimitOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x46 -> // CHAINID (Istanbul+)
          chainIdOperationV2 != null
              ? chainIdOperationV2.executeFixedCostOperation(frame)
              : InvalidOperation.invalidOperationResult(opcode);
      case 0x48 -> // BASEFEE (London+)
          enableLondon
              ? BaseFeeOperationV2.staticOperation(frame, frame.stackDataV2())
              : InvalidOperation.invalidOperationResult(opcode);
      case 0x4a -> // BLOBBASEFEE (Cancun+)
          enableCancun
              ? BlobBaseFeeOperationV2.staticOperation(frame, frame.stackDataV2())
              : InvalidOperation.invalidOperationResult(opcode);
      case 0x4b -> // SLOTNUM (Amsterdam+)
          enableAmsterdam
              ? SlotNumOperationV2.staticOperation(frame, frame.stackDataV2())
              : InvalidOperation.invalidOperationResult(opcode);
      case 0x58 -> PcOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x59 -> MSizeOperationV2.staticOperation(frame, frame.stackDataV2());
      case 0x5a -> gasOperationV2.executeFixedCostOperation(frame);
      // Control flow operations
      case 0x00 -> StopOperationV2.staticOperation(frame);
      case 0xf3 -> ReturnOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator);
      case 0xfd -> // REVERT (Byzantium+)
          enableByzantium
              ? RevertOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator)
              : InvalidOperation.invalidOperationResult(opcode);
      case 0xfe -> InvalidOperationV2.INVALID_RESULT;
      case 0xa0, 0xa1, 0xa2, 0xa3, 0xa4 -> {
        int topicCount = opcode - 0xa0;
        yield LogOperationV2.staticOperation(frame, frame.stackDataV2(), topicCount, gasCalculator);
      }
      case 0xff ->
          SelfDestructOperationV2.staticOperation(
              frame,
              frame.stackDataV2(),
              gasCalculator,
              enableCancun,
              enableAmsterdam ? EIP7708TransferLogEmitter.INSTANCE : TransferLogEmitter.NOOP);
      case 0xfc -> // PAY (EIP-7708, Amsterdam+)
          enableAmsterdam
              ? PayOperationV2.staticOperation(frame, frame.stackDataV2(), gasCalculator)
              : InvalidOperation.invalidOperationResult(opcode);
      default -> {
        final Operation currentOperation =
            pc < code.length ? operationArray[opcode] : endOfScriptStop;
        frame.setCurrentOperation(currentOperation);
        yield currentOperation.execute(frame, this);
      }
    };
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
    checkNotNull(codeHash);

    Code result = jumpDestOnlyCodeCache.getIfPresent(codeHash);
    if (result == null) {
      result = new Code(codeBytes);
      jumpDestOnlyCodeCache.put(codeHash, result);
    }

    return result;
  }
}
