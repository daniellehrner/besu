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
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.v2.StackArithmetic;
import org.hyperledger.besu.evm.v2.testutils.TestMessageFrameBuilderV2;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

class CallDataLoadOperationV2Test {

  // 40 bytes: 0x00, 0x01, ..., 0x27
  private static final Bytes INPUT =
      Bytes.fromHexString(
          "0x000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f2021222324252627");

  private static MessageFrame frameWithOffset(final Bytes input, final long offset) {
    final MessageFrame frame = new TestMessageFrameBuilderV2().inputData(input).build();
    frame.setTopV2(StackArithmetic.pushLong(frame.stackDataV2(), frame.stackTopV2(), offset));
    return frame;
  }

  private static UInt256 expectedWord(final Bytes input, final int offset) {
    final byte[] word = new byte[32];
    for (int i = 0; i < 32; i++) {
      final int index = offset + i;
      word[i] = index < input.size() ? input.get(index) : 0;
    }
    return UInt256.fromBytesBE(word);
  }

  @Test
  void loadsAFullWordFromTheInput() {
    final MessageFrame frame = frameWithOffset(INPUT, 3);
    final OperationResult result =
        CallDataLoadOperationV2.staticOperation(frame, frame.stackDataV2());
    assertThat(result.getHaltReason()).isNull();
    assertThat(getV2StackItem(frame, 0)).isEqualTo(expectedWord(INPUT, 3));
  }

  @Test
  void loadsFromAnInputThatIsNotArrayBacked() {
    // A slice keeps an offset into its parent, so a direct array read would start at the wrong byte
    final Bytes slice = INPUT.slice(5, 35);
    final MessageFrame frame = frameWithOffset(slice, 1);
    CallDataLoadOperationV2.staticOperation(frame, frame.stackDataV2());
    assertThat(getV2StackItem(frame, 0)).isEqualTo(expectedWord(slice, 1));
  }

  @Test
  void zeroFillsAWordThatRunsPastTheInput() {
    final MessageFrame frame = frameWithOffset(INPUT, 20);
    CallDataLoadOperationV2.staticOperation(frame, frame.stackDataV2());
    assertThat(getV2StackItem(frame, 0)).isEqualTo(expectedWord(INPUT, 20));
  }

  @Test
  void loadsZeroPastTheEndOfTheInput() {
    final MessageFrame frame = frameWithOffset(INPUT, 40);
    CallDataLoadOperationV2.staticOperation(frame, frame.stackDataV2());
    assertThat(getV2StackItem(frame, 0)).isEqualTo(UInt256.ZERO);
  }

  @Test
  void loadsZeroForAnOffsetBeyondIntRange() {
    final MessageFrame frame = frameWithOffset(INPUT, 1L << 40);
    CallDataLoadOperationV2.staticOperation(frame, frame.stackDataV2());
    assertThat(getV2StackItem(frame, 0)).isEqualTo(UInt256.ZERO);
  }

  @Test
  void loadsZeroForAnOffsetNearIntMax() {
    final MessageFrame frame = frameWithOffset(INPUT, Integer.MAX_VALUE - 5);
    CallDataLoadOperationV2.staticOperation(frame, frame.stackDataV2());
    assertThat(getV2StackItem(frame, 0)).isEqualTo(UInt256.ZERO);
  }

  @Test
  void loadsZeroFromEmptyInput() {
    final MessageFrame frame = frameWithOffset(Bytes.EMPTY, 0);
    CallDataLoadOperationV2.staticOperation(frame, frame.stackDataV2());
    assertThat(getV2StackItem(frame, 0)).isEqualTo(UInt256.ZERO);
  }

  @Test
  void haltsOnAnEmptyStack() {
    final MessageFrame frame = new TestMessageFrameBuilderV2().inputData(INPUT).build();
    final OperationResult result =
        CallDataLoadOperationV2.staticOperation(frame, frame.stackDataV2());
    assertThat(result.getHaltReason()).isEqualTo(ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
  }
}
