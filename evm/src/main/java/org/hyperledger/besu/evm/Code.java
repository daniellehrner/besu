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

import com.google.common.base.MoreObjects;
import org.apache.tuweni.bytes.Bytes;

/** Represents EVM code associated with an account. */
public class Code {

  /** The constant EMPTY_CODE. */
  public static final Code EMPTY_CODE = new Code(Bytes.EMPTY);

  private static final byte PUSH_1 = 0x60;

  /** The bytes representing the code. */
  private final Bytes bytes;

  /** The hash of the code, needed for accessing metadata about the bytecode */
  private Hash codeHash;

  private final int size;

  /** Bit mask marking bytes that are PUSH immediate data rather than opcodes */
  private long[] pushDataBitMask = null;

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
    if (jumpDestination < 0 || jumpDestination >= size) {
      return true;
    }

    // Testing the byte first keeps destinations that cannot be valid from triggering an analysis
    if (bytes.get(jumpDestination) != JumpDestOperation.OPCODE) {
      return true;
    }

    if (pushDataBitMask == null) {
      pushDataBitMask = calculatePushDataBitMask();
    }

    // The byte is a JUMPDEST, so it is a destination unless it is the immediate data of a PUSH
    return (pushDataBitMask[jumpDestination >>> 6] & (1L << (jumpDestination & 0x3F))) != 0L;
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
   * Returns the bitmask marking which bytes of this code are PUSH immediate data. The bitmask is an
   * array of longs, where each bit represents one byte of the code.
   *
   * @return an array of long values marking PUSH immediate data, or null if not yet computed
   */
  public long[] getPushDataBitMask() {
    return pushDataBitMask;
  }

  /**
   * Sets the PUSH immediate data bitmask for this code.
   *
   * @param pushDataBitMask an array of long values marking PUSH immediate data
   */
  public void setPushDataBitMask(final long[] pushDataBitMask) {
    this.pushDataBitMask = pushDataBitMask;
  }

  /**
   * Computes a bitmask where each bit set to 1 indicates a byte that is the immediate data of a
   * PUSH rather than an opcode. Together with a JUMPDEST byte test this validates dynamic jumps.
   *
   * <p>Marking immediate data rather than jump destinations keeps the cost proportional to the
   * number of PUSH instructions, so code consisting largely of JUMPDEST costs nothing to record.
   *
   * @return the PUSH immediate data bitmask
   */
  long[] calculatePushDataBitMask() {
    final byte[] rawCode = bytes.toArrayUnsafe();
    final int length = rawCode.length;

    // A PUSH near the end of the code marks positions past its end, so the map carries a spare
    // long rather than bounds checking every run
    final long[] bitmap = new long[(length >> 6) + 2];

    for (int pc = 0; pc < length; ) {
      final byte opcode = rawCode[pc];
      pc++;

      // PUSH1..PUSH32 are the only opcodes carrying immediate data, and as signed bytes every
      // other opcode compares below PUSH1
      if (opcode < PUSH_1) {
        continue;
      }

      final int immediateSize = opcode - PUSH_1 + 1;
      final int index = pc >>> 6;
      final int offset = pc & 0x3F;

      // Immediate data is at most 32 bits, so a run spans at most two longs
      final long mask = (1L << immediateSize) - 1;
      bitmap[index] |= mask << offset;
      if (offset + immediateSize > 64) {
        bitmap[index + 1] |= mask >>> (64 - offset);
      }

      pc += immediateSize;
    }

    return bitmap;
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
