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

import java.util.Arrays;

/**
 * * Utility class for performing arithmetic operations on {@link Word256} values.
 *
 * <p>This class provides methods for addition, subtraction, multiplication, division, and modular
 * arithmetic on 256-bit words represented by four 64-bit long values.
 */
final class Word256Arithmetic {

  private Word256Arithmetic() {}

  /**
   * Adds two Word256 values.
   *
   * @param a the first Word256 value
   * @param b the second Word256 value
   * @return the sum of a and b as a new Word256
   */
  static Word256 add(final Word256 a, final Word256 b) {
    long r0 = a.l0 + b.l0;
    long carry = Long.compareUnsigned(r0, a.l0) < 0 ? 1 : 0;

    long r1 = a.l1 + b.l1 + carry;
    carry = Long.compareUnsigned(r1, a.l1) < 0 || (carry == 1 && r1 == a.l1) ? 1 : 0;

    long r2 = a.l2 + b.l2 + carry;
    carry = Long.compareUnsigned(r2, a.l2) < 0 || (carry == 1 && r2 == a.l2) ? 1 : 0;

    long r3 = a.l3 + b.l3 + carry;

    return new Word256(r0, r1, r2, r3);
  }

  /**
   * Subtracts one Word256 value from another.
   *
   * @param a the Word256 value to subtract from
   * @param b the Word256 value to subtract
   * @return the result of a - b as a new Word256
   */
  static Word256 sub(final Word256 a, final Word256 b) {
    long[] r;
    long r0, r1, r2, r3;
    long borrow;

    r = Word256Helpers.subtract64(a.l0, b.l0, 0);
    r0 = r[0];
    borrow = r[1];

    r = Word256Helpers.subtract64(a.l1, b.l1, borrow);
    r1 = r[0];
    borrow = r[1];

    r = Word256Helpers.subtract64(a.l2, b.l2, borrow);
    r2 = r[0];
    borrow = r[1];

    r = Word256Helpers.subtract64(a.l3, b.l3, borrow);
    r3 = r[0];

    return new Word256(r0, r1, r2, r3);
  }

  /**
   * Negates a Word256 value (i.e., computes -a).
   *
   * @param a the Word256 value to negate
   * @return the negated value as a new Word256
   */
  static Word256 negate(final Word256 a) {
    return sub(Word256Constants.ZERO, a);
  }

  /**
   * Computes the absolute value of a Word256 value.
   *
   * @param a the Word256 value
   * @return the absolute value of a as a new Word256
   */
  static Word256 abs(final Word256 a) {
    return isNegative(a) ? negate(a) : a;
  }

  /**
   * Divides one Word256 value by another.
   *
   * @param dividend the Word256 value to be divided
   * @param divisor the Word256 value to divide by
   * @return the quotient of dividend / divisor as a new Word256
   * @throws ArithmeticException if divisor is zero
   */
  public static Word256 divide(final Word256 dividend, final Word256 divisor) {
    if (divisor.isZero()) {
      throw new ArithmeticException("Division by zero");
    }

    if (compareUnsigned(dividend, divisor) < 0) {
      return Word256Constants.ZERO;
    }

    if (dividend.equals(divisor)) {
      return Word256Constants.ONE;
    }

    final long[] u = {dividend.l0, dividend.l1, dividend.l2, dividend.l3}; // little-endian
    final long[] v = {divisor.l0, divisor.l1, divisor.l2, divisor.l3}; // little-endian
    final long[] q = new long[4];

    final int n = Word256Helpers.significantLength(v);
    final int m = Word256Helpers.significantLength(u) - n;

    final int shift = Long.numberOfLeadingZeros(v[n - 1]);

    final long[] vn = Word256Helpers.shiftLeft(v, shift, n);
    final long[] un = Word256Helpers.shiftLeftExtended(u, shift, m + n);

    if (n == 1) {
      return Word256Helpers.divideBySingleWord(u, v[0], m, q);
    }

    return Word256Helpers.divideKnuth(m, un, n, vn, q);
  }

  /**
   * Computes the modulus of a Word256 value with respect to another Word256 value.
   *
   * @param value the Word256 value to be reduced
   * @param modulus the Word256 modulus
   * @return the result of value % modulus as a new Word256
   */
  static Word256 mod(final Word256 value, final Word256 modulus) {
    if (Word256Comparison.isZero(modulus)) {
      return Word256Constants.ZERO;
    }

    if (compareUnsigned(value, modulus) < 0) {
      return value;
    }

    final Word256 result = sub(value, mul(divide(value, modulus), modulus));

    // Strip high bits explicitly (truncate to modulus bit width)
    final int modBitLength = modulus.bitLength();
    final Word256 mask = Word256Helpers.maskBelow(modBitLength);
    return Word256Bitwise.and(result, mask);
  }

  /**
   * Signed division of two Word256 values.
   *
   * @param a the dividend
   * @param b the divisor
   * @return the quotient of a / b as a new Word256
   */
  static Word256 sdiv(final Word256 a, final Word256 b) {
    if (Word256Comparison.isZero(b)) return Word256Constants.ZERO;
    boolean negative = isNegative(a) ^ isNegative(b);
    Word256 absA = abs(a);
    Word256 absB = abs(b);
    Word256 result = divide(absA, absB);
    return negative ? negate(result) : result;
  }

  /**
   * Signed modulus of two Word256 values.
   *
   * @param a the dividend
   * @param b the divisor
   * @return the result of a % b as a new Word256
   */
  static Word256 smod(final Word256 a, final Word256 b) {
    if (Word256Comparison.isZero(b)) return Word256Constants.ZERO;
    boolean negative = isNegative(a);
    Word256 absMod = mod(abs(a), abs(b));
    return negative ? negate(absMod) : absMod;
  }

  /**
   * Adds two Word256 values and reduces the result modulo a third Word256 value.
   *
   * @param a the first Word256 value
   * @param b the second Word256 value
   * @param modulus the modulus to reduce by
   * @return the result of (a + b) % modulus as a new Word256
   */
  static Word256 addMod(final Word256 a, final Word256 b, final Word256 modulus) {
    if (Word256Comparison.isZero(modulus)) {
      return Word256Constants.ZERO;
    }

    // Step 1: Reduce a and b modulo modulus
    final Word256 x = a.mod(modulus);
    final Word256 y = b.mod(modulus);

    // Step 2: Perform x + y with carry
    final long[] sum = new long[5];

    sum[0] = x.l0 + y.l0;
    long carry = Long.compareUnsigned(sum[0], x.l0) < 0 ? 1 : 0;

    sum[1] = x.l1 + y.l1 + carry;
    carry = ((Long.compareUnsigned(sum[1], x.l1) < 0) || (carry == 1 && sum[1] == x.l1)) ? 1 : 0;

    sum[2] = x.l2 + y.l2 + carry;
    carry = ((Long.compareUnsigned(sum[2], x.l2) < 0) || (carry == 1 && sum[2] == x.l2)) ? 1 : 0;

    sum[3] = x.l3 + y.l3 + carry;
    carry = ((Long.compareUnsigned(sum[3], x.l3) < 0) || (carry == 1 && sum[3] == x.l3)) ? 1 : 0;

    sum[4] = carry;

    // Step 3: Overflow path (5-limb division mod 4-limb modulus)
    if (sum[4] != 0) {
      final long[] un = Arrays.copyOf(sum, 5); // dividend
      final long[] vn = {modulus.l0, modulus.l1, modulus.l2, modulus.l3};
      final long[] q = new long[2]; // quotient (ignored)
      final long[] rem = Word256Helpers.divideAndRemainderKnuth(1, un, 4, vn, q);
      final Word256 result = new Word256(rem[0], rem[1], rem[2], rem[3]);
      return result;
    }

    // Step 4: Try subtracting modulus: sum - modulus, with unsigned borrow tracking
    final long[] reduced = trySubtract(sum, modulus);
    final Word256 result;
    if (reduced != null) {
      result = new Word256(reduced[0], reduced[1], reduced[2], reduced[3]);
    } else {
      result = new Word256(sum[0], sum[1], sum[2], sum[3]);
    }

    return result;
  }

  private static long[] trySubtract(final long[] sum, final Word256 modulus) {
    final long[] mod = {modulus.l0, modulus.l1, modulus.l2, modulus.l3};
    final long[] tmp = new long[4];
    long borrow = 0;

    for (int i = 0; i < 4; i++) {
      final long subtrahend = mod[i] + borrow;
      tmp[i] = sum[i] - subtrahend;
      final long newBorrow = Word256Helpers.getBorrow(sum[i], subtrahend, tmp[i]);

      if (newBorrow < borrow) {
        return null; // Invalid borrow propagation
      }
      borrow = newBorrow;
    }

    return (borrow == 0) ? tmp : null;
  }

  /**
   * Multiplies two Word256 values.
   *
   * @param a the first Word256 value
   * @param b the second Word256 value
   * @return the product of a and b as a new Word256
   */
  static Word256 mul(final Word256 a, final Word256 b) {
    final long[] x = {a.l0, a.l1, a.l2, a.l3}; // LSB to MSB
    final long[] y = {b.l0, b.l1, b.l2, b.l3};

    final long[] r = new long[4]; // 256-bit result only

    for (int i = 0; i < 4; i++) {
      long carry = 0;
      for (int j = 0; j + i < 4; j++) {
        int k = i + j;
        long xj = x[j];
        long yi = y[i];
        long lo = xj * yi;
        long hi = Math.multiplyHigh(xj, yi);

        long sum = r[k] + lo;
        boolean carry0 = Long.compareUnsigned(sum, lo) < 0;

        r[k] = sum;
        long nextCarry = hi + (carry0 ? 1 : 0) + carry;

        carry = nextCarry;
      }
      // drop overflow (k >= 4)
    }

    return new Word256(r[0], r[1], r[2], r[3]);
  }

  /**
   * Multiplies two Word256 values and reduces the result modulo a third Word256 value.
   *
   * @param a the first Word256 value
   * @param b the second Word256 value
   * @param modulus the modulus to reduce by
   * @return the result of (a * b) % modulus as a new Word256
   */
  public static Word256 mulmod(final Word256 a, final Word256 b, final Word256 modulus) {
    // Special case: mod == 2^256 - 1
    if (modulus.equals(Word256.MAX)) {
      final long[] product = Word256Helpers.multiplyFull(a, b);

      final Word256 lo = new Word256(product[0], product[1], product[2], product[3]);
      final Word256 hi = new Word256(product[4], product[5], product[6], product[7]);

      Word256 result = lo.add(hi);
      if (result.compareUnsigned(lo) < 0) {
        result = result.add(Word256.ONE); // propagate carry
      }

      if (result.compareUnsigned(modulus) >= 0) {
        result = result.sub(modulus);
      }

      if (result.equals(modulus)) {
        return Word256.ZERO;
      }

      return result;
    }

    if (modulus.isZero() || a.isZero() || b.isZero()) {
      return Word256.ZERO;
    }

    final long[] product = Word256Helpers.multiplyFull(a, b);

    final boolean highIsZero =
        product[4] == 0 && product[5] == 0 && product[6] == 0 && product[7] == 0;
    if (highIsZero) {
      final Word256 lo = new Word256(product[0], product[1], product[2], product[3]);
      return Word256Arithmetic.mod(lo, modulus);
    }

    final long[] modLimbs = new long[] {modulus.l0, modulus.l1, modulus.l2, modulus.l3};
    final int modLen = Word256Helpers.significantLength(modLimbs);
    if (modLen == 0) {
      return Word256.ZERO;
    }

    final int shift = Long.numberOfLeadingZeros(modulus.getLimb(modLen - 1));
    final long[] modShifted = Word256Helpers.shiftLeft(modLimbs, shift, modLen);

    final int m = 4;
    final int unLen = m + modLen + 1;

    final long[] unShifted = new long[unLen]; // make sure it has enough room
    System.arraycopy(product, 0, unShifted, 0, 8); // copy the full product
    final long[] un = Word256Helpers.shiftLeft(unShifted, shift, unLen);

    final long[] q = new long[modLen + 1];
    final long[] normRem =
        (shift == 0)
            ? Arrays.copyOfRange(un, 0, modLen)
            : Word256Helpers.divideAndRemainderKnuth(m, un, modLen, modShifted, q);

    // Shift remainder back (de-normalize)
    final long[] rem =
        (shift == 0) ? normRem : Word256Helpers.shiftRight(normRem, shift, normRem.length);

    final Word256 result =
        new Word256(
            rem.length > 0 ? rem[0] : 0,
            rem.length > 1 ? rem[1] : 0,
            rem.length > 2 ? rem[2] : 0,
            rem.length > 3 ? rem[3] : 0);

    return Word256Arithmetic.mod(result, modulus);
  }

  /**
   * Exponentiates a base Word256 value to the power of an exponent Word256 value.
   *
   * @param base the base Word256 value
   * @param exponent the exponent Word256 value
   * @return the result of base^exponent as a new Word256
   */
  static Word256 exp(final Word256 base, final Word256 exponent) {
    if (exponent.isZero()) {
      return Word256Constants.ONE;
    }

    if (base.isZero()) {
      return Word256Constants.ZERO;
    }

    final int highestBit = exponent.bitLength() - 1;
    Word256 result = Word256Constants.ONE;
    Word256 power = base;

    for (int i = 0; i <= highestBit; i++) {
      if (exponent.getBit(i) == 1) {
        result = mul(result, power);
      }

      if (i != highestBit) {
        power = mul(power, power);
      }
    }

    return result;
  }
}
