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

import java.util.Arrays;

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
  public static long[] multiplyFull(final Word256 a, final Word256 b) {
    final long[] x = {a.l0, a.l1, a.l2, a.l3}; // LSB to MSB
    final long[] y = {b.l0, b.l1, b.l2, b.l3};
    final long[] result = new long[8];

    for (int i = 0; i < 4; i++) {
      final long xi = x[i];
      final long xLow = xi & 0xFFFFFFFFL;
      final long xHigh = xi >>> 32;

      for (int j = 0; j < 4; j++) {
        final long yj = y[j];
        final long yLow = yj & 0xFFFFFFFFL;
        final long yHigh = yj >>> 32;

        final int k = i + j;

        // 64x64 = 128-bit multiplication (unsigned)
        long p0 = xLow * yLow;
        long p1 = xLow * yHigh;
        long p2 = xHigh * yLow;
        long p3 = xHigh * yHigh;

        long middle = (p1 & 0xFFFFFFFFL) + (p2 & 0xFFFFFFFFL) + (p0 >>> 32);
        long carry = middle >>> 32;

        long lo = (p0 & 0xFFFFFFFFL) | (middle << 32);
        long hi = p3 + (p1 >>> 32) + (p2 >>> 32) + carry;

        // Add lo to result[k]
        long sum = result[k] + lo;
        boolean carry1 = Long.compareUnsigned(sum, result[k]) < 0;
        result[k] = sum;

        // Add hi + carry1 to result[k + 1]
        long sum1 = result[k + 1] + hi + (carry1 ? 1 : 0);
        boolean carry2 =
            Long.compareUnsigned(sum1, result[k + 1]) < 0 || (carry1 && sum1 == result[k + 1]);
        result[k + 1] = sum1;

        // Propagate carry2
        int l = k + 2;
        while (carry2 && l < 8) {
          long next = result[l] + 1;
          carry2 = next == 0;
          result[l++] = next;
        }
      }
    }

    return result;
  }

  public static long[] divideAndRemainderKnuth(
      final int m, final long[] un, final int n, final long[] vn, final long[] q) {

    for (int j = m; j >= 0; j--) {
      final long u2 = un[j + n];
      final long u1 = un[j + n - 1];
      final long v1 = vn[n - 1];
      final long v0 = vn[n - 2];

      long qHat = estimateQHat(u2, u1, v1);

      while (overflowEstimate(qHat, v1, v0, u2, u1)) {
        qHat--;
      }

      if (mulSub(un, vn, qHat, j) < 0) {
        qHat--;
        addBack(un, vn, j);
      }

      if (j < q.length) {
        q[j] = qHat;
      }
    }

    return Arrays.copyOf(un, n); // full remainder, not truncated
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

  static Word256 maskAbove(final int bitIndex) {
    if (bitIndex < 0 || bitIndex >= 256) {
      throw new IllegalArgumentException("bitIndex must be in [0, 255]");
    }
    if (bitIndex == 0) {
      return Word256.MINUS_ONE;
    }

    final int wordIndex = bitIndex / 64;
    final int bitInWord = bitIndex % 64;

    final long[] longs = new long[4];
    for (int i = 0; i < wordIndex; i++) {
      longs[i] = -1L;
    }
    longs[wordIndex] = ~0L << bitInWord;
    // remaining are 0 by default

    return new Word256(longs[0], longs[1], longs[2], longs[3]);
  }

  /**
   * Returns a mask with all bits below the specified index set to 1, the rest 0.
   *
   * @param bitIndex index in range [0, 256]
   * @return Word256 mask
   */
  static Word256 maskBelow(final int bitIndex) {
    if (bitIndex < 0 || bitIndex > 256) {
      throw new IllegalArgumentException("bitIndex must be in [0, 256]");
    }
    if (bitIndex == 256) {
      return Word256.MINUS_ONE;
    } else if (bitIndex == 0) {
      return Word256.ZERO;
    }

    final int wordIndex = bitIndex / 64;
    final int bitInWord = bitIndex % 64;

    final long[] longs = new long[4];
    for (int i = 0; i < wordIndex; i++) {
      longs[i] = -1L;
    }
    if (wordIndex < 4) {
      longs[wordIndex] = (1L << bitInWord) - 1;
    }

    return new Word256(longs[0], longs[1], longs[2], longs[3]);
  }

  static Word256 divideBySingleWord(final long[] u, final long d, final int m, final long[] q) {
    long remHi = 0;
    long remLo = 0;

    for (int j = m; j >= 0; j--) {
      remHi = remLo;
      remLo = u[j];

      long qhat = divideUnsigned128(remHi, remLo, d);

      if (j < q.length) {
        q[j] = qhat;
      }

      // Update remainder: remainder = (remHi:remLo) - qhat * d
      long prodLo = d * qhat;
      long prodHi = Math.multiplyHigh(d, qhat);

      long borrow = 0;
      if (Long.compareUnsigned(remLo, prodLo) < 0) {
        borrow = 1;
      }
      remLo = remLo - prodLo;
      remHi = remHi - prodHi - borrow;
    }

    return new Word256(q[0], q[1], q[2], q[3]);
  }

  private static long divideUnsigned128(final long hi, final long lo, final long d) {
    if (d == 0) {
      throw new ArithmeticException("Division by zero");
    }

    if (hi == 0) {
      return Long.divideUnsigned(lo, d);
    }

    long quotient = 0;
    long remainder = 0;

    for (int i = 127; i >= 0; i--) {
      // Shift remainder left by 1 and bring in next bit from hi:lo
      remainder <<= 1;
      final long bit = (i >= 64) ? ((hi >>> (i - 64)) & 1) : ((lo >>> i) & 1);
      remainder |= bit;

      quotient <<= 1;
      if (Long.compareUnsigned(remainder, d) >= 0) {
        remainder = Long.remainderUnsigned(remainder, d);
        quotient |= 1;
      }
    }

    return quotient;
  }

  static Word256 divideKnuth(
      final int m, final long[] un, final int n, final long[] vn, final long[] q) {
    for (int j = m; j >= 0; j--) {
      final long u2 = un[j + n];
      final long u1 = un[j + n - 1];

      final long v1 = vn[n - 1];
      final long v0 = vn[n - 2];

      long qHat = Word256Helpers.estimateQHat(u2, u1, v1);

      while (Word256Helpers.overflowEstimate(qHat, v1, v0, u2, u1)) {
        qHat--;
      }

      if (Word256Helpers.mulSub(un, vn, qHat, j) < 0) {
        qHat--;
        Word256Helpers.addBack(un, vn, j);
      }

      if (j < q.length) {
        q[j] = qHat;
      }
    }

    return new Word256(q[0], q[1], q[2], q[3]);
  }

  static int significantLength(final long[] x) {
    for (int i = x.length - 1; i >= 0; i--) {
      if (x[i] != 0) {
        return i + 1;
      }
    }

    return 0;
  }

  static long[] shiftLeft(final long[] x, final int shift, final int len) {
    long[] r = new long[len];
    long carry = 0;
    for (int i = 0; i < len; i++) {
      r[i] = (x[i] << shift) | carry;
      carry = x[i] >>> (64 - shift);
    }
    return r;
  }

  static long[] shiftLeftExtended(final long[] x, final int shift, final int len) {
    final long[] r = new long[len + 1];
    long carry = 0;

    for (int i = 0; i < len; i++) {
      final long xi = (i < x.length) ? x[i] : 0;
      r[i] = (xi << shift) | carry;
      carry = (shift == 0) ? 0 : (xi >>> (64 - shift));
    }

    r[len] = carry;
    return r;
  }

  static long estimateQHat(final long u2, final long u1, final long v1) {
    return Long.divideUnsigned((u2 << 32) | (u1 >>> 32), v1 >>> 32);
  }

  static boolean overflowEstimate(
      final long qhat, final long v1, final long v0, final long u2, final long u1) {
    final long rhat = ((u2 << 32) | (u1 >>> 32)) - qhat * (v1 >>> 32);
    final long left = (rhat << 32) | (u1 & 0xFFFFFFFFL);
    final long right = qhat * v0;

    return Long.compareUnsigned(left, right) < 0;
  }

  static long mulSub(final long[] un, final long[] vn, final long qhat, final int j) {
    long borrow = 0;
    for (int i = 0; i < vn.length; i++) {
      final long prod = qhat * vn[i];
      final long diff = un[i + j] - prod - borrow;

      borrow = Long.compareUnsigned(prod + borrow, un[i + j]) > 0 ? 1 : 0;
      un[i + j] = diff;
    }
    return borrow;
  }

  static void addBack(final long[] un, final long[] vn, final int j) {
    long carry = 0;
    for (int i = 0; i < vn.length; i++) {
      long sum = un[i + j] + vn[i] + carry;
      carry = Long.compareUnsigned(sum, un[i + j]) < 0 ? 1 : 0;
      un[i + j] = sum;
    }
  }

  static long[] shiftLeft512(final long[] x, final int shift) {
    if (shift == 0) {
      return Arrays.copyOf(x, 8);
    }

    final long[] result = new long[8];
    long carry = 0;

    for (int i = 0; i < 8; i++) {
      long limb = x[i];
      result[i] = (limb << shift) | carry;
      carry = (limb >>> (64 - shift));
    }

    return result;
  }

  public static long[] shiftRight(final long[] x, final int shift, final int len) {
    if (shift == 0) {
      return Arrays.copyOf(x, len);
    }

    final long[] result = new long[len];
    long carry = 0;

    for (int i = len - 1; i >= 0; i--) {
      final long limb = i < x.length ? x[i] : 0;
      result[i] = (limb >>> shift) | carry;
      carry = (limb << (64 - shift));
    }

    return result;
  }
}
