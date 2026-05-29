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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.calltrace;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.vm.AbstractDebugOperationTracer;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.tracing.OpCodeTracerConfigBuilder.OpCodeTracerConfig;
import org.hyperledger.besu.evm.tracing.TraceFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.apache.tuweni.bytes.Bytes;

/**
 * An {@link AbstractDebugOperationTracer} optimized for {@code callTracer} output. It produces a
 * minimal "skeleton" list of {@link TraceFrame}s containing only the frames {@link
 * CallTracerResultConverter} actually consumes (CALL/CREATE entries,
 * RETURN/REVERT/STOP/SELFDESTRUCT, exceptional halts and soft failures, plus depth-boundary frames
 * so implicit-return detection still works).
 *
 * <p>For an opcode-heavy transaction this drops 99%+ of the per-step frames the regular {@link
 * org.hyperledger.besu.ethereum.vm.DebugOperationTracer} would accumulate.
 *
 * <p>Frame retention uses a one-step lookahead buffer: the most recent frame is buffered and only
 * committed once the next frame arrives, when we can also decide whether the depth changed. The
 * final buffered frame is committed by {@link #finalizeTrace()}.
 */
public class CallTracerOperationTracer extends AbstractDebugOperationTracer {

  private final List<TraceFrame> skeleton = new ArrayList<>();

  // Buffered next-to-commit frame and its metadata.
  private TraceFrame.Builder bufferedBuilder;
  private int bufferedDepth;
  private boolean bufferedIsInteresting;

  private int lastKeptDepth = Integer.MIN_VALUE;
  private int lastFrameDepth = Integer.MIN_VALUE;
  private Bytes inputData;

  /**
   * Creates a call-tracer-mode operation tracer. Geth-style child-call gas accounting is required
   * for {@link CallTracerGasCalculator} to compute correct {@code gasUsed} values, so {@code
   * recordChildCallGas} is hard-coded to {@code true}.
   *
   * @param options the opcode tracer config (storage/memory/stack flags, opcode filter)
   */
  public CallTracerOperationTracer(final OpCodeTracerConfig options) {
    super(options, true);
  }

  @Override
  protected void capturePreExecutionState(final MessageFrame frame) {
    if (frame.getDepth() > lastFrameDepth) inputData = frame.getInputData().copy();
    else inputData = frame.getInputData();
  }

  @Override
  public void tracePostExecution(final MessageFrame frame, final OperationResult operationResult) {
    // Commit any buffered frame using the new frame's pre-execution gas as its post-execution gas
    // and the new frame's depth as the lookahead signal.
    flushBufferedFrame(gasRemaining, depth);

    if (!traceOpcode) {
      return;
    }

    final Operation currentOperation = frame.getCurrentOperation();
    final String opcode = currentOperation.getName();
    final int opcodeNumber = (opcode != null) ? currentOperation.getOpcode() : Integer.MAX_VALUE;
    final long thisGasCost = computeGasCost(currentOperation, operationResult, frame);

    final Optional<ExceptionalHaltReason> haltReason =
        Optional.ofNullable(operationResult.getHaltReason()).or(frame::getExceptionalHaltReason);

    final Optional<Map<Address, Wei>> maybeRefunds =
        frame.getRefunds().isEmpty() ? Optional.empty() : Optional.of(frame.getRefunds());

    final Optional<Code> maybeCode =
        Optional.ofNullable(frame.getMessageFrameStack().peek()).map(MessageFrame::getCode);

    final Optional<Bytes> memorySlice =
        captureSoftFailureMemorySlice(frame, currentOperation, operationResult);

    bufferedBuilder =
        TraceFrame.builder()
            .setPc(pc)
            .setOpcode(opcode)
            .setOpcodeNumber(opcodeNumber)
            .setGasRemaining(gasRemaining)
            .setGasCost(thisGasCost == 0 ? OptionalLong.empty() : OptionalLong.of(thisGasCost))
            .setGasRefund(frame.getGasRefund())
            .setDepth(depth)
            .setExceptionalHaltReason(haltReason)
            .setRecipient(frame.getRecipientAddress())
            .setValue(frame.getApparentValue())
            .setInputData(inputData)
            .setOutputData(frame.getOutputData())
            .setStack(preExecutionStack)
            .setMaybeMemorySlice(memorySlice)
            .setWorldUpdater(frame.getWorldUpdater())
            .setRevertReason(frame.getRevertReason())
            .setMaybeRefunds(maybeRefunds)
            .setMaybeCode(maybeCode)
            .setStackItemsProduced(frame.getCurrentOperation().getStackItemsProduced())
            .setVirtualOperation(currentOperation.isVirtualOperation())
            .setSoftFailureReason(operationResult.getSoftFailureReason())
            .setGasAvailableForChildCall(operationResult.getGasAvailableForChildCall());
    bufferedDepth = depth;
    bufferedIsInteresting = isInterestingForCallTracer(opcode, operationResult, haltReason);
    lastFrameDepth = depth;
    frame.reset();
  }

  @Override
  public void tracePrecompileCall(
      final MessageFrame frame, final long gasRequirement, final Bytes output) {
    // Precompile callbacks update the most-recently-recorded frame in place. Mirror
    // DebugOperationTracer's behavior, but operate against either the buffered frame or the last
    // frame already in the skeleton. lastKeptDepth is only updated in the synthesis branch;
    // the buffered branch defers it to flushBufferedFrame, and the skeleton-update branch
    // doesn't change which frame is "last kept".
    final Address recipient = frame.getRecipientAddress();
    final Bytes precompileInput = frame.getInputData().copy();

    if (bufferedBuilder != null) {
      bufferedBuilder
          .setExceptionalHaltReason(frame.getExceptionalHaltReason())
          .setRevertReason(frame.getRevertReason())
          .setPrecompiledGasCost(gasRequirement)
          .setPrecompileIOData(recipient, precompileInput, output);
      bufferedIsInteresting = true;
      return;
    }

    if (!skeleton.isEmpty()) {
      final TraceFrame previous = skeleton.removeLast();
      final TraceFrame updated =
          TraceFrame.from(previous)
              .setExceptionalHaltReason(frame.getExceptionalHaltReason())
              .setRevertReason(frame.getRevertReason())
              .setPrecompiledGasCost(gasRequirement)
              .setPrecompileIOData(recipient, precompileInput, output)
              .build();
      skeleton.add(updated);
      return;
    }

    final TraceFrame synthesized =
        TraceFrame.builder()
            .setPc(frame.getPC())
            .setOpcodeNumber(Integer.MAX_VALUE)
            .setGasRemaining(frame.getRemainingGas())
            .setGasRefund(frame.getGasRefund())
            .setDepth(frame.getDepth())
            .setRecipient(recipient)
            .setValue(frame.getValue())
            .setInputData(precompileInput)
            .setOutputData(frame.getOutputData())
            .setWorldUpdater(frame.getWorldUpdater())
            .setMaybeRefunds(Optional.ofNullable(frame.getRefunds()))
            .setMaybeCode(Optional.ofNullable(frame.getCode()))
            .setStackItemsProduced(frame.getMaxStackSize())
            .setVirtualOperation(true)
            .setPrecompiledGasCost(gasRequirement)
            .setPrecompileIOData(recipient, precompileInput, output)
            .build();
    skeleton.add(synthesized);
    lastKeptDepth = synthesized.getDepth();
    lastFrameDepth = synthesized.getDepth();
  }

  @Override
  public void traceAccountCreationResult(
      final MessageFrame frame, final Optional<ExceptionalHaltReason> haltReason) {
    haltReason.ifPresent(
        reason -> {
          if (bufferedBuilder != null) {
            bufferedBuilder.setExceptionalHaltReason(Optional.of(reason));
            bufferedIsInteresting = true;
            return;
          }
          for (int i = skeleton.size() - 1; i >= 0; i--) {
            final TraceFrame current = skeleton.get(i);
            if (!"RETURN".equals(current.getOpcode())) {
              skeleton.set(
                  i,
                  TraceFrame.from(current).setExceptionalHaltReason(Optional.of(reason)).build());
              return;
            }
          }
        });
  }

  /** Flushes the final buffered frame. Must be called once the transaction has finished. */
  public void finalizeTrace() {
    flushBufferedFrame(0L, Integer.MIN_VALUE);
  }

  @Override
  public List<TraceFrame> getTraceFrames() {
    return skeleton;
  }

  private void flushBufferedFrame(final long nextGasRemaining, final int nextDepth) {
    if (bufferedBuilder == null) {
      return;
    }
    final boolean depthBoundary =
        bufferedDepth != lastKeptDepth
            || (nextDepth != Integer.MIN_VALUE && nextDepth != bufferedDepth);
    if (bufferedIsInteresting || depthBoundary) {
      if (nextDepth != Integer.MIN_VALUE) {
        bufferedBuilder.setGasRemainingPostExecution(nextGasRemaining);
      }
      skeleton.add(bufferedBuilder.build());
      lastKeptDepth = bufferedDepth;
    }
    bufferedBuilder = null;
  }

  private static boolean isInterestingForCallTracer(
      final String opcode,
      final OperationResult operationResult,
      final Optional<ExceptionalHaltReason> haltReason) {
    if (haltReason.isPresent() || operationResult.getSoftFailureReason().isPresent()) {
      return true;
    }
    return switch (OpcodeCategory.of(opcode)) {
      case CALL, CREATE, RETURN, REVERT, HALT, SELFDESTRUCT -> true;
      case OTHER -> false;
    };
  }
}
