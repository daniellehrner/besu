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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache;

import static org.hyperledger.besu.metrics.BesuMetricCategory.BONSAI_CACHE;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.chain.BlockAddedEvent;
import org.hyperledger.besu.ethereum.chain.BlockAddedObserver;
import org.hyperledger.besu.ethereum.trie.pathbased.common.StorageSubscriber;
import org.hyperledger.besu.metrics.ObservableMetricsSystem;
import org.hyperledger.besu.plugin.data.AddedBlockContext.EventType;
import org.hyperledger.besu.plugin.services.trielogs.TrieLogEvent;
import org.hyperledger.besu.util.cache.MemoryBoundCache;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.tuweni.bytes.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache for account data (RLP-encoded) keyed by address hash.
 *
 * <p>This cache integrates with:
 *
 * <ul>
 *   <li>{@link TrieLogEvent.TrieLogObserver} - to update cache when new blocks are processed
 *   <li>{@link StorageSubscriber} - to invalidate cache on storage clears
 *   <li>{@link BlockAddedObserver} - to invalidate cache on chain reorgs
 * </ul>
 *
 * <p>The cache is updated (not just invalidated) when TrieLogs are received, using the updated
 * AccountValue directly from the TrieLog to avoid unnecessary DB reads.
 */
public class AccountCache
    implements TrieLogEvent.TrieLogObserver, StorageSubscriber, BlockAddedObserver {

  private static final Logger LOG = LoggerFactory.getLogger(AccountCache.class);

  /** Default cache size: 50 MB */
  public static final long DEFAULT_MAX_SIZE_BYTES = 50L * 1024 * 1024;

  private final MemoryBoundCache<Hash, Bytes> cache;
  private final AtomicLong updateCount = new AtomicLong(0);
  private final AtomicLong reorgInvalidationCount = new AtomicLong(0);

  // Metrics tracking
  private long lastRequestCount = 0;
  private long lastRequestTimestamp = System.nanoTime();

  /** Creates a new AccountCache with the default maximum size. */
  public AccountCache() {
    this(DEFAULT_MAX_SIZE_BYTES);
  }

  /**
   * Creates a new AccountCache with the specified maximum size.
   *
   * @param maxSizeBytes maximum cache size in bytes
   */
  public AccountCache(final long maxSizeBytes) {
    this.cache = new MemoryBoundCache<>(maxSizeBytes, AccountCacheMemoryFootprint::estimate);
    LOG.info("AccountCache initialized with max size {} MB", maxSizeBytes / (1024 * 1024));
  }

  /**
   * Sets up the metrics system for the account cache.
   *
   * @param metricsSystem the metrics system to use
   */
  public void setupMetricsSystem(final ObservableMetricsSystem metricsSystem) {
    metricsSystem.createLongGauge(
        BONSAI_CACHE,
        "account_cache_size",
        "Current number of entries in the account cache",
        cache::estimatedSize);

    metricsSystem.createGauge(
        BONSAI_CACHE, "account_cache_hit_rate", "Hit rate of the account cache", cache::hitRate);

    metricsSystem.createLongGauge(
        BONSAI_CACHE,
        "account_cache_evictions",
        "Total number of evictions from the account cache",
        cache::evictionCount);

    metricsSystem.createLongGauge(
        BONSAI_CACHE,
        "account_cache_eviction_weight",
        "Total weight of evictions from the account cache",
        cache::evictionWeight);

    metricsSystem.createLongGauge(
        BONSAI_CACHE,
        "account_cache_updates",
        "Total number of updates from TrieLog events",
        updateCount::get);

    metricsSystem.createLongGauge(
        BONSAI_CACHE,
        "account_cache_reorg_invalidations",
        "Total number of cache invalidations due to chain reorgs",
        reorgInvalidationCount::get);

    metricsSystem.createGauge(
        BONSAI_CACHE,
        "account_cache_lookups_per_second",
        "Estimated number of account cache lookups per second",
        () -> {
          long now = System.nanoTime();
          long currentCount = cache.requestCount();

          long deltaRequests = currentCount - lastRequestCount;
          long deltaTimeNanos = now - lastRequestTimestamp;

          lastRequestCount = currentCount;
          lastRequestTimestamp = now;

          if (deltaTimeNanos == 0) {
            return 0.0;
          }
          return (deltaRequests * 1_000_000_000.0) / deltaTimeNanos;
        });
  }

  // ==================== Cache Operations ====================

  /**
   * Gets the cached account data if present.
   *
   * @param addressHash the address hash to look up
   * @return the cached RLP-encoded account data, or empty if not cached
   */
  public Optional<Bytes> getIfPresent(final Hash addressHash) {
    Bytes result = cache.getIfPresent(addressHash);
    return Optional.ofNullable(result);
  }

  /**
   * Puts account data into the cache.
   *
   * @param addressHash the address hash key
   * @param accountRlp the RLP-encoded account data
   */
  public void put(final Hash addressHash, final Bytes accountRlp) {
    cache.put(addressHash, accountRlp);
  }

  /**
   * Invalidates a single cache entry.
   *
   * @param addressHash the address hash to invalidate
   */
  public void invalidate(final Hash addressHash) {
    cache.invalidate(addressHash);
  }

  /** Invalidates all cache entries. */
  public void invalidateAll() {
    cache.invalidateAll();
  }

  // ==================== TrieLogObserver Implementation ====================

  /**
   * Called when a TrieLog is added (new block processed).
   *
   * <p>Note: Cache updates are now done synchronously in the storage layer (putAccountInfoState and
   * removeAccountInfoState) to ensure the cache is always in sync with the flat DB. This method is
   * kept for potential future use but no longer updates the cache.
   *
   * @param event the TrieLog event containing account changes
   */
  @Override
  public void onTrieLogAdded(final TrieLogEvent event) {
    // Cache updates are now handled synchronously in BonsaiWorldStateKeyValueStorage.Updater
    // to ensure consistency between flat DB and cache. This prevents stale reads during
    // block execution.
    LOG.atTrace()
        .setMessage("TrieLog received for block {} (cache updated synchronously)")
        .addArgument(event.layer()::getBlockHash)
        .log();
  }

  // ==================== BlockAddedObserver Implementation ====================

  /**
   * Called when a block is added to the blockchain.
   *
   * <p>On chain reorgs (CHAIN_REORG event), we invalidate the entire cache to ensure consistency.
   * The cache will be repopulated by subsequent TrieLog events for the new chain.
   *
   * @param event the block added event
   */
  @Override
  public void onBlockAdded(final BlockAddedEvent event) {
    if (event.getEventType() == EventType.CHAIN_REORG) {
      LOG.debug(
          "Chain reorg detected, invalidating account cache. Common ancestor: {}",
          event.getCommonAncestorHash());
      cache.invalidateAll();
      reorgInvalidationCount.incrementAndGet();
    }
    // For HEAD_ADVANCED, cache is updated via onTrieLogAdded() - no action needed here
  }

  // ==================== StorageSubscriber Implementation ====================

  @Override
  public void onClearStorage() {
    LOG.debug("Storage cleared, invalidating account cache");
    cache.invalidateAll();
  }

  @Override
  public void onClearFlatDatabaseStorage() {
    LOG.debug("Flat database storage cleared, invalidating account cache");
    cache.invalidateAll();
  }

  @Override
  public void onClearTrieLog() {
    LOG.debug("TrieLog cleared, invalidating account cache");
    cache.invalidateAll();
  }
}
