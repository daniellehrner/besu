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
import org.hyperledger.besu.evm.v2.testutils.TestMessageFrameBuilderV2;

import java.util.Random;

import org.junit.jupiter.api.Test;

class PushOperationV2Test {

  /** The pushed word: the immediate bytes, with bytes past the end of the code reading as zero. */
  private static UInt256 expected(final byte[] code, final int start, final int len) {
    final byte[] word = new byte[32];
    for (int i = 0; i < len; i++) {
      final int index = start + i;
      word[32 - len + i] = index < code.length ? code[index] : 0;
    }
    return UInt256.fromBytesBE(word);
  }

  private static UInt256 push(final byte[] code, final int pc, final int len) {
    final MessageFrame frame = new TestMessageFrameBuilderV2().build();
    final OperationResult result =
        PushOperationV2.staticOperation(frame, frame.stackDataV2(), code, pc, len);
    assertThat(result.getHaltReason()).isNull();
    assertThat(frame.stackTopV2()).isEqualTo(1);
    assertThat(frame.getPC()).isEqualTo(pc + len);
    return getV2StackItem(frame, 0);
  }

  @Test
  void everyLengthAtEveryPositionMatchesTheReference() {
    final Random random = new Random(3);
    final byte[] code = new byte[200];
    random.nextBytes(code);
    for (int len = 1; len <= 32; len++) {
      for (int pc = 0; pc < code.length; pc++) {
        assertThat(push(code, pc, len))
            .as("PUSH%d at pc %d", len, pc)
            .isEqualTo(expected(code, pc + 1, len));
      }
    }
  }

  @Test
  void immediateStartingAtTheEndOfCodeIsZero() {
    final byte[] code = {(byte) 0x7f};
    assertThat(push(code, 0, 32)).isEqualTo(UInt256.ZERO);
  }

  @Test
  void immediateInTheFirstBytesOfCodeIsNotContaminatedByLaterBytes() {
    final byte[] code = new byte[40];
    for (int i = 0; i < code.length; i++) {
      code[i] = (byte) 0xff;
    }
    code[1] = 0x12;
    code[2] = 0x34;
    assertThat(push(code, 0, 2)).isEqualTo(UInt256.fromLong(0x1234L));
  }

  @Test
  void haltsWhenTheStackIsFull() {
    final MessageFrame frame = new TestMessageFrameBuilderV2().build();
    final byte[] code = new byte[40];
    while (frame.stackHasSpaceV2(1)) {
      frame.setTopV2(frame.stackTopV2() + 1);
    }
    final OperationResult result =
        PushOperationV2.staticOperation(frame, frame.stackDataV2(), code, 0, 1);
    assertThat(result.getHaltReason()).isEqualTo(ExceptionalHaltReason.TOO_MANY_STACK_ITEMS);
  }
}
