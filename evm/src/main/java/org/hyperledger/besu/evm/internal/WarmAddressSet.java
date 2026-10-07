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
import java.util.function.Consumer;

import org.apache.tuweni.bytes.Bytes;

/**
 * The set of accounts a transaction has already touched, as EIP-2929 defines warmth.
 *
 * <p>Every CALL, BALANCE, EXTCODE* and SELFDESTRUCT consults it, so it is a flat open-addressing
 * table with the same secret-constant universal hash as {@link WarmStorageTable}: addresses are
 * chosen by whoever sends the transaction, so a fixed hash would let them collide every probe into
 * one chain. It supports membership, insertion, iteration and rolling back to an earlier mark.
 */
public final class WarmAddressSet implements Undoable {

  private static final int INITIAL_CAPACITY = 32;
  private static final long[] MULTIPLIERS = new long[7];

  static {
    final SecureRandom random = SecureRandomProvider.publicSecureRandom();
    for (int i = 0; i < MULTIPLIERS.length; i++) {
      MULTIPLIERS[i] = random.nextLong();
    }
  }

  private long[] hashes;
  private Address[] addresses;
  private int mask;
  private int size;

  private long[] logLevels;
  private Address[] logAddresses;
  private int logSize;

  /** Creates an empty set. */
  public WarmAddressSet() {
    allocate(INITIAL_CAPACITY);
    logLevels = new long[INITIAL_CAPACITY];
    logAddresses = new Address[INITIAL_CAPACITY];
  }

  private void allocate(final int capacity) {
    hashes = new long[capacity];
    addresses = new Address[capacity];
    mask = capacity - 1;
  }

  /**
   * Marks an address as warm.
   *
   * @param address the address
   * @return true if the address was already warm
   */
  public boolean warmUp(final Address address) {
    final long hash = hash(address);
    int index = (int) hash & mask;
    while (true) {
      final long candidate = hashes[index];
      if (candidate == 0L) {
        break;
      }
      if (candidate == hash && addresses[index].equals(address)) {
        return true;
      }
      index = (index + 1) & mask;
    }
    hashes[index] = hash;
    addresses[index] = address;
    size++;
    log(address);
    if (size * 2 > hashes.length) {
      grow();
    }
    return false;
  }

  /**
   * Whether an address is warm.
   *
   * @param address the address
   * @return true if the address is warm
   */
  public boolean contains(final Address address) {
    return find(address, hash(address)) >= 0;
  }

  /**
   * Number of warm addresses.
   *
   * @return the count
   */
  public int size() {
    return size;
  }

  /**
   * Whether no address is warm.
   *
   * @return true if empty
   */
  public boolean isEmpty() {
    return size == 0;
  }

  /**
   * Visits every warm address, in no particular order.
   *
   * @param visitor receives the address
   */
  public void forEach(final Consumer<Address> visitor) {
    for (int i = 0; i < hashes.length; i++) {
      if (hashes[i] != 0L) {
        visitor.accept(addresses[i]);
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
      logAddresses[logSize] = null;
      remove(find(address, hash(address)));
    }
  }

  private void log(final Address address) {
    if (logSize == logLevels.length) {
      final int capacity = logSize * 2;
      logLevels = Arrays.copyOf(logLevels, capacity);
      logAddresses = Arrays.copyOf(logAddresses, capacity);
    }
    logLevels[logSize] = Undoable.incrementMarkStatic();
    logAddresses[logSize] = address;
    logSize++;
  }

  private int find(final Address address, final long hash) {
    int index = (int) hash & mask;
    while (true) {
      final long candidate = hashes[index];
      if (candidate == 0L) {
        return -1;
      }
      if (candidate == hash && addresses[index].equals(address)) {
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
        hole = next;
      }
      next = (next + 1) & mask;
    }
    hashes[hole] = 0L;
    addresses[hole] = null;
    size--;
  }

  private void grow() {
    final long[] oldHashes = hashes;
    final Address[] oldAddresses = addresses;
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
      }
    }
  }

  private static long hash(final Address address) {
    final long hash = multiplyMix(address.getBytes());
    // zero marks an empty cell
    return hash == 0L ? 1L : hash;
  }

  /**
   * Pair-multiply-shift over the five 32-bit lanes of the address; see {@link WarmStorageTable} for
   * why the lanes are paired and the high half of the sum is taken.
   */
  private static long multiplyMix(final Bytes bytes) {
    long sum = MULTIPLIERS[6];
    sum += (MULTIPLIERS[0] + lane(bytes, 0)) * (MULTIPLIERS[1] + lane(bytes, 4));
    sum += (MULTIPLIERS[2] + lane(bytes, 8)) * (MULTIPLIERS[3] + lane(bytes, 12));
    sum += (MULTIPLIERS[4] + lane(bytes, 16)) * MULTIPLIERS[5];
    return sum >>> 32;
  }

  private static long lane(final Bytes bytes, final int offset) {
    return bytes.getInt(offset) & 0xffffffffL;
  }
}
