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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import com.google.common.base.MoreObjects;
import org.apache.tuweni.bytes.Bytes;

/** Represents EVM code associated with an account. */
public class Code {

  /** The constant EMPTY_CODE. */
  public static final Code EMPTY_CODE = new Code(Bytes.EMPTY);

  private static final VarHandle LONG_VIEW =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  private static final long EIGHT_JUMPDESTS = 0x5b5b5b5b5b5b5b5bL;

  // PUSH1..PUSH32 are exactly the opcodes 0b011xxxxx
  private static final long PUSH_OPCODE_PREFIX_MASKS = 0xE0E0E0E0E0E0E0E0L;
  private static final long EIGHT_PUSH_PREFIXES = 0x6060606060606060L;

  private static final long LOW_SEVEN_BITS = 0x7F7F7F7F7F7F7F7FL;
  private static final long HIGH_BITS = 0x8080808080808080L;
  // Multiplying by this sums the high bit of byte n into bit 56 + n without any overlap
  private static final long BYTE_FLAG_GATHER = 0x0002040810204081L;

  /** The bytes representing the code. */
  private final Bytes bytes;

  /** The hash of the code, needed for accessing metadata about the bytecode */
  private Hash codeHash;

  private final int size;

  /** Bit mask for jump destinations, used to optimize JUMP/JUMPI operations */
  private long[] jumpDestBitMask = null;

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

    // Testing the byte first keeps destinations that cannot be valid from triggering an analysis
    if (bytes.get(jumpDestination) != JumpDestOperation.OPCODE) {
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
   * Computes a bitmask where each bit set to 1 indicates a valid `JUMPDEST` opcode in the EVM
   * bytecode. The bitmap is organized in 64-byte chunks, each represented as a `long` (64 bits).
   * This is used for efficiently validating dynamic jumps (`JUMP`, `JUMPI`) at runtime.
   */
  long[] calculateJumpDestBitMask() {
    final int size = getSize();
    final long[] bitmap = new long[(size >> 6) + 1];
    final byte[] rawCode = getBytes().toArrayUnsafe();
    final int length = rawCode.length;

    int i = 0;
    while (i < length) {
      // Entries without a PUSH are marked whole, everything else is walked. A walk covers all
      // entries up to the next one without a PUSH, so that it costs one call per run of them
      while (i + 64 <= length && markEntryWithoutPush(rawCode, i, bitmap)) {
        i += 64;
      }
      if (i >= length) {
        break;
      }
      int end = i + 64;
      while (end + 64 <= length && !markEntryWithoutPush(rawCode, end, bitmap)) {
        end += 64;
      }
      i = walkEntries(rawCode, i, Math.min(end, length), bitmap);
    }
    return bitmap;
  }

  /**
   * Marks the JUMPDESTs of the 64 bytes at the offset, unless one of them is a PUSH. Without a PUSH
   * every byte is an instruction and the flags can be taken from the bytes as they are; with one
   * the immediate data has to be skipped, which needs a walk. The caller has to guarantee that the
   * offset is a multiple of 64 and that 64 bytes are in bounds.
   *
   * @return whether the entry was marked
   */
  private static boolean markEntryWithoutPush(
      final byte[] rawCode, final int offset, final long[] bitmap) {
    for (int k = 0; k < 64; k += 8) {
      if (pushFlags((long) LONG_VIEW.get(rawCode, offset + k)) != 0L) {
        return false;
      }
    }
    long bits = 0L;
    for (int k = 0; k < 64; k += 8) {
      bits |= jumpDestFlags((long) LONG_VIEW.get(rawCode, offset + k)) << k;
    }
    bitmap[offset >>> 6] = bits;
    return true;
  }

  /**
   * Walks the instructions from the offset up to the end and marks the JUMPDESTs among them, one
   * bitmap entry at a time. When a PUSH straddles the end, the walk carries on to the next entry
   * boundary, as the entry after starts with immediate data and cannot be marked whole. Kept apart
   * from the word-wise code on purpose: this loop already uses nearly every register, and anything
   * else compiled into the same method makes it spill
   *
   * @return the offset of the first instruction at an entry boundary at or after the end
   */
  private static int walkEntries(
      final byte[] rawCode, final int offset, final int end, final long[] bitmap) {
    final int length = rawCode.length;
    int i = offset;
    while (i < end || ((i & 0x3F) != 0 && i < length)) {
      final int entryPos = i >> 6;
      final int entryEnd = Math.min((entryPos + 1) << 6, length);
      long thisEntry = 0L;
      for (; i < entryEnd; i++) {
        final byte opcode = rawCode[i];
        // JUMPDEST is tested on its own and first: inside the switch its place would be up to the
        // profile, and a run of JUMPDESTs is the shape that has to stay cheap
        if (opcode == JumpDestOperation.OPCODE) {
          // A long shift only uses the low six bits of its count, which is the position within
          // the entry
          thisEntry |= 1L << i;
        } else if (opcode >= 0x60) {
          switch (opcode) {
            case 0x60:
              i += 1;
              break;
            case 0x61:
              i += 2;
              break;
            case 0x62:
              i += 3;
              break;
            case 0x63:
              i += 4;
              break;
            case 0x64:
              i += 5;
              break;
            case 0x65:
              i += 6;
              break;
            case 0x66:
              i += 7;
              break;
            case 0x67:
              i += 8;
              break;
            case 0x68:
              i += 9;
              break;
            case 0x69:
              i += 10;
              break;
            case 0x6a:
              i += 11;
              break;
            case 0x6b:
              i += 12;
              break;
            case 0x6c:
              i += 13;
              break;
            case 0x6d:
              i += 14;
              break;
            case 0x6e:
              i += 15;
              break;
            case 0x6f:
              i += 16;
              break;
            case 0x70:
              i += 17;
              break;
            case 0x71:
              i += 18;
              break;
            case 0x72:
              i += 19;
              break;
            case 0x73:
              i += 20;
              break;
            case 0x74:
              i += 21;
              break;
            case 0x75:
              i += 22;
              break;
            case 0x76:
              i += 23;
              break;
            case 0x77:
              i += 24;
              break;
            case 0x78:
              i += 25;
              break;
            case 0x79:
              i += 26;
              break;
            case 0x7a:
              i += 27;
              break;
            case 0x7b:
              i += 28;
              break;
            case 0x7c:
              i += 29;
              break;
            case 0x7d:
              i += 30;
              break;
            case 0x7e:
              i += 31;
              break;
            case 0x7f:
              i += 32;
              break;
            default:
              break;
          }
        }
      }
      bitmap[entryPos] = thisEntry;
    }
    return i;
  }

  /** Flags, in the high bit of each byte, the bytes of the word that are a PUSH opcode. */
  private static long pushFlags(final long word) {
    return bytesEqualTo(word & PUSH_OPCODE_PREFIX_MASKS, EIGHT_PUSH_PREFIXES);
  }

  /** Flags, in the low eight bits, the bytes of the word that are JUMPDEST. */
  private static long jumpDestFlags(final long word) {
    return gatherByteFlags(bytesEqualTo(word, EIGHT_JUMPDESTS));
  }

  /** Flags, in the high bit of each byte, which bytes of the word equal their byte in pattern. */
  private static long bytesEqualTo(final long word, final long pattern) {
    final long diff = word ^ pattern;
    // Adding 0x7F to the low seven bits carries into the high bit for every non-zero byte, and
    // the byte's own high bit covers the remaining case, so only zero bytes end up with a clear
    // high bit. The carries cannot cross a byte boundary
    return ~(((diff & LOW_SEVEN_BITS) + LOW_SEVEN_BITS) | diff) & HIGH_BITS;
  }

  /** Moves the high bit of byte n into bit n, giving one flag bit per byte. */
  private static long gatherByteFlags(final long byteFlags) {
    return (byteFlags * BYTE_FLAG_GATHER) >>> 56;
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
