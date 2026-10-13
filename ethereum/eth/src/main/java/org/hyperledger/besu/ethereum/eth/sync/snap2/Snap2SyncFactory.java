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
package org.hyperledger.besu.ethereum.eth.sync.snap2;

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.sync.PivotBlockSelector;
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.common.checkpoint.Checkpoint;
import org.hyperledger.besu.ethereum.eth.sync.snap2.chain.ChainHeadSource;
import org.hyperledger.besu.ethereum.eth.sync.snap2.chain.PeerHeadSource;
import org.hyperledger.besu.ethereum.eth.sync.snap2.net.RequestScheduler;
import org.hyperledger.besu.ethereum.eth.sync.snap2.storage.BonsaiSyncStateStorage;
import org.hyperledger.besu.ethereum.eth.sync.snap2.storage.ForestSyncStateStorage;
import org.hyperledger.besu.ethereum.eth.sync.snap2.storage.SyncStateStorage;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.SnapSyncController;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.storage.StorageProvider;
import org.hyperledger.besu.ethereum.trie.forest.storage.ForestWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.worldstate.FlatDbMode;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.metrics.SyncDurationMetrics;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;

import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates the SNAP2 sync, or nothing when the node already holds a synced chain. */
public final class Snap2SyncFactory {

  private static final Logger LOG = LoggerFactory.getLogger(Snap2SyncFactory.class);

  private Snap2SyncFactory() {}

  public static Optional<SnapSyncController> create(
      final SynchronizerConfiguration syncConfig,
      final Path dataDirectory,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final MetricsSystem metricsSystem,
      final EthContext ethContext,
      final WorldStateStorageCoordinator worldStateStorageCoordinator,
      final StorageProvider storageProvider,
      final SyncState syncState,
      final PivotBlockSelector pivotBlockSelector,
      final SyncDurationMetrics syncDurationMetrics) {
    final MutableBlockchain blockchain = protocolContext.getBlockchain();
    final Snap2Marker marker = new Snap2Marker(dataDirectory);
    final long floor = syncState.getCheckpoint().map(Checkpoint::blockNumber).orElse(0L);
    if (syncState.isResyncNeeded()) {
      LOG.info("World state resync requested, syncing it again with SNAP2");
    } else if (marker.exists()) {
      LOG.info("A previous SNAP2 sync did not finish, syncing the world state again");
    } else if (blockchain.getChainHeadBlockNumber() > floor) {
      LOG.info(
          "Chain head is block {}, no SNAP2 sync needed", blockchain.getChainHeadBlockNumber());
      return Optional.empty();
    }
    final ChainHeadSource heads =
        pivotBlockSelector instanceof ChainHeadSource source
            ? source
            : new PeerHeadSource(ethContext.getEthPeers());
    final boolean sortedIngest =
        Boolean.TRUE.equals(syncConfig.getSnapSyncConfiguration().isSnap2SortedIngestEnabled());
    boolean triesWhileDownloading =
        Boolean.TRUE.equals(
            syncConfig.getSnapSyncConfiguration().isSnap2StorageTriesWhileDownloading());
    if (sortedIngest && triesWhileDownloading) {
      // the slots in a sorted file are only readable once the file is stored
      LOG.warn(
          "The SNAP2 storage tries are built at the end, as the state is written as sorted files");
      triesWhileDownloading = false;
    }
    final Snap2Configuration config =
        Snap2Configuration.getDefault().toBuilder()
            .accountChunks(syncConfig.getSnapSyncConfiguration().getSnap2AccountRanges())
            .storageTriesWhileDownloading(triesWhileDownloading)
            .build();
    return Optional.of(
        new Snap2SyncController(
            config,
            protocolSchedule,
            blockchain,
            ethContext.getEthPeers(),
            syncState,
            syncStorage(worldStateStorageCoordinator, storageProvider, sortedIngest),
            heads,
            metricsSystem,
            marker,
            syncConfig.isSnapSyncHeadersToCheckpointOnly(),
            syncConfig.getSnapSyncConfiguration().isSnapSyncTransactionIndexingEnabled(),
            syncDurationMetrics,
            () ->
                new RequestScheduler(
                    ethContext.getEthPeers(),
                    metricsSystem,
                    config.requestTimeout(),
                    config.maxSnapRequestsPerPeer(),
                    config.maxEthRequestsPerPeer(),
                    config.workerThreads())));
  }

  private static SyncStateStorage syncStorage(
      final WorldStateStorageCoordinator coordinator,
      final StorageProvider storageProvider,
      final boolean sortedIngest) {
    if (coordinator.getDataStorageFormat() == DataStorageFormat.FOREST) {
      if (sortedIngest) {
        LOG.warn("The SNAP2 state is only written as sorted files with Bonsai");
      }
      return new ForestSyncStateStorage(
          coordinator.getStrategy(ForestWorldStateKeyValueStorage.class), storageProvider);
    }
    final BonsaiWorldStateKeyValueStorage bonsai =
        coordinator.getStrategy(BonsaiWorldStateKeyValueStorage.class);
    if (bonsai.getFlatDbMode() == FlatDbMode.ARCHIVE) {
      throw new IllegalStateException("SNAP2 sync does not support the Bonsai archive format");
    }
    return new BonsaiSyncStateStorage(bonsai, sortedIngest);
  }
}
