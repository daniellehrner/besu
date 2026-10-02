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
package org.hyperledger.besu.ethereum.eth.sync.snapsync;

import static org.hyperledger.besu.ethereum.eth.sync.StorageExceptionManager.canRetryOnError;
import static org.hyperledger.besu.ethereum.eth.sync.StorageExceptionManager.errorCountAtThreshold;
import static org.hyperledger.besu.ethereum.eth.sync.StorageExceptionManager.getRetryableErrorCounter;

import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.AccountRangeDataRequest;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.SnapDataRequest;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.SnapRequestContext;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.StorageRangeDataRequest;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.heal.TrieNodeHealingRequest;
import org.hyperledger.besu.ethereum.trie.RangeManager;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorageTransaction;
import org.hyperledger.besu.plugin.services.storage.WorldStateKeyValueStorage;
import org.hyperledger.besu.services.tasks.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PersistDataStep {
  private static final Logger LOG = LoggerFactory.getLogger(PersistDataStep.class);

  // the range download writes no trie logs
  private static final KeyValueStorageTransaction NO_TRIE_LOG_TRANSACTION =
      new KeyValueStorageTransaction() {
        @Override
        public void put(final byte[] key, final byte[] value) {}

        @Override
        public void remove(final byte[] key) {}

        @Override
        public void commit() {}

        @Override
        public void rollback() {}

        @Override
        public void close() {}
      };

  private final SnapSyncProcessState snapSyncState;
  private final WorldStateStorageCoordinator worldStateStorageCoordinator;
  private final SnapRequestContext downloadState;

  private final SnapSyncConfiguration snapSyncConfiguration;

  private final Optional<WorldStateSortedIngest> sortedIngest;

  public PersistDataStep(
      final SnapSyncProcessState snapSyncState,
      final WorldStateStorageCoordinator worldStateStorageCoordinator,
      final SnapRequestContext downloadState,
      final SnapSyncConfiguration snapSyncConfiguration) {
    this(
        snapSyncState,
        worldStateStorageCoordinator,
        downloadState,
        snapSyncConfiguration,
        Optional.empty());
  }

  public PersistDataStep(
      final SnapSyncProcessState snapSyncState,
      final WorldStateStorageCoordinator worldStateStorageCoordinator,
      final SnapRequestContext downloadState,
      final SnapSyncConfiguration snapSyncConfiguration,
      final Optional<WorldStateSortedIngest> sortedIngest) {
    this.snapSyncState = snapSyncState;
    this.worldStateStorageCoordinator = worldStateStorageCoordinator;
    this.downloadState = downloadState;
    this.snapSyncConfiguration = snapSyncConfiguration;
    this.sortedIngest = sortedIngest;
  }

  public List<Task<SnapDataRequest>> persist(final List<Task<SnapDataRequest>> tasks) {
    try {
      final WorldStateKeyValueStorage.Updater updater = worldStateStorageCoordinator.updater();
      final List<SnapDataRequest> writtenByUpdater = new ArrayList<>();
      for (Task<SnapDataRequest> task : tasks) {
        final SnapDataRequest request = task.getData();
        if (request.isResponseReceived()) {
          // enqueue child requests
          final Stream<SnapDataRequest> childRequests =
              request.getChildRequests(downloadState, worldStateStorageCoordinator, snapSyncState);
          final List<SnapDataRequest> children;
          if (!(request instanceof TrieNodeHealingRequest)) {
            // whether a range is continued decides what the sorted ingest does with its response
            children = childRequests.toList();
            if (isAccountRangeDownload(request) && isWrittenBySortedIngest(request)) {
              // before any of the storage can arrive, which is as soon as its requests are queued
              announceStorage(children);
            }
            enqueueChildren(withAccountRangeOrigin(request, children.stream()));
          } else {
            children = List.of();
            if (!request.isExpired(snapSyncState)) {
              enqueueChildren(childRequests);
            } else {
              continue;
            }
          }

          // persist nodes
          final int persistedNodes;
          if (isWrittenBySortedIngest(request)) {
            persistedNodes = persistWithSortedIngest(request, children);
          } else {
            persistedNodes =
                request.persist(
                    worldStateStorageCoordinator,
                    updater,
                    downloadState,
                    snapSyncState,
                    snapSyncConfiguration);
            writtenByUpdater.add(request);
          }
          if (persistedNodes > 0) {
            if (request instanceof TrieNodeHealingRequest) {
              downloadState.getMetricsManager().notifyTrieNodesHealed(persistedNodes);
            } else {
              downloadState.getMetricsManager().notifyNodesGenerated(persistedNodes);
            }
          }
        }
      }
      updater.commit();
      writtenByUpdater.forEach(downloadState::onRequestStored);
    } catch (StorageException storageException) {
      if (canRetryOnError(storageException)) {
        // We reset the task by setting it to null. This way, it is considered as failed by the
        // pipeline, and it will attempt to execute it again later. not display all the retryable
        // issues
        if (errorCountAtThreshold()) {
          LOG.info(
              "Encountered {} retryable RocksDB errors, latest error message {}",
              getRetryableErrorCounter(),
              storageException.getMessage());
        }
        tasks.forEach(task -> task.getData().clear());
      } else {
        throw storageException;
      }
    }
    return tasks;
  }

  /**
   * This method will heal the local flat database if necessary and persist it
   *
   * @param tasks range to heal and/or persist
   * @return completed tasks
   */
  public List<Task<SnapDataRequest>> healFlatDatabase(final List<Task<SnapDataRequest>> tasks) {
    final BonsaiWorldStateKeyValueStorage.Updater updater =
        (BonsaiWorldStateKeyValueStorage.Updater) worldStateStorageCoordinator.updater();
    for (Task<SnapDataRequest> task : tasks) {
      // heal and/or persist
      task.getData()
          .persist(
              worldStateStorageCoordinator,
              updater,
              downloadState,
              snapSyncState,
              snapSyncConfiguration);
      // enqueue child requests, these will be the right part of the ranges to complete if we have
      // not healed all the range
      enqueueChildren(
          task.getData()
              .getChildRequests(downloadState, worldStateStorageCoordinator, snapSyncState));
    }
    updater.commit();
    return tasks;
  }

  public Task<SnapDataRequest> persist(final Task<SnapDataRequest> task) {
    return persist(List.of(task)).get(0);
  }

  public Task<SnapDataRequest> healFlatDatabase(final Task<SnapDataRequest> task) {
    return healFlatDatabase(List.of(task)).get(0);
  }

  private void enqueueChildren(final Stream<SnapDataRequest> childRequests) {
    downloadState.enqueueRequests(childRequests);
  }

  /** The account and storage ranges of the range download are what arrives in key order. */
  private boolean isWrittenBySortedIngest(final SnapDataRequest request) {
    return sortedIngest.filter(WorldStateSortedIngest::isActive).isPresent()
        && (isAccountRangeDownload(request) || request instanceof StorageRangeDataRequest);
  }

  /**
   * Tells the sorted ingest which accounts of an account range response get their storage
   * requested, in the order of the accounts.
   */
  private void announceStorage(final List<SnapDataRequest> children) {
    sortedIngest
        .orElseThrow()
        .announceStorage(
            children.stream()
                .filter(StorageRangeDataRequest.class::isInstance)
                .map(child -> ((StorageRangeDataRequest) child).getAccountHash().getBytes())
                .toList());
  }

  private int persistWithSortedIngest(
      final SnapDataRequest request, final List<SnapDataRequest> children) {
    final BonsaiWorldStateKeyValueStorage worldStateStorage =
        worldStateStorageCoordinator.getStrategy(BonsaiWorldStateKeyValueStorage.class);
    // the request writes into a transaction that only collects its entries
    final SortedIngestTransaction collected = new SortedIngestTransaction();
    final int persistedNodes =
        request.persist(
            worldStateStorageCoordinator,
            new BonsaiWorldStateKeyValueStorage.Updater(
                collected,
                NO_TRIE_LOG_TRANSACTION,
                worldStateStorage.getFlatDbStrategy(),
                worldStateStorage.getComposedWorldStateStorage(),
                worldStateStorage.getTrieNodeStrategy()),
            downloadState,
            snapSyncState,
            snapSyncConfiguration);
    final WorldStateSortedIngest.Batch batch =
        collected.toBatch(() -> downloadState.onRequestStored(request));
    final WorldStateSortedIngest ingest = sortedIngest.orElseThrow();
    if (request instanceof AccountRangeDataRequest accountRequest) {
      ingest.writeAccountRange(
          accountRequest.getEndKeyHash(),
          batch,
          children.stream().noneMatch(PersistDataStep::isAccountRangeDownload));
    } else {
      final StorageRangeDataRequest storageRequest = (StorageRangeDataRequest) request;
      // the ranges the rest of the storage is requested in
      final List<Bytes32> continuationEnds =
          children.stream()
              .filter(StorageRangeDataRequest.class::isInstance)
              .map(child -> ((StorageRangeDataRequest) child).getEndKeyHash())
              .toList();
      if (storageRequest.getStartKeyHash().equals(RangeManager.MIN_RANGE)) {
        ingest.writeStorageStart(
            storageRequest.getAccountHash().getBytes(),
            storageRequest.getEndKeyHash(),
            batch,
            continuationEnds);
      } else {
        ingest.writeStorageContinuation(
            storageRequest.getAccountHash().getBytes(),
            storageRequest.getStartKeyHash(),
            storageRequest.getEndKeyHash(),
            batch,
            continuationEnds);
      }
    }
    return persistedNodes;
  }

  /**
   * Marks the storage and code requests spawned by a request of the range download with the account
   * range response they descend from, so the download state knows which account ranges still have
   * unfinished children.
   */
  private Stream<SnapDataRequest> withAccountRangeOrigin(
      final SnapDataRequest parent, final Stream<SnapDataRequest> childRequests) {
    final Optional<AccountRangeResumeTracker.Origin> origin =
        isAccountRangeDownload(parent)
            ? Optional.of(
                new AccountRangeResumeTracker.Origin(
                    ((AccountRangeDataRequest) parent).getEndKeyHash(),
                    ((AccountRangeDataRequest) parent).getStartKeyHash()))
            : parent.getAccountRangeOrigin();
    if (origin.isEmpty()) {
      return childRequests;
    }
    return childRequests.map(
        child -> {
          // the next account range request of the partition is not a child of this response
          if (!isAccountRangeDownload(child)) {
            child.setAccountRangeOrigin(origin.get());
          }
          return child;
        });
  }

  private static boolean isAccountRangeDownload(final SnapDataRequest request) {
    return request instanceof AccountRangeDataRequest accountRangeDataRequest
        && accountRangeDataRequest.isRangeDownload();
  }
}
