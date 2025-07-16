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


import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.evm.word256.Word256Helpers.divideWithRemainder;

import java.math.BigInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class Word256ArithmeticDivTest {

  @Test
  void divideByZeroReturnsZero() {
    final Word256 dividend = Word256.fromLong(12345L);
    final Word256 divisor = Word256.ZERO;
    final Word256 result = dividend.div(divisor);

    assertThat(result).isEqualTo(Word256.ZERO);
  }

  @Test
  void divideSmallerDividendReturnsZero() {
    final Word256 dividend = Word256.fromLong(5L);
    final Word256 divisor = Word256.fromLong(10L);
    final Word256 result = dividend.div(divisor);

    assertThat(result).isEqualTo(Word256.ZERO);
  }

  @Test
  void divideEqualReturnsOne() {
    final Word256 dividend = Word256.fromLong(777L);
    final Word256 divisor = Word256.fromLong(777L);
    final Word256 result = dividend.div(divisor);

    assertThat(result).isEqualTo(Word256.ONE);
  }

  @Test
  void divideSmallNumbersReturnsCorrectResult() {
    final Word256 dividend = Word256.fromLong(100L);
    final Word256 divisor = Word256.fromLong(5L);
    final Word256 result = dividend.div(divisor);

    assertThat(result).isEqualTo(Word256.fromLong(20L));
  }

  @Test
  void dividendSmallerHighWordReturnsZeroQuotient() {
    // dividend = (0, 0, 1, 0), divisor = (0, 0, 1, 1) => dividend < divisor
    final long[] dividend = new long[] {0L, 0L, 1L, 0L};
    final long[] divisor = new long[] {0L, 0L, 1L, 1L};

    final Word256Helpers.DivResult result = divideWithRemainder(dividend, divisor);

    Assertions.assertNotNull(result);
    assertThat(result.quotient()).isEqualTo(new long[] {0L, 0L, 0L, 0L});
    assertThat(result.remainder()).isEqualTo(dividend);
  }

  @Test
  void equalLengthDividendSmallerReturnsZeroQuotient() {
    // dividend = (0,0,2,0), divisor = (0,0,0,3)
    final long[] dividend = new long[] {0L, 0L, 2L, 0L};
    final long[] divisor = new long[] {0L, 0L, 0L, 3L};

    final Word256Helpers.DivResult result = divideWithRemainder(dividend, divisor);

    Assertions.assertNotNull(result);
    assertThat(result.quotient()).isEqualTo(new long[] {0L, 0L, 0L, 0L});
    assertThat(result.remainder()).isEqualTo(dividend);
  }

  @Test
  void dividesZeroByAnyDivisor() {
    final Word256 dividend = Word256.ZERO;
    final Word256 divisor = Word256.fromLong(123456L);
    final Word256 result = dividend.div(divisor);

    assertThat(result).isEqualTo(Word256.ZERO);
  }

  @Test
  void dividesByOneReturnsSameDividend() {
    final long[] dividendLongs = {0, 0, 0, 987654321L};
    final Word256 dividend = Word256.fromLongs(dividendLongs);
    final Word256 divisor = Word256.fromLong(1L);
    final Word256 result = dividend.div(divisor);

    assertThat(result).isEqualTo(dividend);
  }

  @Test
  void dividesMaxValueByTwo() {
    final long[] dividendLongs = {~0L, ~0L, ~0L, ~0L};
    final Word256 dividend = Word256.fromLongs(dividendLongs);
    final Word256 divisor = Word256.fromLong(2L);
    final Word256 result = dividend.div(divisor);

    final BigInteger expected = new BigInteger(1, dividend.toBytesArray())
      .divide(BigInteger.valueOf(2L));

    assertThat(result).isEqualTo(Word256.fromBytes(expected.toByteArray()));
  }

  @Test
  void dividesBySelfReturnsOne() {
    final Word256 dividend = Word256.fromLong(777L);
    final Word256 divisor = Word256.fromLong(777L);
    final Word256 result = dividend.div(divisor);

    assertThat(result).isEqualTo(Word256.ONE);
  }

  @Test
  void dividesWhenDividendSmallerReturnsZero() {
    final Word256 dividend = Word256.fromLong(10L);
    final Word256 divisor = Word256.fromLong(20L);
    final Word256 result = dividend.div(divisor);

    assertThat(result).isEqualTo(Word256.ZERO);
  }

  @Test
  void dividesLargeDividendBySmallDivisor() {
    final long[] dividendLongs = {0L, 0L, 1L, 0L};
    final Word256 dividend = Word256.fromLongs(dividendLongs);
    final Word256 divisor = Word256.fromLong(7L);
    final Word256 result = dividend.div(divisor);

    final BigInteger expected = new BigInteger(1, dividend.toBytesArray())
      .divide(BigInteger.valueOf(7L));

    System.err.println("Expected: " + expected.toString(16));
    System.err.println("Actual: " + result);
    assertThat(result).isEqualTo(Word256.fromBytes(expected.toByteArray()));
  }
}