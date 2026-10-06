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
 * Structural tests for DUP1-16. Equivalence with the original implementation at every index and at
 * the stack limits is covered by StackOperationsV2ComparisonTest.
 */
class DupOperationV2Test {
  private final GasCalculator gasCalculator = new FrontierGasCalculator();

  /** DUPn copies the item at depth n to the top; all other items stay in place. */
  @ParameterizedTest(name = "DUP{0}")
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
  void dupOperation(final int n) {
    final MessageFrame frame = frameWithItems(17);

    final Operation.OperationResult result = DupOperationV2.staticOperation(frame, n);

    assertThat(result.getHaltReason()).isNull();
    assertThat(result.getPcIncrement()).isEqualTo(1);
    assertThat(frame.stackTopV2()).isEqualTo(18);
    assertThat(getV2StackItem(frame, 0)).isEqualTo(word(n));
    for (int depth = 1; depth <= 17; depth++) {
      assertThat(getV2StackItem(frame, depth)).isEqualTo(word(depth));
    }
  }

  @ParameterizedTest(name = "DUP{0}")
  @ValueSource(ints = {1, 2, 15, 16})
  void shouldHaltOnStackUnderflow(final int n) {
    final MessageFrame frame = frameWithItems(n - 1);

    final Operation.OperationResult result = DupOperationV2.staticOperation(frame, n);

    assertThat(result.getHaltReason()).isEqualTo(ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
    assertThat(frame.stackTopV2()).isEqualTo(n - 1);
  }

  @Test
  void shouldHaltOnStackOverflow() {
    final MessageFrame frame = frameWithItems(16);
    frame.setTopV2(MessageFrame.DEFAULT_MAX_STACK_SIZE);

    final Operation.OperationResult result = DupOperationV2.staticOperation(frame, 16);

    assertThat(result.getHaltReason()).isEqualTo(ExceptionalHaltReason.TOO_MANY_STACK_ITEMS);
    assertThat(frame.stackTopV2()).isEqualTo(MessageFrame.DEFAULT_MAX_STACK_SIZE);
  }

  @Test
  void shouldHaltOnInsufficientGas() {
    final MessageFrame frame = new TestMessageFrameBuilderV2().initialGas(1).build();
    frame.setTopV2(17);

    final Operation.OperationResult result =
        new DupOperationV2(16, gasCalculator).execute(frame, null);

    assertThat(result.getHaltReason()).isEqualTo(ExceptionalHaltReason.INSUFFICIENT_GAS);
    assertThat(frame.stackTopV2()).isEqualTo(17);
  }

  @Test
  void gasCostIsVeryLowTier() {
    final Operation.OperationResult result =
        new DupOperationV2(1, gasCalculator).execute(frameWithItems(17), null);

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
