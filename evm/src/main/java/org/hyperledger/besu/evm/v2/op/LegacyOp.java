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

import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason.DefaultExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.OverflowException;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.v2.loop.LoopResult;
import org.hyperledger.besu.evm.v2.loop.LoopTables;

/**
 * An operation still on the old contract: it reads and writes the frame's pc, gas and stack top
 * itself and reports through an {@link OperationResult}, which is translated after it ran.
 */
abstract class LegacyOp extends Op {

  private static final OperationResult OVERFLOW_RESPONSE =
      new OperationResult(0L, DefaultExceptionalHaltReason.TOO_MANY_STACK_ITEMS);
  private static final OperationResult UNDERFLOW_RESPONSE =
      new OperationResult(0L, DefaultExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);

  LegacyOp(final int opcode, final LoopTables tables) {
    super(opcode, tables, true);
  }

  abstract OperationResult run(MessageFrame frame, byte[] code, long[] s, int pc);

  @Override
  public final long exec(
      final MessageFrame frame,
      final byte[] code,
      final long[] s,
      final int top,
      final int pc,
      final long gas) {
    frame.setPC(pc);
    frame.setGasRemaining(gas);
    frame.setTopV2(top);
    return translate(frame, execLegacy(frame, code, s, pc));
  }

  @Override
  public final OperationResult execLegacy(
      final MessageFrame frame, final byte[] code, final long[] s, final int pc) {
    try {
      return run(frame, code, s, pc);
    } catch (final OverflowException oe) {
      return OVERFLOW_RESPONSE;
    } catch (final UnderflowException ue) {
      return UNDERFLOW_RESPONSE;
    }
  }

  /**
   * Translates the result of an operation on the old contract, after it ran.
   *
   * @param frame the frame the operation updated
   * @param result its result
   * @return the packed result
   */
  static long translate(final MessageFrame frame, final OperationResult result) {
    final ExceptionalHaltReason haltReason = result.getHaltReason();
    if (haltReason != null) {
      return LoopResult.halt(frame, haltReason);
    }
    if (frame.decrementRemainingGas(result.getGasCost()) < 0) {
      return LoopResult.halt(DefaultExceptionalHaltReason.INSUFFICIENT_GAS);
    }
    return LoopResult.legacy(frame.stackTopV2(), frame.getPC() + result.getPcIncrement());
  }
}
