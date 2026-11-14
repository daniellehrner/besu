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
package org.hyperledger.besu.ethereum.mainnet.parallelization;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.MainnetTransactionProcessor;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList.BlockAccessListBuilder;
import org.hyperledger.besu.ethereum.mainnet.parallelization.BlockFootprints.Footprint;
import org.hyperledger.besu.ethereum.mainnet.parallelization.BlockFootprints.RawWrite;
import org.hyperledger.besu.ethereum.mainnet.parallelization.BlockFootprints.Seeding;
import org.hyperledger.besu.ethereum.mainnet.staterootcommitter.DefaultStateRootCommitter;
import org.hyperledger.besu.ethereum.mainnet.staterootcommitter.PrecomputedStorageTries;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.PathBasedWorldStateUpdateAccumulator;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.PathBasedWorldStateUpdateAccumulator.StorageLoadSeeder;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.blockhash.BlockHashLookup;
import org.hyperledger.besu.evm.worldstate.UpdateTrackingAccount;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;
import org.hyperledger.besu.plugin.services.worldstate.StateRootCommitter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.annotations.VisibleForTesting;
import org.apache.tuweni.units.bigints.UInt256;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes an EVM v2 block the way Nethermind hands its pre-warm runs over to block processing.
 *
 * <p>Workers run the block's transactions speculatively, as {@link
 * OptimisticConcurrentTransactionProcessor} does, and each run leaves a footprint: the slots it
 * read and wrote. A footprint whose reads an earlier transaction's writes invalidate is refreshed
 * by a refresh worker, nearest to block processing first: the transaction runs again on the slot
 * values the transactions before it leave. Block processing takes a refreshed result when the block
 * holds the values it started from, by default does not wait for a refresh, and reports the writes
 * of the transactions it executes itself, which refreshes the footprints they invalidate.
 *
 * <p>Options, as system properties: {@code besu.t20.refreshThreads} (refresh workers, 1), {@code
 * besu.t20.settledFirst} (refresh a footprint only once the writers it read from hold their
 * values), {@code besu.t20.maxRefreshesPerTx} (refreshes of one transaction, 0 for no limit),
 * {@code besu.t20.waitRefreshMs} (block processing waits that long for a running refresh, 0),
 * {@code besu.t20.heavyThreads} (a pool of its own for heavy transactions' runs, 0 for none),
 * {@code besu.t20.carrySender} and {@code besu.t20.carryNonce} (carried balance and nonce changes,
 * on).
 */
public class HandoffTransactionProcessor extends OptimisticConcurrentTransactionProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(HandoffTransactionProcessor.class);

  private static final boolean LOG_HANDOFF = Boolean.getBoolean("besu.bench.logHandoff");

  // with the conflict log, marks the conflicts of handed-over results: the line after its reason
  private static final boolean LOG_REJECTED = Boolean.getBoolean("besu.bench.logConflicts");

  // more than one refresh worker can run a refresh on writes another one is still replacing; the
  // result is then invalidated again when it is stored
  private static final int REFRESH_THREADS = Integer.getInteger("besu.t20.refreshThreads", 1);

  // block processing reaching a transaction whose refresh runs waits this long for it, as the
  // refresh is ahead of executing the transaction from the start; 0 executes it at once
  private static final long WAIT_REFRESH_MS = Long.getLong("besu.t20.waitRefreshMs", 0L);

  // a refresh worker skips a footprint whose writers are refreshed themselves, until they are done
  private static final boolean SETTLED_FIRST = Boolean.getBoolean("besu.t20.settledFirst");

  // refreshes of one transaction, 0 for no limit: one invalidated again and again is left to block
  // processing
  private static final int MAX_REFRESHES_PER_TX =
      Integer.getInteger("besu.t20.maxRefreshesPerTx", 0);

  // a refresh that reads a slot beyond its seeds starts it from the value the footprints before it
  // leave there, rather than from the parent's
  private static final boolean SEED_ON_LOAD = Boolean.getBoolean("besu.t20.seedOnLoad");

  // block processing imports a taken result without the trie preloads of what it changed and the
  // merge of its slot hashes, which a thread of their own runs; both only warm caches
  private static final boolean CHEAP_TAKES = Boolean.getBoolean("besu.t20.cheapTakes");
  private static final ExecutorService TAKE_SIDE_WORK =
      Executors.newSingleThreadExecutor(
          runnable -> {
            final Thread thread = new Thread(runnable, "besu-take-preloads");
            thread.setDaemon(true);
            return thread;
          });

  // once block processing passes this share of the block, the storage tries are brought to the
  // slot values the footprints predict, while the rest of the block executes; 0 leaves the state
  // root to the end of the block
  private static final double PREDICTED_ROOTS_AT =
      Double.parseDouble(System.getProperty("besu.t20.predictedRootsAt", "0"));

  // tests: block processing runs the refreshes itself before each transaction, so that they happen
  // on the writes of the transactions before it rather than whenever a worker gets to them
  private static volatile boolean refreshSynchronously = false;

  @VisibleForTesting
  static void refreshSynchronously(final boolean synchronously) {
    refreshSynchronously = synchronously;
  }

  // heavy transactions run alone on threads of their own, so that they start at once without
  // holding back the light ones behind them; 0 runs them with the others
  private static final int HEAVY_THREADS = Integer.getInteger("besu.t20.heavyThreads", 0);
  private static final AtomicInteger HEAVY_THREAD_IDS = new AtomicInteger();
  private static final ExecutorService HEAVY_EXECUTOR =
      HEAVY_THREADS <= 0
          ? null
          : Executors.newFixedThreadPool(
              HEAVY_THREADS,
              runnable -> {
                final Thread thread =
                    new Thread(runnable, "besu-heavy-run-" + HEAVY_THREAD_IDS.getAndIncrement());
                thread.setDaemon(true);
                return thread;
              });

  private static final AtomicInteger REFRESH_THREAD_IDS = new AtomicInteger();
  private static final ExecutorService REFRESH_EXECUTOR =
      Executors.newCachedThreadPool(
          runnable -> {
            final Thread thread =
                new Thread(
                    runnable, "besu-footprint-refresh-" + REFRESH_THREAD_IDS.getAndIncrement());
            thread.setDaemon(true);
            return thread;
          });

  private record BlockRun(
      ProtocolContext protocolContext,
      BlockHeader blockHeader,
      List<Transaction> transactions,
      Address miningBeneficiary,
      BlockHashLookup blockHashLookup,
      Wei blobGasPrice,
      Optional<BlockAccessListBuilder> blockAccessListBuilder,
      Optional<BlockHeader> maybeParentHeader,
      Executor executor) {}

  /** A transaction of the block by its sender's nonce. */
  private record SenderNonce(Address sender, long nonce) {}

  // marks the runs started on top of a sender's previous transaction, on the thread running them
  private static final ThreadLocal<Boolean> ON_DEMAND = ThreadLocal.withInitial(() -> false);

  // the latest result of each transaction by its sender's nonce, and the transactions whose own run
  // failed its nonce check waiting for the result of their sender's previous one
  private final Map<SenderNonce, Footprint> resultsBySenderNonce = new ConcurrentHashMap<>();
  private final Map<SenderNonce, Integer> positionsBySenderNonce = new ConcurrentHashMap<>();
  private final Map<SenderNonce, Integer> waitingForPrevious = new ConcurrentHashMap<>();

  private volatile @Nullable BlockFootprints footprints;
  private volatile @Nullable BlockRun blockRun;
  private volatile @Nullable PrecomputedStorageTries precomputedStorageTries;
  private static final java.util.concurrent.atomic.AtomicBoolean ROOTS_EXPLAINED =
      new java.util.concurrent.atomic.AtomicBoolean();
  // block processing only
  private boolean precomputeStarted;

  // counters for the bench log, written by block processing only
  private int takenRefreshed;
  private int takenOnDemand;
  private int missedWhileRefreshing;
  private int missedPendingRefresh;
  private int missedNotInvalidated;
  private final AtomicInteger onDemandRuns = new AtomicInteger();
  private int rejectedRefreshed;
  private int takenOther;
  private int executedReported;
  private int waitedForRefresh;
  private int takenAfterWait;

  // the sender's balance is carried over when earlier transactions only raised it, as Nethermind
  // replays a balance as a change on a minimum
  private static final boolean CARRY_SENDER =
      Boolean.parseBoolean(System.getProperty("besu.t20.carrySender", "true"));

  // a nonce earlier transactions changed is kept for an account the transaction only called or paid
  private static final boolean CARRY_NONCE =
      Boolean.parseBoolean(System.getProperty("besu.t20.carryNonce", "true"));

  public HandoffTransactionProcessor(final MainnetTransactionProcessor transactionProcessor) {
    super(
        transactionProcessor,
        new TransactionCollisionDetector(CARRY_SENDER, CARRY_NONCE),
        BlockProcessingExecutors.ioExecutor(),
        2 * BlockProcessingExecutors.cpuThreads());
  }

  @Override
  public void runAsyncBlock(
      final ProtocolContext protocolContext,
      final BlockHeader blockHeader,
      final List<Transaction> transactions,
      final Address miningBeneficiary,
      final BlockHashLookup blockHashLookup,
      final Wei blobGasPrice,
      final Executor executor,
      final Optional<BlockAccessListBuilder> blockAccessListBuilder,
      final Optional<BlockHeader> maybeParentHeader) {
    final BlockFootprints blockFootprints =
        new BlockFootprints(transactions.size(), SETTLED_FIRST, MAX_REFRESHES_PER_TX);
    final BlockRun run =
        new BlockRun(
            protocolContext,
            blockHeader,
            transactions,
            miningBeneficiary,
            blockHashLookup,
            blobGasPrice,
            blockAccessListBuilder,
            maybeParentHeader,
            executor);
    // set before the runs start, as each stores its footprint when it ends
    footprints = blockFootprints;
    blockRun = run;
    super.runAsyncBlock(
        protocolContext,
        blockHeader,
        transactions,
        miningBeneficiary,
        blockHashLookup,
        blobGasPrice,
        executor,
        blockAccessListBuilder,
        maybeParentHeader);
    if (maybeParentHeader.isPresent() && transactions.size() > 2 && !refreshSynchronously) {
      try {
        for (int i = 0; i < REFRESH_THREADS; i++) {
          REFRESH_EXECUTOR.execute(() -> refreshInvalidated(blockFootprints, run));
        }
      } catch (final RejectedExecutionException e) {
        LOG.debug("Could not start refreshing footprints", e);
      }
    }
  }

  @Override
  public StateRootCommitter stateRootCommitter(
      final StateRootCommitter committer, final MutableWorldState worldState) {
    if (PREDICTED_ROOTS_AT <= 0
        || footprints == null
        || !(committer instanceof DefaultStateRootCommitter)
        || !(worldState instanceof BonsaiWorldState bonsai)) {
      if (PREDICTED_ROOTS_AT > 0 && LOG_HANDOFF && !ROOTS_EXPLAINED.getAndSet(true)) {
        LOG.info(
            "Bench predicted roots off: footprints {} committer {} world state {}",
            footprints != null,
            committer.getClass().getName(),
            worldState.getClass().getName());
      }
      return committer;
    }
    final PrecomputedStorageTries tries = new PrecomputedStorageTries(bonsai);
    precomputedStorageTries = tries;
    return new DefaultStateRootCommitter(tries);
  }

  @Override
  protected @Nullable StorageLoadSeeder predictedValues(final int transactionLocation) {
    final BlockFootprints blockFootprints = footprints;
    if (!SEED_ON_LOAD || blockFootprints == null) {
      return null;
    }
    return (address, slotKey) -> blockFootprints.predicted(transactionLocation, address, slotKey);
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  protected void importTaken(
      final PathBasedWorldStateUpdateAccumulator blockAccumulator,
      final PathBasedWorldStateUpdateAccumulator<?> transactionAccumulator) {
    if (CHEAP_TAKES) {
      blockAccumulator.importStateChangesFromSource(transactionAccumulator, TAKE_SIDE_WORK);
    } else {
      super.importTaken(blockAccumulator, transactionAccumulator);
    }
  }

  @Override
  protected Executor runExecutor(final Transaction transaction, final Executor executor) {
    return HEAVY_EXECUTOR != null && isHeavy(transaction) ? HEAVY_EXECUTOR : executor;
  }

  @Override
  protected void onRunFinished(
      final int transactionLocation,
      final ParallelizedTransactionContext context,
      final @Nullable Map<Address, Map<StorageSlotKey, UInt256>> seeds) {
    final BlockFootprints blockFootprints = footprints;
    final BlockRun run = blockRun;
    // a refresh stores the footprint itself
    if (seeds != null || blockFootprints == null || run == null || context == null) {
      return;
    }
    final Transaction transaction = run.transactions().get(transactionLocation);
    final Optional<Address> sender = transaction.getSenderIfKnown();
    if (context.transactionProcessingResult().isInvalid()) {
      if (sender.isPresent() && transaction.getNonce() > 0 && isNonceTooHigh(context)) {
        runAfterPrevious(blockFootprints, run, transactionLocation, sender.get(), transaction);
      }
      return;
    }
    final boolean onDemand = ON_DEMAND.get();
    final Footprint footprint = Footprint.of(context, run.miningBeneficiary(), onDemand);
    // a run on top of its sender's previous transaction replaces the transaction's failed one
    if (blockFootprints.store(transactionLocation, footprint, !onDemand)) {
      registerResult(blockFootprints, run, transactionLocation, transaction, footprint);
    }
  }

  /**
   * Records the latest result of a transaction by its sender's nonce, and starts the sender's next
   * transaction on it if that one waits for it.
   */
  private void registerResult(
      final BlockFootprints blockFootprints,
      final BlockRun run,
      final int transactionLocation,
      final Transaction transaction,
      final Footprint footprint) {
    final Optional<Address> sender = transaction.getSenderIfKnown();
    if (sender.isEmpty()) {
      return;
    }
    final SenderNonce own = new SenderNonce(sender.get(), transaction.getNonce());
    resultsBySenderNonce.put(own, footprint);
    positionsBySenderNonce.put(own, transactionLocation);
    if (footprint.context.chainPredecessorAccumulator() != null && transaction.getNonce() > 0) {
      final Integer predecessor =
          positionsBySenderNonce.get(new SenderNonce(sender.get(), transaction.getNonce() - 1));
      if (predecessor != null) {
        blockFootprints.chainedOn(transactionLocation, predecessor);
      }
    }
    final Integer next =
        waitingForPrevious.remove(new SenderNonce(sender.get(), transaction.getNonce() + 1));
    if (next != null) {
      startOnTopOf(blockFootprints, run, next, footprint);
    }
  }

  private static boolean isNonceTooHigh(final ParallelizedTransactionContext context) {
    final var validation = context.transactionProcessingResult().getValidationResult();
    return !validation.isValid()
        && validation.getInvalidReason() == TransactionInvalidReason.NONCE_TOO_HIGH;
  }

  /**
   * A transaction whose run failed because its sender's previous transaction in the block had not
   * run before it runs again on top of that one's result, once there is one.
   */
  private void runAfterPrevious(
      final BlockFootprints blockFootprints,
      final BlockRun run,
      final int transactionLocation,
      final Address sender,
      final Transaction transaction) {
    final SenderNonce previous = new SenderNonce(sender, transaction.getNonce() - 1);
    Footprint result = resultsBySenderNonce.get(previous);
    if (result == null) {
      final SenderNonce own = new SenderNonce(sender, transaction.getNonce());
      waitingForPrevious.put(own, transactionLocation);
      // the previous result may have been stored since it was looked up
      result = resultsBySenderNonce.get(previous);
      if (result == null || !waitingForPrevious.remove(own, transactionLocation)) {
        return;
      }
    }
    startOnTopOf(blockFootprints, run, transactionLocation, result);
  }

  private void startOnTopOf(
      final BlockFootprints blockFootprints,
      final BlockRun run,
      final int transactionLocation,
      final Footprint previous) {
    if (blockFootprints.isFinished() || blockFootprints.mainThreadAt() >= transactionLocation) {
      return;
    }
    onDemandRuns.incrementAndGet();
    try {
      run.executor()
          .execute(
              () -> {
                if (blockFootprints.isFinished()
                    || blockFootprints.mainThreadAt() >= transactionLocation) {
                  return;
                }
                ON_DEMAND.set(true);
                try {
                  runOnTopOf(
                      run.protocolContext(),
                      run.blockHeader(),
                      transactionLocation,
                      run.transactions().get(transactionLocation),
                      run.miningBeneficiary(),
                      run.blockHashLookup(),
                      run.blobGasPrice(),
                      run.blockAccessListBuilder(),
                      run.maybeParentHeader(),
                      previous.context.transactionAccumulator());
                } catch (final RuntimeException e) {
                  LOG.debug(
                      "Error running transaction {} on its sender's previous one",
                      transactionLocation,
                      e);
                } finally {
                  ON_DEMAND.set(false);
                }
              });
    } catch (final RejectedExecutionException e) {
      LOG.debug(
          "Could not run transaction {} on its sender's previous one", transactionLocation, e);
    }
  }

  @Override
  public Optional<TransactionProcessingResult> getProcessingResult(
      final MutableWorldState worldState,
      final Address miningBeneficiary,
      final Transaction transaction,
      final int transactionLocation,
      final Optional<Counter> confirmedParallelizedTransactionCounter,
      final Optional<Counter> conflictingButCachedTransactionCounter) {
    final BlockFootprints blockFootprints = footprints;
    if (blockFootprints == null) {
      return super.getProcessingResult(
          worldState,
          miningBeneficiary,
          transaction,
          transactionLocation,
          confirmedParallelizedTransactionCounter,
          conflictingButCachedTransactionCounter);
    }
    final BlockRun run = blockRun;
    if (refreshSynchronously && run != null) {
      blockFootprints.applyReported();
      int position;
      while ((position = blockFootprints.takeInvalidated(transactionLocation - 1)) >= 0) {
        refresh(blockFootprints, run, position);
      }
    }
    blockFootprints.mainThreadAt(transactionLocation);
    final PrecomputedStorageTries tries = precomputedStorageTries;
    if (tries != null
        && !precomputeStarted
        && transactionLocation >= PREDICTED_ROOTS_AT * blockFootprints.size()) {
      precomputeStarted = true;
      try {
        REFRESH_EXECUTOR.execute(() -> tries.start(blockFootprints.predictedFinalWrites()));
      } catch (final RejectedExecutionException e) {
        LOG.debug("Could not start precomputing storage tries", e);
      }
    }
    final boolean waited =
        WAIT_REFRESH_MS > 0 && blockFootprints.awaitRefresh(transactionLocation, WAIT_REFRESH_MS);
    if (waited) {
      waitedForRefresh++;
    }
    final Footprint latest = blockFootprints.latest(transactionLocation);
    if (latest != null && latest.isHandedOver()) {
      // a refreshed run started from later values than the runs this class started earlier
      reachedWithoutRuns(transactionLocation);
      final Optional<TransactionProcessingResult> result =
          takeOrReject(
              worldState,
              miningBeneficiary,
              transaction,
              transactionLocation,
              latest.context,
              confirmedParallelizedTransactionCounter,
              conflictingButCachedTransactionCounter);
      if (result.isPresent()) {
        if (latest.refreshed) {
          takenRefreshed++;
        } else {
          takenOnDemand++;
        }
        if (waited) {
          takenAfterWait++;
        }
        blockFootprints.reportTaken(transactionLocation);
      } else {
        rejectedRefreshed++;
        if (LOG_REJECTED) {
          LOG.info(
              "Bench handoff rejected idx {} refreshed {} waited {}",
              transactionLocation,
              latest.refreshed,
              waited);
        }
      }
      return result;
    }
    final Optional<TransactionProcessingResult> result =
        super.getProcessingResult(
            worldState,
            miningBeneficiary,
            transaction,
            transactionLocation,
            confirmedParallelizedTransactionCounter,
            conflictingButCachedTransactionCounter);
    if (result.isPresent()) {
      takenOther++;
      blockFootprints.reportTaken(transactionLocation);
    } else if (LOG_HANDOFF) {
      if (blockFootprints.isRefreshing(transactionLocation)) {
        missedWhileRefreshing++;
      } else if (blockFootprints.isInvalidated(transactionLocation)) {
        missedPendingRefresh++;
      } else {
        missedNotInvalidated++;
      }
    }
    return result;
  }

  @Override
  public void onExecutedSequentially(final int location, final WorldUpdater transactionUpdater) {
    final BlockFootprints blockFootprints = footprints;
    if (blockFootprints == null) {
      return;
    }
    // the changes of the transaction before they are committed: read from the updater on this
    // thread, hashed on the refresh worker
    final List<RawWrite> writes = new ArrayList<>();
    final Set<Address> cleared = new HashSet<>();
    for (final Account account : transactionUpdater.getTouchedAccounts()) {
      if (account instanceof UpdateTrackingAccount<?> tracked) {
        final Address address = tracked.getAddress();
        if (tracked.getStorageWasCleared()) {
          cleared.add(address);
        }
        for (final Map.Entry<UInt256, UInt256> slot : tracked.getUpdatedStorage().entrySet()) {
          writes.add(new RawWrite(address, slot.getKey(), slot.getValue()));
        }
      }
    }
    cleared.addAll(transactionUpdater.getDeletedAccountAddresses());
    executedReported++;
    blockFootprints.reportExecuted(location, writes, cleared);
  }

  @Override
  public void abort() {
    final BlockFootprints blockFootprints = footprints;
    final BlockRun run = blockRun;
    if (blockFootprints != null) {
      blockFootprints.finish();
      if (LOG_HANDOFF && run != null) {
        final PrecomputedStorageTries tries = precomputedStorageTries;
        final int[] rootCounters = tries == null ? new int[4] : tries.counters();
        LOG.info(
            "Bench handoff block {} txs {} stored {} invalidations {} refreshes stored {} skipped {}"
                + " taken refreshed {} rejected refreshed {} taken other {} executed {}"
                + " on demand runs {} taken on demand {} missed refreshing {} missed pending {}"
                + " missed not invalidated {} waited for refresh {} taken after wait {}"
                + " refreshes capped {} roots precomputed {} taken {} not done {} fell back {}",
            run.blockHeader().getNumber(),
            blockFootprints.size(),
            blockFootprints.stored,
            blockFootprints.invalidations,
            blockFootprints.refreshesStored,
            blockFootprints.refreshesSkipped,
            takenRefreshed,
            rejectedRefreshed,
            takenOther,
            executedReported,
            onDemandRuns.get(),
            takenOnDemand,
            missedWhileRefreshing,
            missedPendingRefresh,
            missedNotInvalidated,
            waitedForRefresh,
            takenAfterWait,
            blockFootprints.refreshesCapped,
            rootCounters[0],
            rootCounters[1],
            rootCounters[2],
            rootCounters[3]);
      }
    }
    footprints = null;
    blockRun = null;
    super.abort();
  }

  /**
   * Refreshes the footprints the block's earlier transactions invalidate, nearest to block
   * processing first, until block processing reaches the block's last transaction. The transaction
   * block processing reaches next is left to it.
   */
  private void refreshInvalidated(final BlockFootprints blockFootprints, final BlockRun run) {
    try {
      while (!blockFootprints.isFinished()) {
        blockFootprints.applyReported();
        final int processing = blockFootprints.mainThreadAt();
        if (processing >= blockFootprints.size() - 1) {
          return;
        }
        final int position = blockFootprints.takeInvalidated(processing + 1);
        if (position >= 0) {
          refresh(blockFootprints, run, position);
          continue;
        }
        blockFootprints.waitForWork();
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final RuntimeException e) {
      LOG.debug("Error refreshing invalidated footprints", e);
    }
  }

  private void refresh(
      final BlockFootprints blockFootprints, final BlockRun run, final int position) {
    final Footprint invalidated = blockFootprints.latest(position);
    if (invalidated == null || !invalidated.refreshable) {
      return;
    }
    final Transaction transaction = run.transactions().get(position);
    // a member of its sender's chain runs again on the latest result of its predecessor
    PathBasedWorldStateUpdateAccumulator<?> previous = null;
    if (invalidated.context.chainPredecessorAccumulator() != null) {
      final Optional<Address> sender = transaction.getSenderIfKnown();
      final Footprint predecessor =
          sender.isEmpty() || transaction.getNonce() == 0
              ? null
              : resultsBySenderNonce.get(new SenderNonce(sender.get(), transaction.getNonce() - 1));
      if (predecessor == null) {
        return;
      }
      previous = predecessor.context.transactionAccumulator();
    }
    final Seeding seeding = blockFootprints.seedsFor(position, invalidated);
    if (seeding == null) {
      return;
    }
    final CompletableFuture<Void> done = blockFootprints.refreshStarts(position);
    try {
      if (blockFootprints.mainThreadAt() >= position) {
        return;
      }
      final ParallelizedTransactionContext context =
          previous == null
              ? runSeeded(
                  run.protocolContext(),
                  run.blockHeader(),
                  position,
                  transaction,
                  run.miningBeneficiary(),
                  run.blockHashLookup(),
                  run.blobGasPrice(),
                  run.blockAccessListBuilder(),
                  run.maybeParentHeader(),
                  seeding.seeds())
              : runSeededOnTopOf(
                  run.protocolContext(),
                  run.blockHeader(),
                  position,
                  transaction,
                  run.miningBeneficiary(),
                  run.blockHashLookup(),
                  run.blobGasPrice(),
                  run.blockAccessListBuilder(),
                  run.maybeParentHeader(),
                  previous,
                  seeding.seeds());
      if (context == null || context.transactionProcessingResult().isInvalid()) {
        return;
      }
      final Footprint refreshed = Footprint.of(context, run.miningBeneficiary());
      if (blockFootprints.store(position, refreshed, false)) {
        registerResult(blockFootprints, run, position, transaction, refreshed);
      }
    } catch (final RuntimeException e) {
      LOG.debug("Error refreshing the footprint of transaction {}", position, e);
    } finally {
      // after the store, so that block processing waiting for it finds the refreshed footprint
      blockFootprints.refreshEnds(position, done);
    }
  }
}
