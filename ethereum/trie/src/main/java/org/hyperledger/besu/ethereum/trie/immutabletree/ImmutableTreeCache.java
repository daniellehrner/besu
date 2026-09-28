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
package org.hyperledger.besu.ethereum.trie.immutabletree;

import org.hyperledger.besu.ethereum.trie.MerkleTrie;
import org.hyperledger.besu.ethereum.trie.NodeLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A cache of immutable Merkle Patricia trees, shared by every state root computation.
 *
 * <p>Roots are registered by {@link TreeKey} (root hash and {@link TreeKind}). Any number of them
 * can be alive at once and they share every subtree they have in common, so keeping the root of a
 * block and of its child costs only the nodes the child changed. A root is opened from the
 * registry, or else by loading its root node alone; the rest of the tree is loaded as traversals
 * touch it.
 *
 * <p>Retention is decided by bindings. The state root of the head world state is bound as {@link
 * TreeRole#HEAD}, the one of the latest payload validation as {@link TreeRole#NEW_PAYLOAD}, and the
 * storage roots each of them computed are bound per account. Bound roots stay while they are in
 * use. Other roots - the roots a computation started from, rolled world states, simulations - are
 * forks: they are dropped a few blocks after their last use, so the cache never pins more than a
 * short window of history.
 *
 * <p>Pruning, run in the background as the head advances, drops expired roots and unloads the nodes
 * of the remaining ones that no traversal has touched for {@link
 * ImmutableTreeCacheConfig#pruneAfterBlocks()} blocks, replacing them by {@link StoredTreeNode}
 * placeholders. If more than {@link ImmutableTreeCacheConfig#maxCachedNodes()} nodes and
 * placeholders are left, it halves that window until they fit, and past a window of zero drops the
 * least recently used roots. Roots locked by a running traversal are skipped.
 */
public final class ImmutableTreeCache {

  private static final Logger LOG = LoggerFactory.getLogger(ImmutableTreeCache.class);

  // One thread for every cache: prune runs are rare and short
  private static final ExecutorService PRUNE_EXECUTOR =
      Executors.newSingleThreadExecutor(
          runnable -> {
            final Thread thread = new Thread(runnable, "besu-immutable-tree-prune");
            thread.setDaemon(true);
            return thread;
          });

  private final ImmutableTreeCacheConfig config;
  private final ConcurrentHashMap<TreeKey, TreeHandle> roots = new ConcurrentHashMap<>();

  private final Object bindingLock = new Object();
  private TreeHandle head;
  private TreeHandle newPayload;
  private final Map<Bytes32, TreeHandle> headStorage = new HashMap<>();
  private final Map<Bytes32, TreeHandle> newPayloadStorage = new HashMap<>();

  private final AtomicLong currentBlock = new AtomicLong();
  private final AtomicLong lastPruneBlock = new AtomicLong();
  private final AtomicBoolean pruneScheduled = new AtomicBoolean();
  private final AtomicInteger walkEpochs = new AtomicInteger();
  private final LongAdder cachedOpens = new LongAdder();
  private final LongAdder storedOpens = new LongAdder();
  private volatile PruneStats lastPrune = PruneStats.NONE;

  /**
   * Creates a cache.
   *
   * @param config its limits
   */
  public ImmutableTreeCache(final ImmutableTreeCacheConfig config) {
    this.config = config;
  }

  /**
   * Returns the limits of this cache.
   *
   * @return the config
   */
  public ImmutableTreeCacheConfig config() {
    return config;
  }

  /**
   * Returns the latest block the cache was advanced to.
   *
   * @return the block number
   */
  public long currentBlock() {
    return currentBlock.get();
  }

  /**
   * Creates a session stamping the nodes it visits with the current block.
   *
   * @param loader loads missing nodes; must be consistent with the roots the session works on
   * @return a new session
   */
  public TreeSession newSession(final NodeLoader loader) {
    return TreeSession.create(loader, (int) Math.min(currentBlock.get(), Integer.MAX_VALUE));
  }

  /**
   * Opens a root: returns its handle if it is registered, otherwise loads its root node - and only
   * that - through the session and registers it as a fork.
   *
   * @param kind the kind of trie
   * @param rootHash the root hash
   * @param session the session opening it
   * @return the handle, or null for the empty root, which needs none
   */
  public TreeHandle open(final TreeKind kind, final Bytes32 rootHash, final TreeSession session) {
    if (rootHash.equals(MerkleTrie.EMPTY_TRIE_NODE_HASH)) {
      return null;
    }
    final TreeKey key = new TreeKey(rootHash, kind);
    final long block = currentBlock.get();
    final TreeHandle cached = roots.get(key);
    if (cached != null) {
      cached.touch(block);
      cachedOpens.increment();
      return cached;
    }
    final TreeNode root = session.loadRoot(rootHash);
    storedOpens.increment();
    final TreeHandle opened = new TreeHandle(key, root, block);
    final TreeHandle raced = roots.putIfAbsent(key, opened);
    return raced != null ? raced : opened;
  }

  /**
   * Returns the handle of a registered root.
   *
   * @param kind the kind of trie
   * @param rootHash the root hash
   * @return the handle, if registered
   */
  public Optional<TreeHandle> lookup(final TreeKind kind, final Bytes32 rootHash) {
    return Optional.ofNullable(roots.get(new TreeKey(rootHash, kind)));
  }

  /**
   * Registers a computed root. If the root is registered already, the existing handle is kept, so
   * one tree stands for each root.
   *
   * @param kind the kind of trie
   * @param root the root node, hashed
   * @return the handle, or null for the empty root
   */
  public TreeHandle register(final TreeKind kind, final TreeNode root) {
    if (root instanceof StoredTreeNode) {
      throw new IllegalArgumentException("Only loaded roots can be registered");
    }
    final Bytes32 rootHash = root.hash();
    if (rootHash.equals(MerkleTrie.EMPTY_TRIE_NODE_HASH)) {
      return null;
    }
    final TreeKey key = new TreeKey(rootHash, kind);
    final long block = currentBlock.get();
    final TreeHandle handle = roots.computeIfAbsent(key, k -> new TreeHandle(k, root, block));
    handle.touch(block);
    if (roots.size() > config.maxUnboundRoots()) {
      // e.g. payload validations or simulations while the head does not move
      schedulePrune();
    }
    return handle;
  }

  /**
   * Binds the state root of the head world state.
   *
   * @param handle the state root handle, or null for the empty state
   */
  public void setHead(final TreeHandle handle) {
    checkKind(handle, TreeKind.STATE);
    synchronized (bindingLock) {
      head = rebind(head, handle);
    }
  }

  /**
   * Binds the state root of the latest payload validation.
   *
   * @param handle the state root handle, or null for the empty state
   */
  public void setNewPayload(final TreeHandle handle) {
    checkKind(handle, TreeKind.STATE);
    synchronized (bindingLock) {
      newPayload = rebind(newPayload, handle);
    }
  }

  /**
   * Binds the storage root of an account for a role, replacing the previous one.
   *
   * @param role the role
   * @param accountHash the account
   * @param handle the storage root handle, or null to unbind (empty storage)
   */
  public void bindStorage(final TreeRole role, final Bytes32 accountHash, final TreeHandle handle) {
    checkKind(handle, TreeKind.STORAGE);
    synchronized (bindingLock) {
      final Map<Bytes32, TreeHandle> bindings = storageBindings(role);
      final TreeHandle previous =
          handle == null ? bindings.remove(accountHash) : bindings.put(accountHash, handle);
      rebind(previous, handle);
    }
  }

  /**
   * Returns the state root bound to a role.
   *
   * @param role the role
   * @return the handle, if bound
   */
  public Optional<TreeHandle> boundState(final TreeRole role) {
    synchronized (bindingLock) {
      return Optional.ofNullable(role == TreeRole.HEAD ? head : newPayload);
    }
  }

  /**
   * Returns the storage root bound to an account for a role.
   *
   * @param role the role
   * @param accountHash the account
   * @return the handle, if bound
   */
  public Optional<TreeHandle> boundStorage(final TreeRole role, final Bytes32 accountHash) {
    synchronized (bindingLock) {
      return Optional.ofNullable(storageBindings(role).get(accountHash));
    }
  }

  private Map<Bytes32, TreeHandle> storageBindings(final TreeRole role) {
    return role == TreeRole.HEAD ? headStorage : newPayloadStorage;
  }

  private static TreeHandle rebind(final TreeHandle previous, final TreeHandle next) {
    if (previous != next) {
      if (previous != null) {
        previous.bindings--;
      }
      if (next != null) {
        next.bindings++;
      }
    }
    return next;
  }

  private static void checkKind(final TreeHandle handle, final TreeKind kind) {
    if (handle != null && handle.kind() != kind) {
      throw new IllegalArgumentException("Expected a " + kind + " root, got " + handle);
    }
  }

  /**
   * Moves the cache to a block. Nodes visited from now on are stamped with it. Advancing the head
   * schedules a prune run every {@link ImmutableTreeCacheConfig#pruneIntervalBlocks()} blocks.
   *
   * @param blockNumber the block number
   * @param isHead whether the head world state reached this block
   */
  public void advanceBlock(final long blockNumber, final boolean isHead) {
    final long block = currentBlock.accumulateAndGet(blockNumber, Math::max);
    if (isHead && block - lastPruneBlock.get() >= config.pruneIntervalBlocks()) {
      schedulePrune();
    }
  }

  private void schedulePrune() {
    if (pruneScheduled.compareAndSet(false, true)) {
      PRUNE_EXECUTOR.execute(
          () -> {
            try {
              prune();
            } catch (final RuntimeException e) {
              LOG.warn("Immutable tree cache prune failed", e);
            } finally {
              pruneScheduled.set(false);
            }
          });
    }
  }

  /** Drops every root and binding. */
  public void clear() {
    synchronized (bindingLock) {
      head = null;
      newPayload = null;
      headStorage.clear();
      newPayloadStorage.clear();
      roots.clear();
    }
    LOG.debug("Immutable tree cache cleared");
  }

  /**
   * Returns the current size of the cache and the outcome of the last prune.
   *
   * @return the stats
   */
  public Stats stats() {
    int stateRoots = 0;
    int storageRoots = 0;
    for (final TreeKey key : roots.keySet()) {
      if (key.kind() == TreeKind.STATE) {
        stateRoots++;
      } else {
        storageRoots++;
      }
    }
    synchronized (bindingLock) {
      return new Stats(
          stateRoots,
          storageRoots,
          head == null ? null : head.rootHash(),
          newPayload == null ? null : newPayload.rootHash(),
          headStorage.size(),
          newPayloadStorage.size(),
          cachedOpens.sum(),
          storedOpens.sum(),
          lastPrune);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Pruning

  /**
   * Drops expired roots and unloads the nodes nothing has used for the prune window. Runs in the
   * background after head advances; callable directly.
   *
   * @return what the run did
   */
  public synchronized PruneStats prune() {
    final long startNanos = System.nanoTime();
    final long block = currentBlock.get();
    lastPruneBlock.set(block);

    final int[] dropped = dropExpiredRoots(block);
    long window = config.pruneAfterBlocks();
    Walk walk = unloadStale(block - window);
    long unloaded = walk.unloaded;
    while (walk.cachedNodes() > config.maxCachedNodes() && window > 0) {
      window /= 2;
      walk = unloadStale(block - window);
      unloaded += walk.unloaded;
    }
    if (walk.cachedNodes() > config.maxCachedNodes()) {
      // Only nodes used in the current block are left, and every registered root keeps its root
      // node loaded: drop the least recently used roots, each worth at least one node
      dropped[0] += dropLeastRecentlyUsed(walk.cachedNodes() - config.maxCachedNodes());
      walk = unloadStale(block - window);
      unloaded += walk.unloaded;
    }

    final PruneStats stats =
        new PruneStats(
            block,
            dropped[0],
            dropped[1],
            unloaded,
            walk.materialized,
            walk.stored,
            walk.estimatedBytes,
            window,
            walk.lockedRoots,
            roots.size(),
            (System.nanoTime() - startNanos) / 1_000_000);
    lastPrune = stats;
    LOG.debug("Immutable tree cache pruned: {}", stats);
    return stats;
  }

  /** Returns {dropped roots, dropped storage bindings}. */
  private int[] dropExpiredRoots(final long block) {
    int droppedBindings = 0;
    int droppedRoots = 0;
    synchronized (bindingLock) {
      for (final TreeRole role : TreeRole.values()) {
        final Iterator<TreeHandle> bindings = storageBindings(role).values().iterator();
        while (bindings.hasNext()) {
          final TreeHandle handle = bindings.next();
          if (!handle.isLocked() && block - handle.lastAccessBlock() > config.pruneAfterBlocks()) {
            bindings.remove();
            handle.bindings--;
            droppedBindings++;
          }
        }
      }
      final List<TreeHandle> unbound = new ArrayList<>();
      final Iterator<TreeHandle> handles = roots.values().iterator();
      while (handles.hasNext()) {
        final TreeHandle handle = handles.next();
        if (handle.bindings > 0 || handle.isLocked()) {
          continue;
        }
        if (block - handle.lastAccessBlock() > config.forkRetentionBlocks()) {
          handles.remove();
          droppedRoots++;
        } else {
          unbound.add(handle);
        }
      }
      if (unbound.size() > config.maxUnboundRoots()) {
        unbound.sort(Comparator.comparingLong(TreeHandle::lastAccessBlock));
        for (int i = 0; i < unbound.size() - config.maxUnboundRoots(); i++) {
          if (roots.remove(unbound.get(i).key(), unbound.get(i))) {
            droppedRoots++;
          }
        }
      }
    }
    return new int[] {droppedRoots, droppedBindings};
  }

  /**
   * Drops up to {@code count} roots, least recently used first, with their storage bindings. The
   * head and new payload state roots are kept.
   */
  private int dropLeastRecentlyUsed(final long count) {
    synchronized (bindingLock) {
      final List<TreeHandle> candidates = new ArrayList<>();
      for (final TreeHandle handle : roots.values()) {
        if (!handle.isLocked() && handle != head && handle != newPayload) {
          candidates.add(handle);
        }
      }
      candidates.sort(Comparator.comparingLong(TreeHandle::lastAccessBlock));
      final int dropCount = (int) Math.min(count, candidates.size());
      final Set<TreeHandle> dropping = Collections.newSetFromMap(new IdentityHashMap<>());
      dropping.addAll(candidates.subList(0, dropCount));
      for (final TreeRole role : TreeRole.values()) {
        final Iterator<TreeHandle> bindings = storageBindings(role).values().iterator();
        while (bindings.hasNext()) {
          final TreeHandle handle = bindings.next();
          if (dropping.contains(handle)) {
            bindings.remove();
            handle.bindings--;
          }
        }
      }
      int dropped = 0;
      for (final TreeHandle handle : dropping) {
        if (roots.remove(handle.key(), handle)) {
          dropped++;
        }
      }
      return dropped;
    }
  }

  private static final class Walk {
    long materialized;
    long stored;
    long unloaded;
    long estimatedBytes;
    int lockedRoots;

    /** What the budget counts: placeholders cost about as much heap as small nodes. */
    long cachedNodes() {
      return materialized + stored;
    }
  }

  /**
   * Walks every unlocked root once, unloading loaded children last used before {@code threshold}. A
   * shared subtree is walked once however many roots reach it.
   */
  private Walk unloadStale(final long threshold) {
    final Walk walk = new Walk();
    final int epoch = walkEpochs.incrementAndGet();
    final int stampThreshold =
        (int) Math.max(Integer.MIN_VALUE, Math.min(threshold, Integer.MAX_VALUE));
    final PathStack path = new PathStack();
    for (final TreeHandle handle : roots.values()) {
      if (handle.isLocked()) {
        walk.lockedRoots++;
        continue;
      }
      walkNode(handle.root(), path, stampThreshold, epoch, walk);
    }
    return walk;
  }

  private static void walkNode(
      final TreeNode node,
      final PathStack path,
      final int threshold,
      final int epoch,
      final Walk walk) {
    if (!(node instanceof MaterializedTreeNode materialized) || materialized.visitEpoch == epoch) {
      return;
    }
    materialized.visitEpoch = epoch;
    walk.materialized++;
    walk.estimatedBytes += estimatedSize(materialized);
    if (node instanceof BranchTreeNode branch) {
      Bytes location = null;
      for (int i = 0; i < TreePaths.RADIX; i++) {
        final MaterializedTreeNode loaded = branch.loadedChild(i);
        if (loaded == null) {
          if (branch.isStoredChild(i)) {
            walk.stored++;
            walk.estimatedBytes += branch.isCompactChild(i) ? COMPACT_SIZE : STORED_SIZE;
          }
        } else if (loaded.lastAccess < threshold && loaded.isReferencedByHash()) {
          if (location == null) {
            location = path.toBytes();
          }
          if (branch.swapChild(
              i, loaded, new StoredTreeNode(location, TreePaths.nibble(i), loaded.hash()))) {
            walk.unloaded++;
            walk.stored++;
            walk.estimatedBytes += branch.isCompactChild(i) ? COMPACT_SIZE : STORED_SIZE;
          }
        } else {
          path.push(i);
          walkNode(loaded, path, threshold, epoch, walk);
          path.pop(1);
        }
      }
    } else if (node instanceof ExtensionTreeNode extension) {
      final TreeNode child = extension.child();
      if (child instanceof StoredTreeNode) {
        walk.stored++;
        walk.estimatedBytes += STORED_SIZE;
      } else if (child instanceof MaterializedTreeNode loaded) {
        if (loaded.lastAccess < threshold && loaded.isReferencedByHash()) {
          if (extension.swapChild(
              loaded, new StoredTreeNode(path.toBytes(), extension.path(), loaded.hash()))) {
            walk.unloaded++;
            walk.stored++;
            walk.estimatedBytes += STORED_SIZE;
          }
        } else {
          path.push(extension.path());
          walkNode(loaded, path, threshold, epoch, walk);
          path.pop(extension.path().size());
        }
      }
    }
  }

  // Heap footprint for the logs, from object layouts with compressed pointers: node, hash, slot
  // array, the shared array of stored hashes (counted per branch, although copies share it) and
  // the byte payloads. Compact slots live in that array; a placeholder object costs more.
  private static final long HASH_SIZE = 72;
  private static final long STORED_SIZE = 32 + HASH_SIZE;
  private static final long COMPACT_SIZE = 0;

  private static long estimatedSize(final MaterializedTreeNode node) {
    return switch (node) {
      case BranchTreeNode branch ->
          40 + HASH_SIZE + 80 + (branch.hasStoredHashes() ? 16 + 16 * 32 + 48 : 0);
      case ExtensionTreeNode extension -> 40 + HASH_SIZE + 40 + extension.path().size();
      case LeafTreeNode leaf -> 40 + HASH_SIZE + 80 + leaf.path().size() + leaf.value().size();
    };
  }

  /** The nibble path of the node being walked. */
  private static final class PathStack {
    private byte[] nibbles = new byte[128];
    private int depth;

    void push(final int nibble) {
      ensureCapacity(depth + 1);
      nibbles[depth++] = (byte) nibble;
    }

    void push(final Bytes segment) {
      ensureCapacity(depth + segment.size());
      for (int i = 0; i < segment.size(); i++) {
        nibbles[depth++] = segment.get(i);
      }
    }

    void pop(final int count) {
      depth -= count;
    }

    Bytes toBytes() {
      return Bytes.wrap(Arrays.copyOf(nibbles, depth));
    }

    private void ensureCapacity(final int size) {
      if (size > nibbles.length) {
        nibbles = Arrays.copyOf(nibbles, Math.max(size, nibbles.length * 2));
      }
    }
  }

  /**
   * Size of the cache.
   *
   * @param stateRoots registered state roots
   * @param storageRoots registered storage roots
   * @param head the state root bound as head, or null
   * @param newPayload the state root bound as new payload, or null
   * @param headStorageBindings accounts with a storage root bound for the head
   * @param newPayloadStorageBindings accounts with a storage root bound for the new payload
   * @param cachedOpens opens served from the registry
   * @param storedOpens opens that loaded the root node from storage
   * @param lastPrune the last prune run
   */
  public record Stats(
      int stateRoots,
      int storageRoots,
      Bytes32 head,
      Bytes32 newPayload,
      int headStorageBindings,
      int newPayloadStorageBindings,
      long cachedOpens,
      long storedOpens,
      PruneStats lastPrune) {}

  /**
   * Outcome of a prune run.
   *
   * @param block the block it ran at
   * @param droppedRoots roots dropped from the registry
   * @param droppedBindings idle storage bindings dropped
   * @param unloadedSubtrees loaded subtrees replaced by placeholders
   * @param materializedNodes loaded nodes left, counted once however many roots share them
   * @param storedPlaceholders placeholders left
   * @param estimatedBytes rough heap footprint of what is left
   * @param windowBlocks the prune window that was applied, after any tightening
   * @param lockedRoots roots skipped because a traversal held them
   * @param registeredRoots roots left in the registry
   * @param durationMillis how long the run took
   */
  public record PruneStats(
      long block,
      int droppedRoots,
      int droppedBindings,
      long unloadedSubtrees,
      long materializedNodes,
      long storedPlaceholders,
      long estimatedBytes,
      long windowBlocks,
      int lockedRoots,
      int registeredRoots,
      long durationMillis) {

    static final PruneStats NONE = new PruneStats(-1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    @Override
    public String toString() {
      return String.format(
          "block=%d droppedRoots=%d droppedStorageBindings=%d unloadedSubtrees=%d"
              + " loadedNodes=%d placeholders=%d estimatedMiB=%d window=%d lockedRoots=%d"
              + " registeredRoots=%d took=%dms",
          block,
          droppedRoots,
          droppedBindings,
          unloadedSubtrees,
          materializedNodes,
          storedPlaceholders,
          estimatedBytes >> 20,
          windowBlocks,
          lockedRoots,
          registeredRoots,
          durationMillis);
    }
  }
}
