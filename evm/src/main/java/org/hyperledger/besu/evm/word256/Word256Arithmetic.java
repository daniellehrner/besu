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
  public static Word256 subtract(final Word256 a, final Word256 b) {
    long r3 = a.l3 - b.l3;
    long borrow = (Long.compareUnsigned(a.l3, b.l3) < 0) ? 1 : 0;

    long b2 = b.l2 + borrow;
    borrow = (Long.compareUnsigned(a.l2, b2) < 0) ? 1 : 0;
    long r2 = a.l2 - b2;

    long b1 = b.l1 + borrow;
    borrow = (Long.compareUnsigned(a.l1, b1) < 0) ? 1 : 0;
    long r1 = a.l1 - b1;

    long b0 = b.l0 + borrow;
    // No need to compute final borrow — we ignore underflow
    long r0 = a.l0 - b0;

    return new Word256(r0, r1, r2, r3);
  }

  /**
   * Negates a Word256 value (i.e., computes -a).
   *
   * @param a the Word256 value to negate
   * @return the negated value as a new Word256
   */
  static Word256 negate(final Word256 a) {
    return subtract(Word256Constants.ZERO, a);
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
      // Single-limb divisor optimization
      return Word256Helpers.divideBySingleWord(u, vn, m, q, shift);
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
    if (Word256Comparison.isZero(modulus)) return Word256Constants.ZERO;
    if (compareUnsigned(value, modulus) < 0) return value;
    return subtract(value, mul(divide(value, modulus), modulus));
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
  static Word256 addmod(final Word256 a, final Word256 b, final Word256 modulus) {
    if (Word256Comparison.isZero(modulus)) return Word256Constants.ZERO;
    return mod(add(a, b), modulus);
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
  static Word256 mulmod(final Word256 a, final Word256 b, final Word256 modulus) {
    if (Word256Comparison.isZero(modulus)) return Word256Constants.ZERO;
    long[] product = Word256Helpers.multiplyFull(a, b);
    return Word256Helpers.mod512(product, modulus);
  }

  /**
   * Exponentiates a base Word256 value to the power of an exponent Word256 value.
   *
   * @param base the base Word256 value
   * @param exponent the exponent Word256 value
   * @return the result of base^exponent as a new Word256
   */
  static Word256 exp(final Word256 base, final Word256 exponent) {
    if (exponent.isZero()) return Word256Constants.ONE;
    if (base.isZero()) return Word256Constants.ZERO;

    Word256 result = Word256Constants.ONE;
    Word256 power = base;

    for (int i = 255; i >= 0; i--) {
      if (exponent.getBit(i) == 1) {
        result = mul(result, power);
      }
      power = mul(power, power);
    }

    return result;
  }
}
