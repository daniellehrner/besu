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
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.heal.TrieNodeHealingRequest;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.plugin.services.storage.WorldStateKeyValueStorage;
import org.hyperledger.besu.services.tasks.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PersistDataStep {
  private static final Logger LOG = LoggerFactory.getLogger(PersistDataStep.class);

  private final SnapSyncProcessState snapSyncState;
  private final WorldStateStorageCoordinator worldStateStorageCoordinator;
  private final SnapRequestContext downloadState;

  private final SnapSyncConfiguration snapSyncConfiguration;

  public PersistDataStep(
      final SnapSyncProcessState snapSyncState,
      final WorldStateStorageCoordinator worldStateStorageCoordinator,
      final SnapRequestContext downloadState,
      final SnapSyncConfiguration snapSyncConfiguration) {
    this.snapSyncState = snapSyncState;
    this.worldStateStorageCoordinator = worldStateStorageCoordinator;
    this.downloadState = downloadState;
    this.snapSyncConfiguration = snapSyncConfiguration;
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
          if (!(request instanceof TrieNodeHealingRequest)) {
            enqueueChildren(withAccountRangeOrigin(request, childRequests));
          } else {
            if (!request.isExpired(snapSyncState)) {
              enqueueChildren(childRequests);
            } else {
              continue;
            }
          }

          // persist nodes
          final int persistedNodes =
              request.persist(
                  worldStateStorageCoordinator,
                  updater,
                  downloadState,
                  snapSyncState,
                  snapSyncConfiguration);
          writtenByUpdater.add(request);
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
