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
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.JUMPDEST_GAS;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.done;
import static org.hyperledger.besu.evm.v2.operation.EvmLoopInlining.ran;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;

/**
 * The JUMPDEST operation, which marks a valid jump destination and does nothing else. The
 * interpreter runs it inline, so it exists only as static methods and the fork's operation table
 * has no instance of it.
 */
public final class JumpDestOperationV2 {

  private JumpDestOperationV2() {}

  /**
   * Performs JumpDest operation.
   *
   * @param frame the frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    // given all the gas it wants, the method always runs
    return ran(frame, jumpDest(ANY_GAS));
  }

  /** JUMPDEST: marks a jump destination and does nothing else. */
  @InlineInEvmLoop(opcodes = 0x5b)
  static long jumpDest(final long gas) {
    if (gas >= JUMPDEST_GAS) {
      return done(JUMPDEST_GAS, 1, 0);
    }
    return FALLBACK;
  }
}
