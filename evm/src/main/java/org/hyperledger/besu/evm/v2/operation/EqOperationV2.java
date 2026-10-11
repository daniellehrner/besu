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
package org.hyperledger.besu.evm.v2.operation;

import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.ANY_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.FALLBACK;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.VERY_LOW_TIER_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.done;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.result;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.storeLong;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.top;

import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * EVM v2 EQ operation using long[] stack representation.
 *
 * <p>Each 256-bit word is stored as four longs: index 0 = most significant 64 bits, index 3 = least
 * significant 64 bits.
 */
public class EqOperationV2 extends AbstractOperationV2 {

  // v1 runs EQ through its fixed-cost operation, which checks the gas before it touches the stack
  // and charges the cost on a stack underflow, and traces show both
  private static final OperationResult EQ_OUT_OF_GAS =
      new OperationResult(3, ExceptionalHaltReason.INSUFFICIENT_GAS);
  private static final OperationResult EQ_UNDERFLOW =
      new OperationResult(3, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);

  /**
   * Instantiates a new Eq operation.
   *
   * @param gasCalculator the gas calculator
   */
  public EqOperationV2(final GasCalculator gasCalculator) {
    super(0x14, "EQ", 2, 1, gasCalculator);
  }

  @Override
  public OperationResult execute(final MessageFrame frame, final EVM evm) {
    return staticOperation(frame);
  }

  /**
   * Execute the EQ opcode on the v2 long[] stack.
   *
   * <p>EQ: stack[top-2] = stack[top-1] == stack[top-2] ? 1 : 0, return top-1.
   *
   * @param frame the message frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    if (frame.getRemainingGas() < VERY_LOW_TIER_GAS) return EQ_OUT_OF_GAS;
    final int sp = frame.stackTopV2();
    return result(frame, eq(frame.stackDataV2(), sp, top(sp), ANY_GAS), EQ_UNDERFLOW);
  }

  /** EQ: whether the top item equals the one below it, which the result replaces. */
  @InlineInEvmLoop(opcodes = 0x14)
  static long eq(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final int b = a - 4;
      final long equal =
          ((s[a] ^ s[b]) | (s[a + 1] ^ s[b + 1]) | (s[a + 2] ^ s[b + 2]) | (s[a + 3] ^ s[b + 3]))
                  == 0
              ? 1L
              : 0L;
      storeLong(s, b, equal);
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }
}
