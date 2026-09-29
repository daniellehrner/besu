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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.UInt256;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Static utility operating directly on the flat {@code long[]} operand stack. Each slot occupies 4
 * consecutive longs in big-endian limb order: {@code [u3, u2, u1, u0]} where u3 is the most
 * significant limb.
 *
 * <p>All methods take {@code (long[] s, int top)} and return the new {@code top}. The caller
 * (operation) is responsible for underflow/overflow checks before calling.
 */
public class StackArithmetic {

  private static final VarHandle LONG_BE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
  private static final VarHandle INT_BE =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

  /** Utility class — not instantiable. */
  private StackArithmetic() {}

  // region Binary Arithmetic (pop 2, push 1, return top-1)
  // ---------------------------------------------------------------------------

  /**
   * MUL: s[top-2] = s[top-1] * s[top-2], return top-1. Delegates to UInt256 for now.
   *
   * @param s the stack array
   * @param top the current top index
   * @return the new top index
   */
  public static int mul(final long[] s, final int top) {
    final int a = (top - 1) << 2;
    final int b = (top - 2) << 2;
    UInt256 va = new UInt256(s[a], s[a + 1], s[a + 2], s[a + 3]);
    UInt256 vb = new UInt256(s[b], s[b + 1], s[b + 2], s[b + 3]);
    UInt256 r = va.mul(vb);
    s[b] = r.u3();
    s[b + 1] = r.u2();
    s[b + 2] = r.u1();
    s[b + 3] = r.u0();
    return top - 1;
  }

  /**
   * DIV: s[top-2] = s[top-1] / s[top-2], return top-1. Delegates to UInt256.
   *
   * @param s the stack array
   * @param top the current top index
   * @return the new top index
   */
  public static int div(final long[] s, final int top) {
    final int a = (top - 1) << 2;
    final int b = (top - 2) << 2;
    UInt256 va = new UInt256(s[a], s[a + 1], s[a + 2], s[a + 3]);
    UInt256 vb = new UInt256(s[b], s[b + 1], s[b + 2], s[b + 3]);
    UInt256 r = va.div(vb);
    s[b] = r.u3();
    s[b + 1] = r.u2();
    s[b + 2] = r.u1();
    s[b + 3] = r.u0();
    return top - 1;
  }

  /**
   * SDIV: s[top-2] = s[top-1] sdiv s[top-2], return top-1. Delegates to UInt256.
   *
   * @param s the stack array
   * @param top the current top index
   * @return the new top index
   */
  public static int signedDiv(final long[] s, final int top) {
    final int a = (top - 1) << 2;
    final int b = (top - 2) << 2;
    UInt256 va = new UInt256(s[a], s[a + 1], s[a + 2], s[a + 3]);
    UInt256 vb = new UInt256(s[b], s[b + 1], s[b + 2], s[b + 3]);
    UInt256 r = va.signedDiv(vb);
    s[b] = r.u3();
    s[b + 1] = r.u2();
    s[b + 2] = r.u1();
    s[b + 3] = r.u0();
    return top - 1;
  }

  /**
   * MOD: s[top-2] = s[top-1] mod s[top-2], return top-1. Delegates to UInt256.
   *
   * @param s the stack array
   * @param top the current top index
   * @return the new top index
   */
  public static int mod(final long[] s, final int top) {
    final int a = (top - 1) << 2;
    final int b = (top - 2) << 2;
    UInt256 va = new UInt256(s[a], s[a + 1], s[a + 2], s[a + 3]);
    UInt256 vb = new UInt256(s[b], s[b + 1], s[b + 2], s[b + 3]);
    UInt256 r = va.mod(vb);
    s[b] = r.u3();
    s[b + 1] = r.u2();
    s[b + 2] = r.u1();
    s[b + 3] = r.u0();
    return top - 1;
  }

  /**
   * SMOD: s[top-2] = s[top-1] smod s[top-2], return top-1. Delegates to UInt256.
   *
   * @param s the stack array
   * @param top the current top index
   * @return the new top index
   */
  public static int signedMod(final long[] s, final int top) {
    final int a = (top - 1) << 2;
    final int b = (top - 2) << 2;
    UInt256 va = new UInt256(s[a], s[a + 1], s[a + 2], s[a + 3]);
    UInt256 vb = new UInt256(s[b], s[b + 1], s[b + 2], s[b + 3]);
    UInt256 r = va.signedMod(vb);
    s[b] = r.u3();
    s[b + 1] = r.u2();
    s[b + 2] = r.u1();
    s[b + 3] = r.u0();
    return top - 1;
  }

  /**
   * EXP: s[top-2] = s[top-1] ** s[top-2] mod 2^256, return top-1.
   *
   * @param s the stack array
   * @param top the current top index
   * @return the new top index
   */
  public static int exp(final long[] s, final int top) {
    final int a = (top - 1) << 2; // base
    final int b = (top - 2) << 2; // exponent
    UInt256 base = new UInt256(s[a], s[a + 1], s[a + 2], s[a + 3]);
    UInt256 power = new UInt256(s[b], s[b + 1], s[b + 2], s[b + 3]);
    BigInteger result = base.toBigInteger().modPow(power.toBigInteger(), BigInteger.TWO.pow(256));
    byte[] rBytes = result.toByteArray();
    if (rBytes.length > 32) {
      rBytes = Arrays.copyOfRange(rBytes, rBytes.length - 32, rBytes.length);
    } else if (rBytes.length < 32) {
      byte[] padded = new byte[32];
      System.arraycopy(rBytes, 0, padded, 32 - rBytes.length, rBytes.length);
      rBytes = padded;
    }
    UInt256 r = UInt256.fromBytesBE(rBytes);
    s[b] = r.u3();
    s[b + 1] = r.u2();
    s[b + 2] = r.u1();
    s[b + 3] = r.u0();
    return top - 1;
  }

  // endregion

  // region Bitwise Binary (pop 2, push 1, return top-1)
  // ---------------------------------------------------------------------------

  /**
   * BYTE: s[top-2] = byte at offset s[top-1] of s[top-2], return top-1.
   *
   * @param s the stack array
   * @param top the current top index
   * @return the new top index
   */
  public static int byte_(final long[] s, final int top) {
    final int a = (top - 1) << 2; // offset
    final int b = (top - 2) << 2; // value
    // offset must be 0..31
    if (s[a] != 0 || s[a + 1] != 0 || s[a + 2] != 0 || s[a + 3] < 0 || s[a + 3] >= 32) {
      s[b] = 0;
      s[b + 1] = 0;
      s[b + 2] = 0;
      s[b + 3] = 0;
      return top - 1;
    }
    int idx = (int) s[a + 3]; // 0..31, big-endian byte index
    // Determine which limb and bit position
    // byte 0 is the MSB of u3, byte 31 is the LSB of u0
    int limbIdx = idx >> 3; // which limb offset from base (0=u3, 3=u0)
    int byteInLimb = 7 - (idx & 7); // byte position within limb (7=MSB, 0=LSB)
    long limb = s[b + limbIdx];
    long result = (limb >>> (byteInLimb << 3)) & 0xFFL;
    s[b] = 0;
    s[b + 1] = 0;
    s[b + 2] = 0;
    s[b + 3] = result;
    return top - 1;
  }

  // endregion

  // region Unary Operations (pop 1, push 1, return top)
  // ---------------------------------------------------------------------------

  /**
   * CLZ: s[top-1] = count leading zeros of s[top-1], return top.
   *
   * @param s the stack array
   * @param top the current top index
   * @return the new top index
   */
  public static int clz(final long[] s, final int top) {
    final int a = (top - 1) << 2;
    int result;
    if (s[a] != 0) {
      result = Long.numberOfLeadingZeros(s[a]);
    } else if (s[a + 1] != 0) {
      result = 64 + Long.numberOfLeadingZeros(s[a + 1]);
    } else if (s[a + 2] != 0) {
      result = 128 + Long.numberOfLeadingZeros(s[a + 2]);
    } else {
      result = 192 + Long.numberOfLeadingZeros(s[a + 3]);
    }
    s[a] = 0;
    s[a + 1] = 0;
    s[a + 2] = 0;
    s[a + 3] = result;
    return top;
  }

  // endregion

  // region Ternary Operations (pop 3, push 1, return top-2)
  // ---------------------------------------------------------------------------

  /**
   * ADDMOD: s[top-3] = (s[top-1] + s[top-2]) mod s[top-3], return top-2.
   *
   * @param s the stack array
   * @param top the current top index
   * @return the new top index
   */
  public static int addMod(final long[] s, final int top) {
    final int a = (top - 1) << 2;
    final int b = (top - 2) << 2;
    final int c = (top - 3) << 2;
    UInt256 va = new UInt256(s[a], s[a + 1], s[a + 2], s[a + 3]);
    UInt256 vb = new UInt256(s[b], s[b + 1], s[b + 2], s[b + 3]);
    UInt256 vc = new UInt256(s[c], s[c + 1], s[c + 2], s[c + 3]);
    UInt256 r = vc.isZero() ? UInt256.ZERO : va.addMod(vb, vc);
    s[c] = r.u3();
    s[c + 1] = r.u2();
    s[c + 2] = r.u1();
    s[c + 3] = r.u0();
    return top - 2;
  }

  /**
   * MULMOD: s[top-3] = (s[top-1] * s[top-2]) mod s[top-3], return top-2.
   *
   * @param s the stack array
   * @param top the current top index
   * @return the new top index
   */
  public static int mulMod(final long[] s, final int top) {
    final int a = (top - 1) << 2;
    final int b = (top - 2) << 2;
    final int c = (top - 3) << 2;
    UInt256 va = new UInt256(s[a], s[a + 1], s[a + 2], s[a + 3]);
    UInt256 vb = new UInt256(s[b], s[b + 1], s[b + 2], s[b + 3]);
    UInt256 vc = new UInt256(s[c], s[c + 1], s[c + 2], s[c + 3]);
    UInt256 r = vc.isZero() ? UInt256.ZERO : va.mulMod(vb, vc);
    s[c] = r.u3();
    s[c + 1] = r.u2();
    s[c + 2] = r.u1();
    s[c + 3] = r.u0();
    return top - 2;
  }

  // endregion

  // region Stack Manipulation
  // ---------------------------------------------------------------------------

  /**
   * DUP: copy slot at depth to new top, return top+1. depth is 1-based (DUP1 = depth 1).
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 1-based depth of the slot to duplicate
   * @return the new top index
   */
  public static int dup(final long[] s, final int top, final int depth) {
    final int src = (top - depth) << 2;
    final int dst = top << 2;
    s[dst] = s[src];
    s[dst + 1] = s[src + 1];
    s[dst + 2] = s[src + 2];
    s[dst + 3] = s[src + 3];
    return top + 1;
  }

  /**
   * SWAP: swap top with slot at depth, return top. depth is 1-based (SWAP1 = depth 1).
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 1-based depth of the slot to swap with the top
   * @return the new top index
   */
  public static int swap(final long[] s, final int top, final int depth) {
    final int a = (top - 1) << 2;
    final int b = (top - 1 - depth) << 2;
    long t;
    t = s[a];
    s[a] = s[b];
    s[b] = t;
    t = s[a + 1];
    s[a + 1] = s[b + 1];
    s[b + 1] = t;
    t = s[a + 2];
    s[a + 2] = s[b + 2];
    s[b + 2] = t;
    t = s[a + 3];
    s[a + 3] = s[b + 3];
    s[b + 3] = t;
    return top;
  }

  /**
   * EXCHANGE: swap slot at n with slot at m (both 0-indexed from top), return top.
   *
   * @param s the stack array
   * @param top the current top index
   * @param n the 0-based index of the first slot (from top)
   * @param m the 0-based index of the second slot (from top)
   * @return the new top index
   */
  public static int exchange(final long[] s, final int top, final int n, final int m) {
    final int a = (top - 1 - n) << 2;
    final int b = (top - 1 - m) << 2;
    long t;
    t = s[a];
    s[a] = s[b];
    s[b] = t;
    t = s[a + 1];
    s[a + 1] = s[b + 1];
    s[b + 1] = t;
    t = s[a + 2];
    s[a + 2] = s[b + 2];
    s[b + 2] = t;
    t = s[a + 3];
    s[a + 3] = s[b + 3];
    s[b + 3] = t;
    return top;
  }

  /**
   * PUSH0: push zero, return top+1.
   *
   * @param s the stack array
   * @param top the current top index
   * @return the new top index
   */
  public static int pushZero(final long[] s, final int top) {
    final int dst = top << 2;
    s[dst] = 0;
    s[dst + 1] = 0;
    s[dst + 2] = 0;
    s[dst + 3] = 0;
    return top + 1;
  }

  /**
   * PUSH1..PUSH32: decode bytes from code into a new top slot, return top+1.
   *
   * @param s the stack array
   * @param top the current top index
   * @param code the bytecode array
   * @param start the start offset within the code array
   * @param len the number of bytes to push (1-32)
   * @return the new top index
   */
  public static int pushFromBytes(
      final long[] s, final int top, final byte[] code, final int start, final int len) {
    final int end = start + len;
    if (end <= code.length && start >= 8) {
      // Each limb is one aligned-to-the-end read; the highest limb reads up to seven bytes that
      // precede the immediate and masks them off, so no limb is assembled byte by byte. The
      // opcode byte guarantees at least one byte before the immediate, and start >= 8 keeps the
      // widest read in bounds. The rare cases live in their own method so this one stays small
      // enough for the JIT to inline at a hot call site.
      final int dst = top << 2;
      final int limbs = (len + 7) >> 3;
      final int rem = len - ((limbs - 1) << 3);
      final long mask = rem == 8 ? -1L : (1L << (rem << 3)) - 1L;
      switch (limbs) {
        case 1 -> {
          s[dst] = 0;
          s[dst + 1] = 0;
          s[dst + 2] = 0;
          s[dst + 3] = getLong(code, end - 8) & mask;
        }
        case 2 -> {
          s[dst] = 0;
          s[dst + 1] = 0;
          s[dst + 2] = getLong(code, end - 16) & mask;
          s[dst + 3] = getLong(code, end - 8);
        }
        case 3 -> {
          s[dst] = 0;
          s[dst + 1] = getLong(code, end - 24) & mask;
          s[dst + 2] = getLong(code, end - 16);
          s[dst + 3] = getLong(code, end - 8);
        }
        default -> {
          s[dst] = getLong(code, end - 32) & mask;
          s[dst + 1] = getLong(code, end - 24);
          s[dst + 2] = getLong(code, end - 16);
          s[dst + 3] = getLong(code, end - 8);
        }
      }
      return top + 1;
    }
    return pushFromBytesSlow(s, top, code, start, len);
  }

  /**
   * An immediate within the first bytes of the code, or truncated by the end of the code: the
   * missing bytes read as zero.
   */
  private static int pushFromBytesSlow(
      final long[] s, final int top, final byte[] code, final int start, final int len) {
    final int dst = top << 2;
    s[dst] = 0;
    s[dst + 1] = 0;
    s[dst + 2] = 0;
    s[dst + 3] = 0;
    if (start >= code.length) {
      return top + 1;
    }
    final int copyLen = Math.min(len, code.length - start);
    int bytePos = len - 1;
    for (int i = 0; i < copyLen; i++) {
      final int limbOffset = 3 - (bytePos >> 3);
      final int shift = (bytePos & 7) << 3;
      s[dst + limbOffset] |= (code[start + i] & 0xFFL) << shift;
      bytePos--;
    }
    return top + 1;
  }

  /**
   * Push a long value (GAS, NUMBER, etc.), return top+1.
   *
   * @param s the stack array
   * @param top the current top index
   * @param value the long value to push
   * @return the new top index
   */
  public static int pushLong(final long[] s, final int top, final long value) {
    final int dst = top << 2;
    s[dst] = 0;
    s[dst + 1] = 0;
    s[dst + 2] = 0;
    s[dst + 3] = value;
    return top + 1;
  }

  /**
   * Push a Wei value onto the stack, return top+1.
   *
   * @param s the stack array
   * @param top the current top index
   * @param value the Wei value to push
   * @return the new top index
   */
  public static int pushWei(final long[] s, final int top, final Wei value) {
    final int dst = top << 2;
    final byte[] bytes = value.toArrayUnsafe();
    s[dst] = getLong(bytes, 0);
    s[dst + 1] = getLong(bytes, 8);
    s[dst + 2] = getLong(bytes, 16);
    s[dst + 3] = getLong(bytes, 24);
    return top + 1;
  }

  /**
   * Push an Address (20 bytes), return top+1.
   *
   * @param s the stack array
   * @param top the current top index
   * @param addr the address to push
   * @return the new top index
   */
  public static int pushAddress(final long[] s, final int top, final Address addr) {
    final int dst = top << 2;
    byte[] bytes = addr.getBytes().toArrayUnsafe();
    // Address is 20 bytes: fits in u2(4 bytes) + u1(8 bytes) + u0(8 bytes)
    s[dst] = 0; // u3
    s[dst + 1] = getInt(bytes, 0) & 0xFFFFFFFFL; // u2 (top 4 bytes)
    s[dst + 2] = getLong(bytes, 4); // u1
    s[dst + 3] = getLong(bytes, 12); // u0
    return top + 1;
  }

  // endregion

  // region Boundary Helpers (read/write slots without changing top)
  // ---------------------------------------------------------------------------

  /**
   * Extract u0 (LSB limb) of slot at depth from top.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @return the least-significant limb of the slot
   */
  public static long longAt(final long[] s, final int top, final int depth) {
    return s[((top - 1 - depth) << 2) + 3];
  }

  /**
   * Check if slot at depth is zero.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @return true if the slot is zero
   */
  public static boolean isZeroAt(final long[] s, final int top, final int depth) {
    final int off = (top - 1 - depth) << 2;
    return (s[off] | s[off + 1] | s[off + 2] | s[off + 3]) == 0;
  }

  /**
   * Check if slot at depth fits in a non-negative int.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @return true if the slot value fits in a non-negative int
   */
  public static boolean fitsInInt(final long[] s, final int top, final int depth) {
    final int off = (top - 1 - depth) << 2;
    return s[off] == 0
        && s[off + 1] == 0
        && s[off + 2] == 0
        && s[off + 3] >= 0
        && s[off + 3] <= Integer.MAX_VALUE;
  }

  /**
   * Check if slot at depth fits in a non-negative long.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @return true if the slot value fits in a non-negative long
   */
  public static boolean fitsInLong(final long[] s, final int top, final int depth) {
    final int off = (top - 1 - depth) << 2;
    return s[off] == 0 && s[off + 1] == 0 && s[off + 2] == 0 && s[off + 3] >= 0;
  }

  /**
   * Clamp slot at depth to long, returning Long.MAX_VALUE if it doesn't fit in a non-negative long.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @return the slot value as a long, or Long.MAX_VALUE if it does not fit
   */
  public static long clampedToLong(final long[] s, final int top, final int depth) {
    final int off = (top - 1 - depth) << 2;
    if (s[off] != 0 || s[off + 1] != 0 || s[off + 2] != 0 || s[off + 3] < 0) {
      return Long.MAX_VALUE;
    }
    return s[off + 3];
  }

  /**
   * Clamp slot at depth to int, returning Integer.MAX_VALUE if it doesn't fit in a non-negative
   * int.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @return the slot value as an int, or Integer.MAX_VALUE if it does not fit
   */
  public static int clampedToInt(final long[] s, final int top, final int depth) {
    final int off = (top - 1 - depth) << 2;
    if (s[off] != 0
        || s[off + 1] != 0
        || s[off + 2] != 0
        || s[off + 3] < 0
        || s[off + 3] > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return (int) s[off + 3];
  }

  /**
   * Write 32 big-endian bytes from slot at depth into dst.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @param dst the destination byte array (must have at least 32 bytes)
   */
  public static void toBytesAt(final long[] s, final int top, final int depth, final byte[] dst) {
    final int off = (top - 1 - depth) << 2;
    longIntoBytes(dst, 0, s[off]);
    longIntoBytes(dst, 8, s[off + 1]);
    longIntoBytes(dst, 16, s[off + 2]);
    longIntoBytes(dst, 24, s[off + 3]);
  }

  /**
   * Write 32 big-endian bytes from slot at depth into dst starting at dstOff.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @param dst the destination byte array
   * @param dstOff the offset in the destination at which the word starts
   */
  public static void toBytesAt(
      final long[] s, final int top, final int depth, final byte[] dst, final int dstOff) {
    final int off = (top - 1 - depth) << 2;
    longIntoBytes(dst, dstOff, s[off]);
    longIntoBytes(dst, dstOff + 8, s[off + 1]);
    longIntoBytes(dst, dstOff + 16, s[off + 2]);
    longIntoBytes(dst, dstOff + 24, s[off + 3]);
  }

  /**
   * Read bytes into slot at depth from src[srcOff..srcOff+len). Pads with zeros.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @param src the source byte array
   * @param srcOff the start offset within the source array
   * @param len the number of bytes to read (up to 32)
   */
  public static void fromBytesAt(
      final long[] s,
      final int top,
      final int depth,
      final byte[] src,
      final int srcOff,
      final int len) {
    final int off = (top - 1 - depth) << 2;
    // Fast path: full 32-byte read — decode 8 bytes per limb
    if (len >= 32 && srcOff + 32 <= src.length) {
      s[off] = bytesToLong(src, srcOff);
      s[off + 1] = bytesToLong(src, srcOff + 8);
      s[off + 2] = bytesToLong(src, srcOff + 16);
      s[off + 3] = bytesToLong(src, srcOff + 24);
      return;
    }
    // Slow path: variable-length, byte-by-byte
    s[off] = 0;
    s[off + 1] = 0;
    s[off + 2] = 0;
    s[off + 3] = 0;
    int end = srcOff + Math.min(len, 32);
    if (end > src.length) end = src.length;
    int pos = 0;
    for (int i = srcOff; i < end; i++, pos++) {
      int limbIdx = pos >> 3;
      int shift = (7 - (pos & 7)) << 3;
      s[off + limbIdx] |= (src[i] & 0xFFL) << shift;
    }
  }

  /**
   * Extract 20-byte Address from slot at depth.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @return the address stored in the slot
   */
  public static Address toAddressAt(final long[] s, final int top, final int depth) {
    final int off = (top - 1 - depth) << 2;
    byte[] bytes = new byte[20];
    // u2 has top 4 bytes, u1 has next 8, u0 has last 8
    putInt(bytes, 0, (int) s[off + 1]);
    putLong(bytes, 4, s[off + 2]);
    putLong(bytes, 12, s[off + 3]);
    return Address.wrap(org.apache.tuweni.bytes.Bytes.wrap(bytes));
  }

  /**
   * Materialize UInt256 record from slot at depth (boundary only).
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @return the UInt256 value at the slot
   */
  public static UInt256 getAt(final long[] s, final int top, final int depth) {
    final int off = (top - 1 - depth) << 2;
    return new UInt256(s[off], s[off + 1], s[off + 2], s[off + 3]);
  }

  /**
   * Write a Wei value into slot at depth.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @param value the Wei value to write
   */
  public static void putWeiAt(final long[] s, final int top, final int depth, final Wei value) {
    final int off = (top - 1 - depth) << 2;
    final byte[] bytes = value.toArrayUnsafe();
    s[off] = getLong(bytes, 0);
    s[off + 1] = getLong(bytes, 8);
    s[off + 2] = getLong(bytes, 16);
    s[off + 3] = getLong(bytes, 24);
  }

  /**
   * Write UInt256 record into slot at depth.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @param val the UInt256 value to write
   */
  public static void putAt(final long[] s, final int top, final int depth, final UInt256 val) {
    final int off = (top - 1 - depth) << 2;
    s[off] = val.u3();
    s[off + 1] = val.u2();
    s[off + 2] = val.u1();
    s[off + 3] = val.u0();
  }

  /**
   * Write raw limbs into slot at depth.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @param u3 the most-significant limb
   * @param u2 the second limb
   * @param u1 the third limb
   * @param u0 the least-significant limb
   */
  public static void putAt(
      final long[] s,
      final int top,
      final int depth,
      final long u3,
      final long u2,
      final long u1,
      final long u0) {
    final int off = (top - 1 - depth) << 2;
    s[off] = u3;
    s[off + 1] = u2;
    s[off + 2] = u1;
    s[off + 3] = u0;
  }

  /**
   * Number of significant bytes in slot at depth. Used by EXP gas calculation.
   *
   * @param s the stack array
   * @param top the current top index
   * @param depth the 0-based depth from the top
   * @return the number of significant bytes in the slot
   */
  public static int byteLengthAt(final long[] s, final int top, final int depth) {
    final int off = (top - 1 - depth) << 2;
    if (s[off] != 0) return 24 + byteLen(s[off]);
    if (s[off + 1] != 0) return 16 + byteLen(s[off + 1]);
    if (s[off + 2] != 0) return 8 + byteLen(s[off + 2]);
    if (s[off + 3] != 0) return byteLen(s[off + 3]);
    return 0;
  }

  // endregion

  // region Private Helpers
  // ---------------------------------------------------------------------------

  private static long getLong(final byte[] b, final int off) {
    return (long) LONG_BE.get(b, off);
  }

  private static void putLong(final byte[] b, final int off, final long v) {
    LONG_BE.set(b, off, v);
  }

  private static int getInt(final byte[] b, final int off) {
    return (int) INT_BE.get(b, off);
  }

  private static void putInt(final byte[] b, final int off, final int v) {
    INT_BE.set(b, off, v);
  }

  /** Build a long from 1-8 big-endian bytes. */
  /** Decode 8 big-endian bytes from src[off] into a long. */
  private static long bytesToLong(final byte[] src, final int off) {
    return getLong(src, off);
  }

  private static void longIntoBytes(final byte[] bytes, final int offset, final long value) {
    putLong(bytes, offset, value);
  }

  private static int byteLen(final long v) {
    return (64 - Long.numberOfLeadingZeros(v) + 7) / 8;
  }

  // endregion
}
