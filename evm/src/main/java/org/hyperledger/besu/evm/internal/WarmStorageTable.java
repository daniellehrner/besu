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
package org.hyperledger.besu.evm.internal;

import org.hyperledger.besu.collections.undo.Undoable;
import org.hyperledger.besu.crypto.SecureRandomProvider;
import org.hyperledger.besu.datatypes.Address;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.BiConsumer;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * The set of storage slots a transaction has already touched, as EIP-2929 defines warmth, with the
 * values the transaction has seen in them.
 *
 * <p>Every SLOAD and SSTORE consults it, so it is a flat open-addressing table keyed by a hash of
 * the address and the slot rather than a map of maps. Next to warmth it keeps the current value of
 * a slot once the transaction has read or written it, and the value before the transaction once an
 * SSTORE has needed it, so that repeated accesses skip the world state. Rolling back to an earlier
 * mark forgets the slots warmed since and restores the values written since. The hash is a
 * universal family with secret constants drawn at start-up: slot keys are chosen by whoever sends
 * the transaction, so a fixed hash would let them collide every probe into one chain.
 */
public final class WarmStorageTable implements Undoable {

  private static final int INITIAL_CAPACITY = 64;
  private static final int INITIAL_LOG_CAPACITY = 32;
  private static final long[] MULTIPLIERS = new long[15];

  static {
    final SecureRandom random = SecureRandomProvider.publicSecureRandom();
    for (int i = 0; i < MULTIPLIERS.length; i++) {
      MULTIPLIERS[i] = random.nextLong();
    }
  }

  private static final VarHandle LONG_BE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
  private static final VarHandle INT_BE =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

  // a key as words: the slot's four, then the address's two and a half
  private static final int WORDS = 7;
  // a value as the four words the v2 stack keeps, most significant first
  private static final int VALUE_WORDS = 4;

  private static final byte WARM_UP = 0;
  private static final byte WRITE = 1;
  private static final byte CLEAR = 2;

  private long[] hashes;
  // compared instead of the keys themselves, whose equals reads them a byte at a time
  private long[] words;
  private Address[] addresses;
  // what the transaction sees in the slot, null until it has read or written it
  private UInt256[] currentValues;
  private long[] currentWords;
  // what the slot held before the transaction, null until an SSTORE has needed it
  private UInt256[] originalValues;
  private int mask;
  private int size;
  // the key being looked up; a table belongs to one transaction, so one thread
  private final long[] probe = new long[WORDS];

  private long[] logLevels;
  private byte[] logKinds;
  // the slot a warm-up or a write touched
  private long[] logKeys;
  // the account whose storage a clear emptied
  private Address[] logAddresses;
  // the value a write replaced
  private UInt256[] logValues;
  private int logSize;

  /** Creates an empty table. */
  public WarmStorageTable() {
    allocate(INITIAL_CAPACITY);
    logLevels = new long[INITIAL_LOG_CAPACITY];
    logKinds = new byte[INITIAL_LOG_CAPACITY];
    logKeys = new long[INITIAL_LOG_CAPACITY * WORDS];
    logAddresses = new Address[INITIAL_LOG_CAPACITY];
    logValues = new UInt256[INITIAL_LOG_CAPACITY];
  }

  private void allocate(final int capacity) {
    hashes = new long[capacity];
    words = new long[capacity * WORDS];
    addresses = new Address[capacity];
    currentValues = new UInt256[capacity];
    currentWords = new long[capacity * VALUE_WORDS];
    originalValues = new UInt256[capacity];
    mask = capacity - 1;
  }

  /**
   * Marks a slot as warm.
   *
   * @param address the account the slot belongs to
   * @param slot the slot
   * @return true if the slot was already warm
   */
  public boolean warmUp(final Address address, final Bytes32 slot) {
    return insert(address, load(address, slot)) >= 0;
  }

  /**
   * Marks a slot, given as the words of the v2 stack, as warm.
   *
   * <p>The returned entry stays valid until the next warm-up or rollback.
   *
   * @param address the account the slot belongs to
   * @param slot0 the most significant word of the slot
   * @param slot1 the second word of the slot
   * @param slot2 the third word of the slot
   * @param slot3 the least significant word of the slot
   * @return the slot's entry if it was already warm, or the bitwise complement of its new entry
   */
  public int warmUp(
      final Address address,
      final long slot0,
      final long slot1,
      final long slot2,
      final long slot3) {
    final long[] key = probe;
    key[0] = slot0;
    key[1] = slot1;
    key[2] = slot2;
    key[3] = slot3;
    loadAddress(address, key);
    return insert(address, key);
  }

  private int insert(final Address address, final long[] key) {
    final long hash = hash(key);
    int index = (int) hash & mask;
    while (true) {
      final long candidate = hashes[index];
      if (candidate == 0L) {
        break;
      }
      if (candidate == hash && holds(index, key)) {
        return index;
      }
      index = (index + 1) & mask;
    }
    if ((size + 1) * 2 > hashes.length) {
      grow();
      index = (int) hash & mask;
      while (hashes[index] != 0L) {
        index = (index + 1) & mask;
      }
    }
    hashes[index] = hash;
    System.arraycopy(key, 0, words, index * WORDS, WORDS);
    addresses[index] = address;
    size++;
    logSlot(WARM_UP, key, 0, null);
    return ~index;
  }

  /**
   * Whether a slot is warm.
   *
   * @param address the account the slot belongs to
   * @param slot the slot
   * @return true if the slot is warm
   */
  public boolean contains(final Address address, final Bytes32 slot) {
    final long[] key = load(address, slot);
    return find(key, hash(key)) >= 0;
  }

  /**
   * Number of warm slots.
   *
   * @return the count
   */
  public int size() {
    return size;
  }

  /**
   * Whether no slot is warm.
   *
   * @return true if empty
   */
  public boolean isEmpty() {
    return size == 0;
  }

  /**
   * Visits every warm slot, in no particular order.
   *
   * @param visitor receives the account and the slot
   */
  public void forEach(final BiConsumer<Address, Bytes32> visitor) {
    for (int i = 0; i < hashes.length; i++) {
      if (hashes[i] != 0L) {
        final int base = i * WORDS;
        final byte[] slot = new byte[32];
        LONG_BE.set(slot, 0, words[base]);
        LONG_BE.set(slot, 8, words[base + 1]);
        LONG_BE.set(slot, 16, words[base + 2]);
        LONG_BE.set(slot, 24, words[base + 3]);
        visitor.accept(addresses[i], Bytes32.wrap(slot));
      }
    }
  }

  /**
   * The value the transaction sees in a slot, if it has read or written the slot.
   *
   * @param entry the slot's entry
   * @return the value, or null if the table does not know it
   */
  public UInt256 currentValue(final int entry) {
    return currentValues[entry];
  }

  /**
   * Copies the value the transaction sees in a slot into a word of the v2 stack, if the table knows
   * it.
   *
   * @param entry the slot's entry
   * @param stack the v2 stack
   * @param offset the index of the word's most significant long
   * @return whether the table knew the value
   */
  public boolean copyCurrentValue(final int entry, final long[] stack, final int offset) {
    if (currentValues[entry] == null) {
      return false;
    }
    final int base = entry * VALUE_WORDS;
    stack[offset] = currentWords[base];
    stack[offset + 1] = currentWords[base + 1];
    stack[offset + 2] = currentWords[base + 2];
    stack[offset + 3] = currentWords[base + 3];
    return true;
  }

  /**
   * Records the value the world state holds in a slot, as the transaction has just read it.
   *
   * @param entry the slot's entry
   * @param value the value
   * @param word0 the most significant word of the value
   * @param word1 the second word of the value
   * @param word2 the third word of the value
   * @param word3 the least significant word of the value
   */
  public void fillCurrentValue(
      final int entry,
      final UInt256 value,
      final long word0,
      final long word1,
      final long word2,
      final long word3) {
    // a read changes nothing, so the value holds as far back as the last write a rollback restores
    setCurrentValue(entry, value, word0, word1, word2, word3);
  }

  /**
   * Records the value the world state holds in a slot, as the transaction has just read it.
   *
   * @param entry the slot's entry
   * @param value the value
   */
  public void fillCurrentValue(final int entry, final UInt256 value) {
    setCurrentValue(
        entry, value, value.getLong(0), value.getLong(8), value.getLong(16), value.getLong(24));
  }

  /**
   * Records a value the transaction has written into a slot; rolling back past the write restores
   * the value it replaced.
   *
   * @param entry the slot's entry
   * @param value the value
   * @param word0 the most significant word of the value
   * @param word1 the second word of the value
   * @param word2 the third word of the value
   * @param word3 the least significant word of the value
   */
  public void writeCurrentValue(
      final int entry,
      final UInt256 value,
      final long word0,
      final long word1,
      final long word2,
      final long word3) {
    final UInt256 replaced = currentValues[entry];
    if (value.equals(replaced)) {
      return;
    }
    logSlot(WRITE, words, entry * WORDS, replaced);
    setCurrentValue(entry, value, word0, word1, word2, word3);
  }

  private void setCurrentValue(
      final int entry,
      final UInt256 value,
      final long word0,
      final long word1,
      final long word2,
      final long word3) {
    currentValues[entry] = value;
    final int base = entry * VALUE_WORDS;
    currentWords[base] = word0;
    currentWords[base + 1] = word1;
    currentWords[base + 2] = word2;
    currentWords[base + 3] = word3;
  }

  /**
   * The value a slot held before the transaction, if an SSTORE has needed it.
   *
   * @param entry the slot's entry
   * @return the value, or null if the table does not know it
   */
  public UInt256 originalValue(final int entry) {
    return originalValues[entry];
  }

  /**
   * Records the value a slot held before the transaction.
   *
   * @param entry the slot's entry
   * @param value the value
   */
  public void fillOriginalValue(final int entry, final UInt256 value) {
    originalValues[entry] = value;
  }

  /**
   * Forgets the values of every slot of an account whose storage the transaction has cleared;
   * rolling back past the clear forgets them again, as they may have been read since.
   *
   * @param address the account
   */
  public void clearValues(final Address address) {
    forgetValues(address);
    if (logSize == logLevels.length) {
      growLog();
    }
    logLevels[logSize] = Undoable.incrementMarkStatic();
    logKinds[logSize] = CLEAR;
    logAddresses[logSize] = address;
    logSize++;
  }

  private void forgetValues(final Address address) {
    final long[] key = probe;
    loadAddress(address, key);
    for (int i = 0; i < hashes.length; i++) {
      final int base = i * WORDS;
      if (hashes[i] != 0L
          && words[base + 4] == key[4]
          && words[base + 5] == key[5]
          && words[base + 6] == key[6]) {
        currentValues[i] = null;
        originalValues[i] = null;
      }
    }
  }

  @Override
  public long lastUpdate() {
    return logSize == 0 ? 0L : logLevels[logSize - 1];
  }

  @Override
  public void undo(final long mark) {
    while (logSize > 0 && logLevels[logSize - 1] > mark) {
      logSize--;
      final byte kind = logKinds[logSize];
      if (kind == CLEAR) {
        forgetValues(logAddresses[logSize]);
        logAddresses[logSize] = null;
        continue;
      }
      final long[] key = probe;
      System.arraycopy(logKeys, logSize * WORDS, key, 0, WORDS);
      final int index = find(key, hash(key));
      if (kind == WARM_UP) {
        remove(index);
      } else {
        final UInt256 replaced = logValues[logSize];
        logValues[logSize] = null;
        if (replaced == null) {
          currentValues[index] = null;
        } else {
          setCurrentValue(
              index,
              replaced,
              replaced.getLong(0),
              replaced.getLong(8),
              replaced.getLong(16),
              replaced.getLong(24));
        }
      }
    }
  }

  private void logSlot(
      final byte kind, final long[] key, final int keyOffset, final UInt256 replaced) {
    if (logSize == logLevels.length) {
      growLog();
    }
    logLevels[logSize] = Undoable.incrementMarkStatic();
    logKinds[logSize] = kind;
    System.arraycopy(key, keyOffset, logKeys, logSize * WORDS, WORDS);
    logValues[logSize] = replaced;
    logSize++;
  }

  private void growLog() {
    final int capacity = logLevels.length * 2;
    logLevels = Arrays.copyOf(logLevels, capacity);
    logKinds = Arrays.copyOf(logKinds, capacity);
    logKeys = Arrays.copyOf(logKeys, capacity * WORDS);
    logAddresses = Arrays.copyOf(logAddresses, capacity);
    logValues = Arrays.copyOf(logValues, capacity);
  }

  private int find(final long[] key, final long hash) {
    int index = (int) hash & mask;
    while (true) {
      final long candidate = hashes[index];
      if (candidate == 0L) {
        return -1;
      }
      if (candidate == hash && holds(index, key)) {
        return index;
      }
      index = (index + 1) & mask;
    }
  }

  private boolean holds(final int index, final long[] key) {
    final int base = index * WORDS;
    return words[base] == key[0]
        && words[base + 1] == key[1]
        && words[base + 2] == key[2]
        && words[base + 3] == key[3]
        && words[base + 4] == key[4]
        && words[base + 5] == key[5]
        && words[base + 6] == key[6];
  }

  private long[] load(final Address address, final Bytes32 slot) {
    final byte[] slotBytes = slot.toArrayUnsafe();
    final long[] key = probe;
    key[0] = (long) LONG_BE.get(slotBytes, 0);
    key[1] = (long) LONG_BE.get(slotBytes, 8);
    key[2] = (long) LONG_BE.get(slotBytes, 16);
    key[3] = (long) LONG_BE.get(slotBytes, 24);
    loadAddress(address, key);
    return key;
  }

  private static void loadAddress(final Address address, final long[] key) {
    final byte[] addressBytes = address.getBytes().toArrayUnsafe();
    key[4] = (long) LONG_BE.get(addressBytes, 0);
    key[5] = (long) LONG_BE.get(addressBytes, 8);
    key[6] = (int) INT_BE.get(addressBytes, 16) & 0xffffffffL;
  }

  /**
   * Removes the entry at an index, shifting later entries of the same probe run back so every
   * remaining entry stays reachable from its home index.
   */
  private void remove(final int index) {
    int hole = index;
    int next = (hole + 1) & mask;
    while (hashes[next] != 0L) {
      final int home = (int) hashes[next] & mask;
      // the entry can move into the hole only if the hole lies on its way from home
      final boolean movable =
          hole <= next ? (home <= hole || home > next) : (home <= hole && home > next);
      if (movable) {
        move(next, hole);
        hole = next;
      }
      next = (next + 1) & mask;
    }
    hashes[hole] = 0L;
    addresses[hole] = null;
    currentValues[hole] = null;
    originalValues[hole] = null;
    size--;
  }

  private void move(final int from, final int to) {
    hashes[to] = hashes[from];
    System.arraycopy(words, from * WORDS, words, to * WORDS, WORDS);
    addresses[to] = addresses[from];
    currentValues[to] = currentValues[from];
    System.arraycopy(currentWords, from * VALUE_WORDS, currentWords, to * VALUE_WORDS, VALUE_WORDS);
    originalValues[to] = originalValues[from];
  }

  private void grow() {
    final long[] oldHashes = hashes;
    final long[] oldWords = words;
    final Address[] oldAddresses = addresses;
    final UInt256[] oldCurrentValues = currentValues;
    final long[] oldCurrentWords = currentWords;
    final UInt256[] oldOriginalValues = originalValues;
    allocate(oldHashes.length * 2);
    for (int i = 0; i < oldHashes.length; i++) {
      final long hash = oldHashes[i];
      if (hash != 0L) {
        int index = (int) hash & mask;
        while (hashes[index] != 0L) {
          index = (index + 1) & mask;
        }
        hashes[index] = hash;
        System.arraycopy(oldWords, i * WORDS, words, index * WORDS, WORDS);
        addresses[index] = oldAddresses[i];
        currentValues[index] = oldCurrentValues[i];
        System.arraycopy(
            oldCurrentWords, i * VALUE_WORDS, currentWords, index * VALUE_WORDS, VALUE_WORDS);
        originalValues[index] = oldOriginalValues[i];
      }
    }
  }

  private static long hash(final long[] key) {
    final long hash = multiplyMix(key);
    // zero marks an empty cell
    return hash == 0L ? 1L : hash;
  }

  /**
   * Pair-multiply-shift over the 32-bit lanes of the slot and the address: each pair of lanes is
   * offset by secret constants, multiplied, and the products summed; the top 32 bits of the sum are
   * the hash. Summing single-lane products modulo 2^64 would let two keys that differ only in the
   * top bits of two lanes collide regardless of the secret, which is why the lanes are paired and
   * the high half is taken.
   */
  private static long multiplyMix(final long[] key) {
    long sum = MULTIPLIERS[14];
    sum += (MULTIPLIERS[0] + (key[0] >>> 32)) * (MULTIPLIERS[1] + (key[0] & 0xffffffffL));
    sum += (MULTIPLIERS[2] + (key[1] >>> 32)) * (MULTIPLIERS[3] + (key[1] & 0xffffffffL));
    sum += (MULTIPLIERS[4] + (key[2] >>> 32)) * (MULTIPLIERS[5] + (key[2] & 0xffffffffL));
    sum += (MULTIPLIERS[6] + (key[3] >>> 32)) * (MULTIPLIERS[7] + (key[3] & 0xffffffffL));
    sum += (MULTIPLIERS[8] + (key[4] >>> 32)) * (MULTIPLIERS[9] + (key[4] & 0xffffffffL));
    sum += (MULTIPLIERS[10] + (key[5] >>> 32)) * (MULTIPLIERS[11] + (key[5] & 0xffffffffL));
    sum += (MULTIPLIERS[12] + key[6]) * MULTIPLIERS[13];
    return sum >>> 32;
  }
}
