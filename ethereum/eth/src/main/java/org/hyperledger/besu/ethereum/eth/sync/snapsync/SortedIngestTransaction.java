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
package org.hyperledger.besu.ethereum.eth.sync.snapsync;

import org.hyperledger.besu.ethereum.eth.sync.snapsync.WorldStateSortedIngest.Batch;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.WorldStateSortedIngest.Entry;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorageTransaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects what a snap request writes instead of writing it, so the entries can be handed to {@link
 * WorldStateSortedIngest} in key order.
 */
class SortedIngestTransaction implements SegmentedKeyValueStorageTransaction {

  private final Map<SegmentIdentifier, List<Entry>> puts = new LinkedHashMap<>();
  private final Map<SegmentIdentifier, List<byte[]>> removals = new LinkedHashMap<>();

  @Override
  public void put(final SegmentIdentifier segmentIdentifier, final byte[] key, final byte[] value) {
    puts.computeIfAbsent(segmentIdentifier, __ -> new ArrayList<>()).add(new Entry(key, value));
  }

  @Override
  public void remove(final SegmentIdentifier segmentIdentifier, final byte[] key) {
    removals.computeIfAbsent(segmentIdentifier, __ -> new ArrayList<>()).add(key);
  }

  @Override
  public void commit() {
    // the collected entries are taken with toBatch
  }

  @Override
  public void rollback() {
    puts.clear();
    removals.clear();
  }

  @Override
  public void close() {}

  /**
   * The collected entries of every segment in ascending key order. Of several entries with the same
   * key the one written last is kept, as it would be by a transaction.
   *
   * @param onStored called once the entries are part of the storage
   * @return the batch to hand to the sorted ingest
   */
  Batch toBatch(final Runnable onStored) {
    final Map<SegmentIdentifier, List<Entry>> sorted = new LinkedHashMap<>();
    puts.forEach(
        (segment, entries) -> {
          final Entry[] ordered = entries.toArray(Entry[]::new);
          // the sort is stable, so equal keys keep the order they were written in
          Arrays.sort(ordered, (a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
          final List<Entry> distinct = new ArrayList<>(ordered.length);
          for (int i = 0; i < ordered.length; i++) {
            if (i + 1 == ordered.length || !Arrays.equals(ordered[i].key(), ordered[i + 1].key())) {
              distinct.add(ordered[i]);
            }
          }
          sorted.put(segment, distinct);
        });
    return new Batch(sorted, removals, onStored);
  }
}
