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
  private static final ThreadLocal<long[]> THREAD_LOCAL_LONG_2 = ThreadLocal.withInitial(() -> new long[2]);
  private static final ThreadLocal<long[]> THREAD_LOCAL_LONG_4 = ThreadLocal.withInitial(() -> new long[4]);

  static long[] getScratchLong2() {
    return THREAD_LOCAL_LONG_2.get();
  }
  static long[] getScratchLong4() {
    return THREAD_LOCAL_LONG_4.get();
  }

  private static final long TWO32 = 1L << 32;
  private static final long MASK32 = TWO32 - 1L;
  private static final long[] ZERO_QUOTIENT = new long[4];

  record Div128By64Result(long quotient, long remainder) {}
  record MultiplyResult(long hi, long lo) {}

  private Word256Helpers() {
    // Prevent instantiation
  }

  static long add64(final long x, final long y, final long carryIn, final long[] carryOut) {
    final long sum = x + y + carryIn;

    if (carryOut != null) {
      // This computes the carry out by checking if both x and y have a bit set in the same position,
      // or if either x or y has a bit set but the sum does not, indicating an overflow.
      carryOut[0] = ((x & y) | ((x | y) & ~sum)) >>> 63;
    }
    return sum;
  }

  /** Multiplies two 64-bit integers and returns the result as a long array of two elements. */
  static long[] subtract64(final long x, final long y, final long borrowIn) {
    final long diff = x - y - borrowIn;

    // Bitwise computation of borrowOut: ((~x & y) | (~(x ^ y) & diff)) >>> 63
    final long t1 = ~x & y;
    final long t2 = ~(x ^ y) & diff;
    final long borrowOut = (t1 | t2) >>> 63;

    final long[] result = getScratchLong2();
    result[0] = diff;
    result[1] = borrowOut;
    return result;
  }

  /**
   * Multiplies two unsigned 64-bit integers and returns the result as a long array of two elements:
   * the high part and the low part of the product.
   *
   * @param x the first unsigned 64-bit integer
   * @param y the second unsigned 64-bit integer
   * @return an array containing the high and low parts of the product
   */
  static MultiplyResult multiplyHighLowUnsigned(final long x, final long y) {
    final long x0 = x & 0xFFFFFFFFL;
    final long x1 = x >>> 32;
    final long y0 = y & 0xFFFFFFFFL;
    final long y1 = y >>> 32;

    final long w0 = x0 * y0;
    final long t = x1 * y0 + (w0 >>> 32);
    final long w1 = t & 0xFFFFFFFFL;
    final long w2 = t >>> 32;

    final long lo = x * y;
    final long hi = x1 * y1 + w2 + ((x0 * y1 + w1) >>> 32);

    return new MultiplyResult(hi, lo);
  }

  /**
   * Multiplies two unsigned 64-bit integers and adds a third unsigned 64-bit integer to the low
   * part of the product.
   *
   * @param z the unsigned 64-bit integer to add to the low part of the product
   * @param x the first unsigned 64-bit integer
   * @param y the second unsigned 64-bit integer
   * @return an array containing the high part and the low part of the result
   */
  static long[] unsignedMultiplyAdd(final long z, final long x, final long y) {
    final MultiplyResult m = multiplyHighLowUnsigned(x, y);
    final long loSum = m.lo + z;
    final boolean carry = Long.compareUnsigned(loSum, m.lo) < 0;
    final long hi = m.hi + (carry ? 1 : 0);


    final long[] result = getScratchLong2();
    result[0] = hi;
    result[1] = loSum;
    return result;
  }

  /**
   * Multiplies two unsigned 64-bit integers, adds a third unsigned 64-bit integer to the low part
   * of the product, and includes a carry from a previous operation.
   *
   * @param z the unsigned 64-bit integer to add to the low part of the product
   * @param x the first unsigned 64-bit integer
   * @param y the second unsigned 64-bit integer
   * @param carry the carry from a previous operation
   * @return an array containing the high part and the low part of the result
   */
  static MultiplyResult unsignedMultiplyAddWithCarry(
      final long z, final long x, final long y, final long carry) {
    final MultiplyResult m = multiplyHighLowUnsigned(x, y);

    // lo1 = loMul + carry
    final long lo1 = m.lo + carry;
    final boolean carry1 = Long.compareUnsigned(lo1, m.lo) < 0;

    // lo2 = lo1 + z
    final long lo2 = lo1 + z;
    final boolean carry2 = Long.compareUnsigned(lo2, lo1) < 0;

    // hi = hiMul + carry1 + carry2
    final long hi1 = m.hi + (carry1 ? 1 : 0);
    final long hi2 = hi1 + (carry2 ? 1 : 0);

    return new MultiplyResult(hi2, lo2);
  }

  /**
   * Multiplies three pairs of unsigned 64-bit integers and adds their products.
   *
   * @param x1 the first unsigned 64-bit integer of the first pair
   * @param y1 the second unsigned 64-bit integer of the first pair
   * @param x2 the first unsigned 64-bit integer of the second pair
   * @param y2 the second unsigned 64-bit integer of the second pair
   * @param x3 the first unsigned 64-bit integer of the third pair
   * @param y3 the second unsigned 64-bit integer of the third pair
   * @return the sum of the products as a long
   */
  static long unsignedMulAdd3(
      final long x1, final long y1, final long x2, final long y2, final long x3, final long y3) {
    return x1 * y1 + x2 * y2 + x3 * y3;
  }

  /**
   * Multiplies two unsigned 64-bit integers and adds a variable number of additional unsigned
   * integers to the result.
   *
   * @param x the first unsigned 64-bit integer
   * @param y the second unsigned 64-bit integer
   * @param extras additional unsigned integers to add to the product
   * @return the result of the multiplication and addition as a long
   */
  static long unsignedMultiplyAndAdd(final long x, final long y, final long... extras) {
    long result = x * y;
    for (final long e : extras) {
      result = result + e;
    }
    return result;
  }

  /**
   * Converts a byte array to a long value, interpreting the bytes as a big-endian 64-bit integer.
   *
   * @param bytes the byte array
   * @param offset the offset in the byte array
   * @return the long value represented by the bytes
   */
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
  static void writeToByteArray(final byte[] dest, final int offset, final long value) {
    for (int i = 0; i < 8; i++) {
      dest[offset + i] = (byte) (value >>> (56 - (i * 8)));
    }
  }

  static void divideWithRemainder(final long[] quotient, final long[] remainder,  final long[] dividend, final long[] divisor) {
//    System.err.printf("dividend %s\n", Word256.fromLongs(dividend));
//    System.err.printf("divisor %s\n", Word256.fromLongs(divisor));

    int divisorLength = 4;
    for (int i = 3; i >= 0; i--) {
      if (divisor[i] != 0L) {
        divisorLength = i + 1;
        break;
      }
    }
//    System.err.printf("divisorLength %d\n", divisorLength);

    final int shift = Long.numberOfLeadingZeros(divisor[divisorLength - 1]);
//    System.err.printf("shift %d\n", shift);

    long[] normalizedDivisor = new long[divisorLength];
    for (int i = divisorLength - 1; i > 0; i--) {
      normalizedDivisor[i] = (divisor[i] << shift) | (divisor[i - 1] >>> (64 - shift));
    }
    normalizedDivisor[0] = divisor[0] << shift;

    for (int i = divisorLength - 1; i > 0; i--) {
//      System.err.printf("normalizedDivisor[%d] %s\n", i, normalizedDivisor[i]);
    }

    int dividendLength = 4;
    for (int i = 3; i >= 0; i--) {
      if (dividend[i] != 0L) {
        dividendLength = i + 1;
        break;
      }
    }
//    System.err.printf("dividendLength %d\n", dividendLength);

    if (dividendLength < divisorLength) {
      // Dividend is smaller than divisor, return 0 quotient and the dividend as remainder
      System.arraycopy(ZERO_QUOTIENT, 0, quotient, 0, 4);

      if (remainder != null) {
        System.arraycopy(dividend, 0, remainder, 0, 4);
      }

      return;
    }

    final long[] normalizedDividend = new long[dividendLength + 1];
    normalizedDividend[dividendLength] = dividend[dividendLength - 1] >>> (64 - shift);

    for (int i = dividendLength - 1; i > 0; i--) {
      normalizedDividend[i] = (dividend[i] << shift) | (dividend[i - 1] >>> (64 - shift));
//      System.err.printf("normalizedDividend[%d] %s\n", i, normalizedDividend[i]);
    }
    normalizedDividend[0] = dividend[0] << shift;

    if (divisorLength == 1) {
      divideWithRemainderBySingleWord(quotient, remainder, normalizedDividend, normalizedDivisor[0]);
      remainder[0] = remainder[0] >>> shift;

//      System.err.println("Final result:");
//      System.err.println("quotient[0]: " + Long.toUnsignedString(divResult.quotient[0]));
//      System.err.println("quotient[1]: " + Long.toUnsignedString(divResult.quotient[1]));
//      System.err.println("quotient[2]: " + Long.toUnsignedString(divResult.quotient[2]));
//      System.err.println("quotient[3]: " + Long.toUnsignedString(divResult.quotient[3]));
//      System.err.printf("remainder %d\n", remainderResult[0]);
      return;
    }

    int normalizedDividendLength = normalizedDividend.length;
    while (normalizedDividendLength > 0 && normalizedDividend[normalizedDividendLength - 1] == 0) {
      normalizedDividendLength--;
    }

  }

  private static void divideWithRemainderBySingleWord(final long[] quotient, final long[] remainderResult, final long[] normalizedDividend, final long divisor) {
//    System.err.println("divideWithRemainderBySingleWord");

    final long reciprocal = computeApproximateReciprocal64(divisor);
//    System.err.printf("reciprocal %s\n", Long.toUnsignedString(reciprocal));
    long remainder = normalizedDividend[normalizedDividend.length - 1];
//    System.err.printf("remainder %s\n", Long.toUnsignedString(remainder));

    for (int i = normalizedDividend.length - 2; i >= 0; i--) {
      final Div128By64Result res  = divide128by64WithReciprocal(remainder, normalizedDividend[i], divisor, reciprocal);
      quotient[i] = res.quotient;
      remainder = res.remainder;

//      System.err.printf("quotient[%d] %s\n", i, Long.toUnsignedString(quotient[i]));
//      System.err.printf("remainder %s\n", Long.toUnsignedString(remainder));
    }

    if (remainderResult != null) {
      remainderResult[0] = remainder;
      remainderResult[1] = 0L;
      remainderResult[2] = 0L;
      remainderResult[3] = 0L;
    }
  }

  private static Div128By64Result divide128by64WithReciprocal(final long dividendHi, final long dividendLo,
                                                              final long divisor, final long reciprocal) {
//    System.err.println("divide128by64WithReciprocal");
//    System.err.println("dividendHi: " + Long.toUnsignedString(dividendHi));
//    System.err.println("dividendLo: " + Long.toUnsignedString(dividendLo));
//    System.err.println("divisor: " + Long.toUnsignedString(divisor));
//    System.err.println("reciprocal: " + Long.toUnsignedString(reciprocal));

    final MultiplyResult quotientResult = multiplyHighLowUnsigned(reciprocal, dividendHi);
    long quotientHi = quotientResult.hi;
    long quotientLo = quotientResult.lo;
//    System.err.println("quotientHi: " + Long.toUnsignedString(quotientHi));
//    System.err.println("quotientLo: " + Long.toUnsignedString(quotientLo));

    quotientLo = add64(quotientLo, dividendLo, 0L, null);
//    System.err.println("quotientLo: " + Long.toUnsignedString(quotientLo));

    quotientHi = add64(quotientHi, dividendHi, quotientLo, null);
//    System.err.println("sumHi[0]: " + Long.toUnsignedString(sumHi[0]));
//    System.err.println("sumHi[1]: " + Long.toUnsignedString(sumHi[1]));
    quotientHi++;
//    System.err.println("quotientHi after increment: " + Long.toUnsignedString(quotientHi));

    long remainder = dividendLo - quotientHi * divisor;
//    System.err.println("remainder: " + Long.toUnsignedString(remainder));

    if (Long.compareUnsigned(remainder, quotientLo) > 0) {
      quotientHi--;
      remainder += divisor;
    }
//    System.err.println("quotientHi after potential --: " + Long.toUnsignedString(quotientHi));
//    System.err.println("remainder after potential increment: " + Long.toUnsignedString(remainder));

    if (Long.compareUnsigned(remainder, divisor) >= 0) {
      quotientHi++;
      remainder -= divisor;
    }
//    System.err.println("quotientHi after potential ++: " + Long.toUnsignedString(quotientHi));
//    System.err.println("remainder after potential decrement: " + Long.toUnsignedString(remainder));

    final long[] result = getScratchLong2();
    result[0] = quotientHi;
    result[1] = remainder;
    return new Div128By64Result(quotientHi, remainder);
  }

  /**
   * Divides a 128-bit unsigned integer represented by two 64-bit longs by another 64-bit unsigned
   * integer, returning the quotient and remainder.
   *
   * @param hi the high part of the 128-bit unsigned integer
   * @param lo the low part of the 128-bit unsigned integer
   * @param y the divisor (64-bit unsigned integer)
   * @return a Div64Result containing the quotient and remainder
   */
  static long[] div64(final long hi, final long lo, final long y) {
    if (y == 0L) {
      throw new ArithmeticException("Division by zero");
    }

    // Important: unsigned comparison
    if (Long.compareUnsigned(y, hi) <= 0) {
      throw new ArithmeticException("Quotient overflow");
    }

    if (hi == 0L) {
      final long quotient = Long.divideUnsigned(lo, y);
      final long remainder = Long.remainderUnsigned(lo, y);

      final long[] result = getScratchLong2();
      result[0] = quotient;
      result[1] = remainder;
      return result;
    }

    final int s = Long.numberOfLeadingZeros(y);
    final long yNorm = y << s;

    final long yn1 = yNorm >>> 32;
    final long yn0 = yNorm & MASK32;

    final long un32 = (hi << s) | (lo >>> (64 - s));
    final long un10 = lo << s;
    final long un1 = un10 >>> 32;
    final long un0 = un10 & MASK32;

    long q1 = Long.divideUnsigned(un32, yn1);
    long rhat = un32 - q1 * yn1;

    while (Long.compareUnsigned(q1, TWO32) >= 0 ||
      Long.compareUnsigned(q1 * yn0, TWO32 * rhat + un1) > 0) {
      q1--;
      rhat += yn1;
      if (Long.compareUnsigned(rhat, TWO32) >= 0) {
        break;
      }
    }

    final long un21 = un32 * TWO32 + un1 - q1 * yNorm;
    long q0 = Long.divideUnsigned(un21, yn1);
    rhat = un21 - q0 * yn1;

    while (Long.compareUnsigned(q0, TWO32) >= 0 ||
      Long.compareUnsigned(q0 * yn0, TWO32 * rhat + un0) > 0) {
      q0--;
      rhat += yn1;
      if (Long.compareUnsigned(rhat, TWO32) >= 0) {
        break;
      }
    }

    final long quotient = q1 * TWO32 + q0;
    final long remainder = ((un21 * TWO32 + un0) - q0 * yNorm) >>> s;

    final long[] result = getScratchLong2();
    result[0] = quotient;
    result[1] = remainder;
    return result;
  }

  /**
   * Computes the approximate reciprocal of a 64-bit unsigned divisor,
   * i.e., floor((2^128 - 1) / d)
   *
   * @param d the unsigned 64-bit divisor
   * @return the approximate reciprocal
   */
  static long computeApproximateReciprocal64(final long d) {
    if (d == 0L) {
      throw new ArithmeticException("Division by zero");
    }

    if (d == 1L) {
      return -1L; // 0xFFFFFFFFFFFFFFFFL
    }

    final long[] result = div64(~d, ~0L, d);
    return result[0];
  }
}
