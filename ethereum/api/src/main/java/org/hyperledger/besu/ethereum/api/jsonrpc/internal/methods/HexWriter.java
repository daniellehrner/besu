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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Zero-allocation hex encoding of byte arrays directly into an output buffer. Provides multiple
 * encoding strategies with different performance characteristics, all producing identical output.
 *
 * <p>All methods write a {@code 0x}-prefixed, leading-zero-stripped hex string into {@code dest}
 * starting at {@code destPos} and return the new write position. The caller must ensure {@code dest}
 * has enough room: at most {@code 2 + len * 2} bytes.
 *
 * <p>Candidates (see {@code CompactHexBenchmark} for numbers):
 *
 * <ul>
 *   <li>{@link #nibblePair} — per-nibble loop with branch to skip leading zeros; used as the
 *       baseline.
 *   <li>{@link #bytePair} — byte-pair lookup table, two byte-stores per input byte; eliminates
 *       per-nibble branching.
 *   <li>{@link #shortPack} — short-wide store via {@link VarHandle}; halves the number of store
 *       instructions compared to {@link #bytePair}.
 *   <li>{@link #intPack} — int-wide store (4 hex chars / 2 input bytes at once); further reduces
 *       store count for large values.
 * </ul>
 */
public final class HexWriter {

  private static final byte[] HEX = {
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };

  /** byte value → two adjacent hex ASCII bytes: {@code HEX_PAIR[(b & 0xFF) << 1]} is high nibble. */
  static final byte[] HEX_PAIR = new byte[512];

  /** byte value → two hex ASCII bytes packed as a native-endian {@code short}. */
  static final short[] HEX_SHORT = new short[256];

  private static final VarHandle SHORT_HANDLE =
      MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.nativeOrder());

  private static final VarHandle INT_HANDLE =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());

  static {
    for (int i = 0; i < 256; i++) {
      final byte hi = HEX[(i >> 4) & 0xF];
      final byte lo = HEX[i & 0xF];
      HEX_PAIR[i << 1] = hi;
      HEX_PAIR[(i << 1) + 1] = lo;
      if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
        HEX_SHORT[i] = (short) ((lo << 8) | (hi & 0xFF));
      } else {
        HEX_SHORT[i] = (short) ((hi << 8) | (lo & 0xFF));
      }
    }
  }

  private HexWriter() {}

  // ── Candidate 1: nibblePair (baseline) ──────────────────────────────
  //
  // Encodes each input byte by looking up the high and low nibbles separately in
  // a 16-entry HEX table. Two conditional branches per byte are used to detect
  // and skip leading zeros.
  //
  // Characteristics:
  //   + Simple, no VarHandle / endianness concerns
  //   − Two unpredictable branches per byte in the leading-zero region
  //   − Requires an intermediate buffer (hexBuf) + System.arraycopy to the output

  /**
   * Encodes {@code bytes[0..len)} into {@code hexBuf} as {@code 0x}-prefixed hex with leading zeros
   * stripped, then copies the result into {@code dest} at {@code destPos}.
   *
   * @return the new write position in {@code dest}
   */
  public static int nibblePair(
      final byte[] bytes,
      final int len,
      final byte[] hexBuf,
      final byte[] dest,
      final int destPos) {
    final int hexLen = nibblePairToHexBuf(bytes, len, hexBuf);
    System.arraycopy(hexBuf, 0, dest, destPos, hexLen);
    return destPos + hexLen;
  }

  /**
   * Writes {@code 0x}-prefixed, leading-zero-stripped hex into {@code hexBuf}.
   *
   * @return the number of bytes written
   */
  public static int nibblePairToHexBuf(final byte[] bytes, final int len, final byte[] hexBuf) {
    int pos = 0;
    hexBuf[pos++] = '0';
    hexBuf[pos++] = 'x';
    if (len == 0) {
      hexBuf[pos++] = '0';
      return pos;
    }
    boolean leadingZero = true;
    for (int i = 0; i < len; i++) {
      final byte b = bytes[i];
      final int hi = (b >> 4) & 0xF;
      if (!leadingZero || hi != 0) {
        hexBuf[pos++] = HEX[hi];
        leadingZero = false;
      }
      final int lo = b & 0xF;
      if (!leadingZero || lo != 0 || i == len - 1) {
        hexBuf[pos++] = HEX[lo];
        leadingZero = false;
      }
    }
    return pos;
  }

  // ── Candidate 2: bytePair ──────────────────────────────────────────
  //
  // Uses a 512-byte lookup table (HEX_PAIR) to convert each input byte to two
  // hex ASCII bytes in one table access. Leading zeros are skipped by scanning
  // for the first non-zero byte, then emitting its nibbles individually. All
  // subsequent bytes use the branchless table lookup.
  //
  // Characteristics:
  //   + No per-nibble branching in the hot loop
  //   + Writes directly to the output buffer (no intermediate copy)
  //   − Two byte-stores per input byte

  /**
   * Encodes {@code bytes[0..len)} directly into {@code dest} at {@code destPos} as {@code
   * 0x}-prefixed hex with leading zeros stripped.
   *
   * @return the new write position in {@code dest}
   */
  public static int bytePair(
      final byte[] bytes, final int len, final byte[] dest, final int destPos) {
    int wp = destPos;
    dest[wp++] = '0';
    dest[wp++] = 'x';

    if (len == 0) {
      dest[wp++] = '0';
      return wp;
    }

    int start = 0;
    while (start < len && bytes[start] == 0) start++;

    if (start == len) {
      dest[wp++] = '0';
      return wp;
    }

    // First non-zero byte — emit nibbles individually to strip a leading zero nibble
    int idx = (bytes[start] & 0xFF) << 1;
    if (HEX_PAIR[idx] != '0') {
      dest[wp++] = HEX_PAIR[idx];
    }
    dest[wp++] = HEX_PAIR[idx + 1];
    start++;

    // Remaining bytes — branchless: two byte-stores per input byte
    for (int i = start; i < len; i++) {
      idx = (bytes[i] & 0xFF) << 1;
      dest[wp++] = HEX_PAIR[idx];
      dest[wp++] = HEX_PAIR[idx + 1];
    }
    return wp;
  }

  // ── Candidate 3: shortPack ─────────────────────────────────────────
  //
  // Extends bytePair by packing both hex ASCII chars into a native-endian short
  // and writing them with a single VarHandle.set(). This halves the number of
  // store instructions in the hot loop.
  //
  // Characteristics:
  //   + One store per input byte instead of two
  //   + Writes directly to output buffer
  //   − Requires VarHandle (Java 9+) and endianness-aware lookup table
  //   − First non-zero byte still uses byte-level writes

  /**
   * Encodes {@code bytes[0..len)} directly into {@code dest} at {@code destPos} as {@code
   * 0x}-prefixed hex with leading zeros stripped. Uses short-wide stores for the bulk loop.
   *
   * @return the new write position in {@code dest}
   */
  public static int shortPack(
      final byte[] bytes, final int len, final byte[] dest, final int destPos) {
    int wp = destPos;
    dest[wp++] = '0';
    dest[wp++] = 'x';

    if (len == 0) {
      dest[wp++] = '0';
      return wp;
    }

    int start = 0;
    while (start < len && bytes[start] == 0) start++;

    if (start == len) {
      dest[wp++] = '0';
      return wp;
    }

    // First non-zero byte — emit nibbles individually to strip a leading zero nibble
    int idx = (bytes[start] & 0xFF) << 1;
    if (HEX_PAIR[idx] != '0') {
      dest[wp++] = HEX_PAIR[idx];
    }
    dest[wp++] = HEX_PAIR[idx + 1];
    start++;

    // Remaining bytes — one short-wide store (2 hex chars) per input byte
    for (int i = start; i < len; i++) {
      SHORT_HANDLE.set(dest, wp, HEX_SHORT[bytes[i] & 0xFF]);
      wp += 2;
    }
    return wp;
  }

  // ── Candidate 4: intPack ───────────────────────────────────────────
  //
  // Processes two input bytes at a time, packing four hex ASCII chars into a
  // native-endian int and writing them with a single VarHandle.set(). Falls back
  // to a short-wide store for an odd trailing byte.
  //
  // Characteristics:
  //   + One store per two input bytes (half the stores of shortPack)
  //   + Best throughput for large values (32 bytes)
  //   − More complex loop structure (pair + remainder)
  //   − Endianness packing logic for both short and int

  /**
   * Encodes {@code bytes[0..len)} directly into {@code dest} at {@code destPos} as {@code
   * 0x}-prefixed hex with leading zeros stripped. Uses int-wide stores for the bulk loop.
   *
   * @return the new write position in {@code dest}
   */
  public static int intPack(
      final byte[] bytes, final int len, final byte[] dest, final int destPos) {
    int wp = destPos;
    dest[wp++] = '0';
    dest[wp++] = 'x';

    if (len == 0) {
      dest[wp++] = '0';
      return wp;
    }

    int start = 0;
    while (start < len && bytes[start] == 0) start++;

    if (start == len) {
      dest[wp++] = '0';
      return wp;
    }

    // First non-zero byte — emit nibbles individually to strip a leading zero nibble
    int idx = (bytes[start] & 0xFF) << 1;
    if (HEX_PAIR[idx] != '0') {
      dest[wp++] = HEX_PAIR[idx];
    }
    dest[wp++] = HEX_PAIR[idx + 1];
    start++;

    // Pairs of bytes — one int-wide store (4 hex chars) per 2 input bytes
    final int pairEnd = start + ((len - start) & ~1);
    for (int i = start; i < pairEnd; i += 2) {
      final short s0 = HEX_SHORT[bytes[i] & 0xFF];
      final short s1 = HEX_SHORT[bytes[i + 1] & 0xFF];
      final int packed;
      if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
        packed = (s0 & 0xFFFF) | ((s1 & 0xFFFF) << 16);
      } else {
        packed = ((s0 & 0xFFFF) << 16) | (s1 & 0xFFFF);
      }
      INT_HANDLE.set(dest, wp, packed);
      wp += 4;
    }

    // Odd trailing byte
    if (pairEnd < len) {
      SHORT_HANDLE.set(dest, wp, HEX_SHORT[bytes[pairEnd] & 0xFF]);
      wp += 2;
    }
    return wp;
  }
}
