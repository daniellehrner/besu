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
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.BlockImportTimings;
import org.hyperledger.besu.ethereum.mainnet.MainnetTransactionProcessor;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.AccessLocationTracker;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList.BlockAccessListBuilder;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessListAccountLookup;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessListOverlay;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.PartialBlockAccessView;
import org.hyperledger.besu.ethereum.mainnet.parallelization.prefetch.BalPrefetcher;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.PathBasedWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.PathBasedWorldStateUpdateAccumulator;
import org.hyperledger.besu.ethereum.worldstate.WorldStateQueryParams;
import org.hyperledger.besu.evm.blockhash.BlockHashLookup;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({"unchecked", "rawtypes"})
public class BalConcurrentTransactionProcessor extends ParallelBlockTransactionProcessor {

  private static final Logger LOG =
      LoggerFactory.getLogger(BalConcurrentTransactionProcessor.class);

  private final MainnetTransactionProcessor transactionProcessor;
  private final BlockAccessList blockAccessList;
  private final BlockAccessListAccountLookup blockAccessListAccountLookup;
  private final Optional<BalPrefetcher> maybePrefetcher;
  // only while debug logging is on: when each transaction ran, to see what the import waits for
  private ExecutionTrace trace;

  public BalConcurrentTransactionProcessor(
      final MainnetTransactionProcessor transactionProcessor,
      final BlockAccessList blockAccessList,
      final BalConfiguration balConfiguration) {
    this.transactionProcessor = transactionProcessor;
    this.blockAccessList = blockAccessList;
    this.blockAccessListAccountLookup = BlockAccessListAccountLookup.of(blockAccessList);
    this.maybePrefetcher =
        balConfiguration.isBalPreFetchReadingEnabled()
            ? Optional.of(
                new BalPrefetcher(
                    balConfiguration.isBalPreFetchSortingEnabled(),
                    balConfiguration.getBalPreFetchBatchSize()))
            : Optional.empty();
  }

  private Optional<BonsaiWorldState> getWorldStateForTransaction(
      final ProtocolContext protocolContext,
      final Optional<BlockHeader> maybeParentHeader,
      final int transactionLocation) {
    return maybeParentHeader.flatMap(
        blockHeader ->
            protocolContext
                .getWorldStateArchive()
                .getWorldState(
                    WorldStateQueryParams.newBuilder()
                        .withBlockHeader(blockHeader)
                        .withShouldWorldStateUpdateHead(false)
                        .withBalOverlay(
                            new BlockAccessListOverlay(
                                blockAccessListAccountLookup, (long) transactionLocation + 1L))
                        .build())
                .map(BonsaiWorldState.class::cast));
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

    trace = LOG.isDebugEnabled() ? new ExecutionTrace(blockHeader.getNumber(), transactions) : null;
    maybePrefetcher.ifPresent(
        balPrefetchMechanism -> {
          final Optional<BonsaiWorldState> maybeWorldState =
              maybeParentHeader.flatMap(
                  parentHeader ->
                      protocolContext
                          .getWorldStateArchive()
                          .getWorldState(
                              WorldStateQueryParams.newBuilder()
                                  .withBlockHeader(parentHeader)
                                  .withShouldWorldStateUpdateHead(false)
                                  .build())
                          .map(BonsaiWorldState.class::cast));
          if (maybeWorldState.isPresent()) {
            balPrefetchMechanism
                .prefetch(
                    maybeWorldState.get(),
                    blockAccessList,
                    BlockProcessingExecutors.ioExecutor(),
                    BlockProcessingExecutors.ioExecutor())
                .exceptionally(
                    ex -> {
                      LOG.error("Prefetch failed", ex);
                      return null;
                    })
                .whenComplete((result, ex) -> maybeWorldState.get().close());
          } else {
            LOG.info("Prefetcher block header for block not loaded {}", blockHeader);
          }
        });
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
  }

  @Override
  protected ParallelizedTransactionContext runTransaction(
      final ProtocolContext protocolContext,
      final BlockHeader blockHeader,
      final int transactionLocation,
      final Transaction transaction,
      final Address miningBeneficiary,
      final BlockHashLookup blockHashLookup,
      final Wei blobGasPrice,
      final Optional<BlockAccessListBuilder> blockAccessListBuilder,
      final Optional<BlockHeader> maybeParentHeader) {

    final long start = System.nanoTime();
    final BonsaiWorldState ws =
        getWorldStateForTransaction(protocolContext, maybeParentHeader, transactionLocation)
            .orElse(null);
    if (ws == null) {
      return null;
    }

    try {
      ws.disableCacheMerkleTrieLoader();
      final ParallelizedTransactionContext.Builder ctxBuilder =
          new ParallelizedTransactionContext.Builder();

      final PathBasedWorldStateUpdateAccumulator<?> blockUpdater = ws.getAccumulator();
      final WorldUpdater txUpdater = blockUpdater.updater();
      final Optional<AccessLocationTracker> txTracker =
          blockAccessListBuilder.map(
              b ->
                  BlockAccessListBuilder.createTransactionAccessLocationTracker(
                      transactionLocation));

      final TransactionProcessingResult result =
          transactionProcessor.processTransaction(
              txUpdater,
              blockHeader,
              transaction.detachedCopy(),
              miningBeneficiary,
              OperationTracer.NO_TRACING,
              blockHashLookup.forkForParallelWorker(),
              TransactionValidationParams.processingBlock(),
              blobGasPrice,
              txTracker);

      ctxBuilder.transactionProcessingResult(result);
      // the receipt's bloom depends on this transaction alone, so it is hashed here, in parallel
      result.getLogsBloom();

      return ctxBuilder.build();
    } finally {
      ws.close();
      final ExecutionTrace current = trace;
      if (current != null) {
        current.record(transactionLocation, start, System.nanoTime());
      }
    }
  }

  @Override
  // TODO: Throw instead of returning Optional.empty()?
  public Optional<TransactionProcessingResult> getProcessingResult(
      final MutableWorldState worldState,
      final Address miningBeneficiary,
      final Transaction transaction,
      final int txIndex,
      final Optional<Counter> confirmedParallelizedTransactionCounter,
      final Optional<Counter> conflictingButCachedTransactionCounter) {

    final CompletableFuture<ParallelizedTransactionContext> future = removeFuture(txIndex);
    if (future != null) {
      try {
        final long waitStart = System.nanoTime();
        final ParallelizedTransactionContext ctx = future.get();
        BlockImportTimings.addSince(BlockImportTimings.Phase.TX_WAIT, waitStart);
        if (trace != null && txIndex == futures.length - 1) {
          trace.log();
        }

        if (ctx == null) {
          LOG.trace("Transaction context for transaction {} is empty.", txIndex);
          return Optional.empty();
        }

        final PathBasedWorldState pathWs = (PathBasedWorldState) worldState;
        final PathBasedWorldStateUpdateAccumulator blockAccumulator =
            (PathBasedWorldStateUpdateAccumulator) pathWs.updater();

        final TransactionProcessingResult result = ctx.transactionProcessingResult();
        final Optional<PartialBlockAccessView> maybePartialBlockAccessView =
            result.getPartialBlockAccessView();
        if (maybePartialBlockAccessView.isEmpty()) {
          LOG.trace("Partial block access view for transaction {} is empty.", txIndex);
          return Optional.empty();
        }

        blockAccumulator.importStateChangesFromPartialView(
            maybePartialBlockAccessView.get(), transactionProcessor.getClearEmptyAccounts());

        confirmedParallelizedTransactionCounter.ifPresent(Counter::inc);
        result.setIsProcessedInParallel(Optional.of(Boolean.TRUE));

        return Optional.of(result);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.error("Interrupted while waiting for transaction {} processing result.", txIndex, e);
        return Optional.empty();
      } catch (final Exception e) {
        LOG.error(
            "Error integrating transaction processing result for transaction {}.", txIndex, e);
        return Optional.empty();
      }
    }

    LOG.error("No future found for transaction {}.", txIndex);
    return Optional.empty();
  }

  private static final class ExecutionTrace {
    private final long blockNumber;
    private final long dispatchNanos = System.nanoTime();
    private final long[] gasLimits;
    private final long[] startNanos;
    private final long[] endNanos;

    ExecutionTrace(final long blockNumber, final List<Transaction> transactions) {
      this.blockNumber = blockNumber;
      this.gasLimits = transactions.stream().mapToLong(Transaction::getGasLimit).toArray();
      this.startNanos = new long[gasLimits.length];
      this.endNanos = new long[gasLimits.length];
    }

    void record(final int txIndex, final long start, final long end) {
      startNanos[txIndex] = start;
      endNanos[txIndex] = end;
    }

    void log() {
      long busy = 0;
      long lastEnd = dispatchNanos;
      long latestStart = dispatchNanos;
      int longest = 0;
      for (int i = 0; i < startNanos.length; i++) {
        final long duration = endNanos[i] - startNanos[i];
        busy += duration;
        lastEnd = Math.max(lastEnd, endNanos[i]);
        latestStart = Math.max(latestStart, startNanos[i]);
        if (duration > endNanos[longest] - startNanos[longest]) {
          longest = i;
        }
      }
      LOG.debug(
          "BAL execution #{}: {} tx, workers busy {} ms, last done {} ms and last started {} ms after"
              + " dispatch, longest tx #{} ({} gas limit) {} ms, started {} ms after dispatch",
          blockNumber,
          startNanos.length,
          millis(busy),
          millis(lastEnd - dispatchNanos),
          millis(latestStart - dispatchNanos),
          longest,
          gasLimits.length == 0 ? 0 : gasLimits[longest],
          startNanos.length == 0 ? 0 : millis(endNanos[longest] - startNanos[longest]),
          startNanos.length == 0 ? 0 : millis(startNanos[longest] - dispatchNanos));
      if (LOG.isTraceEnabled()) {
        final StringBuilder durations = new StringBuilder();
        for (int i = 0; i < startNanos.length; i++) {
          durations
              .append(i == 0 ? "" : ",")
              .append(gasLimits[i])
              .append(':')
              .append((startNanos[i] - dispatchNanos) / 1000)
              .append(':')
              .append((endNanos[i] - startNanos[i]) / 1000);
        }
        LOG.trace("BAL tx timings #{} gasLimit:startUs:durationUs {}", blockNumber, durations);
      }
    }

    private static String millis(final long nanos) {
      return String.format("%.1f", nanos / 1e6);
    }
  }
}
