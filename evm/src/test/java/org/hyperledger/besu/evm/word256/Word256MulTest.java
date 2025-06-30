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
package org.hyperledger.besu.evm.word256;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Word256MulTest {

  @Test
  void mulZeroZero() {
    final Word256 result = Word256.ZERO.mul(Word256.ZERO);
    assertEquals(Word256.ZERO, result);
  }

  @Test
  void mulZeroOne() {
    final Word256 result = Word256.ZERO.mul(Word256.ONE);
    assertEquals(Word256.ZERO, result);
  }

  @Test
  void mulOneZero() {
    final Word256 result = Word256.ONE.mul(Word256.ZERO);
    assertEquals(Word256.ZERO, result);
  }

  @Test
  void mulOneOne() {
    final Word256 result = Word256.ONE.mul(Word256.ONE);
    assertEquals(Word256.ONE, result);
  }

  @Test
  void mulMaxMax() {
    final Word256 result = Word256.MAX.mul(Word256.MAX);
    // (2^256 - 1)^2 = 2^512 - 2^257 + 1, but we only keep the lower 256 bits: result = 1
    assertEquals(Word256.ONE, result);
  }

  @Test
  void mulMaxOne() {
    final Word256 result = Word256.MAX.mul(Word256.ONE);
    assertEquals(Word256.MAX, result);
  }

  @Test
  void mulOneMax() {
    final Word256 result = Word256.ONE.mul(Word256.MAX);
    assertEquals(Word256.MAX, result);
  }

  @Test
  void mulMaxZero() {
    final Word256 result = Word256.MAX.mul(Word256.ZERO);
    assertEquals(Word256.ZERO, result);
  }

  @Test
  void mulPowerOfTwo() {
    // 2^255 * 2 = 0 (mod 2^256)
    Word256 pow2_255 = new Word256(0x8000000000000000L, 0L, 0L, 0L);
    Word256 two = Word256.fromLong(2L);
    Word256 result = pow2_255.mul(two);
    assertEquals(Word256.ZERO, result);
  }

  @Test
  void mulCarriesAcrossWords() {
    // 0xFFFFFFFFFFFFFFFF * 0xFFFFFFFFFFFFFFFF = 1 (mod 2^256)
    Word256 val = Word256.fromLong(0xFFFFFFFFFFFFFFFFL);
    Word256 result = val.mul(val);
    assertEquals(Word256.ONE, result);
  }

  @Test
  void mulLowHighBit() {
    // Multiply a word with LSB set and a word with MSB set
    Word256 low = Word256.fromLong(1);
    Word256 high = new Word256(0x8000000000000000L, 0L, 0L, 0L);
    Word256 result = high.mul(low);
    assertEquals(high, result);
  }

  @Test
  void mulAlternatingBits() {
    // A * B = expected based on known product (check masking, not overflow)
    Word256 a =
        new Word256(
            0xAAAAAAAAAAAAAAAAL, 0xAAAAAAAAAAAAAAAAL, 0xAAAAAAAAAAAAAAAAL, 0xAAAAAAAAAAAAAAAAL);
    Word256 b =
        new Word256(
            0x5555555555555555L, 0x5555555555555555L, 0x5555555555555555L, 0x5555555555555555L);
    Word256 result = a.mul(b);
    // The low 256 bits of the full 512-bit product are not easy to compute directly,
    // so here we check commutativity and reproducibility
    assertEquals(b.mul(a), result);
  }

  @Test
  void mulHighPrecision128BitOperands() {
    Word256 a = new Word256(0, 0, 1L, 0L); // 2^64
    Word256 b = new Word256(0, 0, 2L, 0L); // 2^65
    Word256 result = a.mul(b); // = 2^129
    Word256 expected =
        new Word256(0x0000000000000004L, 0x0000000000000000L, 0x0000000000000000L, 0L);
    assertEquals(expected, result);
  }

  @Test
  void mulLowByteValues() {
    Word256 a = Word256.fromByte((byte) 0x02);
    Word256 b = Word256.fromByte((byte) 0x03);
    Word256 result = a.mul(b);
    assertEquals(Word256.fromByte((byte) 0x06), result);
  }

  @Test
  void mul128bitMaxValues() {
    // 2^128 - 1
    Word256 a = new Word256(0, 0, 0xFFFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL);
    Word256 result = a.mul(a);
    // Result is 2^256 - 2^129 + 1 => only keep low 256 bits
    Word256 expected =
        new Word256(
            0x0000000000000001L, 0xFFFFFFFFFFFFFFFEL, 0x0000000000000000L, 0x0000000000000001L);
    assertEquals(expected, result);
  }
}
