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
package org.hyperledger.besu.ethereum.mainnet.staterootcommitter;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.mainnet.parallelization.BlockProcessingExecutors;
import org.hyperledger.besu.ethereum.trie.MerkleTrie;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.WorldStateKeyValueStorage;
import org.hyperledger.besu.plugin.services.worldstate.StateRootComputation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.tuweni.bytes.Bytes;

/** Factories for {@link StateRootComputation} results produced by state root committers. */
public final class StateRootComputations {

  private StateRootComputations() {}

  /** Deferred Bonsai storage write applied at persist time. */
  @FunctionalInterface
  protected interface UpdaterWrite {
    void applyTo(BonsaiWorldStateKeyValueStorage.Updater updater);
  }

  public static StateRootComputation pathBased(final Hash root, final List<UpdaterWrite> writes) {
    return new PathBased(root, writes, false);
  }

  /**
   * A computation that holds every write of the state it computed, even where the world state it
   * was computed on discards them, so that the state can be stored from the computation alone, over
   * the state it was computed from.
   *
   * @param root the computed state root
   * @param writes every write of the computed state
   * @return the computation
   */
  public static StateRootComputation withAllWrites(
      final Hash root, final List<UpdaterWrite> writes) {
    return new PathBased(root, writes, true);
  }

  /**
   * Whether a computation holds every write of the state it computed.
   *
   * @param computation the computation
   * @return true if the state can be stored from the computation alone
   */
  public static boolean holdsAllWrites(final StateRootComputation computation) {
    return computation instanceof PathBased pathBased && pathBased.allWrites();
  }

  public static StateRootComputation forest(final Hash root) {
    return new Forest(root);
  }

  /**
   * Starts collecting the nodes of the tries whose commits a computation deferred, so that applying
   * it, which moves the head, only has to store them. Nothing waits for the collection: applying
   * the computation collects whatever the background has not reached yet.
   *
   * @param computation the computation
   * @return completes when the background has collected every deferred trie
   */
  public static CompletableFuture<Void> collectDeferredTrieNodes(
      final StateRootComputation computation) {
    if (!(computation instanceof PathBased pathBased)) {
      return CompletableFuture.completedFuture(null);
    }
    final List<DeferredTrieCommit> deferred =
        pathBased.writes().stream()
            .filter(DeferredTrieCommit.class::isInstance)
            .map(DeferredTrieCommit.class::cast)
            .toList();
    if (deferred.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    return CompletableFuture.runAsync(
        () -> deferred.forEach(DeferredTrieCommit::nodes),
        BlockProcessingExecutors.stateRootExecutor());
  }

  static UpdaterWrite deferredTrieCommit(
      final MerkleTrie<Bytes, Bytes> trie, final TrieWriteOf writeOf) {
    return new DeferredTrieCommit(trie, writeOf);
  }

  private record PathBased(Hash root, List<UpdaterWrite> writes, boolean allWrites)
      implements StateRootComputation {

    @Override
    public void applyTo(final WorldStateKeyValueStorage.Updater updater) {
      final BonsaiWorldStateKeyValueStorage.Updater bonsaiUpdater =
          (BonsaiWorldStateKeyValueStorage.Updater) updater;
      writes.forEach(write -> write.applyTo(bonsaiUpdater));
    }
  }

  /** A trie commit deferred until the writes are applied, collected once by whoever comes first. */
  private static final class DeferredTrieCommit implements UpdaterWrite {
    private final MerkleTrie<Bytes, Bytes> trie;
    private final TrieWriteOf writeOf;
    private List<UpdaterWrite> nodes;

    private DeferredTrieCommit(final MerkleTrie<Bytes, Bytes> trie, final TrieWriteOf writeOf) {
      this.trie = trie;
      this.writeOf = writeOf;
    }

    private synchronized List<UpdaterWrite> nodes() {
      if (nodes == null) {
        final List<UpdaterWrite> committed = new ArrayList<>();
        trie.commit((location, hash, value) -> committed.add(writeOf.apply(location, hash, value)));
        nodes = committed;
      }
      return nodes;
    }

    @Override
    public void applyTo(final BonsaiWorldStateKeyValueStorage.Updater updater) {
      nodes().forEach(node -> node.applyTo(updater));
    }
  }

  private record Forest(Hash root) implements StateRootComputation {

    @Override
    public void applyTo(final WorldStateKeyValueStorage.Updater updater) {
      // Forest persistence applies trie updates during root computation.
    }
  }
}
