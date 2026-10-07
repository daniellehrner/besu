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

import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.evm.UInt256;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.FrontierGasCalculator;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.v2.StackArithmetic;
import org.hyperledger.besu.evm.v2.testutils.TestMessageFrameBuilderV2;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

class MemoryWordOperationsV2Test {

  private static final GasCalculator GAS = new FrontierGasCalculator();
  private static final Bytes32 WORD =
      Bytes32.fromHexString("0x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");

  private static MessageFrame frame() {
    return new TestMessageFrameBuilderV2().initialGas(1_000_000L).build();
  }

  private static void push(final MessageFrame frame, final Bytes32 value) {
    final long[] s = frame.stackDataV2();
    final int top = frame.stackTopV2();
    StackArithmetic.fromBytesAt(s, top + 1, 0, value.toArrayUnsafe(), 0, 32);
    frame.setTopV2(top + 1);
  }

  private static void push(final MessageFrame frame, final long value) {
    frame.setTopV2(StackArithmetic.pushLong(frame.stackDataV2(), frame.stackTopV2(), value));
  }

  @Test
  void mstoreThenMloadRoundTrips() {
    final MessageFrame frame = frame();
    push(frame, WORD);
    push(frame, 64L);
    assertThat(MstoreOperationV2.staticOperation(frame, frame.stackDataV2(), GAS).getHaltReason())
        .isNull();
    assertThat(frame.stackTopV2()).isZero();
    assertThat(frame.memoryWordSize()).isEqualTo(3);
    assertThat(frame.readMemory(64, 32)).isEqualTo(WORD);

    push(frame, 64L);
    assertThat(MloadOperationV2.staticOperation(frame, frame.stackDataV2(), GAS).getHaltReason())
        .isNull();
    assertThat(getV2StackItem(frame, 0)).isEqualTo(UInt256.fromBytesBE(WORD.toArrayUnsafe()));
  }

  @Test
  void mloadPastTheEndExpandsMemoryAndReadsZero() {
    final MessageFrame frame = frame();
    push(frame, 100L);
    MloadOperationV2.staticOperation(frame, frame.stackDataV2(), GAS);
    assertThat(getV2StackItem(frame, 0)).isEqualTo(UInt256.ZERO);
    assertThat(frame.memoryWordSize()).isEqualTo(5);
  }

  @Test
  void unalignedMstoreOverlapsExistingWords() {
    final MessageFrame frame = frame();
    push(frame, WORD);
    push(frame, 0L);
    MstoreOperationV2.staticOperation(frame, frame.stackDataV2(), GAS);
    push(frame, WORD);
    push(frame, 5L);
    MstoreOperationV2.staticOperation(frame, frame.stackDataV2(), GAS);
    final Bytes expected = Bytes.concatenate(WORD.slice(0, 5), WORD, Bytes.repeat((byte) 0, 27));
    assertThat(frame.readMemory(0, 64)).isEqualTo(expected);
  }

  @Test
  void mloadFailsWithoutGas() {
    final MessageFrame frame = new TestMessageFrameBuilderV2().initialGas(2L).build();
    push(frame, 0L);
    final OperationResult result =
        MloadOperationV2.staticOperation(frame, frame.stackDataV2(), GAS);
    assertThat(result.getHaltReason()).isNotNull();
  }

  @Test
  void keccakHashesTheMemoryRangeInPlace() {
    final MessageFrame frame = frame();
    push(frame, WORD);
    push(frame, 7L);
    MstoreOperationV2.staticOperation(frame, frame.stackDataV2(), GAS);
    push(frame, 20L);
    push(frame, 3L);
    Keccak256OperationV2.staticOperation(frame, frame.stackDataV2(), GAS);
    final Bytes32 expected = Hash.keccak256(frame.readMemory(3, 20));
    assertThat(getV2StackItem(frame, 0)).isEqualTo(UInt256.fromBytesBE(expected.toArrayUnsafe()));
  }

  @Test
  void keccakOfAnEmptyRangeDoesNotExpandMemory() {
    final MessageFrame frame = frame();
    push(frame, 0L);
    push(frame, 1L << 40);
    Keccak256OperationV2.staticOperation(frame, frame.stackDataV2(), GAS);
    assertThat(frame.memoryWordSize()).isZero();
    assertThat(getV2StackItem(frame, 0))
        .isEqualTo(UInt256.fromBytesBE(Hash.keccak256(Bytes.EMPTY).toArrayUnsafe()));
  }

  @Test
  void memoryUpdatesAreRecordedByDefaultAndSkippedWhenNotTracing() {
    final MessageFrame recording = frame();
    push(recording, WORD);
    push(recording, 32L);
    MstoreOperationV2.staticOperation(recording, recording.stackDataV2(), GAS);
    assertThat(recording.getMaybeUpdatedMemory()).isPresent();
    assertThat(recording.getMaybeUpdatedMemory().get().getOffset()).isEqualTo(32L);
    assertThat(recording.getMaybeUpdatedMemory().get().getValue()).isEqualTo(WORD);

    final MessageFrame silent = frame();
    silent.setRecordUpdatesForTracer(false);
    push(silent, WORD);
    push(silent, 32L);
    MstoreOperationV2.staticOperation(silent, silent.stackDataV2(), GAS);
    assertThat(silent.getMaybeUpdatedMemory()).isEmpty();
    assertThat(silent.readMemory(32, 32)).isEqualTo(WORD);
  }
}
