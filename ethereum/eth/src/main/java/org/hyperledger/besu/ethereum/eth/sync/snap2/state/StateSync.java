/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.ethereum.eth.sync.snap2.state;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.sync.snap2.Snap2Configuration;
import org.hyperledger.besu.ethereum.eth.sync.snap2.net.RequestFailedException;
import org.hyperledger.besu.ethereum.eth.sync.snap2.net.RequestSender;
import org.hyperledger.besu.ethereum.eth.sync.snap2.net.SyncRequest;
import org.hyperledger.besu.ethereum.eth.sync.snap2.net.SyncRequest.Protocol;
import org.hyperledger.besu.ethereum.eth.sync.snap2.storage.Accounts;
import org.hyperledger.besu.ethereum.eth.sync.snap2.storage.SyncStateStorage;
import org.hyperledger.besu.ethereum.eth.sync.snap2.trie.StorageTries;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.trie.RangeManager;
import org.hyperledger.besu.ethereum.trie.common.PmtStateTrieAccountValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads the flat state at the pivot: accounts, storages and bytecodes in parallel, each
 * response checked against its root and persisted only if the pivot did not move meanwhile.
 */
public final class StateSync {

  private static final Logger LOG = LoggerFactory.getLogger(StateSync.class);
  // response size of a request for one account or one slot
  static final int SINGLE_ITEM_BYTES = 4096;
  private static final int EMPTY_RESPONSES_FOR_STALE_PIVOT = 12;
  private static final int NO_EPOCH = -1;
  private static final int SEEN_CODES_LIMIT = 200_000;
  private static final int BYTES_PER_SLOT = 64;
  // peers stop looking codes up after this many in one request
  static final int MAX_CODES_PER_REQUEST = 1024;

  /** Current pivot; responses requested at another epoch are discarded. */
  public record Pivot(int epoch, BlockHeader header) {
    Bytes32 root() {
      return Bytes32.wrap(header.getStateRoot().getBytes());
    }
  }

  private final SyncStateStorage storage;
  private final RequestSender scheduler;
  private final Snap2Configuration config;
  private final StateCoverage coverage = new StateCoverage();
  // builds the storage tries while downloading, when enabled
  private final Optional<StorageTries> tries;
  private final ReentrantReadWriteLock pivotLock = new ReentrantReadWriteLock();
  private final ScheduledExecutorService loop;
  // writes the verified responses, off the threads that verify them
  private final ExecutorService persister;
  private final CompletableFuture<Void> completion = new CompletableFuture<>();
  private volatile Pivot pivot;
  private volatile boolean pivotStale;
  private volatile boolean stopped;
  private volatile boolean paused;

  // bookkeeping, loop thread only
  private final List<RangeTask> accountTasks = new ArrayList<>();
  // the lowest account range not done yet
  private int firstAccountTask;
  private final ArrayDeque<StorageTask> storageQueue = new ArrayDeque<>();
  private final ArrayDeque<StorageTask> refreshQueue = new ArrayDeque<>();
  private final Set<StorageTask> chunked = new LinkedHashSet<>();
  private final LinkedHashSet<Bytes32> codeQueue = new LinkedHashSet<>();
  private final Set<Bytes32> codesInFlight = new HashSet<>();
  private final Set<Bytes32> seenCodes = new HashSet<>();
  private final Set<EthPeer> emptyPeers = new HashSet<>();
  private final StateServers servers = new StateServers();
  // peers left out of state requests, refreshed on each dispatch
  private Set<EthPeer> excluded = Set.of();
  private volatile int excludedCount;
  private int accountsInFlight;
  private int storageInFlight;
  private int codesRequestsInFlight;
  private int refreshesInFlight;
  private int consecutiveEmpty;

  private final AtomicLong accounts = new AtomicLong();
  private final AtomicLong slots = new AtomicLong();
  private final AtomicLong codes = new AtomicLong();
  // the size of the codes received so far, which tells how many of them fit a response
  private final AtomicLong codeBytes = new AtomicLong();

  public StateSync(
      final SyncStateStorage storage,
      final RequestSender scheduler,
      final Snap2Configuration config) {
    this.storage = storage;
    this.scheduler = scheduler;
    this.config = config;
    this.loop =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("snap2-state").daemon().factory());
    this.persister =
        Executors.newFixedThreadPool(
            Math.max(2, config.workerThreads()),
            Thread.ofPlatform().name("snap2-persist-", 0).daemon().factory());
    this.tries =
        config.storageTriesWhileDownloading()
            ? Optional.of(
                new StorageTries(
                    storage, this::withoutMoves, Math.max(1, config.trieBuildThreads() / 4)))
            : Optional.empty();
  }

  /** Starts downloading the state of the given pivot. */
  public CompletableFuture<Void> start(final BlockHeader pivotHeader) {
    pivot = new Pivot(0, pivotHeader);
    execute(
        () -> {
          RangeManager.generateRanges(
                  RangeManager.MIN_RANGE, RangeManager.MAX_RANGE, config.accountChunks())
              .forEach((from, to) -> accountTasks.add(new RangeTask(from, to)));
          storage
              .sortedWrites()
              .ifPresent(
                  sorted ->
                      sorted.start(
                          accountTasks.stream().map(task -> task.limit).toList(),
                          hash -> coverage.storageTask(hash) != null));
          LOG.info(
              "Starting SNAP2 world state download at block {} with {} account ranges",
              pivotHeader.getNumber(),
              accountTasks.size());
          dispatch();
        });
    loop.scheduleWithFixedDelay(this::dispatchSafely, 500, 500, TimeUnit.MILLISECONDS);
    return completion;
  }

  /** Returns the current pivot. */
  public Pivot pivot() {
    return pivot;
  }

  StateCoverage coverage() {
    return coverage;
  }

  /** Returns the share of the account key space downloaded, from 0 to 1. */
  public double accountCoverage() {
    return coverage.accounts().coverage();
  }

  /** Returns the number of accounts whose storage is still to download. */
  public int pendingStorageCount() {
    return coverage.pendingStorageCount();
  }

  /** Returns whether peers stopped serving the state of the pivot. */
  public boolean isPivotStale() {
    return pivotStale;
  }

  /** Returns whether every account, slot and bytecode of the pivot is persisted. */
  @VisibleForTesting
  boolean isComplete() {
    return completion.isDone() && !completion.isCompletedExceptionally();
  }

  /**
   * Moves the download to a newer pivot, applying the state changes of the blocks in between to
   * what is already persisted.
   */
  public CompletableFuture<StateDiffApplier.Outcome> moveTo(
      final BlockHeader newPivot, final StateDiff diff) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return exclusively(
                () -> {
                  final StateDiffApplier.Outcome outcome =
                      StateDiffApplier.apply(diff, storage, coverage);
                  switchPivot(newPivot);
                  return outcome;
                });
          } finally {
            execute(this::dispatch);
          }
        },
        scheduler.workers());
  }

  /**
   * Runs a write on the flat state at a known pivot, exclusive with pivot moves.
   *
   * @return false if the pivot moved and nothing was run
   */
  boolean withPivot(final int epoch, final Runnable write) {
    return atPivot(
            epoch,
            () -> {
              write.run();
              return Boolean.TRUE;
            })
        != null;
  }

  // the result of a write at a known pivot, or null if the pivot moved
  private <T> T atPivot(final int epoch, final Supplier<T> write) {
    pivotLock.readLock().lock();
    try {
      // a stopped download must not touch a storage that may already be wiped for a new sync
      return stopped || pivot.epoch() != epoch ? null : write.get();
    } finally {
      pivotLock.readLock().unlock();
    }
  }

  // runs an action exclusive with pivot moves, unless the download stopped
  private boolean withoutMoves(final Runnable action) {
    pivotLock.readLock().lock();
    try {
      if (stopped) {
        return false;
      }
      action.run();
      return true;
    } finally {
      pivotLock.readLock().unlock();
    }
  }

  /** Returns the number of peers left out of state requests because they serve no state. */
  public int peersWithoutState() {
    return excludedCount;
  }

  /** Returns the number of storage tries built while downloading. */
  public long storageTriesBuilt() {
    return tries.map(StorageTries::built).orElse(0L);
  }

  /** Returns the number of downloaded storages waiting for their trie. */
  public int storageTriesQueued() {
    return tries.map(StorageTries::queued).orElse(0);
  }

  /** Runs a write on the flat state exclusive with every download write. */
  <T> T exclusively(final Supplier<T> write) {
    pivotLock.writeLock().lock();
    try {
      if (stopped) {
        throw new IllegalStateException("state download stopped");
      }
      // a sorted file stored after the write would hide it
      storage.sortedWrites().ifPresent(SyncStateStorage.SortedWrites::flush);
      return write.get();
    } finally {
      pivotLock.writeLock().unlock();
    }
  }

  /** Stops sending new requests, while several pivot moves are applied in a row. */
  public void pause() {
    paused = true;
  }

  /** Resumes sending requests. */
  public void resume() {
    paused = false;
    execute(this::dispatch);
  }

  /** Switches to a new pivot; callers hold the exclusive lock. */
  void switchPivot(final BlockHeader newPivot) {
    pivot = new Pivot(pivot.epoch() + 1, newPivot);
    pivotStale = false;
    execute(
        () -> {
          consecutiveEmpty = 0;
          emptyPeers.clear();
        });
  }

  /** Stops downloading; once it returns, nothing more is written. */
  public void stop() {
    // waits for writes in progress
    pivotLock.writeLock().lock();
    try {
      stopped = true;
    } finally {
      pivotLock.writeLock().unlock();
    }
    loop.shutdownNow();
    persister.shutdownNow();
    tries.ifPresent(StorageTries::stop);
    storage.sortedWrites().ifPresent(SyncStateStorage.SortedWrites::close);
    completion.completeExceptionally(new CancellationException("state download stopped"));
  }

  /** Returns a one-line progress summary. */
  public String progress() {
    return String.format(
        "accounts %d (%.2f%% of key space), slots %d, bytecodes %d, pending storages %d, %d peers left out",
        accounts.get(),
        coverage.accounts().coverage() * 100,
        slots.get(),
        codes.get(),
        coverage.pendingStorageCount(),
        excludedCount);
  }

  public long downloadedAccounts() {
    return accounts.get();
  }

  public long downloadedSlots() {
    return slots.get();
  }

  public long downloadedCodes() {
    return codes.get();
  }

  private void execute(final Runnable task) {
    if (!loop.isShutdown()) {
      try {
        loop.execute(() -> runLogged(task));
      } catch (final RuntimeException e) {
        LOG.trace("State loop rejected a task", e);
      }
    }
  }

  private void dispatchSafely() {
    runLogged(this::dispatch);
  }

  // a scheduled executor keeps the exceptions of its tasks to itself
  private static void runLogged(final Runnable task) {
    try {
      task.run();
    } catch (final RuntimeException e) {
      LOG.warn("SNAP2 state task failed", e);
    }
  }

  private int inFlight() {
    return accountsInFlight + storageInFlight + codesRequestsInFlight + refreshesInFlight;
  }

  private void dispatch() {
    if (stopped || completion.isDone()) {
      return;
    }
    if (checkCompletion()) {
      return;
    }
    if (pivotStale || paused) {
      return;
    }
    excluded = servers.excluded(System.nanoTime());
    excludedCount = excluded.size();
    final Pivot current = pivot;
    final int capacity = Math.max(1, scheduler.capacity(Protocol.SNAP));
    final int target = capacity + capacity / 4;

    while (inFlight() < target && !refreshQueue.isEmpty()) {
      final StorageTask task = refreshQueue.poll();
      task.refreshQueued = false;
      if (isPending(task) && !task.refreshing) {
        sendRefresh(task, current);
      }
    }

    // accounts feed storages and bytecodes: pause them while those lag, to bound memory
    final boolean backlogged =
        coverage.pendingStorageCount() > config.storageBacklog()
            || codeQueue.size() > config.storageBacklog();
    final int accountShare = backlogged ? 0 : Math.max(1, target / 3);
    // lowest ranges first: written in key order, the state is moved down the database levels
    // instead of being rewritten at each of them
    while (firstAccountTask < accountTasks.size() && accountTasks.get(firstAccountTask).done) {
      firstAccountTask++;
    }
    for (int i = firstAccountTask; i < accountTasks.size(); i++) {
      if (inFlight() >= target || accountsInFlight >= accountShare) {
        break;
      }
      final RangeTask task = accountTasks.get(i);
      if (!task.done && !task.inFlight) {
        sendAccounts(task, current);
      }
    }

    final int codeShare = Math.max(1, target / 8);
    while (inFlight() < target && codesRequestsInFlight < codeShare && sendCodes()) {
      // keep bytecodes flowing alongside the state
    }

    final Iterator<StorageTask> chunkedIterator = chunked.iterator();
    while (chunkedIterator.hasNext() && inFlight() < target) {
      final StorageTask task = chunkedIterator.next();
      if (!isPending(task)) {
        chunkedIterator.remove();
        continue;
      }
      if (task.isRootStale()) {
        if (task.inFlight == 0) {
          requestRefresh(task);
        }
        continue;
      }
      for (final RangeTask chunk : task.chunks) {
        if (inFlight() >= target) {
          break;
        }
        if (!chunk.done && !chunk.inFlight && !task.refreshing) {
          sendStorageChunk(task, chunk, current);
        }
      }
    }

    while (inFlight() < target && !storageQueue.isEmpty()) {
      final List<StorageTask> batch = takeStorageBatch();
      if (batch.isEmpty()) {
        break;
      }
      sendStorageBatch(batch, current);
    }

    while (inFlight() < target && sendCodes()) {
      // use idle capacity for bytecodes
    }
  }

  private boolean isPending(final StorageTask task) {
    return coverage.storageTask(task.accountHash()) == task;
  }

  private void requestRefresh(final StorageTask task) {
    if (!task.refreshing && !task.refreshQueued) {
      task.refreshQueued = true;
      refreshQueue.add(task);
    }
  }

  private List<StorageTask> takeStorageBatch() {
    final List<StorageTask> batch = new ArrayList<>();
    while (batch.size() < config.storageBatchSize() && !storageQueue.isEmpty()) {
      final StorageTask task = storageQueue.poll();
      task.queued = false;
      if (!isPending(task)) {
        continue;
      }
      if (task.isRootStale()) {
        requestRefresh(task);
        continue;
      }
      batch.add(task);
    }
    return batch;
  }

  private void sendAccounts(final RangeTask task, final Pivot current) {
    task.inFlight = true;
    accountsInFlight++;
    final AccountRangeRequest request =
        new AccountRangeRequest(
            current, task.next, task.limit, config.responseBytes(), "account_range");
    send(
        request,
        current.epoch(),
        result -> persistAccounts(request, result),
        persisted -> {
          task.inFlight = false;
          accountsInFlight--;
          if (persisted != null) {
            if (persisted.done) {
              task.done = true;
            } else {
              task.next = RangeManager.incrementBytes32(persisted.coveredEnd).orElseThrow();
            }
            persisted.storageTasks.forEach(this::queueStorage);
            persisted.codeHashes.forEach(this::queueCode);
          }
        });
  }

  /**
   * Sends a request, persists its response off the threads that verify it, then runs the
   * bookkeeping on the loop with what was persisted, or null if nothing was.
   *
   * @param epoch the pivot the request is about, or {@link #NO_EPOCH}
   */
  private <T, R> void send(
      final SyncRequest<T> request,
      final int epoch,
      final Function<T, R> persist,
      final Consumer<R> bookkeeping) {
    scheduler
        .submit(request, epoch == NO_EPOCH ? Set.of() : excluded)
        .whenCompleteAsync(
            (response, error) -> {
              final R persisted =
                  error == null
                      ? persistSafely(() -> persist.apply(response.value()), request)
                      : null;
              execute(
                  () -> {
                    if (error != null) {
                      onFailure(error, epoch);
                    } else if (epoch != NO_EPOCH) {
                      onServed(response.peer(), epoch);
                    }
                    bookkeeping.accept(persisted);
                    dispatch();
                  });
            },
            persister);
  }

  private record AccountsPersisted(
      Bytes32 coveredEnd, boolean done, List<StorageTask> storageTasks, Set<Bytes32> codeHashes) {}

  private AccountsPersisted persistAccounts(
      final AccountRangeRequest request, final AccountRangeRequest.Result result) {
    return atPivot(
        request.pivot.epoch(),
        () -> {
          final Optional<SyncStateStorage.SortedWrites> sorted = storage.sortedWrites();
          final SyncStateStorage.Batch batch = storage.batch();
          final List<StorageTask> storageTasks = new ArrayList<>();
          final Set<Bytes32> codeHashes = new LinkedHashSet<>();
          int count = 0;
          for (int i = 0; i < result.hashes().size(); i++) {
            final Bytes32 hash = result.hashes().get(i);
            if (hash.compareTo(request.limit) > 0) {
              break;
            }
            final Bytes account = result.accounts().get(i);
            if (sorted.isEmpty()) {
              batch.putAccount(hash, account);
            }
            final PmtStateTrieAccountValue value = Accounts.decode(account);
            if (!value.getStorageRoot().equals(Hash.EMPTY_TRIE_HASH)) {
              storageTasks.add(
                  new StorageTask(hash, Bytes32.wrap(value.getStorageRoot().getBytes())));
            }
            if (!value.getCodeHash().equals(Hash.EMPTY)) {
              codeHashes.add(Bytes32.wrap(value.getCodeHash().getBytes()));
            }
            count++;
          }
          final List<Bytes32> hashes = result.hashes();
          final Bytes32 last = hashes.isEmpty() ? null : hashes.getLast();
          final boolean done =
              last == null || !result.hasMore() || last.compareTo(request.limit) >= 0;
          final Bytes32 end = done ? request.limit : last;
          if (sorted.isPresent()) {
            sorted
                .get()
                .accounts(
                    request.limit,
                    result.hashes().subList(0, count),
                    result.accounts().subList(0, count),
                    storageTasks.stream().map(StorageTask::accountHash).toList(),
                    done);
          }
          batch.commit();
          storageTasks.forEach(coverage::addStorageTask);
          coverage.accounts().add(request.origin, end);
          accounts.addAndGet(count);
          return new AccountsPersisted(end, done, storageTasks, codeHashes);
        });
  }

  private void queueStorage(final StorageTask task) {
    if (!task.queued) {
      task.queued = true;
      storageQueue.add(task);
    }
  }

  private void queueStorageFirst(final StorageTask task) {
    if (!task.queued) {
      task.queued = true;
      storageQueue.addFirst(task);
    }
  }

  // seenCodes only spares duplicate lookups of common bytecodes, it is bounded and may forget
  private void queueCode(final Bytes32 codeHash) {
    if (seenCodes.contains(codeHash) || codesInFlight.contains(codeHash)) {
      return;
    }
    if (seenCodes.size() >= SEEN_CODES_LIMIT) {
      seenCodes.clear();
    }
    final Bytes32 compact = codeHash.copy();
    seenCodes.add(compact);
    codeQueue.add(compact);
  }

  private void sendStorageBatch(final List<StorageTask> batch, final Pivot current) {
    storageInFlight++;
    batch.forEach(task -> task.inFlight++);
    final StorageRangesRequest request =
        new StorageRangesRequest(
            current,
            batch,
            RangeManager.MIN_RANGE,
            RangeManager.MAX_RANGE,
            null,
            config.responseBytes());
    submitStorage(request);
  }

  private void sendStorageChunk(
      final StorageTask task, final RangeTask chunk, final Pivot current) {
    storageInFlight++;
    task.inFlight++;
    chunk.inFlight = true;
    final StorageRangesRequest request =
        new StorageRangesRequest(
            current, List.of(task), chunk.next, chunk.limit, chunk, config.responseBytes());
    submitStorage(request);
  }

  private void submitStorage(final StorageRangesRequest request) {
    send(
        request,
        request.pivot.epoch(),
        result -> persistStorage(request, result),
        persisted -> {
          storageInFlight--;
          request.tasks.forEach(task -> task.inFlight--);
          if (request.chunk != null) {
            request.chunk.inFlight = false;
          }
          afterStorage(request, persisted);
        });
  }

  private record StoragePersisted(
      int served, Bytes32 lastSlot, boolean lastComplete, int lastReturned) {}

  private StoragePersisted persistStorage(
      final StorageRangesRequest request, final StorageRangesRequest.Result result) {
    return atPivot(
        request.pivot.epoch(),
        () -> {
          final Optional<SyncStateStorage.SortedWrites> sorted = storage.sortedWrites();
          final SyncStateStorage.Batch batch = storage.batch();
          final List<Covered> covered = new ArrayList<>();
          Bytes32 lastSlot = null;
          boolean lastComplete = true;
          int lastReturned = 0;
          int count = 0;
          for (int i = 0; i < result.accounts().size(); i++) {
            final StorageTask task = request.tasks.get(i);
            final StorageRangesRequest.Slots accountSlots = result.accounts().get(i);
            final List<Bytes32> sortedSlots = new ArrayList<>();
            final List<Bytes32> sortedValues = new ArrayList<>();
            for (int s = 0; s < accountSlots.hashes().size(); s++) {
              final Bytes32 slotHash = accountSlots.hashes().get(s);
              if (slotHash.compareTo(request.limit) > 0) {
                break;
              }
              final Bytes32 value = Bytes32.leftPad(RLP.decodeOne(accountSlots.values().get(s)));
              if (isPending(task) && !value.isZero()) {
                if (sorted.isPresent()) {
                  sortedSlots.add(slotHash);
                  sortedValues.add(value);
                } else {
                  batch.putStorage(task.accountHash(), slotHash, value);
                }
              }
              count++;
            }
            if (sorted.isPresent() && !sortedSlots.isEmpty()) {
              // the slots of a chunk follow each other, so do those of the first request
              sorted
                  .get()
                  .storage(
                      task.accountHash(),
                      request.chunk != null ? request.chunk : task,
                      sortedSlots,
                      sortedValues);
            }
            // the proof covers up to the last returned slot, which may lie past the limit
            final List<Bytes32> returned = accountSlots.hashes();
            final Bytes32 last = returned.isEmpty() ? null : returned.getLast();
            final boolean complete =
                accountSlots.complete() || last == null || last.compareTo(request.limit) >= 0;
            if (isPending(task)) {
              covered.add(new Covered(task, complete ? request.limit : last));
            }
            lastSlot = last;
            lastComplete = complete;
            lastReturned = returned.size();
          }
          batch.commit();
          covered.forEach(range -> cover(range.task(), request.origin, range.end()));
          slots.addAndGet(count);
          return new StoragePersisted(
              result.accounts().size(), lastSlot, lastComplete, lastReturned);
        });
  }

  private record Covered(StorageTask task, Bytes32 end) {}

  // a complete storage can get its trie built from the written slots
  private void cover(final StorageTask task, final Bytes32 origin, final Bytes32 end) {
    if (task.cover(origin, end)) {
      // its slots can go into the sorted files once those of the accounts before it are in
      storage.sortedWrites().ifPresent(sorted -> sorted.storageComplete(task.accountHash()));
      coverage.removeStorageTask(task.accountHash());
      tries.ifPresent(builder -> builder.completed(task.accountHash()));
    }
  }

  private void afterStorage(final StorageRangesRequest request, final StoragePersisted persisted) {
    if (request.chunk != null) {
      final RangeTask chunk = request.chunk;
      if (persisted != null) {
        if (persisted.lastComplete) {
          chunk.done = true;
        } else {
          chunk.next = RangeManager.incrementBytes32(persisted.lastSlot).orElseThrow();
        }
      }
      return;
    }
    // a response serves the first storages asked, in order, the last one possibly cut short
    final int served = persisted == null ? 0 : persisted.served;
    final List<StorageTask> unanswered = new ArrayList<>();
    for (int i = 0; i < request.tasks.size(); i++) {
      final StorageTask task = request.tasks.get(i);
      if (!isPending(task) || i < served - 1) {
        continue;
      }
      if (i >= served) {
        unanswered.add(task);
      } else if (!persisted.lastComplete) {
        splitIntoChunks(task, persisted.lastSlot, persisted.lastReturned);
      }
    }
    // asked for again before the storages queued after them, which keeps the order the sorted
    // files take the slots in
    for (int i = unanswered.size() - 1; i >= 0; i--) {
      queueStorageFirst(unanswered.get(i));
    }
  }

  // a storage too large for one response is downloaded in parallel slot ranges, as many as the
  // density of the slots returned so far suggests: a small storage cut short stays in one range
  private void splitIntoChunks(final StorageTask task, final Bytes32 lastSlot, final int returned) {
    final Bytes32 start = RangeManager.incrementBytes32(lastSlot).orElseThrow();
    final double covered = KeyRangeSet.fraction(RangeManager.MIN_RANGE, lastSlot);
    final double remainingSlots = returned * (1 - covered) / covered;
    final double requests = remainingSlots * BYTES_PER_SLOT / config.responseBytes();
    final int parts =
        (int) Math.max(1, Math.min(config.maxStorageChunks(), Math.ceil(requests / 4)));
    RangeManager.generateRanges(start, RangeManager.MAX_RANGE, parts)
        .forEach((from, to) -> task.chunks.add(new RangeTask(from, to)));
    chunked.add(task);
  }

  private void sendRefresh(final StorageTask task, final Pivot current) {
    task.refreshing = true;
    refreshesInFlight++;
    final AccountRangeRequest request =
        new AccountRangeRequest(
            current, task.accountHash(), task.accountHash(), SINGLE_ITEM_BYTES, "account_refresh");
    send(
        request,
        current.epoch(),
        result -> persistRefresh(request, task, result),
        unused -> {
          task.refreshing = false;
          refreshesInFlight--;
          if (isPending(task) && task.chunks.isEmpty()) {
            queueStorage(task);
          }
        });
  }

  private boolean persistRefresh(
      final AccountRangeRequest request,
      final StorageTask task,
      final AccountRangeRequest.Result result) {
    return withPivot(
        request.pivot.epoch(),
        () -> {
          if (!isPending(task)) {
            return;
          }
          final SyncStateStorage.Batch batch = storage.batch();
          writeRefetchedAccount(batch, task.accountHash(), result);
          batch.commit();
        });
  }

  /**
   * Writes an account fetched again at the pivot, or removes it if it is gone; its storage task
   * follows. Callers hold the pivot lock.
   *
   * @return the storage root of the account, empty if it has no storage
   */
  Optional<Bytes32> writeRefetchedAccount(
      final SyncStateStorage.Batch batch,
      final Bytes32 hash,
      final AccountRangeRequest.Result result) {
    if (!result.startsWith(hash)) {
      LOG.debug("Account {} no longer exists at the pivot, dropping it", hash);
      batch.removeAccount(hash);
      storage.deleteStorage(hash);
      coverage.removeStorageTask(hash);
      return Optional.empty();
    }
    final Bytes account = result.accounts().getFirst();
    batch.putAccount(hash, account);
    final Hash storageRoot = Accounts.decode(account).getStorageRoot();
    if (storageRoot.equals(Hash.EMPTY_TRIE_HASH)) {
      storage.deleteStorage(hash);
      coverage.removeStorageTask(hash);
      return Optional.empty();
    }
    final Bytes32 root = Bytes32.wrap(storageRoot.getBytes());
    final StorageTask task = coverage.storageTask(hash);
    if (task != null) {
      task.refreshRoot(root);
    }
    return Optional.of(root);
  }

  private boolean sendCodes() {
    final List<Bytes32> batch = new ArrayList<>();
    final Iterator<Bytes32> iterator = codeQueue.iterator();
    final int codesPerRequest = codesPerRequest();
    while (iterator.hasNext() && batch.size() < codesPerRequest) {
      final Bytes32 codeHash = iterator.next();
      iterator.remove();
      if (!isStored(codeHash)) {
        batch.add(codeHash);
      }
    }
    if (batch.isEmpty()) {
      return false;
    }
    codesRequestsInFlight++;
    codesInFlight.addAll(batch);
    final ByteCodesRequest request = new ByteCodesRequest(batch, config.responseBytes());
    send(
        request,
        NO_EPOCH,
        this::persistCodes,
        stored -> {
          codesRequestsInFlight--;
          codesInFlight.removeAll(batch);
          // missing codes go back to the queue
          batch.stream()
              .filter(hash -> stored == null || !stored.contains(hash))
              .forEach(codeQueue::add);
        });
    return true;
  }

  // a failed lookup only costs a download
  private boolean isStored(final Bytes32 codeHash) {
    try {
      return storage.hasCode(codeHash);
    } catch (final RuntimeException e) {
      LOG.debug("Failed to look up bytecode {}", codeHash, e);
      return false;
    }
  }

  /**
   * As many codes as fit a response at the average size of the codes received so far, but not fewer
   * than configured: a network of large codes keeps the configured count.
   */
  @VisibleForTesting
  int codesPerRequest() {
    final long received = codes.get();
    if (received == 0) {
      return config.codeBatchSize();
    }
    final long averageSize = Math.max(1, codeBytes.get() / received);
    final long fitting = config.responseBytes() / averageSize;
    return (int) Math.max(config.codeBatchSize(), Math.min(MAX_CODES_PER_REQUEST, fitting));
  }

  private Set<Bytes32> persistCodes(final Map<Bytes32, Bytes> received) {
    final boolean written =
        withoutMoves(
            () -> {
              final SyncStateStorage.Batch batch = storage.batch();
              received.forEach((hash, code) -> batch.putCode(hash, code));
              batch.commit();
            });
    if (!written) {
      return Set.of();
    }
    codes.addAndGet(received.size());
    codeBytes.addAndGet(received.values().stream().mapToLong(Bytes::size).sum());
    return received.keySet();
  }

  // a failed write is retried like a discarded response, and never skips the bookkeeping
  private <R> R persistSafely(final Supplier<R> persist, final SyncRequest<?> request) {
    try {
      return persist.get();
    } catch (final RuntimeException e) {
      LOG.warn("Failed to persist {}, retrying it", request.name(), e);
      return null;
    }
  }

  private void onServed(final EthPeer peer, final int epoch) {
    servers.served(peer, epoch);
    if (epoch == pivot.epoch()) {
      consecutiveEmpty = 0;
      emptyPeers.clear();
    }
  }

  // empty answers of peers that serve the state tell that the pivot is too old
  private void onFailure(final Throwable error, final int epoch) {
    RequestFailedException.from(error)
        .ifPresentOrElse(
            failure -> {
              if (failure.reason() == RequestFailedException.Reason.EMPTY
                  && epoch == pivot.epoch()
                  && failure.peer().isPresent()) {
                onEmpty(failure.peer().get(), epoch);
              }
              LOG.trace("State request failed: {}", failure.getMessage());
            },
            () -> LOG.debug("State request failed", error));
  }

  private void onEmpty(final EthPeer peer, final int epoch) {
    if (servers.empty(peer, epoch, System.nanoTime())) {
      LOG.debug(
          "{} does not serve the state of block {}, leaving it out for a while",
          peer.getLoggableId(),
          pivot.header().getNumber());
      emptyPeers.remove(peer);
      return;
    }
    // a peer that never served state says nothing about the age of the pivot
    if (!servers.hasServed(peer)) {
      return;
    }
    consecutiveEmpty++;
    emptyPeers.add(peer);
    final int candidates = Math.max(1, scheduler.peerCount(Protocol.SNAP) - excluded.size());
    if (consecutiveEmpty >= EMPTY_RESPONSES_FOR_STALE_PIVOT
        && emptyPeers.size() >= Math.min(3, candidates)
        && !pivotStale) {
      LOG.info(
          "Peers stopped serving the state of block {}, the pivot must move",
          pivot.header().getNumber());
      pivotStale = true;
    }
  }

  private boolean checkCompletion() {
    if (accountTasks.isEmpty()
        || !coverage.accounts().isFull()
        || coverage.pendingStorageCount() > 0
        || !codeQueue.isEmpty()
        || !codesInFlight.isEmpty()
        || inFlight() > 0) {
      return false;
    }
    // the tries are built from what was written
    storage.sortedWrites().ifPresent(SyncStateStorage.SortedWrites::flush);
    LOG.info(
        "SNAP2 world state download complete at block {}: {}",
        pivot.header().getNumber(),
        progress());
    completion.complete(null);
    return true;
  }
}
