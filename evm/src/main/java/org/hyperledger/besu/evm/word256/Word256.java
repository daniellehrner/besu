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
 * represented as four 64-bit fields, from least to most significant:
 *
 * <pre>
 *   l0 (bits 63..0), l1 (127..64), l2 (191..128), l3 (255..192)
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
  /** Zero value for Word256 */
  public static final Word256 ZERO = Word256Constants.ZERO;

  /** One value for Word256 */
  public static final Word256 ONE = Word256Constants.ONE;

  /** Negative one value for Word256 */
  public static final Word256 MINUS_ONE = Word256Constants.MINUS_ONE;

  /** Maximum value for Word256 */
  public static final Word256 MAX = Word256Constants.MAX;

  // Least significant to most significant: little-endian layout
  final long l0, l1, l2, l3;

  private byte[] bytesCache;

  /**
   * Constructs a Word256 from four long values.
   *
   * <p>Each long represents 64 bits of the 256-bit word, with {@code l0} being the least
   * significant and {@code l3} being the most significant.
   *
   * @param l0 the least significant 64 bits
   * @param l1 the next 64 bits
   * @param l2 the next 64 bits
   * @param l3 the most significant 64 bits
   */
  Word256(final long l0, final long l1, final long l2, final long l3) {
    this.l0 = l0;
    this.l1 = l1;
    this.l2 = l2;
    this.l3 = l3;
  }

  /**
   * Constructs a Word256 from four long values and a byte array.
   *
   * <p>This constructor is used internally to create a Word256 with a cached byte array
   * representation. The byte array must be exactly 32 bytes long, representing the 256-bit word.
   *
   * @param l0 the least significant 64 bits
   * @param l1 the next 64 bits
   * @param l2 the next 64 bits
   * @param l3 the most significant 64 bits
   * @param bytes the byte array representation of the Word256
   */
  private Word256(final long l0, final long l1, final long l2, final long l3, final byte[] bytes) {
    this(l0, l1, l2, l3);
    this.bytesCache = bytes;
  }

  /**
   * Creates a Word256 from a long value.
   *
   * @param value the long value to convert
   * @return a new Word256 instance representing the long value
   */
  public static Word256 fromLong(final long value) {
    return new Word256(value, 0L, 0L, 0L);
  }

  /**
   * Creates a Word256 from an int value.
   *
   * @param value the int value to convert
   * @return a new Word256 instance representing the int value
   */
  public static Word256 fromInt(final int value) {
    return new Word256(value, 0L, 0L, 0L);
  }

  /**
   * Creates a Word256 from a byte value.
   *
   * @param b the byte value to convert
   * @return a new Word256 instance representing the byte value
   */
  public static Word256 fromByte(final byte b) {
    return new Word256(b & 0xFFL, 0L, 0L, 0L);
  }

  /**
   * Creates a Word256 from a byte array.
   *
   * <p>The byte array must be at most 32 bytes long. If it is shorter, it will be padded with
   * leading zeros to fit the 256-bit representation.
   *
   * @param bytes the byte array to convert
   * @return a new Word256 instance representing the byte array
   * @throws IllegalArgumentException if the byte array is longer than 32 bytes
   */
  public static Word256 fromBytes(final byte[] bytes) {
    if (bytes.length > 32) {
      throw new IllegalArgumentException("Word256 input must be at most 32 bytes");
    }
    final byte[] padded = new byte[32];
    System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
    final long l3 = Word256Helpers.bytesToLong(padded, 0);
    final long l2 = Word256Helpers.bytesToLong(padded, 8);
    final long l1 = Word256Helpers.bytesToLong(padded, 16);
    final long l0 = Word256Helpers.bytesToLong(padded, 24);
    return new Word256(l0, l1, l2, l3, padded);
  }

  /**
   * Adds this Word256 to another Word256.
   *
   * @param other the other Word256 to add
   * @return a new Word256 representing the sum of this and other
   */
  public Word256 add(final Word256 other) {
    return Word256Arithmetic.add(this, other);
  }

  /**
   * Subtracts another Word256 from this Word256.
   *
   * @param other the Word256 to subtract
   * @return a new Word256 representing the result of this - other
   */
  public Word256 sub(final Word256 other) {
    return Word256Arithmetic.subtract(this, other);
  }

  /**
   * Negates this Word256 (i.e., computes -this).
   *
   * @return a new Word256 representing the negation of this
   */
  public Word256 abs() {
    return Word256Arithmetic.abs(this);
  }

  /**
   * Multiplies this Word256 by another Word256.
   *
   * @param other the Word256 to multiply by
   * @return a new Word256 representing the product of this and other
   */
  public Word256 mul(final Word256 other) {
    return Word256Arithmetic.mul(this, other);
  }

  /**
   * Divides this Word256 by another Word256.
   *
   * @param divisor the Word256 to divide by
   * @return a new Word256 representing the quotient of this and divisor
   */
  public Word256 div(final Word256 divisor) {
    return Word256Arithmetic.divide(this, divisor);
  }

  /**
   * Computes the modulus of this Word256 with respect to another Word256.
   *
   * @param modulus the Word256 to use as the modulus
   * @return a new Word256 representing the result of this % modulus
   */
  public Word256 mod(final Word256 modulus) {
    return Word256Arithmetic.mod(this, modulus);
  }

  /**
   * Signed division of this Word256 by another Word256.
   *
   * @param divisor the Word256 to divide by
   * @return a new Word256 representing the signed quotient of this and divisor
   */
  public Word256 sdiv(final Word256 divisor) {
    return Word256Arithmetic.sdiv(this, divisor);
  }

  /**
   * Signed modulus of this Word256 with respect to another Word256.
   *
   * @param modulus the Word256 to use as the modulus
   * @return a new Word256 representing the signed result of this % modulus
   */
  public Word256 smod(final Word256 modulus) {
    return Word256Arithmetic.smod(this, modulus);
  }

  /**
   * Adds this Word256 to another Word256 under a modulus.
   *
   * @param b the Word256 to add
   * @param modulus the modulus to use
   * @return a new Word256 representing the result of (this + b) % modulus
   */
  public Word256 addmod(final Word256 b, final Word256 modulus) {
    return Word256Arithmetic.addmod(this, b, modulus);
  }

  /**
   * Multiplies this Word256 by another Word256 under a modulus.
   *
   * @param b the Word256 to multiply by
   * @param modulus the modulus to use
   * @return a new Word256 representing the result of (this * b) % modulus
   */
  public Word256 mulmod(final Word256 b, final Word256 modulus) {
    return Word256Arithmetic.mulmod(this, b, modulus);
  }

  /**
   * Raises this Word256 to the power of another Word256.
   *
   * @param exponent the Word256 exponent
   * @return a new Word256 representing this raised to the power of exponent
   */
  public Word256 exp(final Word256 exponent) {
    return Word256Arithmetic.exp(this, exponent);
  }

  /**
   * Compares this Word256 with another Word256 in unsigned order.
   *
   * @param other the Word256 to compare with
   * @return a negative integer, zero, or a positive integer as this is less than, equal to, or
   *     greater than other
   */
  public int compareUnsigned(final Word256 other) {
    return Word256Comparison.compareUnsigned(this, other);
  }

  /**
   * Compares this Word256 with another Word256 in signed order.
   *
   * @param other the Word256 to compare with
   * @return a negative integer, zero, or a positive integer as this is less than, equal to, or
   *     greater than other
   */
  public int compareSigned(final Word256 other) {
    return Word256Comparison.compareSigned(this, other);
  }

  /**
   * Checks if this Word256 is negative.
   *
   * @return true if this Word256 is negative, false otherwise
   */
  public boolean isNegative() {
    return Word256Comparison.isNegative(this);
  }

  /**
   * Checks if this Word256 is zero.
   *
   * @return true if this Word256 is zero, false otherwise
   */
  public boolean isZero() {
    return Word256Comparison.isZero(this);
  }

  /**
   * Return the bit at the specified index in this Word256.
   *
   * <p>The index must be in the range [0, 255]. The least significant bit is at index 0, and the
   * most significant bit is at index 255.
   *
   * @param index the bit index (0-255)
   * @return the bit
   */
  public int getBit(final int index) {
    return Word256Bitwise.getBit(this, index);
  }

  /**
   * Sets the bit at the specified index in this Word256 to 1.
   *
   * @param index the bit index (0-255)
   * @return a new Word256 with the specified bit set to 1
   * @throws IllegalArgumentException if the index is out of range
   */
  public Word256 setBit(final int index) {
    return Word256Bitwise.setBit(this, index);
  }

  /**
   * Performs a bitwise AND operation with another Word256.
   *
   * @param other the Word256 to AND with
   * @return a new Word256 representing the result of this AND other
   * @throws NullPointerException if other is null
   */
  public Word256 and(final Word256 other) {
    return Word256Bitwise.and(this, other);
  }

  /**
   * Performs a bitwise OR operation with another Word256.
   *
   * @param other the Word256 to OR with
   * @return a new Word256 representing the result of this OR other
   * @throws NullPointerException if other is null
   */
  public Word256 or(final Word256 other) {
    return Word256Bitwise.or(this, other);
  }

  /**
   * Performs a bitwise XOR operation with another Word256.
   *
   * @param other the Word256 to XOR with
   * @return a new Word256 representing the result of this XOR other
   * @throws NullPointerException if other is null
   */
  public Word256 xor(final Word256 other) {
    return Word256Bitwise.xor(this, other);
  }

  /**
   * Performs a bitwise NOT operation on this Word256.
   *
   * @return a new Word256 representing the bitwise NOT of this
   */
  public Word256 not() {
    return Word256Bitwise.not(this);
  }

  /**
   * Shifts this Word256 left by the specified number of bits.
   *
   * @param shift the number of bits to shift left (0-255)
   * @return a new Word256 representing the result of the left shift
   * @throws IllegalArgumentException if shift is out of range
   */
  public Word256 shl(final int shift) {
    return Word256Bitwise.shl(this, shift);
  }

  /**
   * Shifts this Word256 right by the specified number of bits.
   *
   * @param shift the number of bits to shift right (0-255)
   * @return a new Word256 representing the result of the right shift
   * @throws IllegalArgumentException if shift is out of range
   */
  public Word256 shr(final int shift) {
    return Word256Bitwise.shr(this, shift);
  }

  /**
   * Performs an arithmetic right shift on this Word256 by the specified number of bits.
   *
   * @param shift the number of bits to shift right (0-255)
   * @return a new Word256 representing the result of the arithmetic right shift
   * @throws IllegalArgumentException if shift is out of range
   */
  public Word256 sar(final int shift) {
    return Word256Bitwise.sar(this, shift);
  }

  /**
   * Sign-extends this Word256 based on the specified byte index.
   *
   * <p>If the byte index is out of range (0-30), returns this Word256 unchanged. If the sign bit of
   * the specified byte is set, extends the sign to all higher bits. Otherwise, clears all higher
   * bits.
   *
   * @param extByte the byte index to use for sign extension
   * @return a new Word256 with sign extension applied
   */
  public Word256 signExtend(final Word256 extByte) {
    if (!extByte.fitsInt() || extByte.toInt() >= 31) {
      return this;
    }

    final int byteIndex = extByte.toInt();
    final int bitIndex = byteIndex * 8;
    final int signBit = getBit(bitIndex);

    return signBit == 1
        ? this.or(Word256Helpers.maskAbove(bitIndex))
        : this.and(Word256Helpers.maskBelow(bitIndex + 1));
  }

  /**
   * Checks if this Word256 can be represented as a long without loss of information.
   *
   * @return true if this Word256 fits in a long, false otherwise
   */
  public boolean fitsLong() {
    return l1 == 0 && l2 == 0 && l3 == 0;
  }

  /**
   * Converts this Word256 to a long if it fits, otherwise returns Long.MAX_VALUE.
   *
   * @return the long value if it fits, otherwise Long.MAX_VALUE
   */
  public long toLong() {
    return fitsLong() ? l0 : Long.MAX_VALUE;
  }

  /**
   * Checks if this Word256 can be represented as an int without loss of information.
   *
   * @return true if this Word256 fits in an int, false otherwise
   */
  public boolean fitsInt() {
    return (l1 | l2 | l3) == 0 && (l0 >>> 31) == 0;
  }

  /**
   * Converts this Word256 to an int if it fits, otherwise returns Integer.MAX_VALUE.
   *
   * @return the int value if it fits, otherwise Integer.MAX_VALUE
   */
  public int toInt() {
    return fitsInt() ? (int) l0 : Integer.MAX_VALUE;
  }

  /**
   * Gets the byte at the specified index (0-31) from this Word256.
   *
   * @param index the byte index (0-31)
   * @return the byte value at the specified index
   * @throws IndexOutOfBoundsException if the index is out of range
   */
  public byte get(final int index) {
    if (index < 0 || index >= 32) {
      throw new IndexOutOfBoundsException("Byte index must be in [0, 31]: " + index);
    }
    final int longIndex = 3 - (index / 8);
    final int shift = 8 * (7 - (index % 8));
    final long value =
        switch (longIndex) {
          case 0 -> l0;
          case 1 -> l1;
          case 2 -> l2;
          case 3 -> l3;
          default -> throw new IllegalStateException("Unexpected long index: " + longIndex);
        };
    return (byte) (value >>> shift);
  }

  /**
   * Converts this Word256 to a byte array.
   *
   * <p>The byte array will always be 32 bytes long, padded with leading zeros if necessary.
   *
   * @return a byte array representation of this Word256
   */
  public byte[] toBytes() {
    if (bytesCache != null) {
      return bytesCache;
    }
    bytesCache = new byte[32];
    Word256Helpers.writeLongBE(bytesCache, 0, l3);
    Word256Helpers.writeLongBE(bytesCache, 8, l2);
    Word256Helpers.writeLongBE(bytesCache, 16, l1);
    Word256Helpers.writeLongBE(bytesCache, 24, l0);
    return bytesCache;
  }

  /**
   * Clamps this Word256 to a long value.
   *
   * <p>If this Word256 is zero, returns 0. If it can be represented as a long, returns that value.
   * Otherwise, returns Long.MAX_VALUE.
   *
   * @return the clamped long value
   */
  public long clampedToLong() {
    if (isZero()) return 0;
    if (l1 == 0 && l2 == 0 && l3 == 0) return l0 >= 0 ? l0 : Long.MAX_VALUE;
    return Long.MAX_VALUE;
  }

  /**
   * Clamps this Word256 to an int value.
   *
   * <p>If this Word256 is zero, returns 0. If it can be represented as an int, returns that value.
   * Otherwise, returns Integer.MAX_VALUE.
   *
   * @return the clamped int value
   */
  public int clampedToInt() {
    if (isZero()) return 0;
    if ((l1 | l2 | l3) == 0 && l0 >>> 32 == 0) return (int) l0 >= 0 ? (int) l0 : Integer.MAX_VALUE;
    return Integer.MAX_VALUE;
  }

  /**
   * Gets the least significant byte (LSB) of this Word256.
   *
   * @return the most significant byte
   */
  public byte getLeastSignificantByte() {
    return (byte) (l0 & 0xFF);
  }

  /**
   * Return the number of bytes that are non-zero in this Word256.
   *
   * <p>This is the number of bytes from the most significant byte down to the least significant
   * byte that are non-zero. For example, if the most significant byte is zero but the next one is
   * not, this will return 24, indicating that the Word256 has 24 significant bytes.
   *
   * @return the number of significant bytes (0-32)
   */
  public int byteLength() {
    if (l3 != 0) return 32 - Long.numberOfLeadingZeros(l3) / 8;
    if (l2 != 0) return 24 - Long.numberOfLeadingZeros(l2) / 8;
    if (l1 != 0) return 16 - Long.numberOfLeadingZeros(l1) / 8;
    if (l0 != 0) return 8 - Long.numberOfLeadingZeros(l0) / 8;
    return 0;
  }

  /**
   * Returns the number of bits that are non-zero in this Word256.
   *
   * <p>This is the total number of bits from the most significant bit down to the least significant
   * bit that are non-zero. For example, if the most significant bit is zero but the next one is
   * not, this will return 192, indicating that the Word256 has 192 significant bits.
   *
   * @return the number of significant bits (0-256)
   */
  public int bitLength() {
    if (l3 != 0) return 256 - Long.numberOfLeadingZeros(l3);
    if (l2 != 0) return 192 - Long.numberOfLeadingZeros(l2);
    if (l1 != 0) return 128 - Long.numberOfLeadingZeros(l1);
    if (l0 != 0) return 64 - Long.numberOfLeadingZeros(l0);
    return 0;
  }

  /**
   * Returns the number of leading zeros in this Word256.
   *
   * <p>This is the number of leading bits that are zero, starting from the most significant bit.
   * For example, if the most significant bit is set but the next one is not, this will return 64,
   * indicating that there are 64 leading zeros.
   *
   * @return the number of leading zeros (0-256)
   */
  public int clz() {
    if (l3 != 0) return Long.numberOfLeadingZeros(l3);
    if (l2 != 0) return 64 + Long.numberOfLeadingZeros(l2);
    if (l1 != 0) return 128 + Long.numberOfLeadingZeros(l1);
    if (l0 != 0) return 192 + Long.numberOfLeadingZeros(l0);
    return 256;
  }

  long getLimb(final int index) {
    return switch (index) {
      case 0 -> l0;
      case 1 -> l1;
      case 2 -> l2;
      case 3 -> l3;
      default -> throw new IndexOutOfBoundsException();
    };
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof final Word256 other)) return false;
    return this.l0 == other.l0 && this.l1 == other.l1 && this.l2 == other.l2 && this.l3 == other.l3;
  }

  @Override
  public int hashCode() {
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
      if (val < 0x10) sb.append('0');
      sb.append(Integer.toHexString(val));
    }
    return sb.toString();
  }
}
