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
 * A fixed-size, immutable 256-bit unsigned integer backed by four {@code long} values.
 *
 * <p>This class is designed for high-performance applications such as Ethereum Virtual Machine
 * (EVM) execution, cryptographic arithmetic, and protocol internals. Internally, the word is
 * represented as four 64-bit fields, from most to least significant:
 *
 * <pre>
 *   l0 (bits 255..192), l1 (191..128), l2 (127..64), l3 (63..0)
 * </pre>
 *
 * <p>All arithmetic, logical, and modular operations are implemented using 64-bit math with full
 * carry/borrow handling. No {@link java.math.BigInteger} or heap allocation is used in core paths.
 * Bit-level access and constant-time operations are supported where relevant.
 *
 * <p>To keep the core class compact and JIT-friendly, operations are grouped in package-private
 * helper classes by type (e.g. {@code Word256Arithmetic}, {@code Word256Bitwise}). This design
 * avoids polluting the main API while enabling static dispatch and inlining.
 *
 * <p><strong>Key properties:</strong>
 *
 * <ul>
 *   <li>Immutable, thread-safe, allocation-free once constructed
 *   <li>Semantics match EVM 256-bit word arithmetic exactly
 *   <li>Supports all EVM-relevant operations: {@code ADD}, {@code MUL}, {@code MOD}, etc.
 * </ul>
 */
public final class Word256 {
  public static final Word256 ZERO = Word256Constants.ZERO;
  public static final Word256 ONE = Word256Constants.ONE;
  public static final Word256 MINUS_ONE = Word256Constants.MINUS_ONE;
  public static final Word256 MAX = Word256Constants.MAX;

  // Represents a 256-bit word as four 64-bit long values.
  // l3 is the least significant word, l0 is the most significant word.
  final long l0, l1, l2, l3;

  private byte[] bytesCache;

  public Word256(final long l0, final long l1, final long l2, final long l3) {
    this.l0 = l0;
    this.l1 = l1;
    this.l2 = l2;
    this.l3 = l3;
  }

  private Word256(final long l0, final long l1, final long l2, final long l3, final byte[] bytes) {
    this(l0, l1, l2, l3);
    this.bytesCache = bytes;
  }

  public static Word256 fromLong(final long value) {
    return new Word256(0L, 0L, 0L, value);
  }

  public static Word256 fromInt(final int value) {
    // Convert to long with sign extension
    return new Word256(0L, 0L, 0L, value);
  }

  /**
   * Creates a Word256 where only the lowest byte is set to the given value.
   *
   * @param b the byte value
   * @return Word256 with b in the least significant byte
   */
  public static Word256 fromByte(final byte b) {
    return new Word256(0L, 0L, 0L, b & 0xFFL);
  }

  public static Word256 fromBytes(final byte[] bytes) {
    if (bytes.length > 32) {
      throw new IllegalArgumentException("Word256 input must be at most 32 bytes");
    }

    final byte[] padded = new byte[32];
    System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);

    final long l0 = Word256Helpers.bytesToLong(padded, 0);  // MSB
    final long l1 = Word256Helpers.bytesToLong(padded, 8);
    final long l2 = Word256Helpers.bytesToLong(padded, 16);
    final long l3 = Word256Helpers.bytesToLong(padded, 24); // LSB

    return new Word256(l0, l1, l2, l3, padded);
  }

  /**
   * Returns a mask with all bits above (and including) the specified index set to 1, the rest 0.
   *
   * @param bitIndex index in range [0, 255]
   * @return Word256 mask
   */
  public static Word256 maskAbove(final int bitIndex) {
    if (bitIndex < 0 || bitIndex >= 256) {
      throw new IllegalArgumentException("bitIndex must be in [0, 255]");
    }
    if (bitIndex == 0) {
      return Word256.MINUS_ONE;
    }

    final int wordIndex = bitIndex / 64;
    final int bitInWord = bitIndex % 64;

    final long[] longs = new long[4];
    for (int i = 0; i < wordIndex; i++) {
      longs[i] = 0L;
    }
    longs[wordIndex] = -1L << (64 - bitInWord);
    for (int i = wordIndex + 1; i < 4; i++) {
      longs[i] = -1L;
    }

    return new Word256(longs[0], longs[1], longs[2], longs[3]);
  }

  /**
   * Returns a mask with all bits below the specified index set to 1, the rest 0.
   *
   * @param bitIndex index in range [0, 256]
   * @return Word256 mask
   */
  public static Word256 maskBelow(final int bitIndex) {
    if (bitIndex < 0 || bitIndex > 256) {
      throw new IllegalArgumentException("bitIndex must be in [0, 256]");
    }
    if (bitIndex == 256) {
      return MINUS_ONE;
    } else if (bitIndex == 0) {
      return Word256.ZERO;
    }

    final int wordIndex = bitIndex / 64;
    final int bitInWord = bitIndex % 64;

    final long[] longs = new long[4];
    for (int i = 0; i < wordIndex; i++) {
      longs[i] = -1L;
    }
    if (wordIndex < 4) {
      longs[wordIndex] = ~(-1L << (64 - bitInWord));
    }

    return new Word256(longs[0], longs[1], longs[2], longs[3]);
  }

  public long getL0() {
    return l0;
  }

  public long getL1() {
    return l1;
  }

  public long getL2() {
    return l2;
  }

  public long getL3() {
    return l3;
  }

  // Arithmetic operations
  public Word256 add(final Word256 other) {
    return Word256Arithmetic.add(this, other);
  }

  public Word256 sub(final Word256 other) {
    return Word256Arithmetic.subtract(this, other);
  }

  public Word256 negate() {
    return Word256Arithmetic.negate(this);
  }

  public Word256 abs() {
    return Word256Arithmetic.abs(this);
  }

  public Word256 mul(final Word256 other) {
    return Word256Arithmetic.mul(this, other);
  }

  public Word256 div(final Word256 divisor) {
    return Word256Arithmetic.divide(this, divisor);
  }

  public Word256 mod(final Word256 modulus) {
    return Word256Arithmetic.mod(this, modulus);
  }

  public Word256 sdiv(final Word256 divisor) {
    return Word256Arithmetic.sdiv(this, divisor);
  }

  public Word256 smod(final Word256 modulus) {
    return Word256Arithmetic.smod(this, modulus);
  }

  public Word256 addmod(final Word256 b, final Word256 modulus) {
    return Word256Arithmetic.addmod(this, b, modulus);
  }

  public Word256 mulmod(final Word256 b, final Word256 modulus) {
    return Word256Arithmetic.mulmod(this, b, modulus);
  }

  public Word256 exp(final Word256 exponent) {
    return Word256Arithmetic.exp(this, exponent);
  }

  public int compareUnsigned(final Word256 other) {
    return Word256Comparison.compareUnsigned(this, other);
  }

  public int compareSigned(final Word256 other) {
    return Word256Comparison.compareSigned(this, other);
  }

  public boolean isNegative() {
    return Word256Comparison.isNegative(this);
  }

  public boolean isZero() {
    return this.l0 == 0 && this.l1 == 0 && this.l2 == 0 && this.l3 == 0;
  }

  // Bitwise operations
  public Word256 shiftLeft1() {
    return Word256Bitwise.shiftLeft1(this);
  }

  public int getBit(final int index) {
    return Word256Bitwise.getBit(this, index);
  }

  public Word256 setBit(final int index) {
    return Word256Bitwise.setBit(this, index);
  }

  public Word256 and(final Word256 other) {
    return Word256Bitwise.and(this, other);
  }

  public Word256 or(final Word256 other) {
    return Word256Bitwise.or(this, other);
  }

  public Word256 xor(final Word256 other) {
    return Word256Bitwise.xor(this, other);
  }

  public Word256 not() {
    return Word256Bitwise.not(this);
  }

  public Word256 shl(final int shift) {
    return Word256Bitwise.shl(this, shift);
  }

  public Word256 shr(final int shift) {
    return Word256Bitwise.shr(this, shift);
  }

  public Word256 sar(final int shift) {
    return Word256Bitwise.sar(this, shift);
  }

  /** Returns true if the value fits in a signed 64-bit long. */
  public boolean fitsLong() {
    return l1 == 0 && l2 == 0 && l3 == 0;
  }

  /** Converts the value to a long if it fits, otherwise clamps to Long.MAX_VALUE. */
  public long toLong() {
    if (fitsLong()) {
      return l3;
    }
    return Long.MAX_VALUE;
  }

  /** Returns true if the value fits within a signed 32-bit int. */
  public boolean fitsInt() {
    return (l0 | l1 | l2) == 0 && (l3 >>> 31) == 0;
  }

  /** Converts the value to an int if it fits, otherwise clamps to Integer.MAX_VALUE. */
  public int toInt() {
    if (fitsInt()) {
      return (int) l3;
    }
    return Integer.MAX_VALUE;
  }

  /**
   * Returns the byte at the given index (0 = MSB, 31 = LSB).
   *
   * @param index the byte index (0–31)
   * @return byte value at that position
   */
  public byte get(final int index) {
    if (index < 0 || index >= 32) {
      throw new IndexOutOfBoundsException("Byte index must be in [0, 31]: " + index);
    }
    final int longIndex = index / 8;
    final int shift = 8 * (7 - (index % 8));
    final long value;
    switch (longIndex) {
      case 0:
        value = l3;
        break;
      case 1:
        value = l2;
        break;
      case 2:
        value = l1;
        break;
      case 3:
        value = l0;
        break;
      default:
        throw new IllegalStateException("Unexpected long index: " + longIndex);
    }
    return (byte) (value >>> shift);
  }

  /**
   * Converts this Word256 to a 32-byte big-endian Bytes instance.
   *
   * @return Bytes representation of the word
   */
  public byte[] toBytes() {
    if (bytesCache != null) {
      return bytesCache;
    }

    bytesCache = new byte[32];
    Word256Helpers.writeLongBE(bytesCache, 0, l0);  // MSB first
    Word256Helpers.writeLongBE(bytesCache, 8, l1);
    Word256Helpers.writeLongBE(bytesCache, 16, l2);
    Word256Helpers.writeLongBE(bytesCache, 24, l3); // LSB last

    return bytesCache;
  }

  public int[] toUInt32Array() {
    return new int[] {
      (int) (l0 & 0xFFFFFFFFL),
      (int) (l0 >>> 32),
      (int) (l1 & 0xFFFFFFFFL),
      (int) (l1 >>> 32),
      (int) (l2 & 0xFFFFFFFFL),
      (int) (l2 >>> 32),
      (int) (l3 & 0xFFFFFFFFL),
      (int) (l3 >>> 32),
    };
  }

  public long clampedToLong() {
    if (isZero()) {
      return 0;
    }
    if (l0 == 0 && l1 == 0 && l2 == 0) {
      return l3 >= 0 ? l3 : Long.MAX_VALUE;
    }
    return Long.MAX_VALUE;
  }

  public int clampedToInt() {
    if (isZero()) {
      return 0;
    }
    if (l0 == 0 && l1 == 0 && l2 == 0 && l3 >>> 32 == 0) {
      return (int) l3 >= 0 ? (int) l3 : Integer.MAX_VALUE;
    }
    return Integer.MAX_VALUE;
  }

  public byte getLeastSignificantByte() {
    return (byte) (l3 & 0xFF);
  }

  public int byteLength() {
    if (l0 != 0) {
      return 32 - Long.numberOfLeadingZeros(l0) / 8;
    } else if (l1 != 0) {
      return 24 - Long.numberOfLeadingZeros(l1) / 8;
    } else if (l2 != 0) {
      return 16 - Long.numberOfLeadingZeros(l2) / 8;
    } else if (l3 != 0) {
      return 8 - Long.numberOfLeadingZeros(l3) / 8;
    } else {
      return 0;
    }
  }

  public int bitLength() {
    if (l0 != 0) {
      return 256 - Long.numberOfLeadingZeros(l0);
    } else if (l1 != 0) {
      return 192 - Long.numberOfLeadingZeros(l1);
    } else if (l2 != 0) {
      return 128 - Long.numberOfLeadingZeros(l2);
    } else if (l3 != 0) {
      return 64 - Long.numberOfLeadingZeros(l3);
    } else {
      return 0;
    }
  }

  /**
   * Returns the number of leading zero bits in this 256-bit word.
   *
   * @return number of leading zeros (0–256)
   */
  public int clz() {
    if (l0 != 0) {
      return Long.numberOfLeadingZeros(l0);
    } else if (l1 != 0) {
      return 64 + Long.numberOfLeadingZeros(l1);
    } else if (l2 != 0) {
      return 128 + Long.numberOfLeadingZeros(l2);
    } else if (l3 != 0) {
      return 192 + Long.numberOfLeadingZeros(l3);
    } else {
      return 256;
    }
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof final Word256 other)) {
      return false;
    }

    return this.l0 == other.l0 && this.l1 == other.l1 && this.l2 == other.l2 && this.l3 == other.l3;
  }

  @Override
  public int hashCode() {
    // Based on java.util.Arrays.hashCode(long[])
    int result = Long.hashCode(l0);
    result = 31 * result + Long.hashCode(l1);
    result = 31 * result + Long.hashCode(l2);
    result = 31 * result + Long.hashCode(l3);
    return result;
  }

  @Override
  public String toString() {
    byte[] b = toBytes();
    StringBuilder sb = new StringBuilder(66);
    sb.append("0x");

    for (int i = 0; i < 32; i++) {
      int val = b[i] & 0xFF;
      if (val < 0x10) {
        sb.append('0');
      }
      sb.append(Integer.toHexString(val));
    }

    return sb.toString();
  }
}
