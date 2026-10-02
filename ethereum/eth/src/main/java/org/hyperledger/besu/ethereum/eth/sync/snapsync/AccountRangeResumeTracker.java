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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Tracks how far each account range partition of the snap world state download can safely be
 * resumed from after a restart.
 *
 * <p>The account trie is downloaded as a fixed set of partitions, each identified by its end key.
 * Account range requests of a partition are issued one after the other, and every response spawns
 * storage and code requests for the accounts it contains. Account ranges keep being downloaded
 * while those child requests are still outstanding, so the start of the next account range request
 * is not a safe resume point on its own: accounts before it may still miss storage or code.
 *
 * <p>The safe resume point of a partition is the lowest start among its account range requests that
 * have not completed and its downloaded account ranges that still have unfinished children.
 * Everything before that point is stored together with all of its storage and code.
 */
public class AccountRangeResumeTracker {

  /**
   * The account range response a storage or code request descends from.
   *
   * @param partitionEnd the end key of the account range partition
   * @param rangeStart the start key of the account range request that produced the response
   */
  public record Origin(Bytes32 partitionEnd, Bytes32 rangeStart) {}

  /**
   * A range that has to be downloaded again when the download resumes.
   *
   * @param start the first key to request
   * @param end the end key of the partition
   */
  public record ResumeRange(Bytes32 start, Bytes32 end) {}

  private static class Partition {
    // start key -> number of account range requests with that start that have not completed
    private final NavigableMap<Bytes32, Integer> activeAccountRequests = new TreeMap<>();
    // start key of a downloaded account range -> number of its unfinished child requests
    private final NavigableMap<Bytes32, Integer> unfinishedChildren = new TreeMap<>();

    private boolean isFinished() {
      return activeAccountRequests.isEmpty() && unfinishedChildren.isEmpty();
    }
  }

  private final Map<Bytes32, Partition> partitions = new HashMap<>();

  /**
   * Records an account range request that was queued for download.
   *
   * @param startKeyHash the start of the requested range
   * @param endKeyHash the end of the requested range, which identifies the partition
   */
  public synchronized void accountRequestEnqueued(
      final Bytes32 startKeyHash, final Bytes32 endKeyHash) {
    partitions
        .computeIfAbsent(endKeyHash, __ -> new Partition())
        .activeAccountRequests
        .merge(startKeyHash, 1, Integer::sum);
  }

  /**
   * Records that the response of an account range request has been stored.
   *
   * @param startKeyHash the start of the requested range
   * @param endKeyHash the end of the requested range, which identifies the partition
   */
  public synchronized void accountRequestCompleted(
      final Bytes32 startKeyHash, final Bytes32 endKeyHash) {
    final Partition partition = partitions.get(endKeyHash);
    if (partition != null) {
      decrement(partition.activeAccountRequests, startKeyHash);
      removeIfFinished(endKeyHash, partition);
    }
  }

  /**
   * Records a storage or code request that was queued for an account range response.
   *
   * @param origin the account range response the request descends from
   */
  public synchronized void childEnqueued(final Origin origin) {
    partitions
        .computeIfAbsent(origin.partitionEnd(), __ -> new Partition())
        .unfinishedChildren
        .merge(origin.rangeStart(), 1, Integer::sum);
  }

  /**
   * Records that the response of a storage or code request has been stored.
   *
   * @param origin the account range response the request descends from
   */
  public synchronized void childCompleted(final Origin origin) {
    final Partition partition = partitions.get(origin.partitionEnd());
    if (partition != null) {
      decrement(partition.unfinishedChildren, origin.rangeStart());
      removeIfFinished(origin.partitionEnd(), partition);
    }
  }

  /**
   * Returns the ranges to download again when resuming, one for each partition that is not
   * finished.
   *
   * @return the safe resume range of every unfinished partition
   */
  public synchronized List<ResumeRange> resumeRanges() {
    final List<ResumeRange> ranges = new ArrayList<>(partitions.size());
    partitions.forEach(
        (partitionEnd, partition) -> {
          final Bytes32 start;
          if (partition.unfinishedChildren.isEmpty()) {
            start = partition.activeAccountRequests.firstKey();
          } else if (partition.activeAccountRequests.isEmpty()) {
            start = partition.unfinishedChildren.firstKey();
          } else {
            final Bytes32 firstActive = partition.activeAccountRequests.firstKey();
            final Bytes32 firstUnfinished = partition.unfinishedChildren.firstKey();
            start = firstActive.compareTo(firstUnfinished) <= 0 ? firstActive : firstUnfinished;
          }
          ranges.add(new ResumeRange(start, partitionEnd));
        });
    return ranges;
  }

  /** Forgets all tracked requests. */
  public synchronized void clear() {
    partitions.clear();
  }

  private void removeIfFinished(final Bytes32 partitionEnd, final Partition partition) {
    if (partition.isFinished()) {
      partitions.remove(partitionEnd);
    }
  }

  private static void decrement(final Map<Bytes32, Integer> counts, final Bytes32 key) {
    counts.computeIfPresent(key, (__, count) -> count > 1 ? count - 1 : null);
  }
}
