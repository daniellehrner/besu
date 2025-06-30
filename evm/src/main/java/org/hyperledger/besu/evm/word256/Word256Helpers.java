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

/**
 * Internal helpers for Word256 that support full-width multiplication and 512-bit modular
 * reduction.
 */
final class Word256Helpers {

  private Word256Helpers() {
    // Prevent instantiation
  }

  /**
   * Performs full 512-bit multiplication of two Word256 values.
   *
   * @return an array of 8 unsigned 64-bit limbs, least significant limb at index 0
   */
  static long[] multiplyFull(final Word256 a, final Word256 b) {
    long[] aLimbs = {a.l3, a.l2, a.l1, a.l0};
    long[] bLimbs = {b.l3, b.l2, b.l1, b.l0};
    long[] result = new long[8];

    for (int i = 0; i < 4; i++) {
      for (int j = 0; j < 4; j++) {
        int k = i + j;
        long low = aLimbs[i] * bLimbs[j];
        long high = Math.multiplyHigh(aLimbs[i], bLimbs[j]);

        // accumulate low
        long sum = result[k] + low;
        long carry = Long.compareUnsigned(sum, result[k]) < 0 ? 1 : 0;
        result[k] = sum;

        // accumulate high + carry
        result[k + 1] += high + carry;
      }
    }

    return result;
  }

  /**
   * Reduces a 512-bit value (given as 8 longs, LSB at index 0) modulo a 256-bit modulus. Equivalent
   * to (hi << 256 + lo) % modulus.
   */
  static Word256 mod512(final long[] limbs, final Word256 modulus) {
    if (limbs.length != 8) {
      throw new IllegalArgumentException("Expected 8-limb array");
    }
    if (modulus.l0 == 0 && modulus.l1 == 0 && modulus.l2 == 0 && modulus.l3 == 0) {
      return Word256Constants.ZERO;
    }

    Word256 hi = new Word256(limbs[7], limbs[6], limbs[5], limbs[4]);
    Word256 lo = new Word256(limbs[3], limbs[2], limbs[1], limbs[0]);

    Word256 rem = Word256Constants.ZERO;

    // reduce hi
    for (int i = 0; i < 256; i++) {
      rem = Word256Bitwise.shiftLeft1(rem);
      if (Word256Bitwise.getBit(hi, 255 - i) != 0) {
        rem = Word256Bitwise.setBit(rem, 0);
      }
      if (Word256Comparison.compareUnsigned(rem, modulus) >= 0) {
        rem = Word256Arithmetic.subtract(rem, modulus);
      }
    }

    // reduce lo
    for (int i = 0; i < 256; i++) {
      rem = Word256Bitwise.shiftLeft1(rem);
      if (Word256Bitwise.getBit(lo, 255 - i) != 0) {
        rem = Word256Bitwise.setBit(rem, 0);
      }
      if (Word256Comparison.compareUnsigned(rem, modulus) >= 0) {
        rem = Word256Arithmetic.subtract(rem, modulus);
      }
    }

    return rem;
  }

  static long bytesToLong(final byte[] bytes, final int offset) {
    return ((bytes[offset] & 0xFFL) << 56)
        | ((bytes[offset + 1] & 0xFFL) << 48)
        | ((bytes[offset + 2] & 0xFFL) << 40)
        | ((bytes[offset + 3] & 0xFFL) << 32)
        | ((bytes[offset + 4] & 0xFFL) << 24)
        | ((bytes[offset + 5] & 0xFFL) << 16)
        | ((bytes[offset + 6] & 0xFFL) << 8)
        | ((bytes[offset + 7] & 0xFFL));
  }

  /** Writes a long value as 8 bytes big-endian into the target at the specified offset. */
  static void writeLongBE(final byte[] dest, final int offset, final long value) {
    for (int i = 0; i < 8; i++) {
      dest[offset + i] = (byte) (value >>> (56 - (i * 8)));
    }
  }

  /**
   * Reads 8 bytes from the given byte array starting at the specified offset, interpreting them as
   * a big-endian long value.
   *
   * @param src the source byte array
   * @param offset the offset into the array
   * @return the long value represented by the 8 bytes starting at offset
   */
  static long readLongBE(final byte[] src, final int offset) {
    long value = 0L;
    for (int i = 0; i < 8; i++) {
      value = (value << 8) | (src[offset + i] & 0xFFL);
    }
    return value;
  }
}
