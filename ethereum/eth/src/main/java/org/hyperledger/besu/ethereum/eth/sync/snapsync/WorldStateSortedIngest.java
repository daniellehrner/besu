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

import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.ACCOUNT_INFO_STATE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.ACCOUNT_STORAGE_STORAGE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE;

import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.plugin.services.storage.SegmentBulkLoad;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorageTransaction;
import org.hyperledger.besu.plugin.services.storage.SortedSegmentWriter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the world state downloaded by snap sync through {@link SortedSegmentWriter}s instead of
 * transactions.
 *
 * <p>The range download delivers the state as a number of streams whose keys only ever go up: the
 * account ranges of a partition, the storage of the accounts of a partition, and each of the ranges
 * a large contract is split into. Every stream gets files of its own. Their key ranges hold nothing
 * else, so the storage can keep each file as it was written instead of rewriting its entries
 * several times on their way through the regular write path.
 *
 * <p>The storage of an account that does not fit one response is continued in ranges that are
 * downloaded side by side, while the storage of the accounts after it already arrives. Those
 * accounts wait in memory until the ranges are complete, and the whole storage goes into the file
 * of the partition in key order. That way the many contracts with a few megabytes of storage do not
 * leave the storage with a file for each of their ranges. Only a contract with more storage than a
 * file holds is not waited for: the file of the partition ends with its first response, so that no
 * file spans the account, and its ranges are stored as files of their own.
 *
 * <p>What does not arrive in order, the storage of an account that had to be requested again, is
 * held back until the file that spans its account is stored, and then takes the regular write path.
 * Putting it into the storage first would leave keys inside the key range of that file, and a file
 * that is not alone in its range cannot be kept as it is.
 *
 * <p>A peer answers a range request with one entry more than the range holds, the first one after
 * its end, as the proof that nothing is missing in between. That entry belongs to the stream of the
 * next range, and a file reaching into the range of another stream cannot be kept as it is either.
 * It is therefore taken out of the response before the rest goes into the file.
 *
 * <p>Nothing written here is readable before its file is stored, which {@link #finishAll()} does
 * for whatever is still open. Callers that read the state back have to call it first.
 */
public class WorldStateSortedIngest {

  private static final Logger LOG = LoggerFactory.getLogger(WorldStateSortedIngest.class);

  /**
   * The sizes that decide when entries get a file and when a file is stored.
   *
   * @param targetFileSize a file is stored once it holds this much
   * @param rangeFileThreshold a range of a contract that is not waited for gets files of its own
   *     once it holds this much, below it takes the regular write path
   * @param queuedAccountLimit how much of the storage of an account may wait in memory for the rest
   *     of it. An account with more than this is a contract with files of its own.
   * @param bufferLimit what may wait in memory altogether. Beyond it the partition holding the most
   *     stops waiting for the account it waits for.
   */
  record Limits(
      long targetFileSize, long rangeFileThreshold, long queuedAccountLimit, long bufferLimit) {
    static final Limits DEFAULT =
        new Limits(64L * 1024 * 1024, 1024L * 1024, 64L * 1024 * 1024, 256L * 1024 * 1024);
  }

  /** The segments of the world state whose entries are downloaded in key order. */
  static final List<SegmentIdentifier> SORTED_SEGMENTS =
      List.of(ACCOUNT_INFO_STATE, ACCOUNT_STORAGE_STORAGE, TRIE_BRANCH_STORAGE);

  private static final int STORAGE_PARTITIONS = 16;
  private static final int DIRECT_WRITE_ATTEMPTS = 3;

  /**
   * An entry of a segment.
   *
   * @param key the key
   * @param value the value
   */
  public record Entry(byte[] key, byte[] value) {}

  /** What one response adds to the storage, each segment in ascending key order. */
  public static final class Batch {
    private final Map<SegmentIdentifier, List<Entry>> entries;
    private final Map<SegmentIdentifier, List<byte[]>> removals;
    private final Runnable onStored;
    private final long size;

    Batch(
        final Map<SegmentIdentifier, List<Entry>> entries,
        final Map<SegmentIdentifier, List<byte[]>> removals,
        final Runnable onStored) {
      this.entries = new LinkedHashMap<>();
      long total = 0;
      for (final Map.Entry<SegmentIdentifier, List<Entry>> segment : entries.entrySet()) {
        if (!segment.getValue().isEmpty()) {
          this.entries.put(segment.getKey(), segment.getValue());
          for (final Entry entry : segment.getValue()) {
            total += entry.key().length + entry.value().length;
          }
        }
      }
      this.removals = removals;
      this.onStored = onStored;
      this.size = total;
    }

    private boolean isEmpty() {
      return entries.isEmpty() && removals.isEmpty();
    }

    /**
     * The entries of the batch that belong to the range after the requested one: a peer adds the
     * first entry after the end of the range to its response, and the response comes with the trie
     * node that entry is the leaf of.
     *
     * @param flatSegment the segment of the entries the trie is built from
     * @param keyPrefix what the keys of both segments start with, the account hash for a storage
     * @param rangeEnd the end key of the requested range
     */
    private Map<SegmentIdentifier, List<Entry>> entriesAfter(
        final SegmentIdentifier flatSegment, final Bytes keyPrefix, final Bytes32 rangeEnd) {
      final Map<SegmentIdentifier, List<Entry>> after = new LinkedHashMap<>();
      final List<Entry> flatEntries = entries.getOrDefault(flatSegment, List.of());
      final int firstFlatAfter =
          firstAbove(flatEntries, Bytes.concatenate(keyPrefix, rangeEnd).toArrayUnsafe());
      final List<Entry> trieNodes = entries.getOrDefault(TRIE_BRANCH_STORAGE, List.of());
      int firstNodeAfter =
          firstAbove(
              trieNodes,
              Bytes.concatenate(keyPrefix, Bytes.wrap(pathOf(rangeEnd))).toArrayUnsafe());
      if (firstFlatAfter < flatEntries.size()) {
        after.put(flatSegment, flatEntries.subList(firstFlatAfter, flatEntries.size()));
        // The leaf of the first entry after the range can be a node on the path to the end of the
        // range, which puts its key before that of the end. Nothing but that leaf can be on the
        // path of the entry and behind every other node of the range.
        final byte[] entryPath =
            Bytes.concatenate(
                    keyPrefix,
                    Bytes.wrap(
                        pathOf(
                            Bytes.wrap(flatEntries.get(firstFlatAfter).key())
                                .slice(keyPrefix.size()))))
                .toArrayUnsafe();
        if (firstNodeAfter > 0 && isPrefix(trieNodes.get(firstNodeAfter - 1).key(), entryPath)) {
          // unless the leaf itself is behind the end, and this is a node above it
          final boolean leafIsAfterTheEnd =
              trieNodes.subList(firstNodeAfter, trieNodes.size()).stream()
                  .anyMatch(node -> isPrefix(node.key(), entryPath));
          if (!leafIsAfterTheEnd) {
            firstNodeAfter--;
          }
        }
      }
      if (firstNodeAfter < trieNodes.size()) {
        after.put(TRIE_BRANCH_STORAGE, trieNodes.subList(firstNodeAfter, trieNodes.size()));
      }
      return after;
    }

    /** The index of the first entry whose key is above the given one. */
    private static int firstAbove(final List<Entry> sortedEntries, final byte[] key) {
      int first = sortedEntries.size();
      while (first > 0 && Arrays.compareUnsigned(sortedEntries.get(first - 1).key(), key) > 0) {
        first--;
      }
      return first;
    }

    private static boolean isPrefix(final byte[] prefix, final byte[] key) {
      return prefix.length <= key.length
          && Arrays.equals(prefix, 0, prefix.length, key, 0, prefix.length);
    }

    /** The batch without the given entries, which are the last ones of their segments. */
    private Batch without(
        final Map<SegmentIdentifier, List<Entry>> lastEntries, final Runnable onStored) {
      final Map<SegmentIdentifier, List<Entry>> remaining = new LinkedHashMap<>();
      entries.forEach(
          (segment, segmentEntries) ->
              remaining.put(
                  segment,
                  segmentEntries.subList(
                      0,
                      segmentEntries.size()
                          - lastEntries.getOrDefault(segment, List.of()).size())));
      return new Batch(remaining, removals, onStored);
    }
  }

  /** The files of one stream. A stream has at most one open file, with one writer per segment. */
  private final class Run {
    private final Map<SegmentIdentifier, SortedSegmentWriter> writers = new LinkedHashMap<>();
    // survive the file they were written to: what follows has to stay above them
    private final Map<SegmentIdentifier, byte[]> lastKeys = new HashMap<>();
    private List<Runnable> onStored = new ArrayList<>();
    private long openSize;

    private boolean hasOpenFile() {
      return !writers.isEmpty();
    }

    /** Whether every key of the batch is above what this stream wrote before. */
    private boolean follows(final Batch batch) {
      if (!batch.removals.isEmpty()) {
        return false;
      }
      for (final Map.Entry<SegmentIdentifier, List<Entry>> segment : batch.entries.entrySet()) {
        final byte[] lastKey = lastKeys.get(segment.getKey());
        if (lastKey != null
            && Arrays.compareUnsigned(segment.getValue().getFirst().key(), lastKey) <= 0) {
          return false;
        }
      }
      return true;
    }

    private void append(final Batch batch) {
      batch.entries.forEach(
          (segment, entries) -> {
            final SortedSegmentWriter writer =
                writers.computeIfAbsent(segment, storage::sortedWriter);
            for (final Entry entry : entries) {
              writer.put(entry.key(), entry.value());
            }
            lastKeys.put(segment, entries.getLast().key());
          });
      openSize += batch.size;
      onStored.add(batch.onStored);
    }

    /** Takes the open file off the stream. Running the returned action stores it. */
    private Runnable seal() {
      final List<SortedSegmentWriter> sealedWriters = new ArrayList<>(writers.values());
      final List<Runnable> sealedOnStored = onStored;
      final long sealedSize = openSize;
      writers.clear();
      onStored = new ArrayList<>();
      openSize = 0;
      return () -> {
        try {
          for (final SortedSegmentWriter writer : sealedWriters) {
            writer.finish();
          }
        } catch (final StorageException e) {
          // The responses in the file were reported as persisted long ago and are not requested
          // again, so carrying on would leave a hole in the state that nothing looks for.
          throw new IllegalStateException("Sorted world state file could not be stored", e);
        } finally {
          sealedWriters.forEach(SortedSegmentWriter::close);
        }
        sortedFiles.addAndGet(sealedWriters.size());
        sortedBytes.addAndGet(sealedSize);
        sealedOnStored.forEach(Runnable::run);
      };
    }

    private void storeOpenFile() {
      if (hasOpenFile()) {
        seal().run();
      }
    }

    private void discard() {
      writers.values().forEach(SortedSegmentWriter::close);
      writers.clear();
      onStored.clear();
    }
  }

  /** One of the ranges the storage of an account is continued in. */
  private static final class StorageRange {
    // the batches of a range that has no file
    private List<Batch> buffered = new ArrayList<>();
    private long bufferedSize;
    private boolean complete;
    private Run run;
  }

  private record StorageRangeKey(Bytes account, Bytes32 rangeEnd) {}

  /** The storage of an account that arrived in order and is not in the file of its partition. */
  private static final class QueuedAccount {
    private final byte[] account;
    // null once it is in the file, which it is as soon as nothing is ahead of the account
    private Batch start;
    // the ranges that continue the storage, by their end key and with that in key order
    private final NavigableMap<Bytes32, StorageRange> ranges = new TreeMap<>();
    // what the ranges hold in memory
    private long rangesSize;

    private QueuedAccount(final byte[] account, final Batch start) {
      this.account = account;
      this.start = start;
    }

    private boolean isComplete() {
      return ranges.values().stream().allMatch(range -> range.complete);
    }
  }

  /** A write of entries of an account that has to wait for the main stream to pass the account. */
  private record Waiting(byte[] account, Runnable action) {}

  /** The storage of the accounts whose hash starts with the same nibble. */
  private final class StoragePartition {
    private final Run main = new Run();
    // the first account in the open file of the main stream, null while it has none
    private byte[] openFirstAccount;
    private byte[] lastAccount;
    // The accounts that are not in the file yet, in key order. They wait for the first of them,
    // whose storage is still being downloaded.
    private final Deque<QueuedAccount> queue = new ArrayDeque<>();
    private final Map<Bytes, QueuedAccount> continuedAccounts = new HashMap<>();
    // what the queue holds in memory, read without the lock to find the fullest partition
    private final AtomicLong queuedSize = new AtomicLong();
    private final List<Waiting> waiting = new ArrayList<>();
    // the ranges of the accounts that are not waited for
    private final Map<StorageRangeKey, StorageRange> ownRanges = new HashMap<>();
  }

  private final SegmentedKeyValueStorage storage;
  private final SegmentBulkLoad bulkLoad;
  private final Limits limits;
  private final Executor bulkLoadEndExecutor;
  private final Map<Bytes32, Run> accountPartitions = new ConcurrentHashMap<>();
  private final StoragePartition[] storagePartitions = new StoragePartition[STORAGE_PARTITIONS];
  // what queued accounts and ranges without a file hold in memory
  private final AtomicLong bufferedSize = new AtomicLong();
  // what account range responses hold of the partition after theirs, kept for the end
  private final List<Batch> afterPartitionEnd = new ArrayList<>();
  private volatile boolean finished;

  private final AtomicLong sortedBytes = new AtomicLong();
  private final AtomicLong sortedFiles = new AtomicLong();
  private final AtomicLong directBytes = new AtomicLong();
  private final AtomicLong directBatches = new AtomicLong();
  private final AtomicLong heldBack = new AtomicLong();
  private final AtomicLong accountsNotWaitedFor = new AtomicLong();

  /**
   * @param storage the storage of the world state
   * @param bulkLoadEndExecutor runs what the storage has to catch up on once the state is in, which
   *     can take minutes and must not hold up whoever finishes the ingest
   */
  public WorldStateSortedIngest(
      final SegmentedKeyValueStorage storage, final Executor bulkLoadEndExecutor) {
    this(storage, bulkLoadEndExecutor, Limits.DEFAULT);
  }

  WorldStateSortedIngest(
      final SegmentedKeyValueStorage storage,
      final Executor bulkLoadEndExecutor,
      final Limits limits) {
    this.storage = storage;
    this.bulkLoadEndExecutor = bulkLoadEndExecutor;
    this.limits = limits;
    // A file that cannot be kept in its final place right away is put above the others. Left to
    // itself the storage would start merging it down at once and rewrite what is below it, again
    // and again while the download keeps adding files.
    this.bulkLoad = storage.startBulkLoad(SORTED_SEGMENTS);
    for (int i = 0; i < STORAGE_PARTITIONS; i++) {
      storagePartitions[i] = new StoragePartition();
    }
  }

  /**
   * Whether the state can still be written here. It cannot after {@link #finishAll()}, when whoever
   * reads the state relies on all of it being stored.
   *
   * @return true until the ingest is finished or closed
   */
  public boolean isActive() {
    return !finished;
  }

  /**
   * Writes the accounts of an account range response and the trie nodes above them.
   *
   * @param partitionEnd the end key of the account range partition, which identifies the stream
   * @param batch what the response adds to the storage
   * @param lastOfPartition whether the response completes the partition
   */
  public void writeAccountRange(
      final Bytes32 partitionEnd, final Batch batch, final boolean lastOfPartition) {
    final Batch inPartition = withoutEntriesOfNextPartition(partitionEnd, batch);
    final Run run = accountPartitions.computeIfAbsent(partitionEnd, __ -> new Run());
    synchronized (run) {
      if (inPartition.isEmpty()) {
        inPartition.onStored.run();
      } else if (run.follows(inPartition)) {
        run.append(inPartition);
      } else {
        // what the stream wrote before has to be in the storage first, or it would end up on top
        run.storeOpenFile();
        writeDirect(inPartition);
      }
      if (lastOfPartition || run.openSize >= limits.targetFileSize()) {
        run.storeOpenFile();
      }
    }
  }

  /**
   * Takes the account after the end of the partition, and the trie node it is the leaf of, out of
   * the response. Both are written when everything else is stored: the stream of the next partition
   * writes them as well, into a file that has to find its key range empty to be kept as it is. They
   * are not simply dropped, because a trie node of this response may refer to that leaf, and the
   * next partition may have downloaded it at another pivot block.
   */
  private Batch withoutEntriesOfNextPartition(final Bytes32 partitionEnd, final Batch batch) {
    final Map<SegmentIdentifier, List<Entry>> ofNextPartition =
        batch.entriesAfter(ACCOUNT_INFO_STATE, Bytes.EMPTY, partitionEnd);
    if (ofNextPartition.isEmpty()) {
      return batch;
    }
    final Runnable onBothStored = afterSecondCall(batch.onStored);
    synchronized (afterPartitionEnd) {
      afterPartitionEnd.add(new Batch(ofNextPartition, Map.of(), onBothStored));
    }
    return batch.without(ofNextPartition, onBothStored);
  }

  /**
   * Writes the first storage range response of an account, which holds its whole storage unless
   * that is more than fits one response.
   *
   * @param accountHash the hash of the account
   * @param rangeEnd the end key of the requested range
   * @param response what the response adds to the storage
   * @param continuationEnds the end keys of the ranges the rest of the storage follows in
   */
  public void writeStorageStart(
      final Bytes accountHash,
      final Bytes32 rangeEnd,
      final Batch response,
      final List<Bytes32> continuationEnds) {
    final StoragePartition partition = storagePartitionOf(accountHash);
    final byte[] account = accountHash.toArrayUnsafe();
    final Batch batch = withoutEntriesOfNextRange(accountHash, rangeEnd, response);
    synchronized (partition) {
      if (partition.lastAccount != null
          && Arrays.compareUnsigned(account, partition.lastAccount) <= 0) {
        // requested again after the main stream went on, its ranges are not waited for either
        if (batch.isEmpty()) {
          batch.onStored.run();
        } else {
          afterMainStream(partition, account, () -> writeDirect(batch));
        }
        return;
      }
      partition.lastAccount = account;
      final QueuedAccount queued = new QueuedAccount(account, batch);
      continuationEnds.forEach(end -> queued.ranges.put(end, new StorageRange()));
      partition.queue.addLast(queued);
      if (!continuationEnds.isEmpty()) {
        partition.continuedAccounts.put(accountHash, queued);
      }
      partition.queuedSize.addAndGet(batch.size);
      bufferedSize.addAndGet(batch.size);
      drain(partition, false);
    }
    relieveBuffer();
  }

  /**
   * Writes a storage range response that does not start at the beginning of the storage of its
   * account.
   *
   * @param accountHash the hash of the account
   * @param rangeStart the start key of the requested range
   * @param rangeEnd the end key of the range, which identifies the stream together with the account
   * @param response what the response adds to the storage
   * @param continuationEnds the end keys of the ranges the rest of the range follows in: none if
   *     the response completes the range, the end key of the range itself if the range is simply
   *     continued, or several if the rest of the range is split
   */
  public void writeStorageContinuation(
      final Bytes accountHash,
      final Bytes32 rangeStart,
      final Bytes32 rangeEnd,
      final Batch response,
      final List<Bytes32> continuationEnds) {
    final StoragePartition partition = storagePartitionOf(accountHash);
    final Batch batch = withoutEntriesOfNextRange(accountHash, rangeEnd, response);
    // a range that is split ends as well: the ranges it is split into are streams of their own
    final boolean lastOfRange = !continuationEnds.equals(List.of(rangeEnd));
    synchronized (partition) {
      final QueuedAccount queued = partition.continuedAccounts.get(accountHash);
      if (queued == null) {
        writeOwnRange(partition, accountHash, rangeEnd, batch, lastOfRange);
      } else {
        final StorageRange range =
            queued.ranges.computeIfAbsent(rangeEnd, __ -> new StorageRange());
        if (batch.isEmpty()) {
          batch.onStored.run();
        } else {
          range.buffered.add(batch);
          range.bufferedSize += batch.size;
          queued.rangesSize += batch.size;
          partition.queuedSize.addAndGet(batch.size);
          bufferedSize.addAndGet(batch.size);
        }
        range.complete |= lastOfRange;
        if (!continuationEnds.isEmpty() && lastOfRange) {
          // What the range holds comes before everything the ranges it is split into will hold,
          // while the last of them ends where it did. The start of the response is behind the
          // ranges before this one and so keeps it in its place.
          queued.ranges.remove(rangeEnd);
          queued.ranges.put(rangeStart, range);
          continuationEnds.forEach(end -> queued.ranges.put(end, new StorageRange()));
        }
        drain(partition, false);
      }
    }
    relieveBuffer();
  }

  /**
   * Stores everything that is still open or held back. Afterwards the whole state written here is
   * readable, and nothing more can be written.
   */
  public void finishAll() {
    if (finished) {
      return;
    }
    finished = true;
    accountPartitions
        .values()
        .forEach(
            run -> {
              synchronized (run) {
                run.storeOpenFile();
              }
            });
    for (final StoragePartition partition : storagePartitions) {
      synchronized (partition) {
        // an account is only still waited for if a response never came, what it has is stored
        while (!partition.queue.isEmpty()) {
          drain(partition, true);
        }
        storeMainFile(partition);
        runWaiting(partition, true);
        partition.ownRanges.values().forEach(range -> endOwnRange(partition, null, range));
        partition.ownRanges.clear();
      }
    }
    synchronized (afterPartitionEnd) {
      afterPartitionEnd.forEach(this::writeDirect);
      afterPartitionEnd.clear();
    }
    LOG.info(
        "World state sorted ingest finished: {} MB stored in {} sorted files, {} MB in {} batches through the regular write path, {} accounts with ranges stored on their own, {} writes held back for the file of their partition",
        sortedBytes.get() >> 20,
        sortedFiles.get(),
        directBytes.get() >> 20,
        directBatches.get(),
        accountsNotWaitedFor.get(),
        heldBack.get());
    bulkLoadEndExecutor.execute(bulkLoad::close);
  }

  /** Drops what is not stored yet. Used when the download ends without completing. */
  public void close() {
    if (finished) {
      return;
    }
    finished = true;
    accountPartitions
        .values()
        .forEach(
            run -> {
              synchronized (run) {
                run.discard();
              }
            });
    for (final StoragePartition partition : storagePartitions) {
      synchronized (partition) {
        partition.main.discard();
        partition.queue.clear();
        partition.continuedAccounts.clear();
        partition.queuedSize.set(0);
        partition.waiting.clear();
        partition.ownRanges.values().stream()
            .filter(range -> range.run != null)
            .forEach(range -> range.run.discard());
        partition.ownRanges.clear();
      }
    }
    synchronized (afterPartitionEnd) {
      afterPartitionEnd.clear();
    }
    bulkLoadEndExecutor.execute(bulkLoad::close);
  }

  long sortedBytes() {
    return sortedBytes.get();
  }

  long sortedFiles() {
    return sortedFiles.get();
  }

  long directBatches() {
    return directBatches.get();
  }

  private StoragePartition storagePartitionOf(final Bytes accountHash) {
    return storagePartitions[(accountHash.get(0) & 0xff) >> 4];
  }

  /**
   * Takes the slot after the end of the range, and the trie node it is the leaf of, out of the
   * response. The stream of the next range of the account stores both, and a storage that is
   * downloaded in more than one range is checked node by node once the download is complete.
   */
  private static Batch withoutEntriesOfNextRange(
      final Bytes accountHash, final Bytes32 rangeEnd, final Batch batch) {
    final Map<SegmentIdentifier, List<Entry>> ofNextRange =
        batch.entriesAfter(ACCOUNT_STORAGE_STORAGE, accountHash, rangeEnd);
    return ofNextRange.isEmpty() ? batch : batch.without(ofNextRange, batch.onStored);
  }

  /** The key of a trie node is its path, one nibble per byte. */
  private static byte[] pathOf(final Bytes hash) {
    final byte[] path = new byte[2 * hash.size()];
    for (int i = 0; i < hash.size(); i++) {
      path[2 * i] = (byte) ((hash.get(i) & 0xf0) >> 4);
      path[2 * i + 1] = (byte) (hash.get(i) & 0x0f);
    }
    return path;
  }

  private static Runnable afterSecondCall(final Runnable action) {
    final AtomicInteger calls = new AtomicInteger();
    return () -> {
      if (calls.incrementAndGet() == 2) {
        action.run();
      }
    };
  }

  /**
   * Moves the accounts at the head of the queue into the file of the partition, up to the first one
   * whose storage is not complete. That one is given up waiting for if it holds too much, or if
   * told to.
   */
  private void drain(final StoragePartition partition, final boolean stopWaitingForHead) {
    boolean stopWaiting = stopWaitingForHead;
    while (!partition.queue.isEmpty()) {
      final QueuedAccount head = partition.queue.peekFirst();
      if (head.start != null) {
        final Batch start = head.start;
        head.start = null;
        partition.queuedSize.addAndGet(-start.size);
        bufferedSize.addAndGet(-start.size);
        appendToMain(partition, head.account, start);
      }
      if (head.isComplete()) {
        // The account stays at the head while its ranges go into the file. If the file is stored
        // half way through them, what waits for the main stream to pass a later account has to
        // keep waiting: the next file starts with the rest of this account.
        bufferedSize.addAndGet(-head.rangesSize);
        head.ranges
            .values()
            .forEach(
                range ->
                    range.buffered.forEach(batch -> appendToMain(partition, head.account, batch)));
        removeHead(partition);
      } else if (stopWaiting || head.rangesSize >= limits.queuedAccountLimit()) {
        removeHead(partition);
        accountsNotWaitedFor.incrementAndGet();
        // The ranges are stored on their own from here on, while the accounts after this one go
        // into the file of the partition. They can only be kept as they are if no file spans their
        // account, so the file has to end with it.
        storeMainFile(partition);
        // told to stop waiting means memory is short, and a file takes a range out of it
        final long fileThreshold = stopWaiting ? 0 : limits.rangeFileThreshold();
        head.ranges.forEach(
            (end, range) -> {
              if (range.bufferedSize > 0 && range.bufferedSize >= fileThreshold) {
                giveFiles(partition, head.account, range);
              }
              if (range.complete) {
                endOwnRange(partition, head.account, range);
              } else {
                partition.ownRanges.put(new StorageRangeKey(Bytes.wrap(head.account), end), range);
              }
            });
        stopWaiting = false;
      } else {
        return;
      }
    }
  }

  private void removeHead(final StoragePartition partition) {
    final QueuedAccount head = partition.queue.pollFirst();
    partition.continuedAccounts.remove(Bytes.wrap(head.account));
    partition.queuedSize.addAndGet(-head.rangesSize);
  }

  /** Makes the partition that holds the most in memory stop waiting while too much is held. */
  private void relieveBuffer() {
    while (bufferedSize.get() > limits.bufferLimit()) {
      StoragePartition fullest = null;
      for (final StoragePartition partition : storagePartitions) {
        if (partition.queuedSize.get() > (fullest == null ? 0 : fullest.queuedSize.get())) {
          fullest = partition;
        }
      }
      if (fullest == null) {
        // nothing is waited for, and a range without a file gets one with its next response
        return;
      }
      synchronized (fullest) {
        drain(fullest, true);
      }
    }
  }

  private void appendToMain(
      final StoragePartition partition, final byte[] account, final Batch batch) {
    if (batch.isEmpty()) {
      batch.onStored.run();
    } else if (partition.main.follows(batch)) {
      if (!partition.main.hasOpenFile()) {
        partition.openFirstAccount = account;
      }
      partition.main.append(batch);
      if (partition.main.openSize >= limits.targetFileSize()) {
        storeMainFile(partition);
      }
    } else {
      // what the stream wrote before has to be in the storage first, or it would end up on top
      storeMainFile(partition);
      writeDirect(batch);
    }
  }

  private void storeMainFile(final StoragePartition partition) {
    partition.main.storeOpenFile();
    partition.openFirstAccount = null;
    runWaiting(partition, false);
  }

  private void runWaiting(final StoragePartition partition, final boolean all) {
    if (partition.waiting.isEmpty()) {
      return;
    }
    final List<Waiting> waiting = new ArrayList<>(partition.waiting);
    partition.waiting.clear();
    for (final Waiting write : waiting) {
      if (!all && isAheadOfMainStream(partition, write.account)) {
        partition.waiting.add(write);
      } else {
        write.action.run();
      }
    }
  }

  /**
   * Runs the action, which puts entries of the given account into the storage, once no file of the
   * main stream that is still to be stored spans that account. A null account never waits.
   */
  private void afterMainStream(
      final StoragePartition partition, final byte[] account, final Runnable action) {
    if (account != null && isAheadOfMainStream(partition, account)) {
      heldBack.incrementAndGet();
      partition.waiting.add(new Waiting(account, action));
    } else {
      action.run();
    }
  }

  /** Whether a file of the main stream that is not stored yet spans the account, or will. */
  private static boolean isAheadOfMainStream(
      final StoragePartition partition, final byte[] account) {
    if (partition.main.hasOpenFile()
        && Arrays.compareUnsigned(account, partition.openFirstAccount) >= 0) {
      return true;
    }
    final QueuedAccount head = partition.queue.peekFirst();
    return head != null && Arrays.compareUnsigned(account, head.account) >= 0;
  }

  /** Writes a response of a range of an account that is not waited for. */
  private void writeOwnRange(
      final StoragePartition partition,
      final Bytes accountHash,
      final Bytes32 rangeEnd,
      final Batch batch,
      final boolean lastOfRange) {
    final byte[] account = accountHash.toArrayUnsafe();
    final StorageRangeKey key = new StorageRangeKey(accountHash, rangeEnd);
    final StorageRange range = partition.ownRanges.computeIfAbsent(key, __ -> new StorageRange());
    if (batch.isEmpty()) {
      batch.onStored.run();
    } else if (range.run == null) {
      range.buffered.add(batch);
      range.bufferedSize += batch.size;
      final long bufferedAltogether = bufferedSize.addAndGet(batch.size);
      if (range.bufferedSize >= limits.rangeFileThreshold()
          || bufferedAltogether > limits.bufferLimit()) {
        giveFiles(partition, account, range);
      }
    } else {
      appendToOwnRange(partition, account, range, batch);
    }
    if (range.run != null && range.run.openSize >= limits.targetFileSize()) {
      afterMainStream(partition, account, range.run.seal());
    }
    if (lastOfRange) {
      partition.ownRanges.remove(key);
      endOwnRange(partition, account, range);
    }
  }

  private void giveFiles(
      final StoragePartition partition, final byte[] account, final StorageRange range) {
    final List<Batch> buffered = range.buffered;
    bufferedSize.addAndGet(-range.bufferedSize);
    range.buffered = new ArrayList<>();
    range.bufferedSize = 0;
    range.run = new Run();
    buffered.forEach(batch -> appendToOwnRange(partition, account, range, batch));
  }

  private void appendToOwnRange(
      final StoragePartition partition,
      final byte[] account,
      final StorageRange range,
      final Batch batch) {
    if (range.run.follows(batch)) {
      range.run.append(batch);
    } else {
      // what the range wrote before has to be in the storage first, or it would end up on top
      if (range.run.hasOpenFile()) {
        afterMainStream(partition, account, range.run.seal());
      }
      afterMainStream(partition, account, () -> writeDirect(batch));
    }
  }

  private void endOwnRange(
      final StoragePartition partition, final byte[] account, final StorageRange range) {
    if (range.run == null) {
      bufferedSize.addAndGet(-range.bufferedSize);
      range.buffered.forEach(
          batch -> afterMainStream(partition, account, () -> writeDirect(batch)));
    } else if (range.run.hasOpenFile()) {
      afterMainStream(partition, account, range.run.seal());
    }
  }

  /** Writes a batch the way the state is written without the sorted ingest. */
  private void writeDirect(final Batch batch) {
    StorageException lastFailure = null;
    for (int attempt = 0; attempt < DIRECT_WRITE_ATTEMPTS; attempt++) {
      final SegmentedKeyValueStorageTransaction transaction = storage.startTransaction();
      try {
        batch.entries.forEach(
            (segment, entries) ->
                entries.forEach(entry -> transaction.put(segment, entry.key(), entry.value())));
        batch.removals.forEach(
            (segment, keys) -> keys.forEach(key -> transaction.remove(segment, key)));
      } catch (final StorageException e) {
        // a transaction that is never committed keeps what the implementation holds for it
        transaction.rollback();
        lastFailure = e;
        continue;
      }
      try {
        transaction.commit();
      } catch (final StorageException e) {
        lastFailure = e;
        continue;
      }
      directBytes.addAndGet(batch.size);
      directBatches.incrementAndGet();
      batch.onStored.run();
      return;
    }
    // The batch may belong to a response that was reported as persisted long ago and is not
    // requested again, so carrying on would leave a hole in the state that nothing looks for.
    throw new IllegalStateException("World state batch could not be stored", lastFailure);
  }
}
