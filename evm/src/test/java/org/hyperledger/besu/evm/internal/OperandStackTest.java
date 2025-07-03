/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.evm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hyperledger.besu.evm.word256.Word256;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OperandStackTest {

  @Test
  void construction() {
    final OperandStack stack = new OperandStack(1);
    assertThat(stack.size()).isZero();
  }

  @Test
  void construction_NegativeMaximumSize() {
    assertThatThrownBy(() -> new OperandStack(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void push_StackOverflow() {
    final OperandStack stack = new OperandStack(1);
    stack.push(Word256.fromLong(0x01));
    final Word256 operand = Word256.fromLong(0x02);
    assertThatThrownBy(() -> stack.push(operand)).isInstanceOf(OverflowException.class);
  }

  @Test
  void pop_StackUnderflow() {
    final OperandStack stack = new OperandStack(1);
    assertThatThrownBy(stack::pop).isInstanceOf(UnderflowException.class);
  }

  @Test
  void pushPop() {
    final OperandStack stack = new OperandStack(1);
    stack.push(Word256.fromLong(0x01));
    assertThat(stack.size()).isEqualTo(1);
    assertThat(stack.pop()).isEqualTo(Word256.fromLong(0x01));
  }

  @Test
  void get_NegativeOffset() {
    final OperandStack stack = new OperandStack(1);
    assertThatThrownBy(() -> stack.get(-1)).isInstanceOf(UnderflowException.class);
  }

  @Test
  void get_IndexGreaterThanSize() {
    final OperandStack stack = new OperandStack(1);
    stack.push(Word256.fromLong(0x01));
    assertThatThrownBy(() -> stack.get(2)).isInstanceOf(UnderflowException.class);
  }

  @Test
  void get() {
    final OperandStack stack = new OperandStack(3);
    stack.push(Word256.fromLong(0x01));
    stack.push(Word256.fromLong(0x02));
    stack.push(Word256.fromLong(0x03));
    assertThat(stack.size()).isEqualTo(3);
    assertThat(stack.get(0)).isEqualTo(Word256.fromLong(0x03));
    assertThat(stack.get(1)).isEqualTo(Word256.fromLong(0x02));
    assertThat(stack.get(2)).isEqualTo(Word256.fromLong(0x01));
  }

  @Test
  void set_NegativeOffset() {
    final OperandStack stack = new OperandStack(1);
    final Word256 operand = Word256.fromLong(0x01);
    assertThatThrownBy(() -> stack.set(-1, operand)).isInstanceOf(UnderflowException.class);
  }

  @Test
  void set_IndexGreaterThanSize() {
    final OperandStack stack = new OperandStack(1);
    stack.push(Word256.fromLong(0x01));
    final Word256 operand = Word256.fromLong(0x01);
    assertThatThrownBy(() -> stack.set(2, operand)).isInstanceOf(OverflowException.class);
  }

  @Test
  void set_IndexGreaterThanCurrentSize() {
    final OperandStack stack = new OperandStack(1024);
    stack.push(Word256.fromLong(0x01));
    final Word256 operand = Word256.fromLong(0x01);
    assertThatThrownBy(() -> stack.set(2, operand)).isInstanceOf(OverflowException.class);
  }

  @Test
  void set() {
    final OperandStack stack = new OperandStack(3);
    stack.push(Word256.fromLong(0x01));
    stack.push(Word256.fromLong(0x02));
    stack.push(Word256.fromLong(0x03));
    stack.set(2, Word256.fromLong(0x04));
    assertThat(stack.size()).isEqualTo(3);
    assertThat(stack.get(0)).isEqualTo(Bytes32.fromHexString("0x03"));
    assertThat(stack.get(1)).isEqualTo(Bytes32.fromHexString("0x02"));
    assertThat(stack.get(2)).isEqualTo(Bytes32.fromHexString("0x04"));
  }

  @Test
  void bulkPop() {
    final OperandStack stack = new OperandStack(8);
    stack.push(Word256.fromLong(0x01));
    stack.push(Word256.fromLong(0x02));
    stack.push(Word256.fromLong(0x03));
    stack.push(Word256.fromLong(0x04));
    stack.push(Word256.fromLong(0x05));
    stack.push(Word256.fromLong(0x06));
    stack.push(Word256.fromLong(0x07));
    stack.push(Word256.fromLong(0x08));
    assertThat(stack.size()).isEqualTo(8);
    stack.bulkPop(2);
    assertThat(stack.get(0)).isEqualTo(Bytes32.fromHexString("0x06"));
    stack.bulkPop(6);
    assertThat(stack.isEmpty()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {5, 31, 32, 33, 1023, 1024, 1025})
  void largeOverflows(final int n) {
    final OperandStack stack = new OperandStack(n);
    for (int i = 0; i < n; i++) {
      stack.push(Word256.ONE);
    }
    assertThatThrownBy(() -> stack.push(Word256.ONE)).isInstanceOf(OverflowException.class);
  }
}
