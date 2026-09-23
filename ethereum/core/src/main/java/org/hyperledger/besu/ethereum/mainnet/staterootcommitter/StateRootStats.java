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
package org.hyperledger.besu.ethereum.mainnet.staterootcommitter;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide counters for state root computations, split by pass. A block's root is computed once
 * on a frozen world state while the block is validated, and again when the persisted world state is
 * rolled forward to it; only the first is covered by the block processing timer.
 */
public final class StateRootStats {

  /** Upper bounds (inclusive) of the slot-updates-per-storage-trie buckets; the last is open. */
  private static final int[] STORAGE_UPDATE_BUCKETS = {1, 3, 7, 31, 127};

  public static final int FROZEN = 0;
  public static final int PERSISTING = 1;

  private static final LongAdder[] COMPUTATIONS = adders(2);
  private static final LongAdder[] NANOS = adders(2);
  private static final LongAdder[] STORAGE_TRIES = adders(STORAGE_UPDATE_BUCKETS.length + 1);
  private static final LongAdder[] STORAGE_TRIE_NANOS = adders(STORAGE_UPDATE_BUCKETS.length + 1);

  private StateRootStats() {}

  private static LongAdder[] adders(final int count) {
    final LongAdder[] adders = new LongAdder[count];
    for (int i = 0; i < count; i++) {
      adders[i] = new LongAdder();
    }
    return adders;
  }

  public static void recordComputation(final boolean frozen, final long nanos) {
    final int pass = frozen ? FROZEN : PERSISTING;
    COMPUTATIONS[pass].increment();
    NANOS[pass].add(nanos);
  }

  public static void recordStorageTrie(final int slotUpdates, final long nanos) {
    int bucket = 0;
    while (bucket < STORAGE_UPDATE_BUCKETS.length && slotUpdates > STORAGE_UPDATE_BUCKETS[bucket]) {
      bucket++;
    }
    STORAGE_TRIES[bucket].increment();
    STORAGE_TRIE_NANOS[bucket].add(nanos);
  }

  /** Number of buckets, including the open-ended last one. */
  public static int storageBucketCount() {
    return STORAGE_UPDATE_BUCKETS.length + 1;
  }

  /** Label of a bucket: its inclusive upper bound, or "inf" for the last. */
  public static String storageBucketLabel(final int bucket) {
    return bucket < STORAGE_UPDATE_BUCKETS.length
        ? String.valueOf(STORAGE_UPDATE_BUCKETS[bucket])
        : "inf";
  }

  public static long computations(final int pass) {
    return COMPUTATIONS[pass].sum();
  }

  public static long nanos(final int pass) {
    return NANOS[pass].sum();
  }

  public static long storageTries(final int bucket) {
    return STORAGE_TRIES[bucket].sum();
  }

  public static long storageTrieNanos(final int bucket) {
    return STORAGE_TRIE_NANOS[bucket].sum();
  }
}
