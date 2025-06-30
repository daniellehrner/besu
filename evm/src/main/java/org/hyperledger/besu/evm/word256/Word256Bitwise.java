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

import static org.hyperledger.besu.evm.word256.Word256Constants.MINUS_ONE;
import static org.hyperledger.besu.evm.word256.Word256Constants.ZERO;

/**
 * Utility class for performing bitwise operations on 256-bit words represented by the {@link
 * Word256} class.
 *
 * <p>This class provides methods for shifting, getting, setting bits, and performing bitwise
 * operations such as AND, OR, XOR, and NOT.
 */
final class Word256Bitwise {

  private Word256Bitwise() {}

  /**
   * Shifts the bits of the given Word256 value to the left by one position, filling the least
   * significant bit with zero.
   *
   * @param a the Word256 value to shift
   * @return a new Word256 value with bits shifted left by one
   */
  public static Word256 shiftLeft1(final Word256 a) {
    long n3 = a.l3 << 1;
    long carry2 = (a.l3 >>> 63);

    long n2 = (a.l2 << 1) | carry2;
    long carry1 = (a.l2 >>> 63);

    long n1 = (a.l1 << 1) | carry1;
    long carry0 = (a.l1 >>> 63);

    long n0 = (a.l0 << 1) | carry0;

    return new Word256(n0, n1, n2, n3);
  }

  /**
   * Gets the bit at the specified index from the given Word256 value.
   *
   * @param a the Word256 value
   * @param index the bit index (0-255)
   * @return 1 if the bit is set, 0 if it is not
   * @throws IllegalArgumentException if the index is out of range
   */
  static int getBit(final Word256 a, final int index) {
    if (index < 0 || index >= 256)
      throw new IllegalArgumentException("bit index out of range: " + index);
    int word = index / 64;
    int bit = index % 64;
    long mask = 1L << (63 - bit);
    switch (word) {
      case 3:
        return (a.l3 & mask) != 0 ? 1 : 0;
      case 2:
        return (a.l2 & mask) != 0 ? 1 : 0;
      case 1:
        return (a.l1 & mask) != 0 ? 1 : 0;
      case 0:
        return (a.l0 & mask) != 0 ? 1 : 0;
      default:
        throw new AssertionError();
    }
  }

  /**
   * Sets the bit at the specified index in the given Word256 value to 1.
   *
   * @param a the Word256 value
   * @param index the bit index (0-255)
   * @return a new Word256 value with the specified bit set to 1
   * @throws IllegalArgumentException if the index is out of range
   */
  static Word256 setBit(final Word256 a, final int index) {
    if (index < 0 || index >= 256)
      throw new IllegalArgumentException("bit index out of range: " + index);
    int word = index / 64;
    int bit = index % 64;
    long mask = 1L << (63 - bit);
    switch (word) {
      case 3:
        return new Word256(a.l0, a.l1, a.l2, a.l3 | mask);
      case 2:
        return new Word256(a.l0, a.l1, a.l2 | mask, a.l3);
      case 1:
        return new Word256(a.l0, a.l1 | mask, a.l2, a.l3);
      case 0:
        return new Word256(a.l0 | mask, a.l1, a.l2, a.l3);
      default:
        throw new AssertionError();
    }
  }

  /**
   * Performs a bitwise AND operation on two Word256 values.
   *
   * @param a the first Word256 value
   * @param b the second Word256 value
   * @return a new Word256 value representing the bitwise AND of a and b
   */
  static Word256 and(final Word256 a, final Word256 b) {
    return new Word256(a.l0 & b.l0, a.l1 & b.l1, a.l2 & b.l2, a.l3 & b.l3);
  }

  /**
   * Performs a bitwise OR operation on two Word256 values.
   *
   * @param a the first Word256 value
   * @param b the second Word256 value
   * @return a new Word256 value representing the bitwise OR of a and b
   */
  static Word256 or(final Word256 a, final Word256 b) {
    return new Word256(a.l0 | b.l0, a.l1 | b.l1, a.l2 | b.l2, a.l3 | b.l3);
  }

  /**
   * Performs a bitwise XOR operation on two Word256 values.
   *
   * @param a the first Word256 value
   * @param b the second Word256 value
   * @return a new Word256 value representing the bitwise XOR of a and b
   */
  static Word256 xor(final Word256 a, final Word256 b) {
    return new Word256(a.l0 ^ b.l0, a.l1 ^ b.l1, a.l2 ^ b.l2, a.l3 ^ b.l3);
  }

  /**
   * Performs a bitwise NOT operation on a Word256 value.
   *
   * @param a the Word256 value to negate
   * @return a new Word256 value representing the bitwise NOT of a
   */
  static Word256 not(final Word256 a) {
    return new Word256(~a.l0, ~a.l1, ~a.l2, ~a.l3);
  }

  /**
   * Shifts the bits of the given Word256 value to the left by the specified number of bits.
   *
   * @param value the Word256 value to shift
   * @param shift the number of bits to shift (0-255)
   * @return a new Word256 value with bits shifted left
   */
  static Word256 shl(final Word256 value, final int shift) {
    if (shift <= 0) return value;
    if (shift >= 256) return ZERO;

    final int wholeWords = shift / 64;
    final int bits = shift % 64;

    final long[] src = {value.l0, value.l1, value.l2, value.l3};
    final long[] dst = new long[4];

    for (int i = 3; i >= wholeWords; i--) {
      dst[i] = src[i - wholeWords] << bits;
      if (bits > 0 && i - wholeWords - 1 >= 0) {
        dst[i] |= src[i - wholeWords - 1] >>> (64 - bits);
      }
    }

    return new Word256(dst[0], dst[1], dst[2], dst[3]);
  }

  /**
   * Shifts the bits of the given Word256 value to the right by the specified number of bits.
   *
   * @param value the Word256 value to shift
   * @param shift the number of bits to shift (0-255)
   * @return a new Word256 value with bits shifted right
   */
  static Word256 shr(final Word256 value, final int shift) {
    if (shift <= 0) return value;
    if (shift >= 256) return ZERO;

    final int wholeWords = shift / 64;
    final int bits = shift % 64;

    final long[] src = {value.l0, value.l1, value.l2, value.l3};
    final long[] dst = new long[4];

    for (int i = 0; i <= 3 - wholeWords; i++) {
      dst[i] = src[i + wholeWords] >>> bits;
      if (bits > 0 && i + wholeWords + 1 <= 3) {
        dst[i] |= src[i + wholeWords + 1] << (64 - bits);
      }
    }

    return new Word256(dst[0], dst[1], dst[2], dst[3]);
  }

  /**
   * Performs an arithmetic right shift on the given Word256 value by the specified number of bits.
   *
   * @param value the Word256 value to shift
   * @param shift the number of bits to shift (0-255)
   * @return a new Word256 value with bits shifted right, preserving the sign bit
   */
  static Word256 sar(final Word256 value, final int shift) {
    if (shift <= 0) return value;
    if (shift >= 256) return value.isNegative() ? MINUS_ONE : ZERO;

    final int wholeWords = shift / 64;
    final int bits = shift % 64;

    final long[] src = {value.l0, value.l1, value.l2, value.l3};
    final long[] dst = new long[4];

    final boolean sign = value.isNegative();
    final long fill = sign ? -1L : 0L;

    for (int i = 0; i <= 3 - wholeWords; i++) {
      dst[i] = src[i + wholeWords] >>> bits;
      if (bits > 0 && i + wholeWords + 1 <= 3) {
        dst[i] |= src[i + wholeWords + 1] << (64 - bits);
      }
    }

    for (int i = 4 - wholeWords; i < 4; i++) {
      dst[i] = fill;
    }
    if (sign && bits > 0 && (4 - wholeWords - 1) >= 0) {
      dst[4 - wholeWords - 1] |= (-1L) << (64 - bits);
    }

    return new Word256(dst[0], dst[1], dst[2], dst[3]);
  }

  /**
   * Returns the index of the most significant non-zero word in a 4-limb Word256 representation.
   *
   * @param limbs the 4-limb representation of a Word256 value
   * @return the index of the most significant non-zero word (0-3), or -1 if all limbs are zero
   * @throws IllegalArgumentException if the limbs array does not have exactly 4 elements
   */
  static int leadingNonZeroWord(final long[] limbs) {
    if (limbs.length != 4) {
      throw new IllegalArgumentException("Expected 4-limb Word256 array");
    }
    if (limbs[3] != 0) return 3;
    if (limbs[2] != 0) return 2;
    if (limbs[1] != 0) return 1;
    if (limbs[0] != 0) return 0;
    return -1; // All limbs are zero
  }
}
