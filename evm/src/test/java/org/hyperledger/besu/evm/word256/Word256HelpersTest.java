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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.hyperledger.besu.evm.word256.Word256Helpers.div64;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

class Word256HelpersTest {

  @Test
  void div64_shouldThrowOnQuotientOverflow_whenQuotientTooBig() {
    final long hi = 1L;
    final long lo = 0L;
    final long y = 1L;
    assertThatThrownBy(() -> div64(hi, lo, y))
      .isInstanceOf(ArithmeticException.class)
      .hasMessageContaining("Quotient overflow");
  }

  @Test
  void div64_shouldReturnZeroQuotientAndRemainderWhenHiLoZero() {
    final Word256Helpers.Div64Result result = div64(0L, 0L, 123456789L);
    assertThat(result.quotient()).isEqualTo(0L);
    assertThat(result.remainder()).isEqualTo(0L);
  }

  @Test
  void div64_shouldHandleMaxLoDividedBySmallY() {
    final long hi = 0L;
    final long lo = 0xFFFFFFFFFFFFFFFFL;
    final long y = 0xFFFFFFFFL;
    final Word256Helpers.Div64Result result = div64(hi, lo, y);
    assertThat(result.quotient()).isEqualTo(0x100000001L);
    assertThat(result.remainder()).isEqualTo(0L);
  }

  @Test
  void div64_shouldHandleNonZeroRemainder() {
    final long hi = 0L;
    final long lo = 100L;
    final long y = 9L;
    final Word256Helpers.Div64Result result = div64(hi, lo, y);
    assertThat(result.quotient()).isEqualTo(11L);
    assertThat(result.remainder()).isEqualTo(1L);
  }

  @Test
  void div64_shouldThrowOnDivisionByZero() {
    assertThrows(ArithmeticException.class, () -> div64(0L, 123L, 0L));
  }

  @Test
  void div64_shouldThrowOnQuotientOverflow() {
    final long hi = 10L;
    final long lo = 0L;
    final long y = 5L;
    assertThrows(ArithmeticException.class, () -> div64(hi, lo, y));
  }

  @Test
  void div64_shouldMatchBigInteger_forRandomValues() {
    final long hi = 0x1234567890ABCDEFL;
    final long lo = 0x0FEDCBA987654321L;
    final long y = 0x1ABCDEF012345678L;

    final BigInteger dividend = new BigInteger(1, new byte[]{
      (byte) (hi >>> 56), (byte) (hi >>> 48), (byte) (hi >>> 40), (byte) (hi >>> 32),
      (byte) (hi >>> 24), (byte) (hi >>> 16), (byte) (hi >>> 8), (byte) hi,
      (byte) (lo >>> 56), (byte) (lo >>> 48), (byte) (lo >>> 40), (byte) (lo >>> 32),
      (byte) (lo >>> 24), (byte) (lo >>> 16), (byte) (lo >>> 8), (byte) lo
    });
    final BigInteger divisor = BigInteger.valueOf(y).and(BigInteger.valueOf(0xFFFFFFFFFFFFFFFFL));
    final BigInteger[] bigRes = dividend.divideAndRemainder(divisor);

    final Word256Helpers.Div64Result result = div64(hi, lo, y);

    assertThat(new BigInteger(1, longToBytes(result.quotient()))).isEqualTo(bigRes[0]);
    assertThat(new BigInteger(1, longToBytes(result.remainder()))).isEqualTo(bigRes[1]);
  }

  @Test
  void reciprocal_shouldThrowDivisionByZero() {
    assertThatThrownBy(() -> Word256Helpers.computeApproximateReciprocal64(0L))
      .isInstanceOf(ArithmeticException.class)
      .hasMessageContaining("Division by zero");
  }

  @Test
  void reciprocal_shouldBeMaxValue_whenDivisorIsOne() {
    final long d = 1L;
    final long reciprocal = Word256Helpers.computeApproximateReciprocal64(d);
    assertThat(reciprocal).isEqualTo(-1L);
  }

  @Test
  void reciprocal_shouldThrowOverflow_forInvalidSmallDivisors() {
    for (long d = 1L; d <= 10L; d++) {
      if (Long.compareUnsigned(d, (~d)) >= 0) {
        final long d1 = d;
        assertThatThrownBy(() -> Word256Helpers.computeApproximateReciprocal64(d1))
          .isInstanceOf(ArithmeticException.class)
          .hasMessageContaining("Quotient overflow");
      }
    }
  }

  @Test
  void reciprocal_shouldSatisfyBound_forRandomDivisors() {
    final long[] divisors = {
      0x8000000000000000L,
      0xFFFFFFFFFFFFFFFEL,
      0xFFFFFFFFFFFFFFFFL,
      0xDEADBEEFFEEDC0DEL
    };

    for (final long d : divisors) {
      final long reciprocal = Word256Helpers.computeApproximateReciprocal64(d);
      final BigInteger dBig = new BigInteger(1, ByteBuffer.allocate(Long.BYTES).putLong(d).array());
      final BigInteger product = BigInteger.valueOf(reciprocal).multiply(dBig);
      final BigInteger bound = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
      assertThat(product.add(dBig).compareTo(bound) <= 0).isTrue();
    }
  }

  @Test
  void reciprocal_shouldBeCorrect_forMaxDivisor() {
    final long d = 0xFFFFFFFFFFFFFFFFL;
    final long reciprocal = Word256Helpers.computeApproximateReciprocal64(d);

    // The correct result is 1 (unsigned division)
    assertThat(Long.toUnsignedString(reciprocal)).isEqualTo("1");
  }

  private static byte[] longToBytes(final long val) {
    return new byte[] {
      (byte) (val >>> 56),
      (byte) (val >>> 48),
      (byte) (val >>> 40),
      (byte) (val >>> 32),
      (byte) (val >>> 24),
      (byte) (val >>> 16),
      (byte) (val >>> 8),
      (byte) val
    };
  }
}
