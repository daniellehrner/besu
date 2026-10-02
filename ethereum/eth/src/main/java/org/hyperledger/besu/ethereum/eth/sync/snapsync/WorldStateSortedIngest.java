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
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

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
 * <p>The storage of the accounts of a partition does not arrive in the order of the accounts: the
 * requests for it are answered side by side, and one that fails is sent again later. So the
 * accounts whose storage is to come are announced when their account range arrives, which is in key
 * order, and the storage that arrives ahead of an earlier account waits in memory for it.
 *
 * <p>What is not announced, the storage of an account that is downloaded again because it changed,
 * and what arrives after its account was given up waiting for, is held back until the file that
 * spans its account is stored, and then takes the regular write path. Putting it into the storage
 * first would leave keys inside the key range of that file, and a file that is not alone in its
 * range cannot be kept as it is.
 *
 * <p>A peer answers a range request with one entry more than the range holds, the first one after
 * its end, as the proof that nothing is missing in between. That entry belongs to the stream of the
 * next range, and a file reaching into the range of another stream cannot be kept as it is either.
 * It is therefore taken out of the response before the rest goes into the file.
 *
 * <p>A file that is complete is not stored by the thread that completed it. It is queued, in the
 * order things have to reach the storage, together with the batches that take the regular write
 * path, and one task at a time takes what is queued to the storage, every run of files in one step.
 * Storing a file takes the longer the more files the storage has, and the threads that write here
 * hold the lock of a partition while they do.
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
   *     once it holds this much. Less than this takes the regular write path, when the range ends
   *     or when memory is short: a file for every small range leaves the storage with tens of
   *     thousands of them.
   * @param queuedAccountLimit how much of the storage of an account may wait in memory for the rest
   *     of it. An account with more than this is a contract with files of its own.
   * @param bufferLimit what may wait in memory altogether. Beyond it the partition holding the most
   *     stops waiting for the account it waits for, whether its storage has started to arrive or
   *     not.
   */
  record Limits(
      long targetFileSize, long rangeFileThreshold, long queuedAccountLimit, long bufferLimit) {
    private static final long MEGABYTE = 1024L * 1024;
    private static final long MIN_BUFFER_LIMIT = 256 * MEGABYTE;
    private static final long MAX_BUFFER_LIMIT = 2048 * MEGABYTE;
    private static final long MIN_QUEUED_ACCOUNT_LIMIT = 64 * MEGABYTE;

    static final Limits DEFAULT = forHeap(Runtime.getRuntime().maxMemory());

    /**
     * The limits for a process with the given heap. Every account that is given up waiting for ends
     * the file of its partition and gets files of its own, so the more may wait the fewer and
     * larger the files are. What waits takes about twice its size on the heap, and a twelfth of the
     * heap is allowed to wait. On Ethereum mainnet 256 MiB were not enough: the limit was reached
     * again and again.
     *
     * @param maxHeap the most the heap of the process can grow to
     * @return the limits
     */
    static Limits forHeap(final long maxHeap) {
      final long bufferLimit = Math.max(MIN_BUFFER_LIMIT, Math.min(maxHeap / 12, MAX_BUFFER_LIMIT));
      return new Limits(
          64 * MEGABYTE,
          MEGABYTE,
          Math.max(MIN_QUEUED_ACCOUNT_LIMIT, bufferLimit / 8),
          bufferLimit);
    }
  }

  /** The segments of the world state whose entries are downloaded in key order. */
  static final List<SegmentIdentifier> SORTED_SEGMENTS =
      List.of(ACCOUNT_INFO_STATE, ACCOUNT_STORAGE_STORAGE, TRIE_BRANCH_STORAGE);

  private static final int STORAGE_PARTITIONS = 16;
  private static final int DIRECT_WRITE_ATTEMPTS = 3;
  // more files in one step only hold up everything else that writes to the storage for longer
  private static final int MAX_FILES_PER_STORE_STEP = 512;
  // what may be queued for the storage before whoever completes files has to wait for it
  private static final int MAX_QUEUED_FILES = 4 * MAX_FILES_PER_STORE_STEP;
  private static final long MAX_QUEUED_DIRECT_SIZE = 64L * 1024 * 1024;
  private static final long PROGRESS_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

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

    /**
     * Takes the open file off the stream. Running the returned action queues it for the storage,
     * behind everything that was queued before.
     */
    private Runnable seal() {
      final SortedFiles sealed =
          new SortedFiles(new ArrayList<>(writers.values()), openSize, onStored);
      writers.clear();
      onStored = new ArrayList<>();
      openSize = 0;
      return () -> stores.add(sealed);
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
    // what the range wrote through the regular write path because memory was short
    private long directSize;
    private boolean complete;
    private Run run;
  }

  private record StorageRangeKey(Bytes account, Bytes32 rangeEnd) {}

  /** The storage of an account that is not in the file of its partition yet. */
  private static final class QueuedAccount {
    private final byte[] account;
    // whether the first response for the storage is here
    private boolean arrived;
    // null once it is in the file, which it is as soon as nothing is ahead of the account
    private Batch start;
    // the ranges that continue the storage, by their end key and with that in key order
    private final NavigableMap<Bytes32, StorageRange> ranges = new TreeMap<>();
    // what the ranges hold in memory
    private long rangesSize;

    private QueuedAccount(final byte[] account) {
      this.account = account;
    }

    private boolean isComplete() {
      return arrived && ranges.values().stream().allMatch(range -> range.complete);
    }
  }

  /** A write of entries of an account that has to wait for the main stream to pass the account. */
  private record Waiting(byte[] account, Runnable action) {}

  /** The storage of the accounts whose hash starts with the same nibble. */
  private final class StoragePartition {
    private final Run main = new Run();
    // the first account in the open file of the main stream, null while it has none
    private byte[] openFirstAccount;
    // the last account that joined the queue
    private byte[] lastQueuedAccount;
    // The accounts that are not in the file yet, in key order. They wait for the first of them,
    // whose storage is still being downloaded.
    private final Deque<QueuedAccount> queue = new ArrayDeque<>();
    // the accounts of the queue whose storage has not started to arrive
    private final Map<Bytes, QueuedAccount> announcedAccounts = new HashMap<>();
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
  private final Executor executor;
  private final StoreQueue stores = new StoreQueue();
  private final Map<Bytes32, Run> accountPartitions = new ConcurrentHashMap<>();
  private final StoragePartition[] storagePartitions = new StoragePartition[STORAGE_PARTITIONS];
  // what queued accounts and ranges without a file hold in memory
  private final AtomicLong bufferedSize = new AtomicLong();
  // what account range responses hold of the partition after theirs, kept for the end
  private final List<Batch> afterPartitionEnd = new ArrayList<>();
  private volatile boolean finished;

  private final AtomicLong sortedBytes = new AtomicLong();
  private final AtomicLong sortedFiles = new AtomicLong();
  private final AtomicLong storeSteps = new AtomicLong();
  private final AtomicLong directBytes = new AtomicLong();
  private final AtomicLong directBatches = new AtomicLong();
  private final AtomicLong heldBack = new AtomicLong();
  private final AtomicLong accountsNotWaitedFor = new AtomicLong();

  /**
   * @param storage the storage of the world state
   * @param executor runs what must not hold up the threads that write here: taking the files that
   *     are complete to the storage, and what the storage has to catch up on once the state is in,
   *     which can take minutes
   */
  public WorldStateSortedIngest(final SegmentedKeyValueStorage storage, final Executor executor) {
    this(storage, executor, Limits.DEFAULT);
  }

  WorldStateSortedIngest(
      final SegmentedKeyValueStorage storage, final Executor executor, final Limits limits) {
    this.storage = storage;
    this.executor = executor;
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
   * Announces the accounts whose storage is requested next, in key order: those of an account range
   * response, before any of their storage can arrive. The storage of each of them goes into the
   * file of its partition in this order, whatever order it arrives in.
   *
   * @param accountHashes the hashes of the accounts, ascending
   */
  public void announceStorage(final List<Bytes> accountHashes) {
    for (final Bytes accountHash : accountHashes) {
      final StoragePartition partition = storagePartitionOf(accountHash);
      synchronized (partition) {
        // An account that is behind the last one of the queue is downloaded again. It cannot get
        // its place back, its storage is written when the file that spans it is stored.
        queueAccount(partition, accountHash)
            .ifPresent(queued -> partition.announcedAccounts.put(accountHash, queued));
      }
    }
  }

  /** Puts an account at the end of the queue of its partition, unless it belongs before that. */
  private Optional<QueuedAccount> queueAccount(
      final StoragePartition partition, final Bytes accountHash) {
    final byte[] account = accountHash.toArrayUnsafe();
    if (partition.lastQueuedAccount != null
        && Arrays.compareUnsigned(account, partition.lastQueuedAccount) <= 0) {
      return Optional.empty();
    }
    partition.lastQueuedAccount = account;
    final QueuedAccount queued = new QueuedAccount(account);
    partition.queue.addLast(queued);
    return Optional.of(queued);
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
      // an account that was not announced takes the place it arrives at
      final Optional<QueuedAccount> waitedFor =
          Optional.ofNullable(partition.announcedAccounts.remove(accountHash))
              .or(() -> queueAccount(partition, accountHash));
      if (waitedFor.isEmpty()) {
        // Downloaded again, or no longer waited for: the main stream went on without it, and its
        // ranges are not waited for either.
        if (batch.isEmpty()) {
          batch.onStored.run();
        } else {
          afterMainStream(partition, account, () -> writeDirect(batch));
        }
        return;
      }
      final QueuedAccount queued = waitedFor.get();
      queued.arrived = true;
      queued.start = batch;
      continuationEnds.forEach(end -> queued.ranges.put(end, new StorageRange()));
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
    stores.storeAll();
    LOG.info(
        "World state sorted ingest finished: {} MB stored in {} sorted files in {} steps, {} MB in {} batches through the regular write path, {} accounts with ranges stored on their own, {} writes held back for the file of their partition",
        sortedBytes.get() >> 20,
        sortedFiles.get(),
        storeSteps.get(),
        directBytes.get() >> 20,
        directBatches.get(),
        accountsNotWaitedFor.get(),
        heldBack.get());
    executor.execute(bulkLoad::close);
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
        partition.announcedAccounts.clear();
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
    stores.discard();
    executor.execute(bulkLoad::close);
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
   * told to. One that has not arrived holds nothing, so only being told to ends the wait for it.
   */
  private void drain(final StoragePartition partition, final boolean stopWaitingForHead) {
    boolean stopWaiting = stopWaitingForHead;
    while (!partition.queue.isEmpty()) {
      final QueuedAccount head = partition.queue.peekFirst();
      if (!head.arrived) {
        if (!stopWaiting) {
          return;
        }
        // Nothing of the account is in a file. Whenever its storage arrives, it is written like
        // that of an account that is downloaded again.
        removeHead(partition);
        runWaiting(partition, false);
        stopWaiting = false;
        continue;
      }
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
        // told to stop waiting means memory is short, and what a range holds has to get out of it
        final boolean memoryIsShort = stopWaiting;
        head.ranges.forEach(
            (end, range) -> {
              if (range.bufferedSize >= limits.rangeFileThreshold()) {
                giveFiles(partition, head.account, range);
              } else if (memoryIsShort) {
                writeBufferedDirect(partition, head.account, range);
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
    partition.announcedAccounts.remove(Bytes.wrap(head.account));
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
      if (range.bufferedSize + range.directSize >= limits.rangeFileThreshold()) {
        giveFiles(partition, account, range);
      } else if (bufferedAltogether > limits.bufferLimit()) {
        writeBufferedDirect(partition, account, range);
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

  /**
   * Takes what a range without a file holds in memory to the storage through the regular write
   * path. For when memory is short and the range has too little for a file: the many ranges of a
   * few responses would each leave the storage with a file of their own. A range does this with
   * less than a file is worth altogether, after that it gets a file after all.
   */
  private void writeBufferedDirect(
      final StoragePartition partition, final byte[] account, final StorageRange range) {
    final List<Batch> buffered = range.buffered;
    bufferedSize.addAndGet(-range.bufferedSize);
    range.directSize += range.bufferedSize;
    range.buffered = new ArrayList<>();
    range.bufferedSize = 0;
    buffered.forEach(batch -> afterMainStream(partition, account, () -> writeDirect(batch)));
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

  /**
   * Queues a batch for the regular write path, the way the state is written without the sorted
   * ingest. It reaches the storage after everything that was queued before it.
   */
  private void writeDirect(final Batch batch) {
    stores.add(new DirectWrite(batch));
  }

  private void writeDirectNow(final Batch batch) {
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

  /** What is on its way to the storage. */
  private sealed interface Store permits SortedFiles, DirectWrite {}

  /** The files of a stream that are complete, one per segment. */
  private record SortedFiles(List<SortedSegmentWriter> writers, long size, List<Runnable> onStored)
      implements Store {}

  /** A batch that takes the regular write path. */
  private record DirectWrite(Batch batch) implements Store {}

  /**
   * Takes what is complete to the storage, in the order it was handed over.
   *
   * <p>One thread at a time does that, with everything that was queued while the step before was
   * under way: the files in one step, each batch of the regular write path on its own and after the
   * files queued before it. A batch can replace entries of such a file, which it only does if it
   * gets to the storage second.
   */
  private final class StoreQueue {
    private final Deque<Store> queue = new ArrayDeque<>();
    private int queuedFiles;
    private long queuedDirectSize;
    // a task was handed to the executor and has not started
    private boolean scheduled;
    // the thread that takes the queue to the storage, null if none does
    private Thread storing;
    private boolean discarded;
    private Throwable failure;
    private long lastProgressLogNanos = System.nanoTime();

    /**
     * Queues something for the storage. Waits while too much is queued, which holds up whoever
     * completes files faster than the storage takes them.
     */
    void add(final Store store) {
      synchronized (this) {
        awaitWhile(
            () ->
                failure == null
                    && !discarded
                    && isFull()
                    && (scheduled || storing != null)
                    && storing != Thread.currentThread());
        if (failure != null || discarded) {
          discard(store);
          throwIfFailed();
          return;
        }
        queue.addLast(store);
        count(store, 1);
        if (scheduled || storing != null) {
          return;
        }
        scheduled = true;
      }
      try {
        executor.execute(this::storeQueued);
      } catch (final RuntimeException e) {
        synchronized (this) {
          // nothing will come and store what is queued, the next one to queue has to try again
          scheduled = false;
          notifyAll();
        }
        throw e;
      }
      synchronized (this) {
        // an executor may run the task right here
        throwIfFailed();
      }
    }

    /** Stores everything that is queued, in this thread unless another one is at it. */
    void storeAll() {
      synchronized (this) {
        awaitWhile(() -> storing != null && storing != Thread.currentThread());
        throwIfFailed();
        if (queue.isEmpty() || storing != null) {
          return;
        }
        // a task that was scheduled and starts later finds nothing left to do
        storing = Thread.currentThread();
      }
      storeUntilEmpty();
      synchronized (this) {
        throwIfFailed();
      }
    }

    /** Drops what is queued and lets the step that is under way come to its end. */
    void discard() {
      synchronized (this) {
        discarded = true;
        discardQueued();
        notifyAll();
        awaitWhile(() -> storing != null && storing != Thread.currentThread());
      }
    }

    private void storeQueued() {
      final Thread thread = Thread.currentThread();
      synchronized (this) {
        scheduled = false;
        if (storing != null) {
          return;
        }
        storing = thread;
      }
      // named like the threads of the pipeline stages, for whoever looks at what the node does
      final String name = thread.getName();
      thread.setName(name + " (storeSortedFiles)");
      try {
        storeUntilEmpty();
      } finally {
        thread.setName(name);
      }
    }

    /** For the thread that is registered as the one that stores. */
    private void storeUntilEmpty() {
      while (true) {
        final List<Store> step;
        synchronized (this) {
          if (queue.isEmpty()) {
            // in one go with the check, so that what is queued from now on gets a task of its own
            storing = null;
            notifyAll();
            return;
          }
          step = nextStep();
        }
        try {
          store(step);
        } catch (final RuntimeException | Error e) {
          synchronized (this) {
            failure = e;
            step.forEach(failed -> count(failed, -1));
            discardQueued();
            storing = null;
            notifyAll();
          }
          return;
        }
        synchronized (this) {
          step.forEach(stored -> count(stored, -1));
          notifyAll();
          logProgress();
        }
      }
    }

    /** For the thread that stores, with the monitor of the queue. */
    private void logProgress() {
      final long now = System.nanoTime();
      if (!LOG.isDebugEnabled() || now - lastProgressLogNanos < PROGRESS_LOG_INTERVAL_NANOS) {
        return;
      }
      lastProgressLogNanos = now;
      LOG.debug(
          "World state sorted ingest so far: {} MB stored in {} sorted files in {} steps, {} MB in {} batches through the regular write path, {} accounts with ranges stored on their own, {} writes held back, {} MB waiting in memory, {} files and {} MB of batches queued for the storage",
          sortedBytes.get() >> 20,
          sortedFiles.get(),
          storeSteps.get(),
          directBytes.get() >> 20,
          directBatches.get(),
          accountsNotWaitedFor.get(),
          heldBack.get(),
          bufferedSize.get() >> 20,
          queuedFiles,
          queuedDirectSize >> 20);
    }

    /** Takes the next of what is queued off the queue, as many files as go into one step. */
    private List<Store> nextStep() {
      final List<Store> step = new ArrayList<>();
      int files = 0;
      while (!queue.isEmpty() && files < MAX_FILES_PER_STORE_STEP) {
        final Store store = queue.pollFirst();
        step.add(store);
        if (store instanceof SortedFiles sortedFiles) {
          files += sortedFiles.writers().size();
        }
      }
      return step;
    }

    private void store(final List<Store> step) {
      final Deque<Store> remaining = new ArrayDeque<>(step);
      try {
        final List<SortedFiles> files = new ArrayList<>();
        while (!remaining.isEmpty()) {
          final Store store = remaining.pollFirst();
          if (store instanceof SortedFiles sortedFiles) {
            files.add(sortedFiles);
          } else {
            storeFiles(files);
            files.clear();
            writeDirectNow(((DirectWrite) store).batch());
          }
        }
        storeFiles(files);
      } catch (final RuntimeException | Error e) {
        // the files the step got to are released, stored or not: these are the ones it did not
        remaining.forEach(this::discard);
        throw e;
      }
    }

    private void storeFiles(final List<SortedFiles> files) {
      if (files.isEmpty()) {
        return;
      }
      final List<SortedSegmentWriter> writers =
          files.stream().flatMap(sortedFiles -> sortedFiles.writers().stream()).toList();
      try {
        storage.finishSortedWriters(writers);
      } catch (final StorageException e) {
        // The responses in the files were reported as persisted long ago and are not requested
        // again, so carrying on would leave a hole in the state that nothing looks for.
        throw new IllegalStateException("Sorted world state file could not be stored", e);
      } finally {
        writers.forEach(SortedSegmentWriter::close);
      }
      storeSteps.incrementAndGet();
      for (final SortedFiles stored : files) {
        sortedFiles.addAndGet(stored.writers().size());
        sortedBytes.addAndGet(stored.size());
        stored.onStored().forEach(Runnable::run);
      }
    }

    private boolean isFull() {
      return queuedFiles >= MAX_QUEUED_FILES || queuedDirectSize >= MAX_QUEUED_DIRECT_SIZE;
    }

    private void count(final Store store, final int sign) {
      if (store instanceof SortedFiles sortedFiles) {
        queuedFiles += sign * sortedFiles.writers().size();
      } else {
        queuedDirectSize += sign * ((DirectWrite) store).batch().size;
      }
    }

    private void discardQueued() {
      for (final Store store : queue) {
        count(store, -1);
        discard(store);
      }
      queue.clear();
    }

    /** Releases the files of what will not be stored. A file that was stored stays where it is. */
    private void discard(final Store store) {
      if (store instanceof SortedFiles sortedFiles) {
        sortedFiles.writers().forEach(SortedSegmentWriter::close);
      }
    }

    private void throwIfFailed() {
      if (failure != null) {
        // What was queued was reported as persisted and is not requested again, so carrying on
        // would leave a hole in the state that nothing looks for.
        throw new IllegalStateException("World state could not be stored", failure);
      }
    }

    /** For a thread that holds the monitor of the queue. */
    private void awaitWhile(final BooleanSupplier condition) {
      try {
        while (condition.getAsBoolean()) {
          wait();
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while the world state was being stored", e);
      }
    }
  }
}
