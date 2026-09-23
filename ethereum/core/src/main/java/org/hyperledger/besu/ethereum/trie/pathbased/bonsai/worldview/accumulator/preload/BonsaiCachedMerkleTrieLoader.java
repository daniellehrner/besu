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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.preload;

import static org.hyperledger.besu.metrics.BesuMetricCategory.BLOCKCHAIN;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.ethereum.mainnet.staterootcommitter.StateRootStats;
import org.hyperledger.besu.ethereum.trie.MerkleTrie;
import org.hyperledger.besu.ethereum.trie.MerkleTrieException;
import org.hyperledger.besu.ethereum.trie.TrieNodeLoadStats;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.StorageSubscriber;
import org.hyperledger.besu.ethereum.trie.patricia.StoredMerklePatriciaTrie;
import org.hyperledger.besu.metrics.ObservableMetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.LabelledSuppliedMetric;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

public class BonsaiCachedMerkleTrieLoader implements StorageSubscriber {

  private static final ExecutorService VIRTUAL_POOL = Executors.newVirtualThreadPerTaskExecutor();

  private static final int ACCOUNT_CACHE_SIZE =
      Integer.getInteger("besu.bonsai.trieNodeCache.accountSize", 100_000);
  private static final int STORAGE_CACHE_SIZE =
      Integer.getInteger("besu.bonsai.trieNodeCache.storageSize", 200_000);

  private static final String ACCOUNT = "account";
  private static final String STORAGE = "storage";
  private static final String PRELOAD = "preload";
  private static final String COMMIT = "commit";

  private final Cache<Bytes, Bytes> accountNodes =
      CacheBuilder.newBuilder().recordStats().maximumSize(ACCOUNT_CACHE_SIZE).build();
  private final Cache<Bytes, Bytes> storageNodes =
      CacheBuilder.newBuilder().recordStats().maximumSize(STORAGE_CACHE_SIZE).build();

  public BonsaiCachedMerkleTrieLoader(final ObservableMetricsSystem metricsSystem) {
    metricsSystem.createGuavaCacheCollector(BLOCKCHAIN, "accountsNodes", accountNodes);
    metricsSystem.createGuavaCacheCollector(BLOCKCHAIN, "storageNodes", storageNodes);
    registerLoadMetrics(metricsSystem);
  }

  /** Node lookups of one trie kind in one phase, split by where the node came from. */
  private static final class LookupStats {
    final LongAdder cacheHits = new LongAdder();
    final LongAdder dbReads = new LongAdder();
    final LongAdder dbAbsent = new LongAdder();
    final LongAdder dbReadNanos = new LongAdder();
  }

  /** Preload walks of one trie kind. */
  private static final class WalkStats {
    final LongAdder walks = new LongAdder();
    final LongAdder walkNanos = new LongAdder();
  }

  private final LookupStats accountPreloadLookups = new LookupStats();
  private final LookupStats accountCommitLookups = new LookupStats();
  private final LookupStats storagePreloadLookups = new LookupStats();
  private final LookupStats storageCommitLookups = new LookupStats();
  private final WalkStats accountWalks = new WalkStats();
  private final WalkStats storageWalks = new WalkStats();

  private void registerLoadMetrics(final ObservableMetricsSystem metricsSystem) {
    final LabelledSuppliedMetric lookups =
        metricsSystem.createLabelledSuppliedCounter(
            BLOCKCHAIN,
            "trie_node_lookups_total",
            "Trie node lookups through the cached merkle trie loader",
            "trie",
            "phase",
            "source");
    final LabelledSuppliedMetric dbReadNanos =
        metricsSystem.createLabelledSuppliedCounter(
            BLOCKCHAIN,
            "trie_node_db_read_nanos_total",
            "Time spent reading trie nodes from storage on a cache miss, validation hash included",
            "trie",
            "phase");
    registerLookups(lookups, dbReadNanos, ACCOUNT, PRELOAD, accountPreloadLookups);
    registerLookups(lookups, dbReadNanos, ACCOUNT, COMMIT, accountCommitLookups);
    registerLookups(lookups, dbReadNanos, STORAGE, PRELOAD, storagePreloadLookups);
    registerLookups(lookups, dbReadNanos, STORAGE, COMMIT, storageCommitLookups);

    final LabelledSuppliedMetric walks =
        metricsSystem.createLabelledSuppliedCounter(
            BLOCKCHAIN, "trie_preload_walks_total", "Preload walks down a trie", "trie");
    final LabelledSuppliedMetric walkNanos =
        metricsSystem.createLabelledSuppliedCounter(
            BLOCKCHAIN,
            "trie_preload_walk_nanos_total",
            "Wall time of preload walks, summed over the virtual threads running them",
            "trie");
    walks.labels(accountWalks.walks::sum, ACCOUNT);
    walks.labels(storageWalks.walks::sum, STORAGE);
    walkNanos.labels(accountWalks.walkNanos::sum, ACCOUNT);
    walkNanos.labels(storageWalks.walkNanos::sum, STORAGE);

    final LabelledSuppliedMetric stateRoot =
        metricsSystem.createLabelledSuppliedCounter(
            BLOCKCHAIN,
            "state_root_computation_total",
            "State root computations by pass: frozen while validating, persisting on roll forward",
            "pass",
            "unit");
    stateRoot.labels(() -> StateRootStats.computations(StateRootStats.FROZEN), "frozen", "count");
    stateRoot.labels(() -> StateRootStats.nanos(StateRootStats.FROZEN), "frozen", "nanos");
    stateRoot.labels(
        () -> StateRootStats.computations(StateRootStats.PERSISTING), "persisting", "count");
    stateRoot.labels(() -> StateRootStats.nanos(StateRootStats.PERSISTING), "persisting", "nanos");
    final LabelledSuppliedMetric storageTries =
        metricsSystem.createLabelledSuppliedCounter(
            BLOCKCHAIN,
            "state_root_storage_tries_total",
            "Storage trie updates by number of slot updates",
            "max_updates",
            "unit");
    for (int i = 0; i < StateRootStats.storageBucketCount(); i++) {
      final int bucket = i;
      final String label = StateRootStats.storageBucketLabel(i);
      storageTries.labels(() -> StateRootStats.storageTries(bucket), label, "count");
      storageTries.labels(() -> StateRootStats.storageTrieNanos(bucket), label, "nanos");
    }

    // process-wide: every trie in the node, not only the ones loaded through this class
    final LabelledSuppliedMetric materialise =
        metricsSystem.createLabelledSuppliedCounter(
            BLOCKCHAIN,
            "trie_node_materialise_total",
            "Process-wide trie node RLP decodes and storage validation hashes",
            "step",
            "unit");
    materialise.labels(TrieNodeLoadStats::decodeCount, "decode", "count");
    materialise.labels(TrieNodeLoadStats::decodeNanos, "decode", "nanos");
    materialise.labels(TrieNodeLoadStats::validationHashCount, "validation_hash", "count");
    materialise.labels(TrieNodeLoadStats::validationHashNanos, "validation_hash", "nanos");
  }

  private static void registerLookups(
      final LabelledSuppliedMetric lookups,
      final LabelledSuppliedMetric dbReadNanos,
      final String trie,
      final String phase,
      final LookupStats stats) {
    lookups.labels(stats.cacheHits::sum, trie, phase, "cache");
    lookups.labels(stats.dbReads::sum, trie, phase, "db");
    lookups.labels(stats.dbAbsent::sum, trie, phase, "db_absent");
    dbReadNanos.labels(stats.dbReadNanos::sum, trie, phase);
  }

  public void preLoadAccount(
      final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage,
      final Hash worldStateRootHash,
      final Address account) {
    CompletableFuture.runAsync(
        () -> cacheAccountNodes(worldStateKeyValueStorage, worldStateRootHash, account),
        VIRTUAL_POOL);
  }

  @VisibleForTesting
  public void cacheAccountNodes(
      final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage,
      final Hash worldStateRootHash,
      final Address account) {
    final long storageSubscriberId = worldStateKeyValueStorage.subscribe(this);
    final long start = System.nanoTime();
    try {
      final StoredMerklePatriciaTrie<Bytes, Bytes> accountTrie =
          new StoredMerklePatriciaTrie<>(
              (location, hash) -> {
                Optional<Bytes> node =
                    lookup(
                        accountNodes,
                        hash,
                        accountPreloadLookups,
                        () -> worldStateKeyValueStorage.getAccountStateTrieNode(location, hash));
                node.ifPresent(bytes -> accountNodes.put(Hash.hash(bytes).getBytes(), bytes));
                return node;
              },
              Bytes32.wrap(worldStateRootHash.getBytes()),
              Function.identity(),
              Function.identity());
      accountTrie.get(account.addressHash().getBytes());
    } catch (MerkleTrieException e) {
      // ignore exception for the cache
    } finally {
      accountWalks.walks.increment();
      accountWalks.walkNanos.add(System.nanoTime() - start);
      worldStateKeyValueStorage.unSubscribe(storageSubscriberId);
    }
  }

  public void preLoadStorageSlot(
      final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage,
      final Address account,
      final StorageSlotKey slotKey) {
    CompletableFuture.runAsync(
        () -> cacheStorageNodes(worldStateKeyValueStorage, account, slotKey), VIRTUAL_POOL);
  }

  @VisibleForTesting
  public void cacheStorageNodes(
      final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage,
      final Address account,
      final StorageSlotKey slotKey) {
    final Hash accountHash = account.addressHash();
    final long storageSubscriberId = worldStateKeyValueStorage.subscribe(this);
    final long start = System.nanoTime();
    try {
      worldStateKeyValueStorage
          .getStateTrieNode(Bytes.concatenate(accountHash.getBytes(), Bytes.EMPTY))
          .ifPresent(
              storageRoot -> {
                try {
                  final StoredMerklePatriciaTrie<Bytes, Bytes> storageTrie =
                      new StoredMerklePatriciaTrie<Bytes, Bytes>(
                          (location, hash) -> {
                            Optional<Bytes> node =
                                lookup(
                                    storageNodes,
                                    hash,
                                    storagePreloadLookups,
                                    () ->
                                        worldStateKeyValueStorage.getAccountStorageTrieNode(
                                            accountHash, location, hash));
                            node.ifPresent(
                                bytes -> storageNodes.put(Hash.hash(bytes).getBytes(), bytes));
                            return node;
                          },
                          Bytes32.wrap(Hash.hash(storageRoot).getBytes()),
                          Function.identity(),
                          Function.identity());
                  storageTrie.get(slotKey.getSlotHash().getBytes());
                } catch (MerkleTrieException e) {
                  // ignore exception for the cache
                }
              });
    } finally {
      storageWalks.walks.increment();
      storageWalks.walkNanos.add(System.nanoTime() - start);
      worldStateKeyValueStorage.unSubscribe(storageSubscriberId);
    }
  }

  private static Optional<Bytes> lookup(
      final Cache<Bytes, Bytes> cache,
      final Bytes32 nodeHash,
      final LookupStats stats,
      final Supplier<Optional<Bytes>> storageRead) {
    if (nodeHash.equals(MerkleTrie.EMPTY_TRIE_NODE_HASH)) {
      return Optional.of(MerkleTrie.EMPTY_TRIE_NODE);
    }
    final Bytes cached = cache.getIfPresent(nodeHash);
    if (cached != null) {
      stats.cacheHits.increment();
      return Optional.of(cached);
    }
    final long start = System.nanoTime();
    final Optional<Bytes> node = storageRead.get();
    stats.dbReadNanos.add(System.nanoTime() - start);
    (node.isPresent() ? stats.dbReads : stats.dbAbsent).increment();
    return node;
  }

  public Optional<Bytes> getAccountStateTrieNode(
      final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage,
      final Bytes location,
      final Bytes32 nodeHash) {
    return lookup(
        accountNodes,
        nodeHash,
        accountCommitLookups,
        () -> worldStateKeyValueStorage.getAccountStateTrieNode(location, nodeHash));
  }

  public Optional<Bytes> getAccountStorageTrieNode(
      final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage,
      final Hash accountHash,
      final Bytes location,
      final Bytes32 nodeHash) {
    return lookup(
        storageNodes,
        nodeHash,
        storageCommitLookups,
        () -> worldStateKeyValueStorage.getAccountStorageTrieNode(accountHash, location, nodeHash));
  }
}
