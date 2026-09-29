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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.code;

import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.CODE_STORAGE;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorageTransaction;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes;

/**
 * Stores code by its hash together with its jump destination analysis, so that a load never has to
 * walk the code. A column family written by this strategy carries a marker entry, because its
 * values cannot be told apart from bare code by inspection; the marker is written in the same step
 * as the values, so the column family holds either only bare code or only analysed code.
 */
public class JumpDestCodeStorageStrategy extends CodeHashCodeStorageStrategy {

  /** Reserved key naming the strategy every other entry of the column family was written by. */
  public static final byte[] MARKER_KEY = "codeStorageStrategy".getBytes(StandardCharsets.UTF_8);

  /** The marker value of this strategy. */
  public static final byte[] MARKER = "jumpDest".getBytes(StandardCharsets.UTF_8);

  private static final int HEADER_SIZE = Integer.BYTES;
  private static final ValueLayout.OfInt INT =
      ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
  private static final ValueLayout.OfLong LONG =
      ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

  @Override
  public void putFlatCode(
      final SegmentedKeyValueStorage storage,
      final SegmentedKeyValueStorageTransaction transaction,
      final Hash accountHash,
      final Hash codeHash,
      final Bytes code) {
    transaction.put(CODE_STORAGE, codeHash.getBytes().toArrayUnsafe(), encode(code));
  }

  @Override
  public void markEmpty(final SegmentedKeyValueStorage storage) {
    final SegmentedKeyValueStorageTransaction transaction = storage.startTransaction();
    transaction.put(CODE_STORAGE, MARKER_KEY, MARKER);
    transaction.commit();
  }

  public static boolean isMarkerKey(final byte[] key) {
    return Arrays.equals(key, MARKER_KEY);
  }

  /**
   * Whether the column family was written by this strategy.
   *
   * @param storage the storage holding the code column family
   * @return true when the marker of this strategy is present
   */
  public static boolean isMarked(final SegmentedKeyValueStorage storage) {
    return storage
        .get(CODE_STORAGE, MARKER_KEY)
        .map(
            marker -> {
              if (!Arrays.equals(marker, MARKER)) {
                throw new IllegalStateException(
                    "Unknown code storage strategy " + new String(marker, StandardCharsets.UTF_8));
              }
              return true;
            })
        .orElse(false);
  }

  /**
   * The value this strategy stores for the given code: the code length, the code and its jump
   * destination bitmask, one bit per code byte.
   *
   * @param code the code
   * @return the value to store
   */
  public static byte[] encode(final Bytes code) {
    final long[] jumpDestBitMask = Code.jumpDestBitMaskOf(code);
    final byte[] value = new byte[HEADER_SIZE + code.size() + jumpDestBitMask.length * Long.BYTES];
    final ByteBuffer buffer = ByteBuffer.wrap(value);
    buffer.putInt(code.size()).put(code.toArrayUnsafe());
    buffer.asLongBuffer().put(jumpDestBitMask);
    return value;
  }

  @Override
  protected Code codeOf(final byte[] value, final Hash codeHash) {
    return decode(value, codeHash);
  }

  @Override
  protected Code codeOf(final MemorySegment value, final Hash codeHash) {
    return decode(value, codeHash);
  }

  /**
   * The code a stored value holds, with its jump destination analysis set.
   *
   * @param value the stored value
   * @param codeHash the hash of the code, or null to compute it when it is asked for
   * @return the code
   */
  public static Code decode(final byte[] value, final Hash codeHash) {
    return decode(MemorySegment.ofArray(value), codeHash);
  }

  /**
   * The code a stored value holds, with its jump destination analysis set. The code and the
   * analysis are copied out of the value, which is only readable during the call.
   *
   * @param value a view of the stored value
   * @param codeHash the hash of the code, or null to compute it when it is asked for
   * @return the code
   */
  public static Code decode(final MemorySegment value, final Hash codeHash) {
    final long size = value.byteSize();
    if (size < HEADER_SIZE) {
      throw new IllegalStateException("Stored code value of " + size + " bytes");
    }
    final int codeSize = value.get(INT, 0);
    final int maskLongs = codeSize < 0 ? 0 : (codeSize >> 6) + 1;
    if (codeSize < 0 || size != HEADER_SIZE + (long) codeSize + (long) maskLongs * Long.BYTES) {
      throw new IllegalStateException(
          "Stored code of " + codeSize + " bytes has a value of " + size + " bytes");
    }
    final byte[] code = new byte[codeSize];
    MemorySegment.copy(value, ValueLayout.JAVA_BYTE, HEADER_SIZE, code, 0, codeSize);
    final long[] jumpDestBitMask = new long[maskLongs];
    MemorySegment.copy(value, LONG, HEADER_SIZE + (long) codeSize, jumpDestBitMask, 0, maskLongs);
    final Code decoded = new Code(Bytes.wrap(code), codeHash);
    decoded.setJumpDestBitMask(jumpDestBitMask);
    return decoded;
  }
}
