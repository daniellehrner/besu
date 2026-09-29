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
package org.hyperledger.besu.evm.v2;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import java.util.function.BinaryOperator;

import org.junit.jupiter.api.Test;

/** Checks the two-operand stack helpers against BigInteger over random and edge-case inputs. */
class StackArithmeticTest {

  private static final BigInteger MOD = BigInteger.ONE.shiftLeft(256);
  private static final BigInteger MAX = MOD.subtract(BigInteger.ONE);

  interface StackOp {
    int apply(long[] s, int top);
  }

  private record Case(String name, StackOp op, BinaryOperator<BigInteger> reference) {}

  private static BigInteger signed(final BigInteger v) {
    return v.testBit(255) ? v.subtract(MOD) : v;
  }

  private static BigInteger unsigned(final BigInteger v) {
    return v.signum() < 0 ? v.add(MOD) : v;
  }

  private static final List<Case> CASES =
      List.of(
          new Case("mul", StackArithmetic::mul, (a, b) -> a.multiply(b).mod(MOD)),
          new Case(
              "div",
              StackArithmetic::div,
              (a, b) -> b.signum() == 0 ? BigInteger.ZERO : a.divide(b)),
          new Case(
              "sdiv",
              StackArithmetic::signedDiv,
              (a, b) ->
                  b.signum() == 0
                      ? BigInteger.ZERO
                      : unsigned(signed(a).divide(signed(b)).mod(MOD))),
          new Case(
              "mod", StackArithmetic::mod, (a, b) -> b.signum() == 0 ? BigInteger.ZERO : a.mod(b)),
          new Case(
              "smod",
              StackArithmetic::signedMod,
              (a, b) ->
                  b.signum() == 0
                      ? BigInteger.ZERO
                      : unsigned(signed(a).remainder(signed(b)).mod(MOD))),
          new Case(
              "byte",
              StackArithmetic::byte_,
              (a, b) ->
                  a.compareTo(BigInteger.valueOf(32)) >= 0
                      ? BigInteger.ZERO
                      : b.shiftRight(8 * (31 - a.intValue())).and(BigInteger.valueOf(0xff))),
          new Case("exp", StackArithmetic::exp, (a, b) -> a.modPow(b, MOD)));

  private static final BigInteger[] EDGES = {
    BigInteger.ZERO,
    BigInteger.ONE,
    BigInteger.TWO,
    BigInteger.valueOf(31),
    BigInteger.valueOf(32),
    BigInteger.valueOf(255),
    BigInteger.valueOf(256),
    BigInteger.ONE.shiftLeft(63),
    BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
    BigInteger.ONE.shiftLeft(64),
    BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE),
    BigInteger.ONE.shiftLeft(128),
    BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE),
    BigInteger.ONE.shiftLeft(255),
    MAX,
  };

  private static void put(final long[] s, final int slot, final BigInteger v) {
    final byte[] bytes = v.toByteArray();
    final byte[] word = new byte[32];
    final int n = Math.min(32, bytes.length);
    System.arraycopy(bytes, bytes.length - n, word, 32 - n, n);
    StackArithmetic.fromBytesAt(s, slot + 1, 0, word, 0, 32);
  }

  private static BigInteger get(final long[] s, final int slot) {
    final byte[] word = new byte[32];
    StackArithmetic.toBytesAt(s, slot + 1, 0, word);
    return new BigInteger(1, word);
  }

  private static void check(final Case c, final BigInteger top, final BigInteger below) {
    final long[] s = new long[16];
    put(s, 0, below);
    put(s, 1, top);
    final int newTop;
    try {
      newTop = c.op().apply(s, 2);
    } catch (final RuntimeException e) {
      throw new AssertionError(
          c.name() + "(" + top.toString(16) + ", " + below.toString(16) + ") threw", e);
    }
    assertThat(newTop).as("%s stack top", c.name()).isEqualTo(1);
    assertThat(get(s, 0))
        .as("%s(%s, %s)", c.name(), top.toString(16), below.toString(16))
        .isEqualTo(c.reference().apply(top, below));
  }

  @Test
  void edgeCases() {
    for (final Case c : CASES) {
      for (final BigInteger a : EDGES) {
        for (final BigInteger b : EDGES) {
          check(c, a, b);
        }
      }
    }
  }

  @Test
  void randomOperands() {
    final Random random = new Random(91);
    for (final Case c : CASES) {
      for (int i = 0; i < 2000; i++) {
        final BigInteger a = new BigInteger(random.nextInt(257), random);
        final BigInteger b = new BigInteger(random.nextInt(257), random);
        check(c, a, b);
      }
    }
  }
}
