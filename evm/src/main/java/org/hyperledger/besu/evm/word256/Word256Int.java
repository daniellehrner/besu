package org.hyperledger.besu.evm.word256;

public class Word256Int {
  private final int i0, i1, i2, i3, i4, i5, i6, i7;

  public static final Word256Int ZERO = new Word256Int(0, 0, 0, 0, 0, 0, 0, 0);

  public static final Word256Int ONE = new Word256Int(1, 0, 0, 0, 0, 0, 0, 0);

  public Word256Int(
    final int i0, final int i1, final int i2, final int i3,
    final int i4, final int i5, final int i6, final int i7) {
    this.i0 = i0;
    this.i1 = i1;
    this.i2 = i2;
    this.i3 = i3;
    this.i4 = i4;
    this.i5 = i5;
    this.i6 = i6;
    this.i7 = i7;
  }

  public Word256Int add(final Word256Int other) {
    int[] result = new int[8];
    long carry = 0;

    long sum0 = Integer.toUnsignedLong(this.i0) + Integer.toUnsignedLong(other.i0) + carry;
    result[0] = (int) sum0;
    carry = sum0 >>> 32;

    long sum1 = Integer.toUnsignedLong(this.i1) + Integer.toUnsignedLong(other.i1) + carry;
    result[1] = (int) sum1;
    carry = sum1 >>> 32;

    long sum2 = Integer.toUnsignedLong(this.i2) + Integer.toUnsignedLong(other.i2) + carry;
    result[2] = (int) sum2;
    carry = sum2 >>> 32;

    long sum3 = Integer.toUnsignedLong(this.i3) + Integer.toUnsignedLong(other.i3) + carry;
    result[3] = (int) sum3;
    carry = sum3 >>> 32;

    long sum4 = Integer.toUnsignedLong(this.i4) + Integer.toUnsignedLong(other.i4) + carry;
    result[4] = (int) sum4;
    carry = sum4 >>> 32;

    long sum5 = Integer.toUnsignedLong(this.i5) + Integer.toUnsignedLong(other.i5) + carry;
    result[5] = (int) sum5;
    carry = sum5 >>> 32;

    long sum6 = Integer.toUnsignedLong(this.i6) + Integer.toUnsignedLong(other.i6) + carry;
    result[6] = (int) sum6;
    carry = sum6 >>> 32;

    long sum7 = Integer.toUnsignedLong(this.i7) + Integer.toUnsignedLong(other.i7) + carry;
    result[7] = (int) sum7;

    return new Word256Int(
      result[0], result[1], result[2], result[3],
      result[4], result[5], result[6], result[7]
    );
  }

  public Word256Int sub(final Word256Int other) {
    final int[] result = new int[8];
    long borrow;

    final long x0 = Integer.toUnsignedLong(this.i0);
    final long y0 = Integer.toUnsignedLong(other.i0);
    final long d0 = x0 - y0;
    result[0] = (int) d0;
    borrow = (x0 ^ ((x0 ^ y0) | ((d0) ^ y0))) >>> 63;

    final long x1 = Integer.toUnsignedLong(this.i1);
    final long y1 = Integer.toUnsignedLong(other.i1) + borrow;
    final long d1 = x1 - y1;
    result[1] = (int) d1;
    borrow = (x1 ^ ((x1 ^ y1) | ((d1) ^ y1))) >>> 63;

    final long x2 = Integer.toUnsignedLong(this.i2);
    final long y2 = Integer.toUnsignedLong(other.i2) + borrow;
    final long d2 = x2 - y2;
    result[2] = (int) d2;
    borrow = (x2 ^ ((x2 ^ y2) | ((d2) ^ y2))) >>> 63;

    final long x3 = Integer.toUnsignedLong(this.i3);
    final long y3 = Integer.toUnsignedLong(other.i3) + borrow;
    final long d3 = x3 - y3;
    result[3] = (int) d3;
    borrow = (x3 ^ ((x3 ^ y3) | ((d3) ^ y3))) >>> 63;

    final long x4 = Integer.toUnsignedLong(this.i4);
    final long y4 = Integer.toUnsignedLong(other.i4) + borrow;
    final long d4 = x4 - y4;
    result[4] = (int) d4;
    borrow = (x4 ^ ((x4 ^ y4) | ((d4) ^ y4))) >>> 63;

    final long x5 = Integer.toUnsignedLong(this.i5);
    final long y5 = Integer.toUnsignedLong(other.i5) + borrow;
    final long d5 = x5 - y5;
    result[5] = (int) d5;
    borrow = (x5 ^ ((x5 ^ y5) | ((d5) ^ y5))) >>> 63;

    final long x6 = Integer.toUnsignedLong(this.i6);
    final long y6 = Integer.toUnsignedLong(other.i6) + borrow;
    final long d6 = x6 - y6;
    result[6] = (int) d6;
    borrow = (x6 ^ ((x6 ^ y6) | ((d6) ^ y6))) >>> 63;

    final long x7 = Integer.toUnsignedLong(this.i7);
    final long y7 = Integer.toUnsignedLong(other.i7) + borrow;
    final long d7 = x7 - y7;
    result[7] = (int) d7;
    // final borrow would be (x7 < y7) if you want to track underflow

    return new Word256Int(
      result[0], result[1], result[2], result[3],
      result[4], result[5], result[6], result[7]
    );
  }

  public Word256Int mul(final Word256Int other) {
    final long[] acc = new long[16]; // 512-bit accumulator, use low 8 only

    // Convert to unsigned longs once
    final long x0 = Integer.toUnsignedLong(this.i0);
    final long x1 = Integer.toUnsignedLong(this.i1);
    final long x2 = Integer.toUnsignedLong(this.i2);
    final long x3 = Integer.toUnsignedLong(this.i3);
    final long x4 = Integer.toUnsignedLong(this.i4);
    final long x5 = Integer.toUnsignedLong(this.i5);
    final long x6 = Integer.toUnsignedLong(this.i6);
    final long x7 = Integer.toUnsignedLong(this.i7);

    final long y0 = Integer.toUnsignedLong(other.i0);
    final long y1 = Integer.toUnsignedLong(other.i1);
    final long y2 = Integer.toUnsignedLong(other.i2);
    final long y3 = Integer.toUnsignedLong(other.i3);
    final long y4 = Integer.toUnsignedLong(other.i4);
    final long y5 = Integer.toUnsignedLong(other.i5);
    final long y6 = Integer.toUnsignedLong(other.i6);
    final long y7 = Integer.toUnsignedLong(other.i7);

    // Every multiplication below is 32x32 → 64-bit, broken into low/high 32-bit limbs

    // Manually unroll only terms that contribute to lower 8 limbs
    mulAccumulate(acc, 0, x0, y0);

    mulAccumulate(acc, 1, x0, y1);
    mulAccumulate(acc, 1, x1, y0);

    mulAccumulate(acc, 2, x0, y2);
    mulAccumulate(acc, 2, x1, y1);
    mulAccumulate(acc, 2, x2, y0);

    mulAccumulate(acc, 3, x0, y3);
    mulAccumulate(acc, 3, x1, y2);
    mulAccumulate(acc, 3, x2, y1);
    mulAccumulate(acc, 3, x3, y0);

    mulAccumulate(acc, 4, x0, y4);
    mulAccumulate(acc, 4, x1, y3);
    mulAccumulate(acc, 4, x2, y2);
    mulAccumulate(acc, 4, x3, y1);
    mulAccumulate(acc, 4, x4, y0);

    mulAccumulate(acc, 5, x0, y5);
    mulAccumulate(acc, 5, x1, y4);
    mulAccumulate(acc, 5, x2, y3);
    mulAccumulate(acc, 5, x3, y2);
    mulAccumulate(acc, 5, x4, y1);
    mulAccumulate(acc, 5, x5, y0);

    mulAccumulate(acc, 6, x0, y6);
    mulAccumulate(acc, 6, x1, y5);
    mulAccumulate(acc, 6, x2, y4);
    mulAccumulate(acc, 6, x3, y3);
    mulAccumulate(acc, 6, x4, y2);
    mulAccumulate(acc, 6, x5, y1);
    mulAccumulate(acc, 6, x6, y0);

    mulAccumulate(acc, 7, x0, y7);
    mulAccumulate(acc, 7, x1, y6);
    mulAccumulate(acc, 7, x2, y5);
    mulAccumulate(acc, 7, x3, y4);
    mulAccumulate(acc, 7, x4, y3);
    mulAccumulate(acc, 7, x5, y2);
    mulAccumulate(acc, 7, x6, y1);
    mulAccumulate(acc, 7, x7, y0);

    return new Word256Int(
      (int) acc[0], (int) acc[1], (int) acc[2], (int) acc[3],
      (int) acc[4], (int) acc[5], (int) acc[6], (int) acc[7]
    );
  }

  private static void mulAccumulate(final long[] acc, final int index, final long a, final long b) {
    final long product = a * b;

    // Add low 32 bits to acc[index]
    final long lo = acc[index] + (product & 0xFFFFFFFFL);
    final long carry1 = lo >>> 32;
    acc[index] = lo & 0xFFFFFFFFL;

    // Add high 32 bits + carry1 to acc[index + 1]
    final long hi = (product >>> 32) + acc[index + 1] + carry1;
    acc[index + 1] = hi & 0xFFFFFFFFL;

    // Carry to acc[index + 2]
    acc[index + 2] += hi >>> 32;
  }

  public Word256Int and(final Word256Int other) {
    return new Word256Int(
      this.i0 & other.i0,
      this.i1 & other.i1,
      this.i2 & other.i2,
      this.i3 & other.i3,
      this.i4 & other.i4,
      this.i5 & other.i5,
      this.i6 & other.i6,
      this.i7 & other.i7
    );
  }

  public static Word256Int exp(final Word256Int base, final Word256Int exponent) {
    // Fast path: any number raised to the power of 0 is 1
    if (exponent.isZero()) {
      return Word256Int.ONE;
    }

    // Fast path: 0 raised to any power (except 0) is 0
    if (base.isZero()) {
      return Word256Int.ZERO;
    }

    Word256Int result = Word256Int.ONE;
    Word256Int power = base;

    for (int i = 0; i < 256; i++) {
      if (exponent.getBit(i)) {
        result = result.mul(power);
      }
      power = power.mul(power);
    }

    return result;
  }

  public boolean isZero() {
    return i0 == 0 && i1 == 0 && i2 == 0 && i3 == 0 &&
      i4 == 0 && i5 == 0 && i6 == 0 && i7 == 0;
  }

  public boolean getBit(final int bitIndex) {
    if (bitIndex < 0 || bitIndex >= 256) {
      throw new IndexOutOfBoundsException("bitIndex must be in [0, 255]");
    }
    final int wordIndex = bitIndex >>> 5; // divide by 32
    final int bitInWord = bitIndex & 31;

    final int word = switch (wordIndex) {
      case 0 -> i0;
      case 1 -> i1;
      case 2 -> i2;
      case 3 -> i3;
      case 4 -> i4;
      case 5 -> i5;
      case 6 -> i6;
      case 7 -> i7;
      default -> throw new IllegalStateException("Unexpected index");
    };

    return ((word >>> bitInWord) & 1) != 0;
  }

  public static Word256Int fromBytes(final byte[] bytes) {
    if (bytes.length > 32) {
      throw new IllegalArgumentException("Word256Int input must be at most 32 bytes");
    }

    if (bytes.length < 32) {
      final byte[] padded = new byte[32];
      System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
      return setFromByteArray(padded);
    }

    return setFromByteArray(bytes);
  }

  private static Word256Int setFromByteArray(final byte[] bytes) {
    // Parse as 8 unsigned 32-bit big-endian integers
    return new Word256Int(
      getUInt(bytes, 28), // i0 (least significant 4 bytes)
      getUInt(bytes, 24),
      getUInt(bytes, 20),
      getUInt(bytes, 16),
      getUInt(bytes, 12),
      getUInt(bytes, 8),
      getUInt(bytes, 4),
      getUInt(bytes, 0)   // i7 (most significant 4 bytes)
    );
  }

  private static int getUInt(final byte[] bytes, final int offset) {
    return ((bytes[offset] & 0xFF) << 24)
      | ((bytes[offset + 1] & 0xFF) << 16)
      | ((bytes[offset + 2] & 0xFF) << 8)
      | (bytes[offset + 3] & 0xFF);
  }
}
