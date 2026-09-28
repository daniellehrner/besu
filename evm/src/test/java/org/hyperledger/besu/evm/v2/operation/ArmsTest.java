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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.evm.v2.operation.Arms.FALLBACK;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

/**
 * Checks the arms of the untraced v2 loop against BigInteger. The loop runs these methods' bodies
 * and the operation classes call the methods, so this covers both.
 */
class ArmsTest {

  private static final BigInteger MOD = BigInteger.ONE.shiftLeft(256);
  private static final BigInteger MAX = MOD.subtract(BigInteger.ONE);
  private static final BigInteger WORD = BigInteger.ONE.shiftLeft(64);
  private static final BigInteger B256 = BigInteger.valueOf(256);

  /** An arm that reads only the stack and the gas. */
  interface StackArm {
    long run(long[] s, int sp, int top, long gas);
  }

  /**
   * A two-operand arm and what it computes from the top item and the one below it. It may leave the
   * operands for which {@code wide} holds to the operation's class.
   */
  private record Binary(
      String name,
      StackArm arm,
      long cost,
      BinaryOperator<BigInteger> reference,
      BiPredicate<BigInteger, BigInteger> wide) {

    Binary(
        final String name,
        final StackArm arm,
        final long cost,
        final BinaryOperator<BigInteger> reference) {
      this(name, arm, cost, reference, (a, b) -> false);
    }
  }

  private record Unary(String name, StackArm arm, long cost, UnaryOperator<BigInteger> reference) {}

  private static BigInteger signed(final BigInteger v) {
    return v.testBit(255) ? v.subtract(MOD) : v;
  }

  private static BigInteger unsigned(final BigInteger v) {
    return v.signum() < 0 ? v.add(MOD) : v;
  }

  private static BigInteger bit(final boolean value) {
    return value ? BigInteger.ONE : BigInteger.ZERO;
  }

  private static final List<Binary> BINARY =
      List.of(
          new Binary("add", AddOperationV2::add, 3, (a, b) -> a.add(b).mod(MOD)),
          new Binary("sub", SubOperationV2::sub, 3, (a, b) -> a.subtract(b).mod(MOD)),
          new Binary("and", AndOperationV2::and, 3, BigInteger::and),
          new Binary("or", OrOperationV2::or, 3, BigInteger::or),
          new Binary("xor", XorOperationV2::xor, 3, BigInteger::xor),
          new Binary("eq", EqOperationV2::eq, 3, (a, b) -> bit(a.equals(b))),
          new Binary(
              "lt",
              (s, sp, top, gas) -> LtOperationV2.compare(s, sp, top, 0x10, gas),
              3,
              (a, b) -> bit(a.compareTo(b) < 0)),
          new Binary(
              "gt",
              (s, sp, top, gas) -> LtOperationV2.compare(s, sp, top, 0x11, gas),
              3,
              (a, b) -> bit(a.compareTo(b) > 0)),
          new Binary(
              "slt",
              (s, sp, top, gas) -> LtOperationV2.compare(s, sp, top, 0x12, gas),
              3,
              (a, b) -> bit(signed(a).compareTo(signed(b)) < 0)),
          new Binary(
              "sgt",
              (s, sp, top, gas) -> LtOperationV2.compare(s, sp, top, 0x13, gas),
              3,
              (a, b) -> bit(signed(a).compareTo(signed(b)) > 0)),
          new Binary(
              "shl",
              (s, sp, top, gas) -> ShlOperationV2.shift(s, sp, top, 0x1b, true, gas),
              3,
              (a, b) ->
                  a.compareTo(B256) >= 0 ? BigInteger.ZERO : b.shiftLeft(a.intValue()).mod(MOD)),
          new Binary(
              "shr",
              (s, sp, top, gas) -> ShlOperationV2.shift(s, sp, top, 0x1c, true, gas),
              3,
              (a, b) -> a.compareTo(B256) >= 0 ? BigInteger.ZERO : b.shiftRight(a.intValue())),
          new Binary(
              "sar",
              (s, sp, top, gas) -> ShlOperationV2.shift(s, sp, top, 0x1d, true, gas),
              3,
              (a, b) ->
                  unsigned(signed(b).shiftRight(a.compareTo(B256) >= 0 ? 256 : a.intValue()))),
          new Binary(
              "signextend",
              SignExtendOperationV2::signextend,
              5,
              (a, b) -> {
                if (a.compareTo(BigInteger.valueOf(31)) >= 0) {
                  return b;
                }
                final int bit = a.intValue() * 8 + 7;
                final BigInteger mask = BigInteger.ONE.shiftLeft(bit + 1).subtract(BigInteger.ONE);
                return b.testBit(bit) ? b.or(mask.not().and(MAX)) : b.and(mask);
              }),
          new Binary(
              "mul",
              MulOperationV2::mul,
              5,
              (a, b) -> a.multiply(b).mod(MOD),
              (a, b) -> a.compareTo(WORD) >= 0 && b.compareTo(WORD) >= 0),
          new Binary(
              "div",
              (s, sp, top, gas) -> DivOperationV2.divMod(s, sp, top, 0x04, gas),
              5,
              (a, b) -> b.signum() == 0 ? BigInteger.ZERO : a.divide(b),
              (a, b) -> a.compareTo(WORD) >= 0 || b.compareTo(WORD) >= 0),
          new Binary(
              "mod",
              (s, sp, top, gas) -> DivOperationV2.divMod(s, sp, top, 0x06, gas),
              5,
              (a, b) -> b.signum() == 0 ? BigInteger.ZERO : a.mod(b),
              (a, b) -> a.compareTo(WORD) >= 0 || b.compareTo(WORD) >= 0));

  private static final List<Unary> UNARY =
      List.of(
          new Unary("iszero", IsZeroOperationV2::iszero, 3, a -> bit(a.signum() == 0)),
          new Unary("not", NotOperationV2::not, 3, a -> a.xor(MAX)));

  private static final BigInteger[] EDGES = {
    BigInteger.ZERO,
    BigInteger.ONE,
    BigInteger.TWO,
    BigInteger.valueOf(7),
    BigInteger.valueOf(30),
    BigInteger.valueOf(31),
    BigInteger.valueOf(32),
    BigInteger.valueOf(63),
    BigInteger.valueOf(64),
    BigInteger.valueOf(255),
    BigInteger.valueOf(256),
    BigInteger.ONE.shiftLeft(63),
    WORD.subtract(BigInteger.ONE),
    WORD,
    BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE),
    BigInteger.ONE.shiftLeft(128),
    BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE),
    BigInteger.ONE.shiftLeft(255),
    MAX,
  };

  private static List<BigInteger> operands() {
    final List<BigInteger> operands = new ArrayList<>(List.of(EDGES));
    final Random random = new Random(17);
    for (int i = 0; i < 300; i++) {
      operands.add(new BigInteger(random.nextInt(257), random));
    }
    return operands;
  }

  private static void put(final long[] s, final int slot, final BigInteger v) {
    for (int limb = 0; limb < 4; limb++) {
      s[slot * 4 + 3 - limb] = v.shiftRight(64 * limb).longValue();
    }
  }

  private static BigInteger get(final long[] s, final int slot) {
    BigInteger v = BigInteger.ZERO;
    for (int limb = 0; limb < 4; limb++) {
      v = v.shiftLeft(64).or(new BigInteger(Long.toUnsignedString(s[slot * 4 + limb])));
    }
    return v;
  }

  @Test
  void binaryArmsComputeWhatTheOperationDoes() {
    final List<BigInteger> operands = operands();
    for (final Binary op : BINARY) {
      for (final BigInteger a : operands) {
        for (final BigInteger b : operands) {
          final long[] s = new long[16];
          put(s, 0, b);
          put(s, 1, a);
          final long outcome = op.arm().run(s, 2, 4, op.cost());
          final String what = op.name() + "(" + a.toString(16) + ", " + b.toString(16) + ")";
          if (outcome == FALLBACK && op.wide().test(a, b)) {
            // the operation's class computes these, from untouched operands
            assertThat(get(s, 0)).as(what).isEqualTo(b);
            assertThat(get(s, 1)).as(what).isEqualTo(a);
            continue;
          }
          assertThat(outcome).as(what).isNotEqualTo(FALLBACK);
          assertThat(Arms.cost(outcome)).as(what).isEqualTo(op.cost());
          assertThat(Arms.step(outcome)).as(what).isEqualTo(1);
          assertThat(Arms.delta(outcome)).as(what).isEqualTo(-1);
          assertThat(get(s, 0)).as(what).isEqualTo(op.reference().apply(a, b));
        }
      }
    }
  }

  @Test
  void unaryArmsComputeWhatTheOperationDoes() {
    for (final Unary op : UNARY) {
      for (final BigInteger a : operands()) {
        final long[] s = new long[8];
        put(s, 0, a);
        final long outcome = op.arm().run(s, 1, 0, op.cost());
        final String what = op.name() + "(" + a.toString(16) + ")";
        assertThat(outcome).as(what).isNotEqualTo(FALLBACK);
        assertThat(Arms.cost(outcome)).as(what).isEqualTo(op.cost());
        assertThat(Arms.delta(outcome)).as(what).isZero();
        assertThat(get(s, 0)).as(what).isEqualTo(op.reference().apply(a));
      }
    }
  }

  @Test
  void armsLeaveMissingOperandsAndMissingGasToTheOperation() {
    for (final Binary op : BINARY) {
      final long[] s = new long[16];
      put(s, 0, BigInteger.TWO);
      put(s, 1, BigInteger.ONE);
      assertThat(op.arm().run(s, 1, 0, op.cost())).as(op.name()).isEqualTo(FALLBACK);
      assertThat(op.arm().run(s, 2, 4, op.cost() - 1)).as(op.name()).isEqualTo(FALLBACK);
      assertThat(get(s, 0)).as(op.name()).isEqualTo(BigInteger.TWO);
      assertThat(get(s, 1)).as(op.name()).isEqualTo(BigInteger.ONE);
    }
    for (final Unary op : UNARY) {
      final long[] s = new long[8];
      assertThat(op.arm().run(s, 0, -4, op.cost())).as(op.name()).isEqualTo(FALLBACK);
      assertThat(op.arm().run(s, 1, 0, op.cost() - 1)).as(op.name()).isEqualTo(FALLBACK);
    }
  }

  @Test
  void dupAndSwapReachEveryDepth() {
    for (int depth = 1; depth <= 16; depth++) {
      // seventeen items, item i holding 100 + i, and room for one more
      final long[] s = new long[18 * 4];
      for (int slot = 0; slot < 17; slot++) {
        put(s, slot, BigInteger.valueOf(slot + 100));
      }
      final long dup = DupOperationV2.dup(s, 17, 17 << 2, 0x7f + depth, 3);
      assertThat(Arms.delta(dup)).isEqualTo(1);
      assertThat(get(s, 17)).as("DUP%d", depth).isEqualTo(BigInteger.valueOf(117 - depth));

      final long swap = SwapOperationV2.swap(s, 17, 16 << 2, 0x8f + depth, 3);
      assertThat(Arms.delta(swap)).isZero();
      assertThat(get(s, 16)).as("SWAP%d", depth).isEqualTo(BigInteger.valueOf(116 - depth));
      assertThat(get(s, 16 - depth)).as("SWAP%d", depth).isEqualTo(BigInteger.valueOf(116));
    }
  }

  @Test
  void outcomesCarryNegativeStepsAndDeltas() {
    final long outcome = Arms.done(14, -70_000, -2);
    assertThat(Arms.cost(outcome)).isEqualTo(14);
    assertThat(Arms.step(outcome)).isEqualTo(-70_000);
    assertThat(Arms.delta(outcome)).isEqualTo(-2);
    assertThat(outcome).isNotEqualTo(FALLBACK);
  }
}
