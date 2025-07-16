/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.evm.operation;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigInteger;

import org.hyperledger.besu.evm.frame.MessageFrame;

import org.junit.jupiter.api.Test;

class DivOperationTest extends BaseOperationTest {

  @Test
  void div_zeroDividedByOne_shouldBeZero() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(frame, "0x00", "0x01");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x00");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_oneDividedByOne_shouldBeOne() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(frame, "0x01", "0x01");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x01");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_maxDividedByOne_shouldBeMax() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(
        frame, "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "0x01");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(
        frame, "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_maxDividedByMax_shouldBeOne() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(
        frame,
        "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x01");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_partial_shouldBeCorrect() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(frame, "0x10", "0x03");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x05");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_divisionByZero_shouldBeZero() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(frame, "0x10", "0x00");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x00");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_justBelowDivisor_shouldBeZero() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(frame, "0x02", "0x03");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x00");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_evenDividedByTwo_shouldBeHalved() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(frame, "0x20", "0x02");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x10");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_highBitsDividedBySmall_shouldBeCorrect() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(
        frame, "0x8000000000000000000000000000000000000000000000000000000000000000", "0x02");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(
        frame, "0x4000000000000000000000000000000000000000000000000000000000000000");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_byMax64BitWord_shouldBeCorrect() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(
        frame,
        "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "0xffffffffffffffff");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(
        frame, "0x0000000000000001000000000000000100000000000000010000000000000001");

    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_dividendJustAboveDivisor_shouldBeOne() {
    final MessageFrame frame = mock(MessageFrame.class);
    final BigInteger a =
      new BigInteger("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
    final BigInteger b =
      new BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe", 16);
    final BigInteger res = a.divide(b);
    System.err.println("Result: 0x" + res.toString(16));

    popStackItemsFromHexString(
      frame,
      "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      "0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x01");



    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_maxDividedByAlmostMax_shouldBeOne() {
    final BigInteger a =
      new BigInteger("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
    final BigInteger b =
      new BigInteger("fffffffffffffffeffffffffffffffffffffffffffffffffffffffffffffffff", 16);
    final BigInteger res = a.divide(b);
    System.err.println("Result: 0x" + res.toString(16));

    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(
      frame,
      "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      "0xfffffffffffffffeffffffffffffffffffffffffffffffffffffffffffffffff");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x01");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_dividendWithHighZeroLimbs_shouldStillWork() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(
        frame, "0x00000000000000000000000000000000000000000000000000000000000000ff", "0x11");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x0f");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_nonMultipleByPowerOfTwo_shouldFloor() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(frame, "0x13", "0x04");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x04"); // 0x13 / 0x04 == 0x04 (floor of 19 / 4)
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_smallerDividendThanDivisor_shouldBeZero() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(
        frame, "0x01", "0x1000000000000000000000000000000000000000000000000000000000000000");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x00");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_by128BitNumber_shouldBeCorrect() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(
      frame,
      "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      "0x00000000000000000000000000000001ffffffffffffffffffffffffffffffff");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x00000000000000000000000000000000000000010000000000000000ffffffff");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_dividendIsSignBitOnly_shouldShift() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(
        frame, "0x8000000000000000000000000000000000000000000000000000000000000000", "0x02");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(
        frame, "0x4000000000000000000000000000000000000000000000000000000000000000");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_powerOfTwo_shouldWorkExactly() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(frame, "0x8000000000000000", "0x08");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x1000000000000000");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_evenlyDivisibleLargeNumbers_shouldBeExact() {
    final BigInteger a =
      new BigInteger("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 16);
    final BigInteger b =
      new BigInteger("22222222222222222222222222222222", 16);
    final BigInteger res = a.divide(b);
    System.err.println("Result: 0x" + res.toString(16));

    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(
      frame,
      "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "0x22222222222222222222222222222222");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(frame, "0x500000000000000000000000000000005");
    assertThat(result.getHaltReason()).isNull();
  }

  @Test
  void div_maxDividedByThree_shouldBeRepeatingPattern() {
    final MessageFrame frame = mock(MessageFrame.class);
    popStackItemsFromHexString(
        frame, "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "0x03");

    final Operation.OperationResult result = DivOperation.staticOperation(frame);

    verifyPushStackItemFromHexString(
        frame, "0x5555555555555555555555555555555555555555555555555555555555555555");
    assertThat(result.getHaltReason()).isNull();
  }
}
