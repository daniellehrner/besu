/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.evm;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.code.OpcodeInfo;
import org.hyperledger.besu.evm.operation.JumpDestOperation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.google.common.base.MoreObjects;
import org.apache.tuweni.bytes.Bytes;

/** Represents EVM code associated with an account. */
public class Code {

  /** The constant EMPTY_CODE. */
  public static final Code EMPTY_CODE = new Code(Bytes.EMPTY);

  /** The bytes representing the code. */
  private final Bytes bytes;

  /** The hash of the code, needed for accessing metadata about the bytecode */
  private Hash codeHash;

  private final int size;

  // The untraced v2 loop reads the analysis tables through their plain accessors once analyse()
  // has built them, rather than holding them in locals: five more locals leave the loop too few
  // registers for its own state.

  /** Bit mask for jump destinations, used to optimize JUMP/JUMPI operations */
  private long[] jumpDestBitMask = null;

  private long[] pushBits = null;
  private int[] pushBase = null;
  private long[] pushValues = null;
  private long[] pushWide = null;

  /**
   * Public constructor.
   *
   * @param byteCode The byte representation of the code.
   */
  public Code(final Bytes byteCode) {
    this(byteCode, byteCode.isEmpty() ? Hash.EMPTY : null);
  }

  /**
   * Public constructor.
   *
   * @param byteCode The byte representation of the code.
   * @param codeHash the hash of the bytecode
   */
  public Code(final Bytes byteCode, final Hash codeHash) {
    this.bytes = Bytes.wrap(byteCode.toArrayUnsafe());
    this.codeHash = codeHash;
    this.size = this.bytes.size();
  }

  /**
   * Returns true if the object is equal to this; otherwise false.
   *
   * @param other The object to compare this with.
   * @return True if the object is equal to this, otherwise false.
   */
  @Override
  public boolean equals(final Object other) {
    if (other == null) return false;
    if (other == this) return true;
    if (!(other instanceof Code that)) return false;

    return this.getCodeHash().equals(that.getCodeHash());
  }

  @Override
  public int hashCode() {
    return bytes.hashCode();
  }

  /**
   * Size of the Code, in bytes
   *
   * @return The number of bytes in the code.
   */
  public int getSize() {
    return size;
  }

  /**
   * Get the bytes for the code.
   *
   * @return code bytes.
   */
  public Bytes getBytes() {
    return bytes;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("bytes", bytes).toString();
  }

  /**
   * Hash of the code
   *
   * @return hash of the code.
   */
  public Hash getCodeHash() {
    if (codeHash != null) {
      return codeHash;
    }

    codeHash = Hash.hash(bytes);
    return codeHash;
  }

  /**
   * Is the target jump location valid?
   *
   * @param jumpDestination index from PC=0.
   * @return true if the operation is both a valid opcode and a JUMPDEST
   */
  public boolean isJumpDestInvalid(final int jumpDestination) {
    if (jumpDestination < 0 || jumpDestination >= getSize()) {
      return true;
    }

    if (jumpDestBitMask == null) {
      jumpDestBitMask = calculateJumpDestBitMask();
    }

    // This selects which long in the array holds the bit for the given offset:
    //	1)	>>> 6 is equivalent to jumpDestination / 64
    //	2)	Each long holds 64 bits, so this finds the correct chunk
    final long targetLong = jumpDestBitMask[jumpDestination >>> 6];

    // 1) & 0x3F is jumpDestination % 64
    // 2)	1L << ... gives a mask for the specific bit in that long
    final long targetBit = 1L << (jumpDestination & 0x3F);

    // If the bit is not set, then it is an invalid jump destination
    return (targetLong & targetBit) == 0L;
  }

  /**
   * A more readable representation of the hex bytes, including whitespace and comments after hashes
   *
   * @return The pretty printed code
   */
  public String prettyPrint() {
    int i = 0;
    int len = bytes.size();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(out);
    ps.println("0x # Legacy EVM Code");
    while (i < len) {
      i += printInstruction(i, ps);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  /**
   * Returns the bitmask of valid jump destinations, computing it on first use.
   *
   * @return the bitmask, one bit per byte of code
   */
  long[] jumpDestinations() {
    if (jumpDestBitMask == null) {
      jumpDestBitMask = calculateJumpDestBitMask();
    }
    return jumpDestBitMask;
  }

  /**
   * Returns a bitmask of valid jump destinations for this code. The bitmask is an array of longs,
   * where each bit represents a potential jump destination in the code.
   *
   * @return an array of long values representing the jump destinations, or null if not set
   */
  public long[] getJumpDestBitMask() {
    return jumpDestBitMask;
  }

  /**
   * Sets the jump destination bitmask for this code. This method is intended to be used by the
   * EVM's JumpService to set the valid jump destinations for the code.
   *
   * @param jumpDestBitMask an array of long values representing the jump destinations
   */
  public void setJumpDestBitMask(final long[] jumpDestBitMask) {
    this.jumpDestBitMask = jumpDestBitMask;
  }

  /**
   * Builds the jump destinations and the PUSH tables that the EVM v2 loop reads, unless they are
   * built already. The PUSH tables are only read by the v2 loop, so the standard interpreter never
   * builds them; the jump destinations come out of the same pass.
   */
  public void analyse() {
    if (pushValues == null) {
      final long[] mask = scan(true);
      if (jumpDestBitMask == null) {
        jumpDestBitMask = mask;
      }
    } else if (jumpDestBitMask == null) {
      jumpDestBitMask = calculateJumpDestBitMask();
    }
  }

  /**
   * Bit set at the pc of every PUSH, one long per 64 bytes of code. A plain accessor, as the v2
   * loop's inline code calls it: null until {@link #analyse()} has run.
   *
   * @return the bitmap
   */
  public long[] pushBits() {
    return pushBits;
  }

  /**
   * Number of PUSHes before each 64-byte block, so that the ordinal of a PUSH is the base of its
   * block plus the count of PUSH bits below it in {@link #pushBits()}.
   *
   * @return the bases, null until {@link #analyse()} has run
   */
  public int[] pushBase() {
    return pushBase;
  }

  /**
   * One long per PUSH in code order: the immediate of a PUSH1..PUSH8, or the index of the limbs of
   * a wider PUSH in {@link #pushWide()}.
   *
   * @return the values, null until {@link #analyse()} has run
   */
  public long[] pushValues() {
    return pushValues;
  }

  /**
   * Four big-endian limbs per PUSH9..PUSH32, addressed through {@link #pushValues()}.
   *
   * @return the limbs, null until {@link #analyse()} has run
   */
  public long[] pushWide() {
    return pushWide;
  }

  /**
   * Computes a bitmask where each bit set to 1 indicates a valid {@code JUMPDEST} opcode in the
   * bytecode, one long per 64 bytes of code, used to validate dynamic jumps at runtime.
   */
  long[] calculateJumpDestBitMask() {
    return scan(false);
  }

  /**
   * One walk over the code that skips PUSH immediates and records the valid {@code JUMPDEST}
   * positions; with {@code withPushes} it also decodes every immediate into the PUSH tables. The
   * code cache keeps the result, so the interpreter pays for it once per contract.
   */
  private long[] scan(final boolean withPushes) {
    final byte[] raw = bytes.toArrayUnsafe();
    final int length = raw.length;
    final long[] bitmap = new long[(length >> 6) + 1];

    int pushCount = 0;
    int wideCount = 0;
    for (int i = 0; i < length; ) {
      final int op = raw[i] & 0xff;
      if (op >= 0x60 && op <= 0x7f) {
        final int n = op - 0x5f;
        pushCount++;
        if (n > 8) {
          wideCount++;
        }
        i += 1 + n;
      } else if (op == JumpDestOperation.OPCODE) {
        bitmap[i >> 6] |= 1L << (i & 0x3f);
        i++;
      } else {
        i++;
      }
    }
    if (!withPushes) {
      return bitmap;
    }

    final long[] bits = new long[(length >> 6) + 1];
    final int[] base = new int[(length >> 6) + 1];
    final long[] values = new long[pushCount];
    final long[] wide = new long[wideCount * 4];
    final long[] limbs = new long[4];
    int wideAt = 0;
    int ordinal = 0;
    for (int i = 0; i < length; ) {
      final int op = raw[i] & 0xff;
      if (op >= 0x60 && op <= 0x7f) {
        final int n = op - 0x5f;
        bits[i >> 6] |= 1L << (i & 0x3f);
        if (n > 8) {
          Arrays.fill(limbs, 0L);
          decodeImmediate(raw, i + 1, n, limbs);
          System.arraycopy(limbs, 0, wide, wideAt, 4);
          values[ordinal] = wideAt;
          wideAt += 4;
        } else {
          limbs[3] = 0L;
          decodeImmediate(raw, i + 1, n, limbs);
          values[ordinal] = limbs[3];
        }
        ordinal++;
        i += 1 + n;
      } else {
        i++;
      }
    }
    for (int b = 1; b < base.length; b++) {
      base[b] = base[b - 1] + Long.bitCount(bits[b - 1]);
    }
    pushBits = bits;
    pushBase = base;
    pushValues = values;
    pushWide = wide;
    return bitmap;
  }

  /** Bytes missing at the end of the code read as zero, as the interpreter does. */
  private static void decodeImmediate(
      final byte[] raw, final int start, final int n, final long[] limbs) {
    final int available = Math.max(0, Math.min(n, raw.length - start));
    int bytePos = n - 1;
    for (int i = 0; i < available; i++) {
      limbs[3 - (bytePos >> 3)] |= (raw[start + i] & 0xffL) << ((bytePos & 7) << 3);
      bytePos--;
    }
  }

  /**
   * Prints an individual instruction, including immediate data
   *
   * @param offset Offset within the code
   * @param out the print stream to write to
   * @return the number of bytes to advance the PC (includes consideration of immediate arguments)
   */
  public int printInstruction(final int offset, final PrintStream out) {
    int codeByte = bytes.get(offset) & 0xff;
    OpcodeInfo info = OpcodeInfo.getOpcode(codeByte);
    String push = "";
    String decimalPush = "";
    if (info.pcAdvance() > 1) {
      int start = Math.min(bytes.size(), offset + 1);
      int end = Math.min(bytes.size(), info.pcAdvance() - 1);
      Bytes slice = bytes.slice(start, end);
      push = slice.toUnprefixedHexString();
      if (info.pcAdvance() < 5) {
        decimalPush = "(" + slice.toLong() + ")";
      }
    }
    String name = info.name();
    if (codeByte == 0x5b) {
      name = "JUMPDEST";
    }
    out.printf("%02x%s # [ %d ] %s%s%n", codeByte, push, offset, name, decimalPush);
    return Math.max(1, info.pcAdvance());
  }
}
