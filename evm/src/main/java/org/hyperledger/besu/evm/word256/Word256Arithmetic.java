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

import static org.hyperledger.besu.evm.word256.Word256Comparison.compareUnsigned;
import static org.hyperledger.besu.evm.word256.Word256Comparison.isNegative;

/**
 * Package-private utility class for 256-bit arithmetic operations on {@link Word256}.
 *
 * <p>All methods are pure and allocation-free except for returning new Word256 instances.
 */
final class Word256Arithmetic {

  private Word256Arithmetic() {
    // Prevent instantiation
  }

  public static Word256 add(final Word256 a, final Word256 b) {
    long r3 = a.l3 + b.l3;
    long carry = Long.compareUnsigned(r3, a.l3) < 0 ? 1 : 0;

    long r2 = a.l2 + b.l2 + carry;
    carry = Long.compareUnsigned(r2, a.l2) < 0 || (carry == 1 && r2 == a.l2) ? 1 : 0;

    long r1 = a.l1 + b.l1 + carry;
    carry = Long.compareUnsigned(r1, a.l1) < 0 || (carry == 1 && r1 == a.l1) ? 1 : 0;

    long r0 = a.l0 + b.l0 + carry;

    return new Word256(r0, r1, r2, r3);
  }

  public static Word256 subtract(final Word256 a, final Word256 b) {
    long r3 = a.l3 - b.l3;
    long borrow = Long.compareUnsigned(a.l3, b.l3) < 0 ? 1 : 0;

    long r2 = a.l2 - b.l2 - borrow;
    borrow = Long.compareUnsigned(a.l2, b.l2 + borrow) < 0 ? 1 : 0;

    long r1 = a.l1 - b.l1 - borrow;
    borrow = Long.compareUnsigned(a.l1, b.l1 + borrow) < 0 ? 1 : 0;

    long r0 = a.l0 - b.l0 - borrow;

    return new Word256(r0, r1, r2, r3);
  }

  public static Word256 negate(final Word256 a) {
    return subtract(Word256Constants.ZERO, a);
  }

  public static Word256 abs(final Word256 a) {
    return isNegative(a) ? negate(a) : a;
  }

  public static Word256 divide(final Word256 dividend, final Word256 divisor) {
    if (isZero(divisor)) {
      throw new ArithmeticException("Division by zero");
    }

    if (compareUnsigned(dividend, divisor) < 0) {
      return Word256Constants.ZERO;
    }

    Word256 quotient = Word256Constants.ZERO;
    Word256 remainder = Word256Constants.ZERO;

    for (int i = 255; i >= 0; i--) {
      remainder = Word256Bitwise.shiftLeft1(remainder);
      if (Word256Bitwise.getBit(dividend, i) != 0) {
        remainder = Word256Bitwise.setBit(remainder, 0);
      }
      if (compareUnsigned(remainder, divisor) >= 0) {
        remainder = subtract(remainder, divisor);
        quotient = Word256Bitwise.setBit(quotient, i);
      }
    }

    return quotient;
  }

  public static Word256 mod(final Word256 value, final Word256 modulus) {
    if (isZero(modulus)) {
      return Word256Constants.ZERO;
    }
    if (compareUnsigned(value, modulus) < 0) {
      return value;
    }
    return subtract(value, mul(divide(value, modulus), modulus));
  }

  public static Word256 sdiv(final Word256 a, final Word256 b) {
    if (isZero(b)) {
      return Word256Constants.ZERO;
    }
    boolean negative = isNegative(a) ^ isNegative(b);
    Word256 absA = abs(a);
    Word256 absB = abs(b);
    Word256 result = divide(absA, absB);
    return negative ? negate(result) : result;
  }

  public static Word256 smod(final Word256 a, final Word256 b) {
    if (isZero(b)) {
      return Word256Constants.ZERO;
    }
    boolean negative = isNegative(a);
    Word256 absMod = mod(abs(a), abs(b));
    return negative ? negate(absMod) : absMod;
  }

  public static Word256 addmod(final Word256 a, final Word256 b, final Word256 modulus) {
    if (isZero(modulus)) {
      return Word256Constants.ZERO;
    }
    return mod(add(a, b), modulus);
  }

  public static Word256 mul(final Word256 a, final Word256 b) {
    final long[] x = {a.l3, a.l2, a.l1, a.l0}; // little-endian
    final long[] y = {b.l3, b.l2, b.l1, b.l0};

    final long[] r = new long[4]; // result (only 256 bits)

    // Intermediate 128-bit product results: low and high words
    for (int i = 0; i < 4; i++) {
      long carry = 0;
      for (int j = 0; j + i < 4; j++) {
        final int k = i + j;

        long xj = x[j];
        long yi = y[i];

        long lo = xj * yi;
        long hi = Math.multiplyHigh(xj, yi);

        // Add lo + carry to r[k], and propagate carry
        long sum = r[k] + (lo & 0xFFFFFFFFFFFFFFFFL);
        boolean carry0 = Long.compareUnsigned(sum, lo) < 0;

        r[k] = sum;
        long nextCarry = hi + (carry0 ? 1 : 0) + carry;

        carry = nextCarry;
      }

      // Final carry goes into next word if still within bounds
      if (i + 4 < r.length) {
        r[i + 4] += carry;
      }
    }

    return new Word256(r[3], r[2], r[1], r[0]);
  }

  public static Word256 mulmod(final Word256 a, final Word256 b, final Word256 modulus) {
    if (isZero(modulus)) {
      return Word256Constants.ZERO;
    }
    long[] product = Word256Helpers.multiplyFull(a, b);
    return Word256Helpers.mod512(product, modulus);
  }

  public static Word256 exp(final Word256 base, final Word256 exponent) {
    if (exponent.isZero()) {
      return Word256.ONE;
    }
    if (base.isZero()) {
      return Word256.ZERO;
    }

    Word256 result = Word256.ONE;
    Word256 power = base;
    for (int i = 255; i >= 0; i--) {
      if (exponent.getBit(i) == 1) {
        result = mul(result, power);
      }
      power = mul(power, power);
    }
    return result;
  }

  private static boolean isZero(final Word256 w) {
    return w.l0 == 0 && w.l1 == 0 && w.l2 == 0 && w.l3 == 0;
  }
}
