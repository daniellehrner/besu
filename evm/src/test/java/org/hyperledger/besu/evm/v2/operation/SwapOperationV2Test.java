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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.evm.v2.testutils.TestMessageFrameBuilderV2.getV2StackItem;

import org.hyperledger.besu.evm.UInt256;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.FrontierGasCalculator;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.v2.testutils.TestMessageFrameBuilderV2;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Structural tests for SWAP1-16. Equivalence with the original implementation at every index and at
 * the stack limits is covered by StackOperationsV2ComparisonTest.
 */
class SwapOperationV2Test {
  private final GasCalculator gasCalculator = new FrontierGasCalculator();

  /** SWAPn exchanges the top with the item at depth n + 1; all other items stay in place. */
  @ParameterizedTest(name = "SWAP{0}")
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
  void swapOperation(final int n) {
    final MessageFrame frame = frameWithItems(17);

    final Operation.OperationResult result = SwapOperationV2.staticOperation(frame, n);

    assertThat(result.getHaltReason()).isNull();
    assertThat(result.getPcIncrement()).isEqualTo(1);
    assertThat(frame.stackTopV2()).isEqualTo(17);
    for (int depth = 1; depth <= 17; depth++) {
      final int expected = depth == 1 ? n + 1 : depth == n + 1 ? 1 : depth;
      assertThat(getV2StackItem(frame, depth - 1)).isEqualTo(word(expected));
    }
  }

  @ParameterizedTest(name = "SWAP{0}")
  @ValueSource(ints = {1, 2, 15, 16})
  void shouldHaltOnStackUnderflow(final int n) {
    final MessageFrame frame = frameWithItems(n + 1 - 1);

    final Operation.OperationResult result = SwapOperationV2.staticOperation(frame, n);

    assertThat(result.getHaltReason()).isEqualTo(ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
    assertThat(frame.stackTopV2()).isEqualTo(n + 1 - 1);
  }

  @Test
  void shouldHaltOnInsufficientGas() {
    final MessageFrame frame = new TestMessageFrameBuilderV2().initialGas(1).build();
    frame.setTopV2(17);

    final Operation.OperationResult result =
        new SwapOperationV2(16, gasCalculator).execute(frame, null);

    assertThat(result.getHaltReason()).isEqualTo(ExceptionalHaltReason.INSUFFICIENT_GAS);
    assertThat(frame.stackTopV2()).isEqualTo(17);
  }

  @Test
  void gasCostIsVeryLowTier() {
    final Operation.OperationResult result =
        new SwapOperationV2(1, gasCalculator).execute(frameWithItems(17), null);

    assertThat(result.getGasCost()).isEqualTo(gasCalculator.getVeryLowTierGasCost());
  }

  /** A frame whose item at depth k (1 = top) is word(k). */
  private static MessageFrame frameWithItems(final int count) {
    final TestMessageFrameBuilderV2 builder = new TestMessageFrameBuilderV2();
    for (int depth = count; depth >= 1; depth--) {
      builder.pushStackItem(Bytes32.wrap(word(depth).toBytesBE()));
    }
    return builder.build();
  }

  /** A value with four distinct limbs, so any misplaced limb is detected. */
  private static UInt256 word(final int depth) {
    final long tag = (long) depth << 32;
    return new UInt256(tag | 0xa, tag | 0xb, tag | 0xc, tag | 0xd);
  }
}
