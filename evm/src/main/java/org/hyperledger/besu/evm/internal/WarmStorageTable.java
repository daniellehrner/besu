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

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.BiConsumer;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * The set of storage slots a transaction has already touched, as EIP-2929 defines warmth.
 *
 * <p>Every SLOAD and SSTORE consults it, so it is a flat open-addressing table keyed by a hash of
 * the address and the slot rather than a map of maps, and it supports nothing beyond membership,
 * insertion, iteration and rolling back to an earlier mark. The hash is a universal family with
 * secret constants drawn at start-up: slot keys are chosen by whoever sends the transaction, so a
 * fixed hash would let them collide every probe into one chain.
 */
public final class WarmStorageTable implements Undoable {

  private static final int INITIAL_CAPACITY = 64;
  private static final long[] MULTIPLIERS = new long[15];

  static {
    final SecureRandom random = SecureRandomProvider.publicSecureRandom();
    for (int i = 0; i < MULTIPLIERS.length; i++) {
      MULTIPLIERS[i] = random.nextLong();
    }
  }

  private long[] hashes;
  private Address[] addresses;
  private Bytes32[] slots;
  private int mask;
  private int size;

  private long[] logLevels;
  private Address[] logAddresses;
  private Bytes32[] logSlots;
  private int logSize;

  /** Creates an empty table. */
  public WarmStorageTable() {
    allocate(INITIAL_CAPACITY);
    logLevels = new long[INITIAL_CAPACITY];
    logAddresses = new Address[INITIAL_CAPACITY];
    logSlots = new Bytes32[INITIAL_CAPACITY];
  }

  private void allocate(final int capacity) {
    hashes = new long[capacity];
    addresses = new Address[capacity];
    slots = new Bytes32[capacity];
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
    final long hash = hash(address, slot);
    int index = (int) hash & mask;
    while (true) {
      final long candidate = hashes[index];
      if (candidate == 0L) {
        break;
      }
      if (candidate == hash && slots[index].equals(slot) && addresses[index].equals(address)) {
        return true;
      }
      index = (index + 1) & mask;
    }
    hashes[index] = hash;
    addresses[index] = address;
    slots[index] = slot;
    size++;
    log(address, slot);
    if (size * 2 > hashes.length) {
      grow();
    }
    return false;
  }

  /**
   * Whether a slot is warm.
   *
   * @param address the account the slot belongs to
   * @param slot the slot
   * @return true if the slot is warm
   */
  public boolean contains(final Address address, final Bytes32 slot) {
    return find(address, slot, hash(address, slot)) >= 0;
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
        visitor.accept(addresses[i], slots[i]);
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
      final Address address = logAddresses[logSize];
      final Bytes32 slot = logSlots[logSize];
      logAddresses[logSize] = null;
      logSlots[logSize] = null;
      remove(find(address, slot, hash(address, slot)));
    }
  }

  private void log(final Address address, final Bytes32 slot) {
    if (logSize == logLevels.length) {
      final int capacity = logSize * 2;
      logLevels = Arrays.copyOf(logLevels, capacity);
      logAddresses = Arrays.copyOf(logAddresses, capacity);
      logSlots = Arrays.copyOf(logSlots, capacity);
    }
    logLevels[logSize] = Undoable.incrementMarkStatic();
    logAddresses[logSize] = address;
    logSlots[logSize] = slot;
    logSize++;
  }

  private int find(final Address address, final Bytes32 slot, final long hash) {
    int index = (int) hash & mask;
    while (true) {
      final long candidate = hashes[index];
      if (candidate == 0L) {
        return -1;
      }
      if (candidate == hash && slots[index].equals(slot) && addresses[index].equals(address)) {
        return index;
      }
      index = (index + 1) & mask;
    }
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
        hashes[hole] = hashes[next];
        addresses[hole] = addresses[next];
        slots[hole] = slots[next];
        hole = next;
      }
      next = (next + 1) & mask;
    }
    hashes[hole] = 0L;
    addresses[hole] = null;
    slots[hole] = null;
    size--;
  }

  private void grow() {
    final long[] oldHashes = hashes;
    final Address[] oldAddresses = addresses;
    final Bytes32[] oldSlots = slots;
    allocate(oldHashes.length * 2);
    for (int i = 0; i < oldHashes.length; i++) {
      final long hash = oldHashes[i];
      if (hash != 0L) {
        int index = (int) hash & mask;
        while (hashes[index] != 0L) {
          index = (index + 1) & mask;
        }
        hashes[index] = hash;
        addresses[index] = oldAddresses[i];
        slots[index] = oldSlots[i];
      }
    }
  }

  private static long hash(final Address address, final Bytes32 slot) {
    final long hash = multiplyMix(address, slot);
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
  private static long multiplyMix(final Address address, final Bytes32 slot) {
    final Bytes addressBytes = address.getBytes();
    long sum = MULTIPLIERS[14];
    sum += (MULTIPLIERS[0] + lane(slot, 0)) * (MULTIPLIERS[1] + lane(slot, 4));
    sum += (MULTIPLIERS[2] + lane(slot, 8)) * (MULTIPLIERS[3] + lane(slot, 12));
    sum += (MULTIPLIERS[4] + lane(slot, 16)) * (MULTIPLIERS[5] + lane(slot, 20));
    sum += (MULTIPLIERS[6] + lane(slot, 24)) * (MULTIPLIERS[7] + lane(slot, 28));
    sum += (MULTIPLIERS[8] + lane(addressBytes, 0)) * (MULTIPLIERS[9] + lane(addressBytes, 4));
    sum += (MULTIPLIERS[10] + lane(addressBytes, 8)) * (MULTIPLIERS[11] + lane(addressBytes, 12));
    sum += (MULTIPLIERS[12] + lane(addressBytes, 16)) * MULTIPLIERS[13];
    return sum >>> 32;
  }

  private static long lane(final Bytes bytes, final int offset) {
    return bytes.getInt(offset) & 0xffffffffL;
  }
}
