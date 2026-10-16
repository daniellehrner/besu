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

/**
 * UInt256's arithmetic on the EVM v2 stack's limbs: the operands are read from the stack and the
 * result is written into a stack slot, with no UInt256 in between. The algorithms are UInt256's,
 * step for step; where UInt256 passes several limbs from one of its methods to another, a private
 * record carries them here too.
 *
 * <p>A word at offset {@code o} is {@code s[o]} (most significant limb) to {@code s[o + 3]} (least
 * significant limb).
 */
// the generated multiply chains compute every limb UInt256 does, also those a caller drops
@SuppressWarnings({"UnusedVariable", "AlreadyChecked"})
final class StackArithmetic {

  private StackArithmetic() {}

  // region stack words
  // --------------------------------------------------------------------------

  static void put(
      final long[] s, final int o, final long u3, final long u2, final long u1, final long u0) {
    s[o] = u3;
    s[o + 1] = u2;
    s[o + 2] = u1;
    s[o + 3] = u0;
  }

  /** UInt256.neg, in place. */
  static void negate(final long[] s, final int o) {
    long z0 = ~s[o + 3] + 1;
    long carry = (z0 == 0) ? 1 : 0;
    long z1 = ~s[o + 2] + carry;
    carry = (z1 == 0 && carry == 1) ? 1 : 0;
    long z2 = ~s[o + 1] + carry;
    carry = (z2 == 0 && carry == 1) ? 1 : 0;
    long z3 = ~s[o] + carry;
    put(s, o, z3, z2, z1, z0);
  }

  // endregion

  // region ADD, SUB
  // --------------------------------------------------------------------------

  /** UInt256.add: s[out] = s[a] + s[b]. */
  static void add(final long[] s, final int a, final int b, final int out) {
    final long a3 = s[a], a2 = s[a + 1], a1 = s[a + 2], a0 = s[a + 3];
    final long b3 = s[b], b2 = s[b + 1], b1 = s[b + 2], b0 = s[b + 3];
    long z0 = a0 + b0;
    long carry = Long.compareUnsigned(z0, a0) < 0 ? 1 : 0;

    long z1 = a1 + b1 + carry;
    long overflowed = Long.compareUnsigned(z1, a1) < 0 ? 1 : 0;
    long overflowIfCarried = Long.compareUnsigned(z1, a1) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);

    long z2 = a2 + b2 + carry;
    overflowed = Long.compareUnsigned(z2, a2) < 0 ? 1 : 0;
    overflowIfCarried = Long.compareUnsigned(z2, a2) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);

    long z3 = a3 + b3 + carry;
    put(s, out, z3, z2, z1, z0);
  }

  /** UInt256.sub: s[out] = s[a] - s[b]. */
  static void sub(final long[] s, final int a, final int b, final int out) {
    final long a3 = s[a], a2 = s[a + 1], a1 = s[a + 2], a0 = s[a + 3];
    final long b3 = s[b], b2 = s[b + 1], b1 = s[b + 2], b0 = s[b + 3];
    long z0 = a0 - b0;
    long borrow = Long.compareUnsigned(a0, b0) < 0 ? 1 : 0;

    long z1 = a1 - b1 - borrow;
    long underflowed = Long.compareUnsigned(a1, b1) < 0 ? 1 : 0;
    long underflowIfBorrowed = Long.compareUnsigned(a1, b1) == 0 ? 1 : 0;
    borrow = underflowed | (underflowIfBorrowed & borrow);

    long z2 = a2 - b2 - borrow;
    underflowed = Long.compareUnsigned(a2, b2) < 0 ? 1 : 0;
    underflowIfBorrowed = Long.compareUnsigned(a2, b2) == 0 ? 1 : 0;
    borrow = underflowed | (underflowIfBorrowed & borrow);

    long z3 = a3 - b3 - borrow;
    put(s, out, z3, z2, z1, z0);
  }

  // endregion

  // region shifts
  // --------------------------------------------------------------------------

  /** The shift of UInt256.shl, shr and sar: the word at {@code o}, or 256 when it is larger. */
  static int shiftAmount(final long[] s, final int o) {
    if (s[o] != 0 || s[o + 1] != 0 || s[o + 2] != 0 || Long.compareUnsigned(s[o + 3], 256) >= 0) {
      return 256;
    }
    return (int) s[o + 3];
  }

  /** UInt256.shl0: s[out] = w << shift, for a shift of 0 to 256. */
  static void shl(
      final long[] s,
      final int out,
      final long u3,
      final long u2,
      final long u1,
      final long u0,
      final int shift) {
    long w3 = u3, w2 = u2, w1 = u1, w0 = u0;
    if (shift == 256) {
      w3 = 0;
      w2 = 0;
      w1 = 0;
      w0 = 0;
    } else if (shift != 0) {
      final int wordShift = shift >>> 6;
      final int bitShift = shift & 63;
      switch (wordShift) {
        case 0:
          w3 = shiftLeftWord(w3, w2, bitShift);
          w2 = shiftLeftWord(w2, w1, bitShift);
          w1 = shiftLeftWord(w1, w0, bitShift);
          w0 = shiftLeftWord(w0, 0, bitShift);
          break;
        case 1:
          w3 = shiftLeftWord(w2, w1, bitShift);
          w2 = shiftLeftWord(w1, w0, bitShift);
          w1 = shiftLeftWord(w0, 0, bitShift);
          w0 = 0;
          break;
        case 2:
          w3 = shiftLeftWord(w1, w0, bitShift);
          w2 = shiftLeftWord(w0, 0, bitShift);
          w1 = 0;
          w0 = 0;
          break;
        case 3:
          w3 = shiftLeftWord(w0, 0, bitShift);
          w2 = 0;
          w1 = 0;
          w0 = 0;
          break;
      }
    }
    put(s, out, w3, w2, w1, w0);
  }

  /** UInt256.sar0: s[out] = w >> shift, filling with {@code fill}, for a shift of 0 to 256. */
  static void sar(
      final long[] s,
      final int out,
      final long u3,
      final long u2,
      final long u1,
      final long u0,
      final int shift,
      final long fill) {
    long w3 = u3, w2 = u2, w1 = u1, w0 = u0;
    if (shift == 256) {
      w3 = fill;
      w2 = fill;
      w1 = fill;
      w0 = fill;
    } else if (shift != 0) {
      final int wordShift = shift >>> 6;
      final int bitShift = shift & 63;
      switch (wordShift) {
        case 0:
          w0 = shiftRightWord(w0, w1, bitShift);
          w1 = shiftRightWord(w1, w2, bitShift);
          w2 = shiftRightWord(w2, w3, bitShift);
          w3 = shiftRightWord(w3, fill, bitShift);
          break;
        case 1:
          w0 = shiftRightWord(w1, w2, bitShift);
          w1 = shiftRightWord(w2, w3, bitShift);
          w2 = shiftRightWord(w3, fill, bitShift);
          w3 = fill;
          break;
        case 2:
          w0 = shiftRightWord(w2, w3, bitShift);
          w1 = shiftRightWord(w3, fill, bitShift);
          w2 = fill;
          w3 = fill;
          break;
        case 3:
          w0 = shiftRightWord(w3, fill, bitShift);
          w1 = fill;
          w2 = fill;
          w3 = fill;
          break;
      }
    }
    put(s, out, w3, w2, w1, w0);
  }

  private static long shiftLeftWord(final long value, final long nextValue, final int bitShift) {
    if (bitShift == 0) return value;
    return (value << bitShift) | (nextValue >>> (64 - bitShift));
  }

  private static long shiftRightWord(final long value, final long prevValue, final int bitShift) {
    if (bitShift == 0) return value;
    return (value >>> bitShift) | (prevValue << (64 - bitShift));
  }

  /** UInt256.maskLow: s[out] = w & ((1 << n) - 1), for 1 <= n < 256. */
  private static void maskLow(
      final long[] s,
      final int out,
      final long u3,
      final long u2,
      final long u1,
      final long u0,
      final int n) {
    final int limbsKept = n >>> 6;
    final int bitsInTop = n & 63;
    final long topMask = bitsInTop == 0 ? 0L : (1L << bitsInTop) - 1L;
    final long m0 = limbsKept > 0 ? u0 : (u0 & topMask);
    final long m1 = limbsKept > 1 ? u1 : (limbsKept == 1 ? (u1 & topMask) : 0L);
    final long m2 = limbsKept > 2 ? u2 : (limbsKept == 2 ? (u2 & topMask) : 0L);
    final long m3 = limbsKept == 3 ? (u3 & topMask) : 0L;
    put(s, out, m3, m2, m1, m0);
  }

  // endregion

  // region MUL
  // --------------------------------------------------------------------------

  /** UInt256.mul: s[out] = s[a] * s[b]. */
  static void mul(final long[] s, final int a, final int b, final int out) {
    final long a3 = s[a], a2 = s[a + 1], a1 = s[a + 2], a0 = s[a + 3];
    final long b3 = s[b], b2 = s[b + 1], b1 = s[b + 2], b0 = s[b + 3];
    if ((a0 | a1 | a2 | a3) == 0 || (b0 | b1 | b2 | b3) == 0) {
      put(s, out, 0, 0, 0, 0);
    } else if (((b0 ^ 1L) | b1 | b2 | b3) == 0) {
      put(s, out, a3, a2, a1, a0);
    } else if (((a0 ^ 1L) | a1 | a2 | a3) == 0) {
      put(s, out, b3, b2, b1, b0);
    } else if (a3 != 0) {
      mul256(s, out, a3, a2, a1, a0, b3, b2, b1, b0);
    } else if (a2 != 0) {
      mul192(s, out, a2, a1, a0, b3, b2, b1, b0);
    } else if (a1 != 0) {
      mul128(s, out, a1, a0, b3, b2, b1, b0);
    } else {
      mul64(s, out, a0, b3, b2, b1, b0);
    }
  }

  private static void mul64(
      final long[] s,
      final int out,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    long lo = a0 * b0;
    long hi = Math.unsignedMultiplyHigh(a0, b0);
    final long p0 = lo;
    long carry = hi;
    lo = a0 * b1;
    hi = Math.unsignedMultiplyHigh(a0, b1);
    final long p1 = lo + carry;
    carry = hi + ((Long.compareUnsigned(p1, lo) < 0) ? 1 : 0);
    lo = a0 * b2;
    hi = Math.unsignedMultiplyHigh(a0, b2);
    final long p2 = lo + carry;
    carry = hi + ((Long.compareUnsigned(p2, lo) < 0) ? 1 : 0);
    final long p3 = a0 * b3 + carry;
    put(s, out, p3, p2, p1, p0);
  }

  private static void mul128(
      final long[] s,
      final int out,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    long pr0 = 0, pr1 = 0, pr2 = 0;
    if (b0 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = 0;
    } else {
      long lo = a0 * b0;
      long hi = Math.unsignedMultiplyHigh(a0, b0);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b0;
      hi = Math.unsignedMultiplyHigh(a1, b0);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      pr2 = carry;
    }
    final long p0 = pr0;
    if (b1 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = 0;
    } else {
      long lo = a0 * b1;
      long hi = Math.unsignedMultiplyHigh(a0, b1);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b1;
      hi = Math.unsignedMultiplyHigh(a1, b1);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      pr2 = carry;
    }
    final long p1 = pr0;
    if (b2 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = 0;
    } else {
      long lo = a0 * b2;
      long hi = Math.unsignedMultiplyHigh(a0, b2);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b2;
      hi = Math.unsignedMultiplyHigh(a1, b2);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      pr2 = carry;
    }
    final long p2 = pr0;
    if (b3 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = 0;
    } else {
      long lo = a0 * b3;
      long hi = Math.unsignedMultiplyHigh(a0, b3);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b3;
      hi = Math.unsignedMultiplyHigh(a1, b3);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      pr2 = carry;
    }
    final long p3 = pr0;
    final long p4 = pr1;
    final long p5 = pr2;
    put(s, out, p3, p2, p1, p0);
  }

  private static void mul192(
      final long[] s,
      final int out,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    long pr0 = 0, pr1 = 0, pr2 = 0, pr3 = 0;
    if (b0 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = 0;
    } else {
      long lo = a0 * b0;
      long hi = Math.unsignedMultiplyHigh(a0, b0);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b0;
      hi = Math.unsignedMultiplyHigh(a1, b0);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b0;
      hi = Math.unsignedMultiplyHigh(a2, b0);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      pr3 = carry;
    }
    final long p0 = pr0;
    if (b1 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = 0;
    } else {
      long lo = a0 * b1;
      long hi = Math.unsignedMultiplyHigh(a0, b1);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b1;
      hi = Math.unsignedMultiplyHigh(a1, b1);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b1;
      hi = Math.unsignedMultiplyHigh(a2, b1);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      pr3 = carry;
    }
    final long p1 = pr0;
    if (b2 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = 0;
    } else {
      long lo = a0 * b2;
      long hi = Math.unsignedMultiplyHigh(a0, b2);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b2;
      hi = Math.unsignedMultiplyHigh(a1, b2);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b2;
      hi = Math.unsignedMultiplyHigh(a2, b2);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      pr3 = carry;
    }
    final long p2 = pr0;
    if (b3 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = 0;
    } else {
      long lo = a0 * b3;
      long hi = Math.unsignedMultiplyHigh(a0, b3);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b3;
      hi = Math.unsignedMultiplyHigh(a1, b3);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b3;
      hi = Math.unsignedMultiplyHigh(a2, b3);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      pr3 = carry;
    }
    final long p3 = pr0;
    final long p4 = pr1;
    final long p5 = pr2;
    final long p6 = pr3;
    put(s, out, p3, p2, p1, p0);
  }

  private static void mul256(
      final long[] s,
      final int out,
      final long a3,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    long pr0 = 0, pr1 = 0, pr2 = 0, pr3 = 0, pr4 = 0;
    if (b0 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b0;
      long hi = Math.unsignedMultiplyHigh(a0, b0);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b0;
      hi = Math.unsignedMultiplyHigh(a1, b0);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b0;
      hi = Math.unsignedMultiplyHigh(a2, b0);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b0;
      hi = Math.unsignedMultiplyHigh(a3, b0);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p0 = pr0;
    if (b1 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b1;
      long hi = Math.unsignedMultiplyHigh(a0, b1);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b1;
      hi = Math.unsignedMultiplyHigh(a1, b1);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b1;
      hi = Math.unsignedMultiplyHigh(a2, b1);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b1;
      hi = Math.unsignedMultiplyHigh(a3, b1);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p1 = pr0;
    if (b2 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b2;
      long hi = Math.unsignedMultiplyHigh(a0, b2);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b2;
      hi = Math.unsignedMultiplyHigh(a1, b2);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b2;
      hi = Math.unsignedMultiplyHigh(a2, b2);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b2;
      hi = Math.unsignedMultiplyHigh(a3, b2);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p2 = pr0;
    if (b3 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b3;
      long hi = Math.unsignedMultiplyHigh(a0, b3);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b3;
      hi = Math.unsignedMultiplyHigh(a1, b3);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b3;
      hi = Math.unsignedMultiplyHigh(a2, b3);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b3;
      hi = Math.unsignedMultiplyHigh(a3, b3);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p3 = pr0;
    final long p4 = pr1;
    final long p5 = pr2;
    final long p6 = pr3;
    final long p7 = pr4;
    put(s, out, p3, p2, p1, p0);
  }

  // endregion

  // region EXP
  // --------------------------------------------------------------------------

  /** A 256-bit value passed between the steps of EXP and the multiply-reduce paths. */
  private record W4(long u3, long u2, long u1, long u0) {}

  private static final W4 W4_ZERO = new W4(0, 0, 0, 0);
  private static final W4 W4_ONE = new W4(0, 0, 0, 1);

  /**
   * ExpOperationV2.power: s[out] = base ** exponent, with the exponent's {@code exponentBits} bits
   * read from s[exponent].
   */
  static void power(
      final long[] s, final int base, final int exponent, final int exponentBits, final int out) {
    final long b3 = s[base], b2 = s[base + 1], b1 = s[base + 2], b0 = s[base + 3];
    W4 result = W4_ONE;
    for (int bit = exponentBits - 1; bit >= 0; bit--) {
      result =
          mulW(
              result.u3, result.u2, result.u1, result.u0, result.u3, result.u2, result.u1,
              result.u0);
      final long limb = s[exponent + 3 - (bit >>> 6)];
      if (((limb >>> (bit & 63)) & 1L) != 0) {
        result = mulW(result.u3, result.u2, result.u1, result.u0, b3, b2, b1, b0);
      }
    }
    put(s, out, result.u3, result.u2, result.u1, result.u0);
  }

  /** UInt256.mul, returning the product. */
  private static W4 mulW(
      final long a3,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    if ((a0 | a1 | a2 | a3) == 0 || (b0 | b1 | b2 | b3) == 0) return W4_ZERO;
    if (((b0 ^ 1L) | b1 | b2 | b3) == 0) return new W4(a3, a2, a1, a0);
    if (((a0 ^ 1L) | a1 | a2 | a3) == 0) return new W4(b3, b2, b1, b0);
    if (a3 != 0) return mul256W(a3, a2, a1, a0, b3, b2, b1, b0);
    if (a2 != 0) return mul192W(a2, a1, a0, b3, b2, b1, b0);
    if (a1 != 0) return mul128W(a1, a0, b3, b2, b1, b0);
    return mul64W(a0, b3, b2, b1, b0);
  }

  private static W4 mul64W(
      final long a0, final long b3, final long b2, final long b1, final long b0) {
    long lo = a0 * b0;
    long hi = Math.unsignedMultiplyHigh(a0, b0);
    final long p0 = lo;
    long carry = hi;
    lo = a0 * b1;
    hi = Math.unsignedMultiplyHigh(a0, b1);
    final long p1 = lo + carry;
    carry = hi + ((Long.compareUnsigned(p1, lo) < 0) ? 1 : 0);
    lo = a0 * b2;
    hi = Math.unsignedMultiplyHigh(a0, b2);
    final long p2 = lo + carry;
    carry = hi + ((Long.compareUnsigned(p2, lo) < 0) ? 1 : 0);
    final long p3 = a0 * b3 + carry;
    return new W4(p3, p2, p1, p0);
  }

  private static W4 mul128W(
      final long a1, final long a0, final long b3, final long b2, final long b1, final long b0) {
    long pr0 = 0, pr1 = 0, pr2 = 0;
    if (b0 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = 0;
    } else {
      long lo = a0 * b0;
      long hi = Math.unsignedMultiplyHigh(a0, b0);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b0;
      hi = Math.unsignedMultiplyHigh(a1, b0);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      pr2 = carry;
    }
    final long p0 = pr0;
    if (b1 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = 0;
    } else {
      long lo = a0 * b1;
      long hi = Math.unsignedMultiplyHigh(a0, b1);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b1;
      hi = Math.unsignedMultiplyHigh(a1, b1);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      pr2 = carry;
    }
    final long p1 = pr0;
    if (b2 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = 0;
    } else {
      long lo = a0 * b2;
      long hi = Math.unsignedMultiplyHigh(a0, b2);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b2;
      hi = Math.unsignedMultiplyHigh(a1, b2);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      pr2 = carry;
    }
    final long p2 = pr0;
    if (b3 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = 0;
    } else {
      long lo = a0 * b3;
      long hi = Math.unsignedMultiplyHigh(a0, b3);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b3;
      hi = Math.unsignedMultiplyHigh(a1, b3);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      pr2 = carry;
    }
    final long p3 = pr0;
    final long p4 = pr1;
    final long p5 = pr2;
    return new W4(p3, p2, p1, p0);
  }

  private static W4 mul192W(
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    long pr0 = 0, pr1 = 0, pr2 = 0, pr3 = 0;
    if (b0 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = 0;
    } else {
      long lo = a0 * b0;
      long hi = Math.unsignedMultiplyHigh(a0, b0);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b0;
      hi = Math.unsignedMultiplyHigh(a1, b0);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b0;
      hi = Math.unsignedMultiplyHigh(a2, b0);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      pr3 = carry;
    }
    final long p0 = pr0;
    if (b1 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = 0;
    } else {
      long lo = a0 * b1;
      long hi = Math.unsignedMultiplyHigh(a0, b1);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b1;
      hi = Math.unsignedMultiplyHigh(a1, b1);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b1;
      hi = Math.unsignedMultiplyHigh(a2, b1);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      pr3 = carry;
    }
    final long p1 = pr0;
    if (b2 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = 0;
    } else {
      long lo = a0 * b2;
      long hi = Math.unsignedMultiplyHigh(a0, b2);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b2;
      hi = Math.unsignedMultiplyHigh(a1, b2);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b2;
      hi = Math.unsignedMultiplyHigh(a2, b2);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      pr3 = carry;
    }
    final long p2 = pr0;
    if (b3 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = 0;
    } else {
      long lo = a0 * b3;
      long hi = Math.unsignedMultiplyHigh(a0, b3);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b3;
      hi = Math.unsignedMultiplyHigh(a1, b3);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b3;
      hi = Math.unsignedMultiplyHigh(a2, b3);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      pr3 = carry;
    }
    final long p3 = pr0;
    final long p4 = pr1;
    final long p5 = pr2;
    final long p6 = pr3;
    return new W4(p3, p2, p1, p0);
  }

  private static W4 mul256W(
      final long a3,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    long pr0 = 0, pr1 = 0, pr2 = 0, pr3 = 0, pr4 = 0;
    if (b0 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b0;
      long hi = Math.unsignedMultiplyHigh(a0, b0);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b0;
      hi = Math.unsignedMultiplyHigh(a1, b0);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b0;
      hi = Math.unsignedMultiplyHigh(a2, b0);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b0;
      hi = Math.unsignedMultiplyHigh(a3, b0);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p0 = pr0;
    if (b1 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b1;
      long hi = Math.unsignedMultiplyHigh(a0, b1);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b1;
      hi = Math.unsignedMultiplyHigh(a1, b1);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b1;
      hi = Math.unsignedMultiplyHigh(a2, b1);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b1;
      hi = Math.unsignedMultiplyHigh(a3, b1);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p1 = pr0;
    if (b2 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b2;
      long hi = Math.unsignedMultiplyHigh(a0, b2);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b2;
      hi = Math.unsignedMultiplyHigh(a1, b2);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b2;
      hi = Math.unsignedMultiplyHigh(a2, b2);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b2;
      hi = Math.unsignedMultiplyHigh(a3, b2);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p2 = pr0;
    if (b3 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b3;
      long hi = Math.unsignedMultiplyHigh(a0, b3);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b3;
      hi = Math.unsignedMultiplyHigh(a1, b3);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b3;
      hi = Math.unsignedMultiplyHigh(a2, b3);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b3;
      hi = Math.unsignedMultiplyHigh(a3, b3);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p3 = pr0;
    final long p4 = pr1;
    final long p5 = pr2;
    final long p6 = pr3;
    final long p7 = pr4;
    return new W4(p3, p2, p1, p0);
  }

  // endregion

  // region DIV, SDIV, MOD, SMOD
  // --------------------------------------------------------------------------

  /** UInt256.div: s[out] = s[num] / s[den]. */
  static void div(final long[] s, final int num, final int den, final int out) {
    div(
        s,
        out,
        s[num],
        s[num + 1],
        s[num + 2],
        s[num + 3],
        s[den],
        s[den + 1],
        s[den + 2],
        s[den + 3]);
  }

  private static void div(
      final long[] s,
      final int out,
      final long t3,
      final long t2,
      final long t1,
      final long t0,
      final long d3,
      final long d2,
      final long d1,
      final long d0) {
    if ((t0 | t1 | t2 | t3) == 0) {
      put(s, out, 0, 0, 0, 0);
    } else if (d3 != 0) {
      if ((d3 & (d3 - 1)) == 0 && (d2 | d1 | d0) == 0) {
        sar(s, out, t3, t2, t1, t0, 192 + Long.numberOfTrailingZeros(d3), 0);
      } else {
        divReduce256(s, out, d3, d2, d1, d0, t3, t2, t1, t0);
      }
    } else if (d2 != 0) {
      if ((d2 & (d2 - 1)) == 0 && (d1 | d0) == 0) {
        sar(s, out, t3, t2, t1, t0, 128 + Long.numberOfTrailingZeros(d2), 0);
      } else {
        divReduce192(s, out, d2, d1, d0, t3, t2, t1, t0);
      }
    } else if (d1 != 0) {
      if ((d1 & (d1 - 1)) == 0 && d0 == 0) {
        sar(s, out, t3, t2, t1, t0, 64 + Long.numberOfTrailingZeros(d1), 0);
      } else {
        divReduce128(s, out, d1, d0, t3, t2, t1, t0);
      }
    } else if ((d0 == 0) || (d0 == 1)) {
      if (d0 == 1) {
        put(s, out, t3, t2, t1, t0);
      } else {
        put(s, out, 0, 0, 0, 0);
      }
    } else if ((d0 & (d0 - 1)) == 0) {
      sar(s, out, t3, t2, t1, t0, Long.numberOfTrailingZeros(d0), 0);
    } else {
      divReduce64(s, out, d0, t3, t2, t1, t0);
    }
  }

  /** UInt256.signedDiv: s[out] = s[num] / s[den], signed. */
  static void signedDiv(final long[] s, final int num, final int den, final int out) {
    long t3 = s[num], t2 = s[num + 1], t1 = s[num + 2], t0 = s[num + 3];
    long d3 = s[den], d2 = s[den + 1], d1 = s[den + 2], d0 = s[den + 3];
    if ((t0 | t1 | t2 | t3) == 0 || (d0 | d1 | d2 | d3) == 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    final boolean negative = (t3 < 0) != (d3 < 0);
    if (t3 < 0) {
      long z0 = ~t0 + 1;
      long carry = (z0 == 0) ? 1 : 0;
      long z1 = ~t1 + carry;
      carry = (z1 == 0 && carry == 1) ? 1 : 0;
      long z2 = ~t2 + carry;
      carry = (z2 == 0 && carry == 1) ? 1 : 0;
      t3 = ~t3 + carry;
      t2 = z2;
      t1 = z1;
      t0 = z0;
    }
    if (d3 < 0) {
      long z0 = ~d0 + 1;
      long carry = (z0 == 0) ? 1 : 0;
      long z1 = ~d1 + carry;
      carry = (z1 == 0 && carry == 1) ? 1 : 0;
      long z2 = ~d2 + carry;
      carry = (z2 == 0 && carry == 1) ? 1 : 0;
      d3 = ~d3 + carry;
      d2 = z2;
      d1 = z1;
      d0 = z0;
    }
    div(s, out, t3, t2, t1, t0, d3, d2, d1, d0);
    if (negative) negate(s, out);
  }

  /** UInt256.mod: s[out] = s[num] % s[mod]. */
  static void mod(final long[] s, final int num, final int mod, final int out) {
    mod(
        s,
        out,
        s[num],
        s[num + 1],
        s[num + 2],
        s[num + 3],
        s[mod],
        s[mod + 1],
        s[mod + 2],
        s[mod + 3]);
  }

  private static void mod(
      final long[] s,
      final int out,
      final long t3,
      final long t2,
      final long t1,
      final long t0,
      final long m3,
      final long m2,
      final long m1,
      final long m0) {
    if ((t0 | t1 | t2 | t3) == 0) {
      put(s, out, 0, 0, 0, 0);
    } else if (m3 != 0) {
      modReduce256(s, out, m3, m2, m1, m0, t3, t2, t1, t0);
    } else if (m2 != 0) {
      modReduce192(s, out, m2, m1, m0, t3, t2, t1, t0);
    } else if (m1 != 0) {
      modReduce128(s, out, m1, m0, t3, t2, t1, t0);
    } else if ((m0 == 0) || (m0 == 1)) {
      put(s, out, 0, 0, 0, 0);
    } else {
      put(s, out, 0, 0, 0, modReduce64(m0, t3, t2, t1, t0));
    }
  }

  /** UInt256.signedMod: s[out] = s[num] % s[mod], signed. */
  static void signedMod(final long[] s, final int num, final int mod, final int out) {
    long t3 = s[num], t2 = s[num + 1], t1 = s[num + 2], t0 = s[num + 3];
    long m3 = s[mod], m2 = s[mod + 1], m1 = s[mod + 2], m0 = s[mod + 3];
    if ((t0 | t1 | t2 | t3) == 0
        || ((m0 & -2L) | m1 | m2 | m3) == 0
        || (m3 & m2 & m1 & m0) == -1L) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    final boolean negative = t3 < 0;
    if (t3 < 0) {
      long z0 = ~t0 + 1;
      long carry = (z0 == 0) ? 1 : 0;
      long z1 = ~t1 + carry;
      carry = (z1 == 0 && carry == 1) ? 1 : 0;
      long z2 = ~t2 + carry;
      carry = (z2 == 0 && carry == 1) ? 1 : 0;
      t3 = ~t3 + carry;
      t2 = z2;
      t1 = z1;
      t0 = z0;
    }
    if (m3 < 0) {
      long z0 = ~m0 + 1;
      long carry = (z0 == 0) ? 1 : 0;
      long z1 = ~m1 + carry;
      carry = (z1 == 0 && carry == 1) ? 1 : 0;
      long z2 = ~m2 + carry;
      carry = (z2 == 0 && carry == 1) ? 1 : 0;
      m3 = ~m3 + carry;
      m2 = z2;
      m1 = z1;
      m0 = z0;
    }
    mod(s, out, t3, t2, t1, t0, m3, m2, m1, m0);
    if (negative) negate(s, out);
  }

  // endregion

  // region ADDMOD, MULMOD
  // --------------------------------------------------------------------------

  /** UInt256.addMod: s[out] = (s[a] + s[b]) % s[mod]. */
  static void addMod(final long[] s, final int a, final int b, final int mod, final int out) {
    final long a3 = s[a], a2 = s[a + 1], a1 = s[a + 2], a0 = s[a + 3];
    final long b3 = s[b], b2 = s[b + 1], b1 = s[b + 2], b0 = s[b + 3];
    final long m3 = s[mod], m2 = s[mod + 1], m1 = s[mod + 2], m0 = s[mod + 3];
    if ((a0 | a1 | a2 | a3) == 0) {
      mod(s, out, b3, b2, b1, b0, m3, m2, m1, m0);
      return;
    }
    if ((b0 | b1 | b2 | b3) == 0) {
      mod(s, out, a3, a2, a1, a0, m3, m2, m1, m0);
      return;
    }
    if (((m0 & -2L) | m1 | m2 | m3) == 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    if (m3 != 0) {
      if ((m3 & (m3 - 1)) == 0 && (m2 | m1 | m0) == 0) {
        addMaskLow(s, out, a3, a2, a1, a0, b3, b2, b1, b0, 192 + Long.numberOfTrailingZeros(m3));
      } else {
        sum256(s, out, m3, m2, m1, m0, a3, a2, a1, a0, b3, b2, b1, b0);
      }
    } else if (m2 != 0) {
      if ((m2 & (m2 - 1)) == 0 && (m1 | m0) == 0) {
        addMaskLow(s, out, a3, a2, a1, a0, b3, b2, b1, b0, 128 + Long.numberOfTrailingZeros(m2));
      } else {
        sum192(s, out, m2, m1, m0, a3, a2, a1, a0, b3, b2, b1, b0);
      }
    } else if (m1 != 0) {
      if ((m1 & (m1 - 1)) == 0 && m0 == 0) {
        addMaskLow(s, out, a3, a2, a1, a0, b3, b2, b1, b0, 64 + Long.numberOfTrailingZeros(m1));
      } else {
        sum128(s, out, m1, m0, a3, a2, a1, a0, b3, b2, b1, b0);
      }
    } else if ((m0 & (m0 - 1)) == 0) {
      addMaskLow(s, out, a3, a2, a1, a0, b3, b2, b1, b0, Long.numberOfTrailingZeros(m0));
    } else {
      put(s, out, 0, 0, 0, sum64(m0, a3, a2, a1, a0, b3, b2, b1, b0));
    }
  }

  /** UInt256.add followed by maskLow. */
  private static void addMaskLow(
      final long[] s,
      final int out,
      final long a3,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0,
      final int n) {
    long z0 = a0 + b0;
    long carry = Long.compareUnsigned(z0, a0) < 0 ? 1 : 0;

    long z1 = a1 + b1 + carry;
    long overflowed = Long.compareUnsigned(z1, a1) < 0 ? 1 : 0;
    long overflowIfCarried = Long.compareUnsigned(z1, a1) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);

    long z2 = a2 + b2 + carry;
    overflowed = Long.compareUnsigned(z2, a2) < 0 ? 1 : 0;
    overflowIfCarried = Long.compareUnsigned(z2, a2) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);

    long z3 = a3 + b3 + carry;
    maskLow(s, out, z3, z2, z1, z0, n);
  }

  /** UInt256.mulMod: s[out] = (s[a] * s[b]) % s[mod]. */
  static void mulMod(final long[] s, final int a, final int b, final int mod, final int out) {
    final long a3 = s[a], a2 = s[a + 1], a1 = s[a + 2], a0 = s[a + 3];
    final long b3 = s[b], b2 = s[b + 1], b1 = s[b + 2], b0 = s[b + 3];
    final long m3 = s[mod], m2 = s[mod + 1], m1 = s[mod + 2], m0 = s[mod + 3];
    if ((a0 | a1 | a2 | a3) == 0 || (b0 | b1 | b2 | b3) == 0 || ((m0 & -2L) | m1 | m2 | m3) == 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    if (((a0 ^ 1L) | a1 | a2 | a3) == 0) {
      mod(s, out, b3, b2, b1, b0, m3, m2, m1, m0);
      return;
    }
    if (((b0 ^ 1L) | b1 | b2 | b3) == 0) {
      mod(s, out, a3, a2, a1, a0, m3, m2, m1, m0);
      return;
    }
    if (m3 != 0) {
      if ((m3 & (m3 - 1)) == 0 && (m2 | m1 | m0) == 0) {
        final W4 p = mulW(a3, a2, a1, a0, b3, b2, b1, b0);
        maskLow(s, out, p.u3, p.u2, p.u1, p.u0, 192 + Long.numberOfTrailingZeros(m3));
      } else {
        mulMod256(s, out, m3, m2, m1, m0, a3, a2, a1, a0, b3, b2, b1, b0);
      }
    } else if (m2 != 0) {
      if ((m2 & (m2 - 1)) == 0 && (m1 | m0) == 0) {
        final W4 p = mulW(a3, a2, a1, a0, b3, b2, b1, b0);
        maskLow(s, out, p.u3, p.u2, p.u1, p.u0, 128 + Long.numberOfTrailingZeros(m2));
      } else {
        mulMod192(s, out, m2, m1, m0, a3, a2, a1, a0, b3, b2, b1, b0);
      }
    } else if (m1 != 0) {
      if ((m1 & (m1 - 1)) == 0 && m0 == 0) {
        final W4 p = mulW(a3, a2, a1, a0, b3, b2, b1, b0);
        maskLow(s, out, p.u3, p.u2, p.u1, p.u0, 64 + Long.numberOfTrailingZeros(m1));
      } else {
        mulMod128(s, out, m1, m0, a3, a2, a1, a0, b3, b2, b1, b0);
      }
    } else if ((m0 & (m0 - 1)) == 0) {
      final W4 p = mulW(a3, a2, a1, a0, b3, b2, b1, b0);
      maskLow(s, out, p.u3, p.u2, p.u1, p.u0, Long.numberOfTrailingZeros(m0));
    } else {
      put(s, out, 0, 0, 0, mulMod64(m0, a3, a2, a1, a0, b3, b2, b1, b0));
    }
  }

  // endregion

  // region division primitives
  // --------------------------------------------------------------------------

  private record QR64(long q, long r) {}

  private record QR128(long q, long r1, long r0) {}

  private record QR192(long q, long r2, long r1, long r0) {}

  private record QR256(long q, long r3, long r2, long r1, long r0) {}

  // Lookup table for $\floor{\frac{2^{19} -3 ⋅ 2^8}{d_9 - 256}}$
  private static final short[] LUT =
      new short[] {
        2045, 2037, 2029, 2021, 2013, 2005, 1998, 1990, 1983, 1975, 1968, 1960, 1953, 1946, 1938,
        1931, 1924, 1917, 1910, 1903, 1896, 1889, 1883, 1876, 1869, 1863, 1856, 1849, 1843, 1836,
        1830, 1824, 1817, 1811, 1805, 1799, 1792, 1786, 1780, 1774, 1768, 1762, 1756, 1750, 1745,
        1739, 1733, 1727, 1722, 1716, 1710, 1705, 1699, 1694, 1688, 1683, 1677, 1672, 1667, 1661,
        1656, 1651, 1646, 1641, 1636, 1630, 1625, 1620, 1615, 1610, 1605, 1600, 1596, 1591, 1586,
        1581, 1576, 1572, 1567, 1562, 1558, 1553, 1548, 1544, 1539, 1535, 1530, 1526, 1521, 1517,
        1513, 1508, 1504, 1500, 1495, 1491, 1487, 1483, 1478, 1474, 1470, 1466, 1462, 1458, 1454,
        1450, 1446, 1442, 1438, 1434, 1430, 1426, 1422, 1418, 1414, 1411, 1407, 1403, 1399, 1396,
        1392, 1388, 1384, 1381, 1377, 1374, 1370, 1366, 1363, 1359, 1356, 1352, 1349, 1345, 1342,
        1338, 1335, 1332, 1328, 1325, 1322, 1318, 1315, 1312, 1308, 1305, 1302, 1299, 1295, 1292,
        1289, 1286, 1283, 1280, 1276, 1273, 1270, 1267, 1264, 1261, 1258, 1255, 1252, 1249, 1246,
        1243, 1240, 1237, 1234, 1231, 1228, 1226, 1223, 1220, 1217, 1214, 1211, 1209, 1206, 1203,
        1200, 1197, 1195, 1192, 1189, 1187, 1184, 1181, 1179, 1176, 1173, 1171, 1168, 1165, 1163,
        1160, 1158, 1155, 1153, 1150, 1148, 1145, 1143, 1140, 1138, 1135, 1133, 1130, 1128, 1125,
        1123, 1121, 1118, 1116, 1113, 1111, 1109, 1106, 1104, 1102, 1099, 1097, 1095, 1092, 1090,
        1088, 1086, 1083, 1081, 1079, 1077, 1074, 1072, 1070, 1068, 1066, 1064, 1061, 1059, 1057,
        1055, 1053, 1051, 1049, 1047, 1044, 1042, 1040, 1038, 1036, 1034, 1032, 1030, 1028, 1026,
        1024,
      };

  private static final long TWO_POW_SIXTY = 1L << 60;

  // Taken from https://gmplib.org/~tege/division-paper.pdf taken from III. algorithm 2
  private static long reciprocal(final long x) {
    // Unchecked: x >= (1 << 63)
    long x0 = x & 1L;
    int x9 = (int) (x >>> 55);
    long x40 = 1 + (x >>> 24);
    long x63 = (x + 1) >>> 1;
    long v0 = LUT[x9 - 256] & 0xFFFFL;
    long v1 = (v0 << 11) - ((v0 * v0 * x40) >>> 40) - 1;
    long v2 = (v1 << 13) + ((v1 * (TWO_POW_SIXTY - v1 * x40)) >>> 47);
    long e = ((v2 >>> 1) & (-x0)) - v2 * x63;
    long s = Math.unsignedMultiplyHigh(v2, e);
    long v3 = (s >>> 1) + (v2 << 31);
    long t0 = v3 * x;
    long t1 = Math.unsignedMultiplyHigh(v3, x);
    t0 += x;
    t1 += Long.compareUnsigned(t0, x) < 0 ? 1 : 0;
    t1 += x;
    return v3 - t1;
  }

  private static QR64 div2by1(final long x1, final long x0, final long y, final long yInv) {
    long q0 = x1 * yInv;
    long q1 = Math.unsignedMultiplyHigh(x1, yInv);

    long sum = q0 + x0;
    long carry = ((q0 & x0) | ((q0 | x0) & ~sum)) >>> 63;
    q0 = sum;
    q1 += x1 + carry + 1;

    long r = x0 - q1 * y;

    long adjust = Long.compareUnsigned(q0, r) < 0 ? 1 : 0;
    q1 -= adjust;
    r += adjust * y;

    adjust = Long.compareUnsigned(y, r) <= 0 ? 1 : 0;
    q1 += adjust;
    r -= y * adjust;

    return new QR64(q1, r);
  }

  // endregion

  // region 64-bit modulus (UInt256.UInt64)
  // --------------------------------------------------------------------------

  private static long modReduce64(
      final long m, final long t3, final long t2, final long t1, final long t0) {
    if ((t1 | t2 | t3) == 0) {
      return Long.remainderUnsigned(t0, m);
    }
    final int shift = Long.numberOfLeadingZeros(m);
    final long mn = m << shift;
    final long inv = reciprocal(mn);
    return modReduceNormalised64(mn, 0, t3, t2, t1, t0, shift, inv);
  }

  private static long sum64(
      final long m,
      final long a3,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    long z0 = a0 + b0;
    long carry = Long.compareUnsigned(z0, a0) < 0 ? 1 : 0;
    long z1 = a1 + b1 + carry;
    long overflowed = Long.compareUnsigned(z1, a1) < 0 ? 1 : 0;
    long overflowIfCarried = Long.compareUnsigned(z1, a1) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);
    long z2 = a2 + b2 + carry;
    overflowed = Long.compareUnsigned(z2, a2) < 0 ? 1 : 0;
    overflowIfCarried = Long.compareUnsigned(z2, a2) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);
    long z3 = a3 + b3 + carry;
    overflowed = Long.compareUnsigned(z3, a3) < 0 ? 1 : 0;
    overflowIfCarried = Long.compareUnsigned(z3, a3) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);
    if (carry == 0 && (z1 | z2 | z3) == 0) return Long.remainderUnsigned(z0, m);
    final int shift = Long.numberOfLeadingZeros(m);
    final long mn = m << shift;
    final long inv = reciprocal(mn);
    return modReduceNormalised64(mn, carry, z3, z2, z1, z0, shift, inv);
  }

  private static long mulMod64(
      final long m,
      final long a3,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    final boolean a64 = (a1 | a2 | a3) == 0;
    final boolean b64 = (b1 | b2 | b3) == 0;
    if (a64 && b64) {
      final long lo = a0 * b0;
      final long hi = Math.unsignedMultiplyHigh(a0, b0);
      if (hi == 0) return Long.remainderUnsigned(lo, m);
      return modReduce64(m, 0, 0, hi, lo);
    }
    final int shift = Long.numberOfLeadingZeros(m);
    final long mn = m << shift;
    final long inv = reciprocal(mn);
    final long x = a64 ? a0 : modReduceNormalised64(mn, 0, a3, a2, a1, a0, shift, inv);
    final long y = b64 ? b0 : modReduceNormalised64(mn, 0, b3, b2, b1, b0, shift, inv);
    final long lo = x * y;
    final long hi = Math.unsignedMultiplyHigh(x, y);
    return hi == 0
        ? Long.remainderUnsigned(lo, m)
        : modReduceNormalised64(mn, 0, 0, 0, hi, lo, shift, inv);
  }

  /** UInt64.modReduceNormalised of a 257-bit value: c is its top bit. */
  private static long modReduceNormalised64(
      final long mn,
      final long c,
      final long t3,
      final long t2,
      final long t1,
      final long t0,
      final int shift,
      final long inv) {
    final long v4, v3, v2, v1, v0;
    if (shift == 0) {
      v4 = c;
      v3 = t3;
      v2 = t2;
      v1 = t1;
      v0 = t0;
    } else {
      final int invShift = 64 - shift;
      v0 = t0 << shift;
      v1 = (t1 << shift) | t0 >>> invShift;
      v2 = (t2 << shift) | t1 >>> invShift;
      v3 = (t3 << shift) | t2 >>> invShift;
      v4 = (c << shift) | t3 >>> invShift;
    }
    if ((v4 | v3) == 0 && Long.compareUnsigned(v2, mn) < 0) {
      QR64 qr;
      if (v2 != 0 || Long.compareUnsigned(v1, mn) >= 0) {
        qr = div2by1(v2, v1, mn, inv);
        qr = div2by1(qr.r, v0, mn, inv);
      } else {
        qr = div2by1(v1, v0, mn, inv);
      }
      return qr.r >>> shift;
    }
    QR64 qr;
    if (Long.compareUnsigned(v4, mn) >= 0) {
      qr = div2by1(0, v4, mn, inv);
      qr = div2by1(qr.r, v3, mn, inv);
      qr = div2by1(qr.r, v2, mn, inv);
      qr = div2by1(qr.r, v1, mn, inv);
      qr = div2by1(qr.r, v0, mn, inv);
    } else if (v4 != 0 || Long.compareUnsigned(v3, mn) >= 0) {
      qr = div2by1(v4, v3, mn, inv);
      qr = div2by1(qr.r, v2, mn, inv);
      qr = div2by1(qr.r, v1, mn, inv);
      qr = div2by1(qr.r, v0, mn, inv);
    } else {
      qr = div2by1(v3, v2, mn, inv);
      qr = div2by1(qr.r, v1, mn, inv);
      qr = div2by1(qr.r, v0, mn, inv);
    }
    return qr.r >>> shift;
  }

  private static void divReduce64(
      final long[] s,
      final int out,
      final long m,
      final long t3,
      final long t2,
      final long t1,
      final long t0) {
    if ((t1 | t2 | t3) == 0) {
      put(s, out, 0, 0, 0, Long.divideUnsigned(t0, m));
      return;
    }
    final int shift = Long.numberOfLeadingZeros(m);
    final long mn = m << shift;
    final long inv = reciprocal(mn);
    final long c = 0;
    final long v4, v3, v2, v1, v0;
    if (shift == 0) {
      v4 = c;
      v3 = t3;
      v2 = t2;
      v1 = t1;
      v0 = t0;
    } else {
      final int invShift = 64 - shift;
      v0 = t0 << shift;
      v1 = (t1 << shift) | t0 >>> invShift;
      v2 = (t2 << shift) | t1 >>> invShift;
      v3 = (t3 << shift) | t2 >>> invShift;
      v4 = (c << shift) | t3 >>> invShift;
    }
    if ((v4 | v3) == 0 && Long.compareUnsigned(v2, mn) < 0) {
      if (v2 != 0 || Long.compareUnsigned(v1, mn) >= 0) {
        final QR64 qr1 = div2by1(v2, v1, mn, inv);
        final QR64 qr0 = div2by1(qr1.r, v0, mn, inv);
        put(s, out, 0, 0, qr1.q, qr0.q);
      } else {
        final QR64 qr0 = div2by1(v1, v0, mn, inv);
        put(s, out, 0, 0, 0, qr0.q);
      }
      return;
    }
    if (v4 == 0 && Long.compareUnsigned(v3, mn) < 0) {
      final QR64 qr2 = div2by1(v3, v2, mn, inv);
      final QR64 qr1 = div2by1(qr2.r, v1, mn, inv);
      final QR64 qr0 = div2by1(qr1.r, v0, mn, inv);
      put(s, out, 0, qr2.q, qr1.q, qr0.q);
      return;
    }
    final QR64 qr3 = div2by1(v4, v3, mn, inv);
    final QR64 qr2 = div2by1(qr3.r, v2, mn, inv);
    final QR64 qr1 = div2by1(qr2.r, v1, mn, inv);
    final QR64 qr0 = div2by1(qr1.r, v0, mn, inv);
    put(s, out, qr3.q, qr2.q, qr1.q, qr0.q);
  }

  // endregion

  // region 128-bit modulus (UInt256.UInt128)
  // --------------------------------------------------------------------------

  private static int compare128(
      final long m1, final long m0, final long v3, final long v2, final long v1, final long v0) {
    if ((v3 | v2) != 0) return -1;
    if (v1 != m1) return Long.compareUnsigned(m1, v1);
    return Long.compareUnsigned(m0, v0);
  }

  private static void modReduce128(
      final long[] s,
      final int out,
      final long m1,
      final long m0,
      final long t3,
      final long t2,
      final long t1,
      final long t0) {
    final int cmp = compare128(m1, m0, t3, t2, t1, t0);
    if (cmp == 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    if (cmp > 0) {
      put(s, out, t3, t2, t1, t0);
      return;
    }
    final int shift = Long.numberOfLeadingZeros(m1);
    final long n1, n0;
    if (shift == 0) {
      n1 = m1;
      n0 = m0;
    } else {
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n1);
    modReduceNormalised128(s, out, n1, n0, 0, t3, t2, t1, t0, shift, inv);
  }

  private static void sum128(
      final long[] s,
      final int out,
      final long m1,
      final long m0,
      final long a3,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    long z0 = a0 + b0;
    long carry = Long.compareUnsigned(z0, a0) < 0 ? 1 : 0;
    long z1 = a1 + b1 + carry;
    long overflowed = Long.compareUnsigned(z1, a1) < 0 ? 1 : 0;
    long overflowIfCarried = Long.compareUnsigned(z1, a1) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);
    long z2 = a2 + b2 + carry;
    overflowed = Long.compareUnsigned(z2, a2) < 0 ? 1 : 0;
    overflowIfCarried = Long.compareUnsigned(z2, a2) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);
    long z3 = a3 + b3 + carry;
    overflowed = Long.compareUnsigned(z3, a3) < 0 ? 1 : 0;
    overflowIfCarried = Long.compareUnsigned(z3, a3) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);
    final int cmp = carry == 0 ? compare128(m1, m0, z3, z2, z1, z0) : -1;
    if (cmp == 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    if (cmp > 0) {
      put(s, out, z3, z2, z1, z0);
      return;
    }
    final int shift = Long.numberOfLeadingZeros(m1);
    final long n1, n0;
    if (shift == 0) {
      n1 = m1;
      n0 = m0;
    } else {
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n1);
    modReduceNormalised128(s, out, n1, n0, carry, z3, z2, z1, z0, shift, inv);
  }

  private static void mulMod128(
      final long[] s,
      final int out,
      final long m1,
      final long m0,
      final long a3,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    if ((a2 | a3) == 0 && (b2 | b3) == 0) {
      long pr0 = 0, pr1 = 0, pr2 = 0;
      if (b0 == 0) {
        pr0 = pr1;
        pr1 = pr2;
        pr2 = 0;
      } else {
        long lo = a0 * b0;
        long hi = Math.unsignedMultiplyHigh(a0, b0);
        pr0 = lo + pr1;
        long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
        lo = a1 * b0;
        hi = Math.unsignedMultiplyHigh(a1, b0);
        long res1 = carry + pr2;
        pr1 = res1 + lo;
        carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
        carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
        pr2 = carry;
      }
      final long p0 = pr0;
      if (b1 == 0) {
        pr0 = pr1;
        pr1 = pr2;
        pr2 = 0;
      } else {
        long lo = a0 * b1;
        long hi = Math.unsignedMultiplyHigh(a0, b1);
        pr0 = lo + pr1;
        long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
        lo = a1 * b1;
        hi = Math.unsignedMultiplyHigh(a1, b1);
        long res1 = carry + pr2;
        pr1 = res1 + lo;
        carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
        carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
        pr2 = carry;
      }
      final long p1 = pr0;
      if (b2 == 0) {
        pr0 = pr1;
        pr1 = pr2;
        pr2 = 0;
      } else {
        long lo = a0 * b2;
        long hi = Math.unsignedMultiplyHigh(a0, b2);
        pr0 = lo + pr1;
        long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
        lo = a1 * b2;
        hi = Math.unsignedMultiplyHigh(a1, b2);
        long res1 = carry + pr2;
        pr1 = res1 + lo;
        carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
        carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
        pr2 = carry;
      }
      final long p2 = pr0;
      if (b3 == 0) {
        pr0 = pr1;
        pr1 = pr2;
        pr2 = 0;
      } else {
        long lo = a0 * b3;
        long hi = Math.unsignedMultiplyHigh(a0, b3);
        pr0 = lo + pr1;
        long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
        lo = a1 * b3;
        hi = Math.unsignedMultiplyHigh(a1, b3);
        long res1 = carry + pr2;
        pr1 = res1 + lo;
        carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
        carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
        pr2 = carry;
      }
      final long p3 = pr0;
      final long p4 = pr1;
      final long p5 = pr2;
      final int cmp = compare128(m1, m0, p3, p2, p1, p0);
      if (cmp == 0) {
        put(s, out, 0, 0, 0, 0);
      } else if (cmp > 0) {
        put(s, out, p3, p2, p1, p0);
      } else {
        modReduce128(s, out, m1, m0, p3, p2, p1, p0);
      }
      return;
    }
    long pr0 = 0, pr1 = 0, pr2 = 0, pr3 = 0, pr4 = 0;
    if (b0 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b0;
      long hi = Math.unsignedMultiplyHigh(a0, b0);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b0;
      hi = Math.unsignedMultiplyHigh(a1, b0);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b0;
      hi = Math.unsignedMultiplyHigh(a2, b0);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b0;
      hi = Math.unsignedMultiplyHigh(a3, b0);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p0 = pr0;
    if (b1 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b1;
      long hi = Math.unsignedMultiplyHigh(a0, b1);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b1;
      hi = Math.unsignedMultiplyHigh(a1, b1);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b1;
      hi = Math.unsignedMultiplyHigh(a2, b1);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b1;
      hi = Math.unsignedMultiplyHigh(a3, b1);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p1 = pr0;
    if (b2 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b2;
      long hi = Math.unsignedMultiplyHigh(a0, b2);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b2;
      hi = Math.unsignedMultiplyHigh(a1, b2);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b2;
      hi = Math.unsignedMultiplyHigh(a2, b2);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b2;
      hi = Math.unsignedMultiplyHigh(a3, b2);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p2 = pr0;
    if (b3 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b3;
      long hi = Math.unsignedMultiplyHigh(a0, b3);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b3;
      hi = Math.unsignedMultiplyHigh(a1, b3);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b3;
      hi = Math.unsignedMultiplyHigh(a2, b3);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b3;
      hi = Math.unsignedMultiplyHigh(a3, b3);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p3 = pr0;
    final long p4 = pr1;
    final long p5 = pr2;
    final long p6 = pr3;
    final long p7 = pr4;
    final int shift = Long.numberOfLeadingZeros(m1);
    final long n1, n0;
    if (shift == 0) {
      n1 = m1;
      n0 = m0;
    } else {
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n1);
    final long v8, v7, v6, v5, v4, v3, v2, v1, v0;
    if (shift == 0) {
      v8 = 0;
      v7 = p7;
      v6 = p6;
      v5 = p5;
      v4 = p4;
      v3 = p3;
      v2 = p2;
      v1 = p1;
      v0 = p0;
    } else {
      final int invShift = 64 - shift;
      v0 = p0 << shift;
      v1 = (p1 << shift) | p0 >>> invShift;
      v2 = (p2 << shift) | p1 >>> invShift;
      v3 = (p3 << shift) | p2 >>> invShift;
      v4 = (p4 << shift) | p3 >>> invShift;
      v5 = (p5 << shift) | p4 >>> invShift;
      v6 = (p6 << shift) | p5 >>> invShift;
      v7 = (p7 << shift) | p6 >>> invShift;
      v8 = p7 >>> invShift;
    }
    QR128 qr;
    if (v8 != 0 || Long.compareUnsigned(v7, n1) >= 0) {
      qr = reduceStep128(n1, n0, v8, v7, v6, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v5, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v4, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v3, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v2, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v1, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v0, inv);
    } else if (v7 != 0 || Long.compareUnsigned(v6, n1) >= 0) {
      qr = reduceStep128(n1, n0, v7, v6, v5, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v4, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v3, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v2, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v1, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v0, inv);
    } else if (v6 != 0 || Long.compareUnsigned(v5, n1) >= 0) {
      qr = reduceStep128(n1, n0, v6, v5, v4, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v3, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v2, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v1, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v0, inv);
    } else if (v5 != 0 || Long.compareUnsigned(v4, n1) >= 0) {
      qr = reduceStep128(n1, n0, v5, v4, v3, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v2, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v1, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v0, inv);
    } else {
      qr = reduceStep128(n1, n0, v4, v3, v2, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v1, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v0, inv);
    }
    sar(s, out, 0, 0, qr.r1, qr.r0, shift, 0);
  }

  private static QR128 addBack128(
      final long n1, final long n0, final long v1, final long v0, final long q) {
    long z0 = v0 + n0;
    long carry = (Long.compareUnsigned(z0, v0) < 0) ? 1 : 0;

    long z1 = v1 + n1 + carry;
    long overflow1 = (Long.compareUnsigned(z1, v1) < 0) ? 1 : 0;
    long overflow2 = (Long.compareUnsigned(z1, v1) == 0) ? 1 : 0;
    carry = overflow1 | (overflow2 & carry);

    if (carry == 0) {
      return addBack128(n1, n0, z1, z0, q - 1);
    }
    return new QR128(q, z1, z0);
  }

  private static QR128 mulSub128(
      final long n1, final long n0, final long x1, final long x0, final long q) {
    long p0 = n0 * q;
    long p1 = Math.unsignedMultiplyHigh(n0, q);
    long z0 = x0 - p0;
    long carry = p1 + ((Long.compareUnsigned(x0, z0) < 0) ? 1 : 0);

    long z1 = x1 - carry;
    long borrow = (Long.compareUnsigned(x1, z1) < 0) ? 1 : 0;

    if (borrow != 0) return addBack128(n1, n0, z1, z0, q - 1);
    return new QR128(q, z1, z0);
  }

  private static QR128 mulSubOverflow128(
      final long n1, final long n0, final long v1, final long v0) {
    long z0 = v0 + n0;
    long carry = n0 - 1 + ((Long.compareUnsigned(v0, z0) <= 0) ? 1 : 0);

    long z1 = v1 + n1 - carry;
    if (Long.compareUnsigned(z1, n1) > 0 || (z1 == n1 && Long.compareUnsigned(z0, n0) >= 0)) {
      return addBack128(n1, n0, z1, z0, -2L);
    }
    return new QR128(-1L, z1, z0);
  }

  private static QR128 reduceStep128(
      final long n1, final long n0, final long v2, final long v1, final long v0, final long inv) {
    if (v2 == n1) return mulSubOverflow128(n1, n0, v1, v0);
    final QR64 qr = div2by1(v2, v1, n1, inv);
    if (qr.q != 0) return mulSub128(n1, n0, qr.r, v0, qr.q);
    return new QR128(0, qr.r, v0);
  }

  /** UInt128.modReduceNormalised of a 257-bit value: c is its top bit. */
  private static void modReduceNormalised128(
      final long[] s,
      final int out,
      final long n1,
      final long n0,
      final long c,
      final long t3,
      final long t2,
      final long t1,
      final long t0,
      final int shift,
      final long inv) {
    final long v4, v3, v2, v1, v0;
    if (shift == 0) {
      v4 = c;
      v3 = t3;
      v2 = t2;
      v1 = t1;
      v0 = t0;
    } else {
      final int invShift = 64 - shift;
      v0 = t0 << shift;
      v1 = (t1 << shift) | t0 >>> invShift;
      v2 = (t2 << shift) | t1 >>> invShift;
      v3 = (t3 << shift) | t2 >>> invShift;
      v4 = (c << shift) | t3 >>> invShift;
    }
    QR128 qr;
    if (v4 == 0 && Long.compareUnsigned(v3, n1) < 0) {
      if (v3 != 0 || Long.compareUnsigned(v2, n1) >= 0) {
        qr = reduceStep128(n1, n0, v3, v2, v1, inv);
        qr = reduceStep128(n1, n0, qr.r1, qr.r0, v0, inv);
      } else {
        qr = reduceStep128(n1, n0, v2, v1, v0, inv);
      }
    } else if (Long.compareUnsigned(v4, n1) >= 0) {
      qr = reduceStep128(n1, n0, 0, v4, v3, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v2, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v1, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v0, inv);
    } else {
      qr = reduceStep128(n1, n0, v4, v3, v2, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v1, inv);
      qr = reduceStep128(n1, n0, qr.r1, qr.r0, v0, inv);
    }
    sar(s, out, 0, 0, qr.r1, qr.r0, shift, 0);
  }

  private static void divReduce128(
      final long[] s,
      final int out,
      final long m1,
      final long m0,
      final long t3,
      final long t2,
      final long t1,
      final long t0) {
    final int cmp = compare128(m1, m0, t3, t2, t1, t0);
    if (cmp == 0) {
      put(s, out, 0, 0, 0, 1);
      return;
    }
    if (cmp > 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    final int shift = Long.numberOfLeadingZeros(m1);
    final long n1, n0;
    if (shift == 0) {
      n1 = m1;
      n0 = m0;
    } else {
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n1);
    final long c = 0;
    final long v4, v3, v2, v1, v0;
    if (shift == 0) {
      v4 = c;
      v3 = t3;
      v2 = t2;
      v1 = t1;
      v0 = t0;
    } else {
      final int invShift = 64 - shift;
      v0 = t0 << shift;
      v1 = (t1 << shift) | t0 >>> invShift;
      v2 = (t2 << shift) | t1 >>> invShift;
      v3 = (t3 << shift) | t2 >>> invShift;
      v4 = (c << shift) | t3 >>> invShift;
    }
    if (v4 == 0 && Long.compareUnsigned(v3, n1) < 0) {
      if (v3 != 0 || Long.compareUnsigned(v2, n1) >= 0) {
        final QR128 qr1 = reduceStep128(n1, n0, v3, v2, v1, inv);
        final QR128 qr0 = reduceStep128(n1, n0, qr1.r1, qr1.r0, v0, inv);
        put(s, out, 0, 0, qr1.q, qr0.q);
      } else {
        final QR128 qr0 = reduceStep128(n1, n0, v2, v1, v0, inv);
        put(s, out, 0, 0, 0, qr0.q);
      }
      return;
    }
    final QR128 qr2 = reduceStep128(n1, n0, v4, v3, v2, inv);
    final QR128 qr1 = reduceStep128(n1, n0, qr2.r1, qr2.r0, v1, inv);
    final QR128 qr0 = reduceStep128(n1, n0, qr1.r1, qr1.r0, v0, inv);
    put(s, out, 0, qr2.q, qr1.q, qr0.q);
  }

  // endregion

  // region 192-bit modulus (UInt256.UInt192)
  // --------------------------------------------------------------------------

  private static int compare192(
      final long m2,
      final long m1,
      final long m0,
      final long v3,
      final long v2,
      final long v1,
      final long v0) {
    if (v3 != 0) return -1;
    if (v2 != m2) return Long.compareUnsigned(m2, v2);
    if (v1 != m1) return Long.compareUnsigned(m1, v1);
    return Long.compareUnsigned(m0, v0);
  }

  private static void modReduce192(
      final long[] s,
      final int out,
      final long m2,
      final long m1,
      final long m0,
      final long t3,
      final long t2,
      final long t1,
      final long t0) {
    final int cmp = compare192(m2, m1, m0, t3, t2, t1, t0);
    if (cmp == 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    if (cmp > 0) {
      put(s, out, t3, t2, t1, t0);
      return;
    }
    final int shift = Long.numberOfLeadingZeros(m2);
    final long n2, n1, n0;
    if (shift == 0) {
      n2 = m2;
      n1 = m1;
      n0 = m0;
    } else {
      n2 = (m2 << shift) | (m1 >>> (64 - shift));
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n2);
    modReduceNormalised192(s, out, n2, n1, n0, 0, t3, t2, t1, t0, shift, inv);
  }

  private static void sum192(
      final long[] s,
      final int out,
      final long m2,
      final long m1,
      final long m0,
      final long a3,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    long z0 = a0 + b0;
    long carry = Long.compareUnsigned(z0, a0) < 0 ? 1 : 0;
    long z1 = a1 + b1 + carry;
    long overflowed = Long.compareUnsigned(z1, a1) < 0 ? 1 : 0;
    long overflowIfCarried = Long.compareUnsigned(z1, a1) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);
    long z2 = a2 + b2 + carry;
    overflowed = Long.compareUnsigned(z2, a2) < 0 ? 1 : 0;
    overflowIfCarried = Long.compareUnsigned(z2, a2) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);
    long z3 = a3 + b3 + carry;
    overflowed = Long.compareUnsigned(z3, a3) < 0 ? 1 : 0;
    overflowIfCarried = Long.compareUnsigned(z3, a3) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);
    if (carry == 0) {
      final int cmp = compare192(m2, m1, m0, z3, z2, z1, z0);
      if (cmp == 0) {
        put(s, out, 0, 0, 0, 0);
        return;
      }
      if (cmp > 0) {
        put(s, out, z3, z2, z1, z0);
        return;
      }
    }
    final int shift = Long.numberOfLeadingZeros(m2);
    final long n2, n1, n0;
    if (shift == 0) {
      n2 = m2;
      n1 = m1;
      n0 = m0;
    } else {
      n2 = (m2 << shift) | (m1 >>> (64 - shift));
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n2);
    modReduceNormalised192(s, out, n2, n1, n0, carry, z3, z2, z1, z0, shift, inv);
  }

  private static void mulMod192(
      final long[] s,
      final int out,
      final long m2,
      final long m1,
      final long m0,
      final long a3,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    if (a3 == 0 && b3 == 0) {
      long pr0 = 0, pr1 = 0, pr2 = 0, pr3 = 0;
      if (b0 == 0) {
        pr0 = pr1;
        pr1 = pr2;
        pr2 = pr3;
        pr3 = 0;
      } else {
        long lo = a0 * b0;
        long hi = Math.unsignedMultiplyHigh(a0, b0);
        pr0 = lo + pr1;
        long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
        lo = a1 * b0;
        hi = Math.unsignedMultiplyHigh(a1, b0);
        long res1 = carry + pr2;
        pr1 = res1 + lo;
        carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
        carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
        lo = a2 * b0;
        hi = Math.unsignedMultiplyHigh(a2, b0);
        long res2 = carry + pr3;
        pr2 = res2 + lo;
        carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
        carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
        pr3 = carry;
      }
      final long p0 = pr0;
      if (b1 == 0) {
        pr0 = pr1;
        pr1 = pr2;
        pr2 = pr3;
        pr3 = 0;
      } else {
        long lo = a0 * b1;
        long hi = Math.unsignedMultiplyHigh(a0, b1);
        pr0 = lo + pr1;
        long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
        lo = a1 * b1;
        hi = Math.unsignedMultiplyHigh(a1, b1);
        long res1 = carry + pr2;
        pr1 = res1 + lo;
        carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
        carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
        lo = a2 * b1;
        hi = Math.unsignedMultiplyHigh(a2, b1);
        long res2 = carry + pr3;
        pr2 = res2 + lo;
        carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
        carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
        pr3 = carry;
      }
      final long p1 = pr0;
      if (b2 == 0) {
        pr0 = pr1;
        pr1 = pr2;
        pr2 = pr3;
        pr3 = 0;
      } else {
        long lo = a0 * b2;
        long hi = Math.unsignedMultiplyHigh(a0, b2);
        pr0 = lo + pr1;
        long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
        lo = a1 * b2;
        hi = Math.unsignedMultiplyHigh(a1, b2);
        long res1 = carry + pr2;
        pr1 = res1 + lo;
        carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
        carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
        lo = a2 * b2;
        hi = Math.unsignedMultiplyHigh(a2, b2);
        long res2 = carry + pr3;
        pr2 = res2 + lo;
        carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
        carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
        pr3 = carry;
      }
      final long p2 = pr0;
      if (b3 == 0) {
        pr0 = pr1;
        pr1 = pr2;
        pr2 = pr3;
        pr3 = 0;
      } else {
        long lo = a0 * b3;
        long hi = Math.unsignedMultiplyHigh(a0, b3);
        pr0 = lo + pr1;
        long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
        lo = a1 * b3;
        hi = Math.unsignedMultiplyHigh(a1, b3);
        long res1 = carry + pr2;
        pr1 = res1 + lo;
        carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
        carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
        lo = a2 * b3;
        hi = Math.unsignedMultiplyHigh(a2, b3);
        long res2 = carry + pr3;
        pr2 = res2 + lo;
        carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
        carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
        pr3 = carry;
      }
      final long p3 = pr0;
      final long p4 = pr1;
      final long p5 = pr2;
      final long p6 = pr3;
      // UInt192.compareTo(UInt448)
      int cmp;
      if ((p6 | p5 | p4 | p3) != 0) {
        cmp = -1;
      } else if (p2 != m2) {
        cmp = Long.compareUnsigned(m2, p2);
      } else if (p1 != m1) {
        cmp = Long.compareUnsigned(m1, p1);
      } else {
        cmp = Long.compareUnsigned(m0, p0);
      }
      if (cmp == 0) {
        put(s, out, 0, 0, 0, 0);
      } else if (cmp > 0) {
        put(s, out, p3, p2, p1, p0);
      } else {
        modReduce192Of448(s, out, m2, m1, m0, p6, p5, p4, p3, p2, p1, p0);
      }
      return;
    }
    long pr0 = 0, pr1 = 0, pr2 = 0, pr3 = 0, pr4 = 0;
    if (b0 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b0;
      long hi = Math.unsignedMultiplyHigh(a0, b0);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b0;
      hi = Math.unsignedMultiplyHigh(a1, b0);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b0;
      hi = Math.unsignedMultiplyHigh(a2, b0);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b0;
      hi = Math.unsignedMultiplyHigh(a3, b0);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p0 = pr0;
    if (b1 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b1;
      long hi = Math.unsignedMultiplyHigh(a0, b1);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b1;
      hi = Math.unsignedMultiplyHigh(a1, b1);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b1;
      hi = Math.unsignedMultiplyHigh(a2, b1);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b1;
      hi = Math.unsignedMultiplyHigh(a3, b1);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p1 = pr0;
    if (b2 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b2;
      long hi = Math.unsignedMultiplyHigh(a0, b2);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b2;
      hi = Math.unsignedMultiplyHigh(a1, b2);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b2;
      hi = Math.unsignedMultiplyHigh(a2, b2);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b2;
      hi = Math.unsignedMultiplyHigh(a3, b2);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p2 = pr0;
    if (b3 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b3;
      long hi = Math.unsignedMultiplyHigh(a0, b3);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b3;
      hi = Math.unsignedMultiplyHigh(a1, b3);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b3;
      hi = Math.unsignedMultiplyHigh(a2, b3);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b3;
      hi = Math.unsignedMultiplyHigh(a3, b3);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p3 = pr0;
    final long p4 = pr1;
    final long p5 = pr2;
    final long p6 = pr3;
    final long p7 = pr4;
    final int shift = Long.numberOfLeadingZeros(m2);
    final long n2, n1, n0;
    if (shift == 0) {
      n2 = m2;
      n1 = m1;
      n0 = m0;
    } else {
      n2 = (m2 << shift) | (m1 >>> (64 - shift));
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n2);
    final long v8, v7, v6, v5, v4, v3, v2, v1, v0;
    if (shift == 0) {
      v8 = 0;
      v7 = p7;
      v6 = p6;
      v5 = p5;
      v4 = p4;
      v3 = p3;
      v2 = p2;
      v1 = p1;
      v0 = p0;
    } else {
      final int invShift = 64 - shift;
      v0 = p0 << shift;
      v1 = (p1 << shift) | p0 >>> invShift;
      v2 = (p2 << shift) | p1 >>> invShift;
      v3 = (p3 << shift) | p2 >>> invShift;
      v4 = (p4 << shift) | p3 >>> invShift;
      v5 = (p5 << shift) | p4 >>> invShift;
      v6 = (p6 << shift) | p5 >>> invShift;
      v7 = (p7 << shift) | p6 >>> invShift;
      v8 = p7 >>> invShift;
    }
    QR192 qr;
    if (v8 != 0 || Long.compareUnsigned(v7, n2) >= 0) {
      qr = reduceStep192(n2, n1, n0, v8, v7, v6, v5, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v4, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v3, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v2, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v1, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v0, inv);
    } else if (v7 != 0 || Long.compareUnsigned(v6, n2) >= 0) {
      qr = reduceStep192(n2, n1, n0, v7, v6, v5, v4, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v3, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v2, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v1, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v0, inv);
    } else if (v6 != 0 || Long.compareUnsigned(v5, n2) >= 0) {
      qr = reduceStep192(n2, n1, n0, v6, v5, v4, v3, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v2, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v1, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v0, inv);
    } else {
      qr = reduceStep192(n2, n1, n0, v5, v4, v3, v2, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v1, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v0, inv);
    }
    sar(s, out, 0, qr.r2, qr.r1, qr.r0, shift, 0);
  }

  /** UInt192.modReduce(UInt448), with the comparison already made. */
  private static void modReduce192Of448(
      final long[] s,
      final int out,
      final long m2,
      final long m1,
      final long m0,
      final long u6,
      final long u5,
      final long u4,
      final long u3,
      final long u2,
      final long u1,
      final long u0) {
    // UInt192.compareTo(UInt448), as modReduce makes it again
    int cmp;
    if ((u6 | u5 | u4 | u3) != 0) {
      cmp = -1;
    } else if (u2 != m2) {
      cmp = Long.compareUnsigned(m2, u2);
    } else if (u1 != m1) {
      cmp = Long.compareUnsigned(m1, u1);
    } else {
      cmp = Long.compareUnsigned(m0, u0);
    }
    if (cmp == 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    if (cmp > 0) {
      put(s, out, u3, u2, u1, u0);
      return;
    }
    final int shift = Long.numberOfLeadingZeros(m2);
    final long n2, n1, n0;
    if (shift == 0) {
      n2 = m2;
      n1 = m1;
      n0 = m0;
    } else {
      n2 = (m2 << shift) | (m1 >>> (64 - shift));
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n2);
    // UInt448.shiftLeftWide
    final long v7, v6, v5, v4, v3, v2, v1, v0;
    if (shift == 0) {
      v7 = 0;
      v6 = u6;
      v5 = u5;
      v4 = u4;
      v3 = u3;
      v2 = u2;
      v1 = u1;
      v0 = u0;
    } else {
      final int invShift = 64 - shift;
      v0 = u0 << shift;
      v1 = (u1 << shift) | u0 >>> invShift;
      v2 = (u2 << shift) | u1 >>> invShift;
      v3 = (u3 << shift) | u2 >>> invShift;
      v4 = (u4 << shift) | u3 >>> invShift;
      v5 = (u5 << shift) | u4 >>> invShift;
      v6 = (u6 << shift) | u5 >>> invShift;
      v7 = u6 >>> invShift;
    }
    QR192 qr;
    if ((v7 | v6 | v5) == 0 && Long.compareUnsigned(v4, n2) < 0) {
      if (v4 != 0 || Long.compareUnsigned(v3, n2) >= 0) {
        qr = reduceStep192(n2, n1, n0, v4, v3, v2, v1, inv);
        qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v0, inv);
      } else {
        qr = reduceStep192(n2, n1, n0, v3, v2, v1, v0, inv);
      }
    } else if (v7 != 0 || Long.compareUnsigned(v6, n2) >= 0) {
      qr = reduceStep192(n2, n1, n0, v7, v6, v5, v4, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v3, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v2, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v1, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v0, inv);
    } else if (v6 != 0 || Long.compareUnsigned(v5, n2) >= 0) {
      qr = reduceStep192(n2, n1, n0, v6, v5, v4, v3, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v2, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v1, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v0, inv);
    } else {
      qr = reduceStep192(n2, n1, n0, v5, v4, v3, v2, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v1, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v0, inv);
    }
    sar(s, out, 0, qr.r2, qr.r1, qr.r0, shift, 0);
  }

  private static QR192 addBack192(
      final long n2,
      final long n1,
      final long n0,
      final long v2,
      final long v1,
      final long v0,
      final long q) {
    long z0 = v0 + n0;
    long carry = ((v0 & n0) | ((v0 | n0) & ~z0)) >>> 63;

    long z1 = v1 + n1 + carry;
    long overflow1 = (Long.compareUnsigned(z1, v1) < 0) ? 1 : 0;
    long overflow2 = (Long.compareUnsigned(z1, v1) == 0) ? 1 : 0;
    carry = overflow1 | (overflow2 & carry);

    long z2 = v2 + n2 + carry;
    overflow1 = (Long.compareUnsigned(z2, v2) < 0) ? 1 : 0;
    overflow2 = (Long.compareUnsigned(z2, v2) == 0) ? 1 : 0;
    carry = overflow1 | (overflow2 & carry);

    if (carry == 0) {
      return addBack192(n2, n1, n0, z2, z1, z0, q - 1);
    }
    return new QR192(q, z2, z1, z0);
  }

  private static QR192 mulSub192(
      final long n2,
      final long n1,
      final long n0,
      final long v2,
      final long v1,
      final long v0,
      final long q) {
    long p0 = n0 * q;
    long p1 = Math.unsignedMultiplyHigh(n0, q);
    long z0 = v0 - p0;
    long carry = p1 + (((Long.compareUnsigned(v0, z0) < 0) ? 1 : 0));

    p0 = n1 * q;
    p1 = Math.unsignedMultiplyHigh(n1, q);
    long res = v1 - p0;
    long z1 = res - carry;
    long borrow = (Long.compareUnsigned(res, z1) < 0) ? 1 : 0;
    carry = p1 + ((Long.compareUnsigned(v1, res) < 0) ? 1 : 0);

    long t2 = v2 - carry;
    long z2 = t2 - borrow;
    borrow =
        ((Long.compareUnsigned(v2, t2) < 0) ? 1 : 0) | ((Long.compareUnsigned(t2, z2) < 0) ? 1 : 0);

    if (borrow != 0) return addBack192(n2, n1, n0, z2, z1, z0, q - 1);
    return new QR192(q, z2, z1, z0);
  }

  private static QR192 mulSubOverflow192(
      final long n2, final long n1, final long n0, final long v2, final long v1, final long v0) {
    long z0 = v0 + n0;
    long carry = n0 - 1 + ((Long.compareUnsigned(v0, z0) <= 0) ? 1 : 0);

    long res = v1 - carry;
    long z1 = res + n1;
    long borrow = (Long.compareUnsigned(res, z1) <= 0) ? 1 : 0;
    carry = n1 - 1 + ((Long.compareUnsigned(v1, res) < 0) ? 1 : 0);

    long z2 = v2 - carry + n2 - borrow;
    if (Long.compareUnsigned(z2, n2) > 0
        || (z2 == n2
            && (Long.compareUnsigned(z1, n1) > 0
                || (z1 == n1 && Long.compareUnsigned(z0, n0) >= 0)))) {
      return addBack192(n2, n1, n0, z2, z1, z0, -2L);
    }
    return new QR192(-1L, z2, z1, z0);
  }

  private static QR192 reduceStep192(
      final long n2,
      final long n1,
      final long n0,
      final long v3,
      final long v2,
      final long v1,
      final long v0,
      final long inv) {
    if (v3 == n2) return mulSubOverflow192(n2, n1, n0, v2, v1, v0);
    final QR64 qr = div2by1(v3, v2, n2, inv);
    if (qr.q != 0) return mulSub192(n2, n1, n0, qr.r, v1, v0, qr.q);
    return new QR192(0, v2, v1, v0);
  }

  /** UInt192.modReduceNormalised of a 257-bit value: c is its top bit. */
  private static void modReduceNormalised192(
      final long[] s,
      final int out,
      final long n2,
      final long n1,
      final long n0,
      final long c,
      final long t3,
      final long t2,
      final long t1,
      final long t0,
      final int shift,
      final long inv) {
    final long v4, v3, v2, v1, v0;
    if (shift == 0) {
      v4 = c;
      v3 = t3;
      v2 = t2;
      v1 = t1;
      v0 = t0;
    } else {
      final int invShift = 64 - shift;
      v0 = t0 << shift;
      v1 = (t1 << shift) | t0 >>> invShift;
      v2 = (t2 << shift) | t1 >>> invShift;
      v3 = (t3 << shift) | t2 >>> invShift;
      v4 = (c << shift) | t3 >>> invShift;
    }
    QR192 qr;
    if (Long.compareUnsigned(v4, n2) < 0) {
      if (v4 != 0 || Long.compareUnsigned(v3, n2) >= 0) {
        qr = reduceStep192(n2, n1, n0, v4, v3, v2, v1, inv);
        qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v0, inv);
      } else {
        qr = reduceStep192(n2, n1, n0, v3, v2, v1, v0, inv);
      }
    } else {
      qr = reduceStep192(n2, n1, n0, 0, v4, v3, v2, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v1, inv);
      qr = reduceStep192(n2, n1, n0, qr.r2, qr.r1, qr.r0, v0, inv);
    }
    sar(s, out, 0, qr.r2, qr.r1, qr.r0, shift, 0);
  }

  private static void divReduce192(
      final long[] s,
      final int out,
      final long m2,
      final long m1,
      final long m0,
      final long t3,
      final long t2,
      final long t1,
      final long t0) {
    final int cmp = compare192(m2, m1, m0, t3, t2, t1, t0);
    if (cmp == 0) {
      put(s, out, 0, 0, 0, 1);
      return;
    }
    if (cmp > 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    final int shift = Long.numberOfLeadingZeros(m2);
    final long n2, n1, n0;
    if (shift == 0) {
      n2 = m2;
      n1 = m1;
      n0 = m0;
    } else {
      n2 = (m2 << shift) | (m1 >>> (64 - shift));
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n2);
    final long c = 0;
    final long v4, v3, v2, v1, v0;
    if (shift == 0) {
      v4 = c;
      v3 = t3;
      v2 = t2;
      v1 = t1;
      v0 = t0;
    } else {
      final int invShift = 64 - shift;
      v0 = t0 << shift;
      v1 = (t1 << shift) | t0 >>> invShift;
      v2 = (t2 << shift) | t1 >>> invShift;
      v3 = (t3 << shift) | t2 >>> invShift;
      v4 = (c << shift) | t3 >>> invShift;
    }
    if (v4 == 0 && Long.compareUnsigned(v3, n2) < 0) {
      final QR192 qr0 = reduceStep192(n2, n1, n0, v3, v2, v1, v0, inv);
      put(s, out, 0, 0, 0, qr0.q);
      return;
    }
    final QR192 qr1 = reduceStep192(n2, n1, n0, v4, v3, v2, v1, inv);
    final QR192 qr0 = reduceStep192(n2, n1, n0, qr1.r2, qr1.r1, qr1.r0, v0, inv);
    put(s, out, 0, 0, qr1.q, qr0.q);
  }

  // endregion

  // region 256-bit modulus (UInt256)
  // --------------------------------------------------------------------------

  private static int compare256(
      final long m3,
      final long m2,
      final long m1,
      final long m0,
      final long v3,
      final long v2,
      final long v1,
      final long v0) {
    if (m3 != v3) return Long.compareUnsigned(m3, v3);
    if (m2 != v2) return Long.compareUnsigned(m2, v2);
    if (m1 != v1) return Long.compareUnsigned(m1, v1);
    return Long.compareUnsigned(m0, v0);
  }

  /** UInt256.compareTo(UInt512). */
  private static int compare256Of512(
      final long m3,
      final long m2,
      final long m1,
      final long m0,
      final long u7,
      final long u6,
      final long u5,
      final long u4,
      final long u3,
      final long u2,
      final long u1,
      final long u0) {
    if ((u7 | u6 | u5 | u4) != 0) return -1;
    return compare256(m3, m2, m1, m0, u3, u2, u1, u0);
  }

  private static void modReduce256(
      final long[] s,
      final int out,
      final long m3,
      final long m2,
      final long m1,
      final long m0,
      final long t3,
      final long t2,
      final long t1,
      final long t0) {
    final int cmp = compare256(m3, m2, m1, m0, t3, t2, t1, t0);
    if (cmp == 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    if (cmp > 0) {
      put(s, out, t3, t2, t1, t0);
      return;
    }
    final int shift = Long.numberOfLeadingZeros(m3);
    final long n3, n2, n1, n0;
    if (shift == 0) {
      n3 = m3;
      n2 = m2;
      n1 = m1;
      n0 = m0;
    } else {
      n3 = (m3 << shift) | (m2 >>> (64 - shift));
      n2 = (m2 << shift) | (m1 >>> (64 - shift));
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n3);
    modReduceNormalised256(s, out, n3, n2, n1, n0, 0, t3, t2, t1, t0, shift, inv);
  }

  private static void sum256(
      final long[] s,
      final int out,
      final long m3,
      final long m2,
      final long m1,
      final long m0,
      final long a3,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    long z0 = a0 + b0;
    long carry = Long.compareUnsigned(z0, a0) < 0 ? 1 : 0;
    long z1 = a1 + b1 + carry;
    long overflowed = Long.compareUnsigned(z1, a1) < 0 ? 1 : 0;
    long overflowIfCarried = Long.compareUnsigned(z1, a1) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);
    long z2 = a2 + b2 + carry;
    overflowed = Long.compareUnsigned(z2, a2) < 0 ? 1 : 0;
    overflowIfCarried = Long.compareUnsigned(z2, a2) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);
    long z3 = a3 + b3 + carry;
    overflowed = Long.compareUnsigned(z3, a3) < 0 ? 1 : 0;
    overflowIfCarried = Long.compareUnsigned(z3, a3) == 0 ? 1 : 0;
    carry = overflowed | (overflowIfCarried & carry);
    if (carry == 0) {
      final int cmp = compare256(m3, m2, m1, m0, z3, z2, z1, z0);
      if (cmp == 0) {
        put(s, out, 0, 0, 0, 0);
        return;
      }
      if (cmp > 0) {
        put(s, out, z3, z2, z1, z0);
        return;
      }
    }
    final int shift = Long.numberOfLeadingZeros(m3);
    final long n3, n2, n1, n0;
    if (shift == 0) {
      n3 = m3;
      n2 = m2;
      n1 = m1;
      n0 = m0;
    } else {
      n3 = (m3 << shift) | (m2 >>> (64 - shift));
      n2 = (m2 << shift) | (m1 >>> (64 - shift));
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n3);
    modReduceNormalised256(s, out, n3, n2, n1, n0, carry, z3, z2, z1, z0, shift, inv);
  }

  private static void mulMod256(
      final long[] s,
      final int out,
      final long m3,
      final long m2,
      final long m1,
      final long m0,
      final long a3,
      final long a2,
      final long a1,
      final long a0,
      final long b3,
      final long b2,
      final long b1,
      final long b0) {
    long pr0 = 0, pr1 = 0, pr2 = 0, pr3 = 0, pr4 = 0;
    if (b0 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b0;
      long hi = Math.unsignedMultiplyHigh(a0, b0);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b0;
      hi = Math.unsignedMultiplyHigh(a1, b0);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b0;
      hi = Math.unsignedMultiplyHigh(a2, b0);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b0;
      hi = Math.unsignedMultiplyHigh(a3, b0);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p0 = pr0;
    if (b1 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b1;
      long hi = Math.unsignedMultiplyHigh(a0, b1);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b1;
      hi = Math.unsignedMultiplyHigh(a1, b1);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b1;
      hi = Math.unsignedMultiplyHigh(a2, b1);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b1;
      hi = Math.unsignedMultiplyHigh(a3, b1);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p1 = pr0;
    if (b2 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b2;
      long hi = Math.unsignedMultiplyHigh(a0, b2);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b2;
      hi = Math.unsignedMultiplyHigh(a1, b2);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b2;
      hi = Math.unsignedMultiplyHigh(a2, b2);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b2;
      hi = Math.unsignedMultiplyHigh(a3, b2);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p2 = pr0;
    if (b3 == 0) {
      pr0 = pr1;
      pr1 = pr2;
      pr2 = pr3;
      pr3 = pr4;
      pr4 = 0;
    } else {
      long lo = a0 * b3;
      long hi = Math.unsignedMultiplyHigh(a0, b3);
      pr0 = lo + pr1;
      long carry = hi + ((Long.compareUnsigned(pr0, lo) < 0) ? 1 : 0);
      lo = a1 * b3;
      hi = Math.unsignedMultiplyHigh(a1, b3);
      long res1 = carry + pr2;
      pr1 = res1 + lo;
      carry = (Long.compareUnsigned(res1, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr1, res1) < 0) ? 1 : 0);
      lo = a2 * b3;
      hi = Math.unsignedMultiplyHigh(a2, b3);
      long res2 = carry + pr3;
      pr2 = res2 + lo;
      carry = (Long.compareUnsigned(res2, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr2, res2) < 0) ? 1 : 0);
      lo = a3 * b3;
      hi = Math.unsignedMultiplyHigh(a3, b3);
      long res3 = carry + pr4;
      pr3 = res3 + lo;
      carry = (Long.compareUnsigned(res3, carry) < 0) ? 1 : 0;
      carry += hi + ((Long.compareUnsigned(pr3, res3) < 0) ? 1 : 0);
      pr4 = carry;
    }
    final long p3 = pr0;
    final long p4 = pr1;
    final long p5 = pr2;
    final long p6 = pr3;
    final long p7 = pr4;
    // UInt256.mul(a, b) compares the product with the modulus, and modReduce(UInt512) again
    int cmp = compare256Of512(m3, m2, m1, m0, p7, p6, p5, p4, p3, p2, p1, p0);
    if (cmp == 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    if (cmp > 0) {
      put(s, out, p3, p2, p1, p0);
      return;
    }
    cmp = compare256Of512(m3, m2, m1, m0, p7, p6, p5, p4, p3, p2, p1, p0);
    if (cmp == 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    if (cmp > 0) {
      put(s, out, p3, p2, p1, p0);
      return;
    }
    final int shift = Long.numberOfLeadingZeros(m3);
    final long n3, n2, n1, n0;
    if (shift == 0) {
      n3 = m3;
      n2 = m2;
      n1 = m1;
      n0 = m0;
    } else {
      n3 = (m3 << shift) | (m2 >>> (64 - shift));
      n2 = (m2 << shift) | (m1 >>> (64 - shift));
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n3);
    final long v8, v7, v6, v5, v4, v3, v2, v1, v0;
    if (shift == 0) {
      v8 = 0;
      v7 = p7;
      v6 = p6;
      v5 = p5;
      v4 = p4;
      v3 = p3;
      v2 = p2;
      v1 = p1;
      v0 = p0;
    } else {
      final int invShift = 64 - shift;
      v0 = p0 << shift;
      v1 = (p1 << shift) | p0 >>> invShift;
      v2 = (p2 << shift) | p1 >>> invShift;
      v3 = (p3 << shift) | p2 >>> invShift;
      v4 = (p4 << shift) | p3 >>> invShift;
      v5 = (p5 << shift) | p4 >>> invShift;
      v6 = (p6 << shift) | p5 >>> invShift;
      v7 = (p7 << shift) | p6 >>> invShift;
      v8 = p7 >>> invShift;
    }
    QR256 qr;
    if ((v8 | v7 | v6) == 0 && Long.compareUnsigned(v5, n3) < 0) {
      if (v5 != 0 || Long.compareUnsigned(v4, n3) >= 0) {
        qr = reduceStep256(n3, n2, n1, n0, v5, v4, v3, v2, v1, inv);
        qr = reduceStep256(n3, n2, n1, n0, qr.r3, qr.r2, qr.r1, qr.r0, v0, inv);
      } else {
        qr = reduceStep256(n3, n2, n1, n0, v4, v3, v2, v1, v0, inv);
      }
    } else if (v8 != 0 || Long.compareUnsigned(v7, n3) >= 0) {
      qr = reduceStep256(n3, n2, n1, n0, v8, v7, v6, v5, v4, inv);
      qr = reduceStep256(n3, n2, n1, n0, qr.r3, qr.r2, qr.r1, qr.r0, v3, inv);
      qr = reduceStep256(n3, n2, n1, n0, qr.r3, qr.r2, qr.r1, qr.r0, v2, inv);
      qr = reduceStep256(n3, n2, n1, n0, qr.r3, qr.r2, qr.r1, qr.r0, v1, inv);
      qr = reduceStep256(n3, n2, n1, n0, qr.r3, qr.r2, qr.r1, qr.r0, v0, inv);
    } else if (v7 != 0 || Long.compareUnsigned(v6, n3) >= 0) {
      qr = reduceStep256(n3, n2, n1, n0, v7, v6, v5, v4, v3, inv);
      qr = reduceStep256(n3, n2, n1, n0, qr.r3, qr.r2, qr.r1, qr.r0, v2, inv);
      qr = reduceStep256(n3, n2, n1, n0, qr.r3, qr.r2, qr.r1, qr.r0, v1, inv);
      qr = reduceStep256(n3, n2, n1, n0, qr.r3, qr.r2, qr.r1, qr.r0, v0, inv);
    } else {
      qr = reduceStep256(n3, n2, n1, n0, v6, v5, v4, v3, v2, inv);
      qr = reduceStep256(n3, n2, n1, n0, qr.r3, qr.r2, qr.r1, qr.r0, v1, inv);
      qr = reduceStep256(n3, n2, n1, n0, qr.r3, qr.r2, qr.r1, qr.r0, v0, inv);
    }
    sar(s, out, qr.r3, qr.r2, qr.r1, qr.r0, shift, 0);
  }

  private static QR256 addBack256(
      final long n3,
      final long n2,
      final long n1,
      final long n0,
      final long v3,
      final long v2,
      final long v1,
      final long v0,
      final long q) {
    long z0 = v0 + n0;
    long carry = ((v0 & n0) | ((v0 | n0) & ~z0)) >>> 63;

    long z1 = v1 + n1 + carry;
    long overflow1 = (Long.compareUnsigned(z1, v1) < 0) ? 1 : 0;
    long overflow2 = (Long.compareUnsigned(z1, v1) == 0) ? 1 : 0;
    carry = overflow1 | (overflow2 & carry);

    long z2 = v2 + n2 + carry;
    overflow1 = (Long.compareUnsigned(z2, v2) < 0) ? 1 : 0;
    overflow2 = (Long.compareUnsigned(z2, v2) == 0) ? 1 : 0;
    carry = overflow1 | (overflow2 & carry);

    long z3 = v3 + n3 + carry;
    overflow1 = (Long.compareUnsigned(z3, v3) < 0) ? 1 : 0;
    overflow2 = (Long.compareUnsigned(z3, v3) == 0) ? 1 : 0;
    carry = overflow1 | (overflow2 & carry);

    if (carry == 0) {
      return addBack256(n3, n2, n1, n0, z3, z2, z1, z0, q - 1);
    }
    return new QR256(q, z3, z2, z1, z0);
  }

  private static QR256 mulSub256(
      final long n3,
      final long n2,
      final long n1,
      final long n0,
      final long v3,
      final long v2,
      final long v1,
      final long v0,
      final long q) {
    long p0 = n0 * q;
    long p1 = Math.unsignedMultiplyHigh(n0, q);
    long z0 = v0 - p0;
    long carry = p1 + (((Long.compareUnsigned(v0, z0) < 0) ? 1 : 0));

    p0 = n1 * q;
    p1 = Math.unsignedMultiplyHigh(n1, q);
    long res = v1 - p0;
    long z1 = res - carry;
    long borrow = (Long.compareUnsigned(res, z1) < 0) ? 1 : 0;
    carry = p1 + ((Long.compareUnsigned(v1, res) < 0) ? 1 : 0);

    p0 = n2 * q;
    p1 = Math.unsignedMultiplyHigh(n2, q);
    long t2 = v2 - p0;
    res = t2 - borrow;
    long z2 = res - carry;
    borrow = (Long.compareUnsigned(res, z2) < 0) ? 1 : 0;
    carry =
        p1
            + ((Long.compareUnsigned(v2, t2) < 0) ? 1 : 0)
            + ((Long.compareUnsigned(t2, res) < 0) ? 1 : 0);

    long t3 = v3 - carry;
    long z3 = t3 - borrow;
    borrow =
        ((Long.compareUnsigned(v3, t3) < 0) ? 1 : 0) | ((Long.compareUnsigned(t3, z3) < 0) ? 1 : 0);

    if (borrow != 0) return addBack256(n3, n2, n1, n0, z3, z2, z1, z0, q - 1);
    return new QR256(q, z3, z2, z1, z0);
  }

  private static QR256 mulSubOverflow256(
      final long n3,
      final long n2,
      final long n1,
      final long n0,
      final long v3,
      final long v2,
      final long v1,
      final long v0) {
    long res, borrow;

    long z0 = v0 + n0;
    long carry = n0 - 1 + ((Long.compareUnsigned(v0, z0) <= 0) ? 1 : 0);

    res = v1 - carry;
    long z1 = res + n1;
    borrow = (Long.compareUnsigned(res, z1) <= 0) ? 1 : 0;
    carry = n1 - 1 + ((Long.compareUnsigned(v1, res) < 0) ? 1 : 0);

    res = v2 - carry - borrow;
    long z2 = res + n2;
    borrow = (Long.compareUnsigned(res, z2) <= 0) ? 1 : 0;
    carry = n2 - 1 + ((Long.compareUnsigned(v2, res) < 0) ? 1 : 0);

    long z3 = v3 + n3 - carry - borrow;
    if (Long.compareUnsigned(z3, n3) > 0
        || (z3 == n3
            && (Long.compareUnsigned(z2, n2) > 0
                || (z2 == n2
                    && (Long.compareUnsigned(z1, n1) > 0
                        || (z1 == n1 && Long.compareUnsigned(z0, n0) >= 0)))))) {
      return addBack256(n3, n2, n1, n0, z3, z2, z1, z0, -2L);
    }
    return new QR256(-1L, z3, z2, z1, z0);
  }

  private static QR256 reduceStep256(
      final long n3,
      final long n2,
      final long n1,
      final long n0,
      final long v4,
      final long v3,
      final long v2,
      final long v1,
      final long v0,
      final long inv) {
    if (v4 == n3) return mulSubOverflow256(n3, n2, n1, n0, v3, v2, v1, v0);
    final QR64 qr = div2by1(v4, v3, n3, inv);
    if (qr.q != 0) return mulSub256(n3, n2, n1, n0, qr.r, v2, v1, v0, qr.q);
    return new QR256(0, v3, v2, v1, v0);
  }

  /** UInt256.modReduceNormalised of a 257-bit value: c is its top bit. */
  private static void modReduceNormalised256(
      final long[] s,
      final int out,
      final long n3,
      final long n2,
      final long n1,
      final long n0,
      final long c,
      final long t3,
      final long t2,
      final long t1,
      final long t0,
      final int shift,
      final long inv) {
    final long v4, v3, v2, v1, v0;
    if (shift == 0) {
      v4 = c;
      v3 = t3;
      v2 = t2;
      v1 = t1;
      v0 = t0;
    } else {
      final int invShift = 64 - shift;
      v0 = t0 << shift;
      v1 = (t1 << shift) | t0 >>> invShift;
      v2 = (t2 << shift) | t1 >>> invShift;
      v3 = (t3 << shift) | t2 >>> invShift;
      v4 = (c << shift) | t3 >>> invShift;
    }
    QR256 qr;
    if (Long.compareUnsigned(v4, n3) >= 0) {
      qr = reduceStep256(n3, n2, n1, n0, 0, v4, v3, v2, v1, inv);
      qr = reduceStep256(n3, n2, n1, n0, qr.r3, qr.r2, qr.r1, qr.r0, v0, inv);
    } else {
      qr = reduceStep256(n3, n2, n1, n0, v4, v3, v2, v1, v0, inv);
    }
    sar(s, out, qr.r3, qr.r2, qr.r1, qr.r0, shift, 0);
  }

  private static void divReduce256(
      final long[] s,
      final int out,
      final long m3,
      final long m2,
      final long m1,
      final long m0,
      final long t3,
      final long t2,
      final long t1,
      final long t0) {
    final int cmp = compare256(m3, m2, m1, m0, t3, t2, t1, t0);
    if (cmp == 0) {
      put(s, out, 0, 0, 0, 1);
      return;
    }
    if (cmp > 0) {
      put(s, out, 0, 0, 0, 0);
      return;
    }
    final int shift = Long.numberOfLeadingZeros(m3);
    final long n3, n2, n1, n0;
    if (shift == 0) {
      n3 = m3;
      n2 = m2;
      n1 = m1;
      n0 = m0;
    } else {
      n3 = (m3 << shift) | (m2 >>> (64 - shift));
      n2 = (m2 << shift) | (m1 >>> (64 - shift));
      n1 = (m1 << shift) | (m0 >>> (64 - shift));
      n0 = m0 << shift;
    }
    final long inv = reciprocal(n3);
    final long c = 0;
    final long v4, v3, v2, v1, v0;
    if (shift == 0) {
      v4 = c;
      v3 = t3;
      v2 = t2;
      v1 = t1;
      v0 = t0;
    } else {
      final int invShift = 64 - shift;
      v0 = t0 << shift;
      v1 = (t1 << shift) | t0 >>> invShift;
      v2 = (t2 << shift) | t1 >>> invShift;
      v3 = (t3 << shift) | t2 >>> invShift;
      v4 = (c << shift) | t3 >>> invShift;
    }
    put(s, out, 0, 0, 0, reduceStep256(n3, n2, n1, n0, v4, v3, v2, v1, v0, inv).q);
  }

  // endregion
}
