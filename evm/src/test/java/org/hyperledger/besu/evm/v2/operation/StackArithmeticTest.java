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

import org.hyperledger.besu.evm.UInt256;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/** Compares StackArithmetic with the UInt256 methods it was extracted from. */
class StackArithmeticTest {

  private static final int ROUNDS = Integer.getInteger("stackArithmetic.rounds", 2_000_000);

  private static final long[] SPECIAL = {
    0L,
    1L,
    2L,
    3L,
    -1L,
    -2L,
    Long.MIN_VALUE,
    Long.MAX_VALUE,
    Long.MIN_VALUE + 1,
    0xFFFFFFFFL,
    0x100000000L,
    0x8000000000000001L,
    0xC000000000000000L,
  };

  private final SplittableRandom random = new SplittableRandom(0x5eed_1234_abcdL);

  private long limb() {
    return switch (random.nextInt(6)) {
      case 0, 1 -> SPECIAL[random.nextInt(SPECIAL.length)];
      case 2 -> random.nextLong() & 0xFFFFL;
      case 3 -> random.nextLong() | Long.MIN_VALUE;
      case 4 -> 1L << random.nextInt(64);
      default -> random.nextLong();
    };
  }

  /** A word of 64, 128, 192 or 256 bits, or 0, made of edge-heavy limbs. */
  private long[] word() {
    final long[] w = {limb(), limb(), limb(), limb()};
    final int drop = random.nextInt(5);
    for (int i = 0; i < drop && i < 4; i++) {
      w[i] = 0;
    }
    if (random.nextInt(16) == 0) {
      // a power of two
      final long[] p = new long[4];
      p[random.nextInt(4)] = 1L << random.nextInt(64);
      return p;
    }
    return w;
  }

  /** A second operand related to the first: equal, near it, or sharing some of its limbs. */
  private long[] related(final long[] a) {
    final long[] b = word();
    switch (random.nextInt(8)) {
      case 0 -> System.arraycopy(a, 0, b, 0, 4);
      case 1 -> {
        System.arraycopy(a, 0, b, 0, 4);
        b[3] += random.nextBoolean() ? 1 : -1;
      }
      case 2 -> {
        for (int i = 0; i < 4; i++) {
          if (random.nextBoolean()) b[i] = a[i];
        }
      }
      case 3 -> {
        // the top limbs equal: the division's quotient-overflow and add-back branches
        final int top = random.nextInt(4);
        for (int i = 0; i <= top; i++) {
          b[i] = a[i];
        }
        for (int i = top + 1; i < 4; i++) {
          b[i] = random.nextBoolean() ? -1L : 0L;
        }
      }
      default -> {}
    }
    return b;
  }

  private static UInt256 u(final long[] w) {
    return new UInt256(w[0], w[1], w[2], w[3]);
  }

  private static long[] stack(final long[]... words) {
    final long[] s = new long[words.length * 4];
    for (int i = 0; i < words.length; i++) {
      System.arraycopy(words[i], 0, s, i * 4, 4);
    }
    return s;
  }

  private static UInt256 at(final long[] s, final int o) {
    return new UInt256(s[o], s[o + 1], s[o + 2], s[o + 3]);
  }

  @Test
  void binaryOperationsMatchUInt256() {
    for (int i = 0; i < ROUNDS; i++) {
      final long[] a = word();
      final long[] b = random.nextBoolean() ? related(a) : word();
      final UInt256 ua = u(a), ub = u(b);
      final String ctx = ua.toHexString() + " " + ub.toHexString();

      long[] s = stack(a, b);
      StackArithmetic.add(s, 0, 4, 4);
      assertThat(at(s, 4)).as("add %s", ctx).isEqualTo(ua.add(ub));

      s = stack(a, b);
      StackArithmetic.sub(s, 0, 4, 4);
      assertThat(at(s, 4)).as("sub %s", ctx).isEqualTo(ua.sub(ub));

      s = stack(a, b);
      StackArithmetic.mul(s, 0, 4, 4);
      assertThat(at(s, 4)).as("mul %s", ctx).isEqualTo(ua.mul(ub));

      s = stack(a, b);
      StackArithmetic.div(s, 0, 4, 4);
      assertThat(at(s, 4)).as("div %s", ctx).isEqualTo(ua.div(ub));

      s = stack(a, b);
      StackArithmetic.signedDiv(s, 0, 4, 4);
      assertThat(at(s, 4)).as("sdiv %s", ctx).isEqualTo(ua.signedDiv(ub));

      s = stack(a, b);
      StackArithmetic.mod(s, 0, 4, 4);
      assertThat(at(s, 4)).as("mod %s", ctx).isEqualTo(ua.mod(ub));

      s = stack(a, b);
      StackArithmetic.signedMod(s, 0, 4, 4);
      assertThat(at(s, 4)).as("smod %s", ctx).isEqualTo(ua.signedMod(ub));
    }
  }

  @Test
  void modularOperationsMatchUInt256() {
    for (int i = 0; i < ROUNDS; i++) {
      final long[] m = word();
      final long[] a = random.nextBoolean() ? related(m) : word();
      final long[] b = random.nextBoolean() ? related(random.nextBoolean() ? a : m) : word();
      final UInt256 ua = u(a), ub = u(b), um = u(m);
      final String ctx = ua.toHexString() + " " + ub.toHexString() + " " + um.toHexString();

      long[] s = stack(a, b, m);
      StackArithmetic.addMod(s, 0, 4, 8, 8);
      assertThat(at(s, 8)).as("addmod %s", ctx).isEqualTo(ua.addMod(ub, um));

      s = stack(a, b, m);
      StackArithmetic.mulMod(s, 0, 4, 8, 8);
      assertThat(at(s, 8)).as("mulmod %s", ctx).isEqualTo(ua.mulMod(ub, um));
    }
  }

  @Test
  void mulModOfProductsNearTheModulusMatchesUInt256() {
    for (int i = 0; i < ROUNDS / 4; i++) {
      // factors whose product fits 256 bits, and a modulus at or next to that product
      final int bitsA = 1 + random.nextInt(200);
      final int bitsB = 1 + random.nextInt(255 - bitsA);
      final UInt256 a = u(word()).shiftRight(256 - bitsA);
      final UInt256 b = u(word()).shiftRight(256 - bitsB);
      final UInt256 product = a.mul(b);
      final UInt256 m =
          switch (random.nextInt(3)) {
            case 0 -> product;
            case 1 -> product.add(UInt256.ONE);
            default -> product.sub(UInt256.ONE);
          };
      final long[] s =
          stack(
              new long[] {a.u3(), a.u2(), a.u1(), a.u0()},
              new long[] {b.u3(), b.u2(), b.u1(), b.u0()},
              new long[] {m.u3(), m.u2(), m.u1(), m.u0()});
      StackArithmetic.mulMod(s, 0, 4, 8, 8);
      assertThat(at(s, 8))
          .as("mulmod %s %s %s", a.toHexString(), b.toHexString(), m.toHexString())
          .isEqualTo(a.mulMod(b, m));
    }
  }

  @Test
  void shiftsMatchUInt256() {
    for (int i = 0; i < ROUNDS; i++) {
      final long[] value = word();
      final long[] shift =
          switch (random.nextInt(4)) {
            case 0 -> new long[] {0, 0, 0, random.nextInt(257)};
            case 1 -> new long[] {0, 0, 0, 255 + random.nextInt(3)};
            case 2 -> new long[] {0, 0, 0, random.nextInt(4) * 64L + random.nextInt(2)};
            default -> word();
          };
      final UInt256 uv = u(value), us = u(shift);
      final String ctx = uv.toHexString() + " " + us.toHexString();
      final int amount = StackArithmetic.shiftAmount(stack(shift), 0);

      long[] s = stack(value);
      StackArithmetic.shl(s, 0, value[0], value[1], value[2], value[3], amount);
      assertThat(at(s, 0)).as("shl %s", ctx).isEqualTo(uv.shl(us));

      s = stack(value);
      StackArithmetic.sar(s, 0, value[0], value[1], value[2], value[3], amount, 0);
      assertThat(at(s, 0)).as("shr %s", ctx).isEqualTo(uv.shr(us));

      s = stack(value);
      StackArithmetic.sar(
          s, 0, value[0], value[1], value[2], value[3], amount, value[0] < 0 ? -1L : 0L);
      assertThat(at(s, 0)).as("sar %s", ctx).isEqualTo(uv.sar(us));
    }
  }

  @Test
  void powerMatchesUInt256() {
    for (int i = 0; i < ROUNDS / 20; i++) {
      final long[] base = word();
      final long[] exponent =
          random.nextBoolean() ? new long[] {0, 0, 0, random.nextInt(1 << 12)} : word();
      final int bits = bitLength(exponent);
      UInt256 expected = UInt256.ONE;
      for (int bit = bits - 1; bit >= 0; bit--) {
        expected = expected.mul(expected);
        if (((exponent[3 - (bit >>> 6)] >>> (bit & 63)) & 1L) != 0) {
          expected = expected.mul(u(base));
        }
      }
      final long[] s = stack(base, exponent);
      StackArithmetic.power(s, 0, 4, bits, 4);
      assertThat(at(s, 4))
          .as("exp %s %s", u(base).toHexString(), u(exponent).toHexString())
          .isEqualTo(expected);
    }
  }

  private static int bitLength(final long[] w) {
    for (int i = 0; i < 4; i++) {
      if (w[i] != 0) return (4 - i) * 64 - Long.numberOfLeadingZeros(w[i]);
    }
    return 0;
  }
}
