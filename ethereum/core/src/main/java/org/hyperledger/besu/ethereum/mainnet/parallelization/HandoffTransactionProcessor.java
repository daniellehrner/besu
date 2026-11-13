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
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.blockhash.BlockHashLookup;
import org.hyperledger.besu.evm.worldstate.UpdateTrackingAccount;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * holds the values it started from, never waits for a refresh, and reports the writes of the
 * transactions it executes itself, which refreshes the footprints they invalidate.
 */
public class HandoffTransactionProcessor extends OptimisticConcurrentTransactionProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(HandoffTransactionProcessor.class);

  private static final boolean LOG_HANDOFF = Boolean.getBoolean("besu.bench.logHandoff");

  // tests: block processing runs the refreshes itself before each transaction, so that they happen
  // on the writes of the transactions before it rather than whenever a worker gets to them
  private static volatile boolean refreshSynchronously = false;

  @VisibleForTesting
  static void refreshSynchronously(final boolean synchronously) {
    refreshSynchronously = synchronously;
  }

  private static final AtomicInteger REFRESH_THREADS = new AtomicInteger();
  private static final ExecutorService REFRESH_EXECUTOR =
      Executors.newCachedThreadPool(
          runnable -> {
            final Thread thread =
                new Thread(runnable, "besu-footprint-refresh-" + REFRESH_THREADS.getAndIncrement());
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
      Optional<BlockHeader> maybeParentHeader) {}

  private volatile @Nullable BlockFootprints footprints;
  private volatile @Nullable BlockRun blockRun;

  // counters for the bench log, written by block processing only
  private int takenRefreshed;
  private int rejectedRefreshed;
  private int takenOther;
  private int executedReported;

  public HandoffTransactionProcessor(final MainnetTransactionProcessor transactionProcessor) {
    super(transactionProcessor);
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
    final BlockFootprints blockFootprints = new BlockFootprints(transactions.size());
    final BlockRun run =
        new BlockRun(
            protocolContext,
            blockHeader,
            transactions,
            miningBeneficiary,
            blockHashLookup,
            blobGasPrice,
            blockAccessListBuilder,
            maybeParentHeader);
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
        REFRESH_EXECUTOR.execute(() -> refreshInvalidated(blockFootprints, run));
      } catch (final RejectedExecutionException e) {
        LOG.debug("Could not start refreshing footprints", e);
      }
    }
  }

  @Override
  protected void onRunFinished(
      final int transactionLocation,
      final ParallelizedTransactionContext context,
      final @Nullable Map<Address, Map<StorageSlotKey, UInt256>> seeds) {
    final BlockFootprints blockFootprints = footprints;
    final BlockRun run = blockRun;
    // a refresh stores the footprint itself
    if (seeds != null
        || blockFootprints == null
        || run == null
        || context == null
        || context.transactionProcessingResult().isInvalid()) {
      return;
    }
    blockFootprints.store(
        transactionLocation, Footprint.of(context, run.miningBeneficiary()), true);
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
    final Footprint latest = blockFootprints.latest(transactionLocation);
    if (latest != null && latest.refreshed) {
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
        takenRefreshed++;
        blockFootprints.reportTaken(transactionLocation);
      } else {
        rejectedRefreshed++;
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
        LOG.info(
            "Bench handoff block {} txs {} stored {} invalidations {} refreshes stored {} skipped {}"
                + " taken refreshed {} rejected refreshed {} taken other {} executed {}",
            run.blockHeader().getNumber(),
            blockFootprints.size(),
            blockFootprints.stored,
            blockFootprints.invalidations,
            blockFootprints.refreshesStored,
            blockFootprints.refreshesSkipped,
            takenRefreshed,
            rejectedRefreshed,
            takenOther,
            executedReported);
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
    if (invalidated == null || !invalidated.refreshable || isChainMember(position)) {
      return;
    }
    final Seeding seeding = blockFootprints.seedsFor(position, invalidated);
    if (seeding == null || blockFootprints.mainThreadAt() >= position) {
      return;
    }
    final ParallelizedTransactionContext context;
    try {
      context =
          runSeeded(
              run.protocolContext(),
              run.blockHeader(),
              position,
              run.transactions().get(position),
              run.miningBeneficiary(),
              run.blockHashLookup(),
              run.blobGasPrice(),
              run.blockAccessListBuilder(),
              run.maybeParentHeader(),
              seeding.seeds());
    } catch (final RuntimeException e) {
      LOG.debug("Error refreshing the footprint of transaction {}", position, e);
      return;
    }
    if (context == null || context.transactionProcessingResult().isInvalid()) {
      return;
    }
    blockFootprints.store(position, Footprint.of(context, run.miningBeneficiary()), false);
  }
}
