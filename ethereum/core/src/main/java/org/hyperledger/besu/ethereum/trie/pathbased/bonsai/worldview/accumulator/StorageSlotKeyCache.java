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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.StorageSlotKey;

import java.util.Optional;

import org.apache.tuweni.units.bigints.UInt256;

/**
 * Storage slot keys with their hashes, shared by all accumulators: every transaction of a block
 * gets its own accumulator, and the same slots come back in the next blocks.
 */
final class StorageSlotKeyCache {

  private static final int BITS = 15;
  private static final StorageSlotKey[] KEYS = new StorageSlotKey[1 << BITS];

  private StorageSlotKeyCache() {}

  static StorageSlotKey get(final UInt256 slot) {
    final int index = index(slot);
    // threads race only to store complete keys, whose fields are final
    final StorageSlotKey cached = KEYS[index];
    if (cached != null && slot.equals(cached.getSlotKey().orElse(null))) {
      return cached;
    }
    final StorageSlotKey created = new StorageSlotKey(Hash.hash(slot), Optional.of(slot));
    KEYS[index] = created;
    return created;
  }

  private static int index(final UInt256 slot) {
    // the low bytes differ between small slot numbers, and mapping slots are hashes anyway
    final long low = slot.getLong(24);
    return (int) (((low ^ (low >>> 32)) * 0x9E3779B97F4A7C15L) >>> (64 - BITS));
  }
}
