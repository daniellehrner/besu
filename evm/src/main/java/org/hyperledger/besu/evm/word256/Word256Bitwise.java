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
 * Package-private utility class for bitwise operations on {@link Word256}.
 *
 * <p>Includes shift-left-by-1, single-bit access and modification, and logical ops like
 * AND/OR/XOR/NOT.
 */
final class Word256Bitwise {

  private Word256Bitwise() {
    // Prevent instantiation
  }

  public static Word256 shiftLeft1(final Word256 a) {
    long n0 = a.l0 << 1;
    long n1 = (a.l1 << 1) | (a.l0 >>> 63);
    long n2 = (a.l2 << 1) | (a.l1 >>> 63);
    long n3 = (a.l3 << 1) | (a.l2 >>> 63);
    return new Word256(n0, n1, n2, n3);
  }

  public static int getBit(final Word256 a, final int index) {
    if (index < 0 || index >= 256) {
      throw new IllegalArgumentException("bit index out of range: " + index);
    }
    int word = index / 64;
    int bit = index % 64;
    long mask = 1L << (63 - bit);
    switch (word) {
      case 0:
        return (a.l0 & mask) != 0 ? 1 : 0;
      case 1:
        return (a.l1 & mask) != 0 ? 1 : 0;
      case 2:
        return (a.l2 & mask) != 0 ? 1 : 0;
      case 3:
        return (a.l3 & mask) != 0 ? 1 : 0;
      default:
        throw new AssertionError();
    }
  }

  public static Word256 setBit(final Word256 a, final int index) {
    if (index < 0 || index >= 256) {
      throw new IllegalArgumentException("bit index out of range: " + index);
    }
    int word = index / 64;
    int bit = index % 64;
    long mask = 1L << (63 - bit);
    switch (word) {
      case 0:
        return new Word256(a.l0 | mask, a.l1, a.l2, a.l3);
      case 1:
        return new Word256(a.l0, a.l1 | mask, a.l2, a.l3);
      case 2:
        return new Word256(a.l0, a.l1, a.l2 | mask, a.l3);
      case 3:
        return new Word256(a.l0, a.l1, a.l2, a.l3 | mask);
      default:
        throw new AssertionError();
    }
  }

  public static Word256 and(final Word256 a, final Word256 b) {
    return new Word256(a.l0 & b.l0, a.l1 & b.l1, a.l2 & b.l2, a.l3 & b.l3);
  }

  public static Word256 or(final Word256 a, final Word256 b) {
    return new Word256(a.l0 | b.l0, a.l1 | b.l1, a.l2 | b.l2, a.l3 | b.l3);
  }

  public static Word256 xor(final Word256 a, final Word256 b) {
    return new Word256(a.l0 ^ b.l0, a.l1 ^ b.l1, a.l2 ^ b.l2, a.l3 ^ b.l3);
  }

  public static Word256 not(final Word256 a) {
    return new Word256(~a.l0, ~a.l1, ~a.l2, ~a.l3);
  }

  public static Word256 shl(final Word256 value, final int shift) {
    if (shift <= 0) {
      return value;
    } else if (shift >= 256) {
      return Word256.ZERO;
    }

    final int wholeWords = shift / 64;
    final int bits = shift % 64;

    final long[] src = {value.l0, value.l1, value.l2, value.l3};
    final long[] dst = new long[4];

    for (int i = 0; i < 4 - wholeWords; i++) {
      dst[i] = src[i + wholeWords] << bits;
      if (bits > 0 && i + wholeWords + 1 < 4) {
        dst[i] |= src[i + wholeWords + 1] >>> (64 - bits);
      }
    }

    return new Word256(dst[0], dst[1], dst[2], dst[3]);
  }

  public static Word256 shr(final Word256 value, final int shift) {
    if (shift <= 0) {
      return value;
    } else if (shift >= 256) {
      return Word256.ZERO;
    }

    final int wholeWords = shift / 64;
    final int bits = shift % 64;

    final long[] src = {value.l0, value.l1, value.l2, value.l3};
    final long[] dst = new long[4];

    for (int i = 3; i >= wholeWords; i--) {
      dst[i] = src[i - wholeWords] >>> bits;
      if (bits > 0 && i - wholeWords - 1 >= 0) {
        dst[i] |= src[i - wholeWords - 1] << (64 - bits);
      }
    }

    return new Word256(dst[0], dst[1], dst[2], dst[3]);
  }

  public static Word256 sar(final Word256 value, final int shift) {
    if (shift <= 0) {
      return value;
    } else if (shift >= 256) {
      // All bits replicate the sign bit (i.e., 0 or -1)
      return value.isNegative() ? MINUS_ONE : ZERO;
    }

    final int wholeWords = shift / 64;
    final int bits = shift % 64;

    final long[] src = {value.l0, value.l1, value.l2, value.l3};
    final long[] dst = new long[4];

    final boolean sign = value.isNegative();

    for (int i = 3; i >= wholeWords; i--) {
      dst[i] = src[i - wholeWords] >>> bits;
      if (bits > 0 && i - wholeWords - 1 >= 0) {
        dst[i] |= src[i - wholeWords - 1] << (64 - bits);
      }
    }

    // Fill in upper words with sign extension
    final long fill = sign ? -1L : 0L;
    for (int i = 0; i < wholeWords; i++) {
      dst[i] = fill;
    }
    if (sign && bits > 0) {
      dst[wholeWords] |= (-1L) << (64 - bits);
    }

    return new Word256(dst[0], dst[1], dst[2], dst[3]);
  }
}
