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
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.v2.loop.LoopResult;
import org.hyperledger.besu.evm.v2.loop.LoopTables;

/**
 * One EVM operation as the object loop sees it: what it needs from the stack, what the loop charges
 * before it runs, and the operation itself. The loop reads the fields and makes one virtual call
 * per operation, so every subclass is compiled on its own with its own inlining budget.
 */
public abstract class Op {

  /** The opcode. */
  public final int opcode;

  /** Stack items the operation reads. */
  public final int stackIn;

  /** Stack items it leaves in their place. */
  public final int stackOut;

  /** Gas the loop charges before the operation runs. */
  public final long fixedGas;

  /** Whether the loop must look at the frame state after the operation ran. */
  public final boolean checksState;

  /** Whether the operation keeps its own frame state and answers with the legacy marker. */
  public final boolean legacy;

  /**
   * Creates an operation with the stack and gas numbers the tables hold for its opcode.
   *
   * @param opcode the opcode
   * @param tables the per-opcode tables of the fork
   * @param legacy whether the operation runs on the old contract
   */
  protected Op(final int opcode, final LoopTables tables, final boolean legacy) {
    this.opcode = opcode;
    this.stackIn = tables.stackIn[opcode];
    this.stackOut = tables.stackOut[opcode];
    this.fixedGas = (tables.flags[opcode] & LoopTables.NATIVE) != 0 ? tables.fixedGas[opcode] : 0L;
    this.checksState = (tables.flags[opcode] & LoopTables.STATE) != 0;
    this.legacy = legacy;
  }

  /**
   * Runs the operation. The loop has already checked the stack and charged the fixed gas.
   *
   * @param frame the frame
   * @param code the code being run
   * @param s the stack
   * @param top the stack top
   * @param pc the program counter of this operation
   * @param gas the remaining gas after the fixed charge
   * @return a packed {@link LoopResult}
   */
  public abstract long exec(MessageFrame frame, byte[] code, long[] s, int top, int pc, long gas);

  /**
   * Runs an operation on the old contract against a frame that already holds the loop's state, for
   * the tracing loop which hands the result to the tracer.
   *
   * @param frame the frame
   * @param code the code being run
   * @param s the stack
   * @param pc the program counter of this operation
   * @return the operation's result
   */
  public OperationResult execLegacy(
      final MessageFrame frame, final byte[] code, final long[] s, final int pc) {
    throw new UnsupportedOperationException("not a legacy operation");
  }
}
